//! Recently deleted. A deleted note is its own file moved to
//! `<root>/.trash/<NOTE_ID>.<deleted_at_ms>.syncr`: the deletion time is in the name, so the
//! one atomic rename records it. The trash sits inside the root so that rename never
//! crosses filesystems. It is never scanned as part of the library or cleaned at startup.
//!
//! Purging removes the file and records the note and creation IDs in `library.sqlite3`'s
//! `purged` table, so a stale creation retry gets `IdCollision` instead of recreating it.
use super::{
    SyncrLibrary,
    catalog::{Catalog, creation_retry},
    lock,
};
use crate::{
    error::{collision, internal, invalid, io, locked, missing, storage},
    fault::crash_point,
    files,
    reducer::State,
    store, validation, *,
};
use rusqlite::{Connection, params};
use std::path::PathBuf;

const TRASH_DIR: &str = ".trash";

/// One file in Recently deleted, described by its name alone.
struct Trashed {
    path: PathBuf,
    note_id: Id,
    deleted_at_ms: i64,
}

impl Trashed {
    /// Parses `<NOTE_ID>.<deleted_at_ms>.syncr`; any other entry is ignored.
    fn from_path(path: PathBuf) -> Option<Self> {
        if path.extension().is_none_or(|e| e != "syncr") || !path.is_file() {
            return None;
        }
        let (note_id, ms) = path.file_stem()?.to_str()?.split_once('.')?;
        validation::id(note_id).ok()?;
        let deleted_at_ms = ms.parse().ok()?;
        Some(Trashed {
            note_id: note_id.into(),
            deleted_at_ms,
            path,
        })
    }

    /// Reads the file read-only, then removes the empty sidecars that a read-only WAL open
    /// leaves, so `.trash/` holds only the deleted files themselves.
    fn read<T>(&self, read: impl FnOnce(&Connection, State) -> CoreResult<T>) -> CoreResult<T> {
        let result = store::open(&self.path, false).and_then(|db| {
            let state = store::load(&db)?;
            read(&db, state)
        });
        files::remove_idle_sidecars(&self.path);
        result
    }
}

impl Catalog {
    fn record_purge(&self, note_id: &str, creation: Option<Id>) -> CoreResult<()> {
        self.db
            .execute(
                "INSERT OR REPLACE INTO purged VALUES (?1,?2,?3)",
                params![note_id, creation, store::now_ms()],
            )
            .map_err(storage)?;
        Ok(())
    }
    fn clear_purge(&self, note_id: &str) -> CoreResult<()> {
        self.db
            .execute("DELETE FROM purged WHERE note_id=?1", [note_id])
            .map_err(storage)?;
        Ok(())
    }
    fn is_purged(&self, note_id: &str) -> CoreResult<bool> {
        self.db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM purged WHERE note_id=?1)",
                [note_id],
                |r| r.get(0),
            )
            .map_err(storage)
    }
    /// Whether a purged note still reserves this note ID or creation operation ID.
    pub(super) fn purge_reserves(&self, note_id: &str, operation_id: &str) -> CoreResult<bool> {
        self.db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM purged WHERE note_id=?1 OR creation_operation_id=?2)",
                params![note_id, operation_id],
                |r| r.get(0),
            )
            .map_err(storage)
    }
}

impl SyncrLibrary {
    fn trash_dir(&self) -> PathBuf {
        self.root.join(TRASH_DIR)
    }

    /// Recently deleted entries, from file names only (no note is opened), newest first.
    fn scan_trash(&self) -> CoreResult<Vec<Trashed>> {
        let entries = match std::fs::read_dir(self.trash_dir()) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
            Err(e) => return Err(io(e)),
        };
        let mut result = Vec::new();
        for entry in entries {
            if let Some(trashed) = Trashed::from_path(entry.map_err(io)?.path()) {
                result.push(trashed);
            }
        }
        result.sort_by(|a, b| {
            b.deleted_at_ms
                .cmp(&a.deleted_at_ms)
                .then_with(|| a.note_id.cmp(&b.note_id))
                .then_with(|| a.path.cmp(&b.path))
        });
        Ok(result)
    }

    fn trashed(&self, note_id: &str) -> CoreResult<Vec<Trashed>> {
        let mut entries = self.scan_trash()?;
        entries.retain(|t| t.note_id == note_id);
        Ok(entries)
    }

    /// Permanently removes one trash entry. The purge is recorded first, so a crash leaves
    /// at worst a recorded purge whose file is still listed (purging again finishes it).
    fn purge_entry(&self, catalog: &Catalog, entry: &Trashed) -> CoreResult<()> {
        // An unreadable file is still purgeable; it just records no creation ID.
        let creation = entry.read(|_, state| Ok(state.creation)).ok();
        catalog.record_purge(&entry.note_id, creation)?;
        crash_point("purge_after_record");
        files::remove_with_sidecars(&entry.path)?;
        files::sync(&self.trash_dir())
    }

    /// A creation retry of a deleted note, or `IdCollision` if its note ID is taken. An
    /// unreadable deleted file can't be matched by creation ID, but its file-name ID still
    /// blocks reuse; it never blocks unrelated creations.
    pub(super) fn trashed_creation_retry(
        &self,
        request: &CreateNoteRequest,
    ) -> CoreResult<Option<Receipt>> {
        for entry in self.scan_trash()? {
            let read = entry.read(|db, state| Ok((store::lookup(db, &state.creation)?, state)));
            let Ok((stored, state)) = read else {
                if entry.note_id == request.note_id {
                    return Err(collision());
                }
                continue;
            };
            if state.creation == request.operation_id {
                return creation_retry(stored, request, &self.author).map(Some);
            }
            if state.note_id == request.note_id || entry.note_id == request.note_id {
                return Err(collision());
            }
        }
        Ok(None)
    }

    /// The original receipt of a deleted note's creation. Unreadable files are skipped.
    pub(super) fn trashed_creation_receipt(
        &self,
        operation_id: &str,
    ) -> CoreResult<Option<Receipt>> {
        for entry in self.scan_trash()? {
            let found = entry.read(|db, state| {
                if state.creation != operation_id {
                    return Ok(None);
                }
                Ok(Some(
                    store::lookup(db, operation_id)?.ok_or_else(internal)?.1,
                ))
            });
            if let Ok(Some(receipt)) = found {
                return Ok(Some(receipt));
            }
        }
        Ok(None)
    }
}

pub(super) fn delete_note(library: &SyncrLibrary, note_id: Id) -> CoreResult<()> {
    validation::id(&note_id)?;
    let mut catalog = lock(&library.catalog)?;
    if catalog.open_handle(&note_id)?.is_some() {
        return Err(locked("Close the note before deleting it"));
    }
    let Some(published) = library.find_published(&mut catalog, &note_id)? else {
        return if library.trashed(&note_id)?.is_empty() {
            Err(missing())
        } else {
            Ok(())
        };
    };
    store::fold_journal(&published.path)?;
    let trash = library.trash_dir();
    if !trash.is_dir() {
        std::fs::create_dir_all(&trash).map_err(io)?;
        files::sync(&library.root)?;
    }
    let destination = trash.join(format!("{note_id}.{}.syncr", store::now_ms()));
    crash_point("delete_before_rename");
    files::move_noreplace(&published.path, &destination)?;
    crash_point("delete_after_rename");
    files::remove_idle_sidecars(&published.path);
    // Refresh the cache only; the delete itself already succeeded.
    let _ = library.scan(&mut catalog);
    Ok(())
}

pub(super) fn list_deleted_notes(library: &SyncrLibrary) -> CoreResult<Vec<DeletedNoteSummary>> {
    let _catalog = lock(&library.catalog)?;
    // One unreadable file fails the whole listing rather than silently vanishing.
    library
        .scan_trash()?
        .into_iter()
        .map(|entry| {
            entry.read(|db, state| {
                if state.note_id != entry.note_id {
                    return Err(internal());
                }
                let (created_at_ms, _) = store::times(db, &state)?;
                Ok(DeletedNoteSummary {
                    id: state.note_id,
                    title: state.title,
                    notebook: state.notebook,
                    deleted_at_ms: entry.deleted_at_ms,
                    created_at_ms,
                })
            })
        })
        .collect()
}

pub(super) fn restore_note(library: &SyncrLibrary, note_id: Id) -> CoreResult<()> {
    validation::id(&note_id)?;
    let mut catalog = lock(&library.catalog)?;
    let in_library = library.find_published(&mut catalog, &note_id)?.is_some();
    let entries = library.trashed(&note_id)?;
    let entry = match entries.as_slice() {
        [] if in_library => return Ok(()),
        [] => return Err(missing()),
        [entry] if !in_library => entry,
        _ => return Err(collision()),
    };
    files::move_noreplace(&entry.path, &library.note_path(&note_id))?;
    crash_point("restore_after_rename");
    files::remove_idle_sidecars(&entry.path);
    // A purge recorded just before a crash no longer applies to a restored note.
    catalog.clear_purge(&note_id)?;
    // Refresh the cache only; the restore itself already succeeded.
    let _ = library.scan(&mut catalog);
    Ok(())
}

pub(super) fn purge_note(library: &SyncrLibrary, note_id: Id) -> CoreResult<()> {
    validation::id(&note_id)?;
    let mut catalog = lock(&library.catalog)?;
    let entries = library.trashed(&note_id)?;
    if entries.is_empty() {
        if catalog.is_purged(&note_id)? {
            return Ok(());
        }
        if library.find_published(&mut catalog, &note_id)?.is_some() {
            return Err(invalid("Only notes in Recently deleted can be purged"));
        }
        return Err(missing());
    }
    for entry in &entries {
        library.purge_entry(&catalog, entry)?;
    }
    Ok(())
}

pub(super) fn purge_deleted_older_than(library: &SyncrLibrary, cutoff_ms: i64) -> CoreResult<u32> {
    let catalog = lock(&library.catalog)?;
    let mut count = 0u32;
    for entry in library.scan_trash()? {
        if entry.deleted_at_ms < cutoff_ms {
            library.purge_entry(&catalog, &entry)?;
            count = count.saturating_add(1);
        }
    }
    Ok(count)
}
