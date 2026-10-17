use crate::{
    canonical,
    error::{closed, collision, dependency, internal, invalid, io, missing, storage, unsupported},
    reducer::State,
    store, validation, *,
};
use fs2::FileExt;
use rusqlite::{Connection, OptionalExtension, params};
use std::{
    collections::{BTreeMap, VecDeque},
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard, Weak},
};

/// One published note file as discovered by a scan.
struct Published {
    path: PathBuf,
    state: State,
    created_at_ms: i64,
    modified_at_ms: i64,
}
/// One note in Recently deleted: `<root>/.trash/<NOTE_ID>.<deleted_at_ms>.syncr`. The
/// deletion time lives in the name, so it is recorded by the same atomic rename.
struct Trashed {
    path: PathBuf,
    note_id: Id,
    deleted_at_ms: i64,
}
/// Recently deleted lives inside the root so a move is one same-filesystem rename. It is a
/// directory, never scanned as part of the library and never touched by startup cleanup.
const TRASH_DIR: &str = ".trash";
/// v1: installation + notes cache. v2: `notebooks` registry (names only; note
/// metadata stays authoritative for membership). v3: `purged` record of permanently
/// deleted note/creation IDs so a stale creation retry cannot recreate them.
const LIBRARY_VERSION: u32 = 3;
struct Catalog {
    db: Connection,
    notes: BTreeMap<Id, Weak<Note>>,
}
#[derive(uniffi::Object)]
pub struct SyncrLibrary {
    author: Id,
    root: PathBuf,
    catalog: Mutex<Catalog>,
    _lock: File,
}
fn lock<T>(value: &Mutex<T>) -> CoreResult<MutexGuard<'_, T>> {
    value.lock().map_err(|_| internal())
}

#[uniffi::export]
pub fn open_library(root_path: String) -> CoreResult<Arc<SyncrLibrary>> {
    path_input(
        &root_path,
        "Library root must be an app-owned filesystem path",
    )?;
    let root = Path::new(&root_path);
    std::fs::create_dir_all(root).map_err(io)?;
    let writer = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(root.join(".syncr-writer.lock"))
        .map_err(io)?;
    writer.try_lock_exclusive().map_err(|error| {
        if error.kind() == std::io::ErrorKind::WouldBlock {
            CoreError::NoteLocked {
                detail: "Library is already open by a writer".into(),
                retryable: true,
            }
        } else {
            io(error)
        }
    })?;
    let mut db = Connection::open(root.join("library.sqlite3")).map_err(storage)?;
    let version: u32 = db
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(storage)?;
    if version > LIBRARY_VERSION {
        return Err(unsupported("Unsupported library schema version"));
    }
    store::configure(&db)?;
    let tx = db.transaction().map_err(storage)?;
    tx.execute_batch("CREATE TABLE IF NOT EXISTS installation(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, local_sequence BLOB NOT NULL, path TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS notebooks(name TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS purged(note_id TEXT PRIMARY KEY, creation_operation_id TEXT, purged_at_ms INTEGER NOT NULL);").map_err(storage)?;
    let author = tx
        .query_row(
            "SELECT value FROM installation WHERE key='author_id'",
            [],
            |r| r.get::<_, String>(0),
        )
        .optional()
        .map_err(storage)?
        .unwrap_or_else(new_id);
    validation::id(&author)?;
    tx.execute(
        "INSERT OR IGNORE INTO installation VALUES ('author_id',?1)",
        [&author],
    )
    .map_err(storage)?;
    tx.pragma_update(None, "user_version", LIBRARY_VERSION)
        .map_err(storage)?;
    tx.commit().map_err(storage)?;
    // Unpublished committed temp files are not notes. Publication renames the temp
    // file away, so a published note never keeps this prefix. Only our reserved
    // creation prefix is removed.
    for entry in std::fs::read_dir(root).map_err(io)? {
        let entry = entry.map_err(io)?;
        if entry
            .file_name()
            .to_string_lossy()
            .starts_with(".syncr-create-")
        {
            std::fs::remove_file(entry.path()).map_err(io)?;
        }
    }
    store::sync_directory(root)?;
    // Likewise an interrupted pre-upgrade backup leaves only an unpublished temp copy.
    let backups = root.join(store::BACKUP_DIR);
    if backups.is_dir() {
        for entry in std::fs::read_dir(&backups).map_err(io)? {
            let entry = entry.map_err(io)?;
            if entry
                .file_name()
                .to_string_lossy()
                .starts_with(store::BACKUP_TEMP_PREFIX)
            {
                std::fs::remove_file(entry.path()).map_err(io)?;
            }
        }
    }
    let library = Arc::new(SyncrLibrary {
        author,
        root: root.into(),
        catalog: Mutex::new(Catalog {
            db,
            notes: BTreeMap::new(),
        }),
        _lock: writer,
    });
    library.scan(&mut *lock(&library.catalog)?)?;
    Ok(library)
}
fn path_input(path: &str, diagnostic: &str) -> CoreResult<()> {
    if path.is_empty() || path.starts_with("content:") {
        return Err(invalid(diagnostic));
    }
    Ok(())
}
impl SyncrLibrary {
    // The index is a cache, never creation evidence. Published metadata is authoritative.
    fn scan(&self, catalog: &mut Catalog) -> CoreResult<Vec<Published>> {
        let mut paths = std::fs::read_dir(&self.root)
            .map_err(io)?
            .map(|e| e.map(|e| e.path()).map_err(io))
            .collect::<CoreResult<Vec<_>>>()?;
        paths.sort();
        let mut notes = Vec::new();
        let mut seen = std::collections::HashSet::new();
        for path in paths {
            // Only regular files directly in the root: `.trash/` is a directory and its
            // contents are never part of the library.
            if path.extension().is_some_and(|e| e == "syncr") && path.is_file() {
                let db = store::open(&path, false)?;
                let state = store::load(&db)?;
                if !seen.insert(state.note_id.clone()) {
                    return Err(collision());
                }
                let (created_at_ms, modified_at_ms) = store::times(&db, &state)?;
                notes.push(Published {
                    path,
                    state,
                    created_at_ms,
                    modified_at_ms,
                });
            }
        }
        // Cache failure cannot hide published notes or turn a durable save into an
        // index-dependent outcome. Every read/lookup above uses note metadata.
        let _cache_result = (|| -> CoreResult<()> {
            let tx = catalog.db.transaction().map_err(storage)?;
            tx.execute("DELETE FROM notes", []).map_err(storage)?;
            for Published { path, state, .. } in &notes {
                tx.execute(
                    "INSERT INTO notes VALUES (?1,?2,?3,?4)",
                    params![
                        state.note_id,
                        state.title,
                        state.sequence.to_be_bytes().as_slice(),
                        path.to_string_lossy()
                    ],
                )
                .map_err(storage)?;
            }
            tx.commit().map_err(storage)
        })();
        Ok(notes)
    }
    fn trash(&self) -> PathBuf {
        self.root.join(TRASH_DIR)
    }
    /// Recently deleted entries, from file names only (no note is opened), newest first.
    fn scan_trash(&self) -> CoreResult<Vec<Trashed>> {
        let entries = match std::fs::read_dir(self.trash()) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
            Err(e) => return Err(io(e)),
        };
        let mut result = Vec::new();
        for entry in entries {
            let path = entry.map_err(io)?.path();
            if path.extension().is_none_or(|e| e != "syncr") || !path.is_file() {
                continue;
            }
            let Some((note_id, ms)) = path
                .file_stem()
                .and_then(|s| s.to_str())
                .and_then(|s| s.split_once('.'))
            else {
                continue;
            };
            let (Ok(()), Ok(deleted_at_ms)) = (validation::id(note_id), ms.parse::<i64>()) else {
                continue;
            };
            result.push(Trashed {
                note_id: note_id.into(),
                path,
                deleted_at_ms,
            });
        }
        result.sort_by(|a, b| {
            (b.deleted_at_ms, &a.note_id, &a.path).cmp(&(a.deleted_at_ms, &b.note_id, &b.path))
        });
        Ok(result)
    }
    fn trashed(&self, note_id: &str) -> CoreResult<Vec<Trashed>> {
        Ok(self
            .scan_trash()?
            .into_iter()
            .filter(|t| t.note_id == note_id)
            .collect())
    }
    /// Reads a Recently deleted file read-only, then removes the empty sidecars that a
    /// read-only WAL open leaves, so `.trash/` holds only the deleted files themselves.
    fn read_trashed<T>(
        entry: &Trashed,
        read: impl FnOnce(&Connection, State) -> CoreResult<T>,
    ) -> CoreResult<T> {
        let result = (|| {
            let db = store::open(&entry.path, false)?;
            let state = store::load(&db)?;
            read(&db, state)
        })();
        store::remove_idle_sidecars(&entry.path);
        result
    }
    fn live(catalog: &Catalog, note_id: &str) -> CoreResult<bool> {
        Ok(match catalog.notes.get(note_id).and_then(Weak::upgrade) {
            Some(note) => lock(&note.runtime)?.db.is_some(),
            None => false,
        })
    }
    /// Permanently removes one trash entry. The purge is recorded first, so a crash leaves
    /// at worst a recorded purge whose file is still listed (purging again finishes it).
    fn purge_entry(&self, catalog: &Catalog, entry: &Trashed) -> CoreResult<()> {
        // An unreadable file is still purgeable; it just records no creation ID.
        let creation = Self::read_trashed(entry, |_, state| Ok(state.creation)).ok();
        catalog
            .db
            .execute(
                "INSERT OR REPLACE INTO purged VALUES (?1,?2,?3)",
                params![entry.note_id, creation, store::now_ms()],
            )
            .map_err(storage)?;
        store::fault("purge_after_record");
        store::remove_with_sidecars(&entry.path)?;
        store::sync_directory(&self.trash())
    }
}
#[uniffi::export]
impl SyncrLibrary {
    pub fn author_id(&self) -> Id {
        self.author.clone()
    }
    pub fn list_notes(&self) -> CoreResult<Vec<NoteSummary>> {
        let mut result: Vec<_> = self
            .scan(&mut *lock(&self.catalog)?)?
            .into_iter()
            .map(|p| NoteSummary {
                id: p.state.note_id,
                title: p.state.title,
                local_sequence: p.state.sequence,
                notebook: p.state.notebook,
                created_at_ms: p.created_at_ms,
                modified_at_ms: p.modified_at_ms,
                archived: p.state.archived,
            })
            .collect();
        result.sort_by(|a, b| a.id.cmp(&b.id));
        Ok(result)
    }
    pub fn create_note(&self, request: CreateNoteRequest) -> CoreResult<Receipt> {
        validation::id(&request.operation_id)?;
        validation::id(&request.note_id)?;
        validation::id(&request.first_sheet_id)?;
        if let Some(name) = &request.notebook {
            validation::notebook_name(name)?;
        }
        let canonical = canonical::creation(&request, &self.author)?;
        let mut catalog = lock(&self.catalog)?;
        let existing = self.scan(&mut catalog)?;
        for Published { path, state, .. } in &existing {
            let db = store::open(path, false)?;
            if state.creation == request.operation_id {
                let (old, mut receipt) =
                    store::lookup(&db, &request.operation_id)?.ok_or_else(internal)?;
                if !canonical::creation_matches(&old, &request, &self.author)? {
                    return Err(collision());
                }
                receipt.duplicate = true;
                return Ok(receipt);
            }
            if state.note_id == request.note_id {
                return Err(collision());
            }
        }
        // A deleted note's creation is still recorded: an identical retry reports the
        // original receipt and stays in Recently deleted instead of resurrecting it.
        // An unreadable deleted file can't be matched by creation ID, but its file-name ID still
        // blocks reuse; it never blocks unrelated creations.
        for entry in self.scan_trash()? {
            let read = Self::read_trashed(&entry, |db, state| {
                Ok((store::lookup(db, &state.creation)?, state))
            });
            let Ok((stored, state)) = read else {
                if entry.note_id == request.note_id {
                    return Err(collision());
                }
                continue;
            };
            let retry = (|| {
                if state.creation == request.operation_id {
                    let (old, mut receipt) = stored.ok_or_else(internal)?;
                    if !canonical::creation_matches(&old, &request, &self.author)? {
                        return Err(collision());
                    }
                    receipt.duplicate = true;
                    return Ok(Some(receipt));
                }
                if state.note_id == request.note_id || entry.note_id == request.note_id {
                    return Err(collision());
                }
                Ok(None)
            })()?;
            if let Some(receipt) = retry {
                return Ok(receipt);
            }
        }
        let purged: bool = catalog
            .db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM purged WHERE note_id=?1 OR creation_operation_id=?2)",
                params![request.note_id, request.operation_id],
                |r| r.get(0),
            )
            .map_err(storage)?;
        if purged {
            return Err(CoreError::IdCollision {
                detail: "This ID belongs to a permanently deleted note".into(),
                retryable: false,
            });
        }
        let receipt = Receipt {
            operation_id: request.operation_id.clone(),
            local_sequence: 1,
            cursor: EventCursor {
                stream_id: new_id(),
                sequence: 1,
            },
            duplicate: false,
        };
        let temp = store::temporary(&self.root, ".syncr-create-")?;
        let result = (|| {
            store::initialize(&temp, &request, &self.author, &canonical, &receipt)?;
            store::publish(&temp, &self.root.join(format!("{}.syncr", request.note_id)))?;
            // Refresh the optional cache; published metadata remains authoritative.
            self.scan(&mut catalog)?;
            Ok(receipt)
        })();
        store::cleanup(&temp);
        result
    }
    pub fn open_note(self: &Arc<Self>, note_id: Id) -> CoreResult<Arc<Note>> {
        validation::id(&note_id)?;
        let mut catalog = lock(&self.catalog)?;
        if let Some(note) = catalog.notes.get(&note_id).and_then(Weak::upgrade)
            && lock(&note.runtime)?.db.is_some()
        {
            return Ok(note);
        }
        let path = self
            .scan(&mut catalog)?
            .into_iter()
            .find(|p| p.state.note_id == note_id)
            .ok_or_else(missing)?
            .path;
        let db = store::open(&path, true)?;
        // A note written before the canvas amendment becomes a canvas here, after a
        // backup copy; failure leaves it unchanged and unopened.
        store::upgrade(&db, &self.root.join(store::BACKUP_DIR))?;
        let state = store::load(&db)?;
        let note = Arc::new(Note {
            library: self.clone(),
            path,
            runtime: Mutex::new(Runtime {
                db: Some(db),
                state,
                stream: new_id(),
                sequence: 0,
                events: VecDeque::new(),
            }),
        });
        catalog.notes.insert(note_id, Arc::downgrade(&note));
        Ok(note)
    }
    /// Committed for notes in the library AND in Recently deleted (the creation happened and
    /// its record still exists; lookup never restores the note). NotRecorded once purged.
    pub fn lookup_creation(&self, operation_id: Id) -> CoreResult<OperationLookup> {
        validation::id(&operation_id)?;
        let mut catalog = lock(&self.catalog)?;
        for Published { path, state, .. } in self.scan(&mut catalog)? {
            if state.creation == operation_id {
                let db = store::open(&path, false)?;
                let (_, receipt) = store::lookup(&db, &operation_id)?.ok_or_else(internal)?;
                return Ok(OperationLookup::Committed { receipt });
            }
        }
        // Unreadable deleted files are skipped here; a creation retry still collides on its ID.
        for entry in self.scan_trash()? {
            let found = Self::read_trashed(&entry, |db, state| {
                if state.creation != operation_id {
                    return Ok(None);
                }
                Ok(Some(
                    store::lookup(db, &operation_id)?.ok_or_else(internal)?.1,
                ))
            });
            if let Ok(Some(receipt)) = found {
                return Ok(OperationLookup::Committed { receipt });
            }
        }
        Ok(OperationLookup::NotRecorded)
    }
    /// Moves a closed note into Recently deleted with one no-overwrite rename. Its WAL is
    /// folded first so the moved file holds every commit. `NoteLocked` while a live handle
    /// has it open. Idempotent: an already-deleted note returns Ok.
    pub fn delete_note(&self, note_id: Id) -> CoreResult<()> {
        validation::id(&note_id)?;
        let mut catalog = lock(&self.catalog)?;
        if Self::live(&catalog, &note_id)? {
            return Err(CoreError::NoteLocked {
                detail: "Close the note before deleting it".into(),
                retryable: true,
            });
        }
        let Some(published) = self
            .scan(&mut catalog)?
            .into_iter()
            .find(|p| p.state.note_id == note_id)
        else {
            return if self.trashed(&note_id)?.is_empty() {
                Err(missing())
            } else {
                Ok(())
            };
        };
        store::fold_journal(&published.path)?;
        let trash = self.trash();
        if !trash.is_dir() {
            std::fs::create_dir_all(&trash).map_err(io)?;
            store::sync_directory(&self.root)?;
        }
        let destination = trash.join(format!("{note_id}.{}.syncr", store::now_ms()));
        store::fault("delete_before_rename");
        store::move_noreplace(&published.path, &destination)?;
        store::fault("delete_after_rename");
        store::remove_idle_sidecars(&published.path);
        let _ = self.scan(&mut catalog);
        Ok(())
    }
    /// Recently deleted, newest deletion first.
    pub fn list_deleted_notes(&self) -> CoreResult<Vec<DeletedNoteSummary>> {
        let _catalog = lock(&self.catalog)?;
        self.scan_trash()?
            .into_iter()
            .map(|entry| {
                Self::read_trashed(&entry, |db, state| {
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
    /// Moves a deleted note back to `<root>/<note-id>.syncr` without overwriting. A library
    /// note with the same ID (or file name) returns `IdCollision`. Idempotent: a note that is
    /// already back in the library returns Ok.
    pub fn restore_note(&self, note_id: Id) -> CoreResult<()> {
        validation::id(&note_id)?;
        let mut catalog = lock(&self.catalog)?;
        let in_library = self
            .scan(&mut catalog)?
            .iter()
            .any(|p| p.state.note_id == note_id);
        let mut entries = self.trashed(&note_id)?;
        if entries.is_empty() {
            return if in_library { Ok(()) } else { Err(missing()) };
        }
        if in_library || entries.len() > 1 {
            return Err(collision());
        }
        let entry = entries.remove(0);
        store::move_noreplace(&entry.path, &self.root.join(format!("{note_id}.syncr")))?;
        store::fault("restore_after_rename");
        store::remove_idle_sidecars(&entry.path);
        catalog
            .db
            .execute("DELETE FROM purged WHERE note_id=?1", [&note_id])
            .map_err(storage)?;
        let _ = self.scan(&mut catalog);
        Ok(())
    }
    /// Permanently removes a deleted note's file and SQLite sidecars. Only notes in Recently
    /// deleted can be purged (`InvalidInput` for a library note). Idempotent once purged.
    pub fn purge_note(&self, note_id: Id) -> CoreResult<()> {
        validation::id(&note_id)?;
        let mut catalog = lock(&self.catalog)?;
        let entries = self.trashed(&note_id)?;
        if entries.is_empty() {
            let purged: bool = catalog
                .db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM purged WHERE note_id=?1)",
                    [&note_id],
                    |r| r.get(0),
                )
                .map_err(storage)?;
            if purged {
                return Ok(());
            }
            if self
                .scan(&mut catalog)?
                .iter()
                .any(|p| p.state.note_id == note_id)
            {
                return Err(invalid("Only notes in Recently deleted can be purged"));
            }
            return Err(missing());
        }
        for entry in &entries {
            self.purge_entry(&catalog, entry)?;
        }
        Ok(())
    }
    /// Purges every Recently deleted note deleted strictly before `cutoff_ms`. Returns how
    /// many were purged. Stops at the first failure; already purged ones stay purged.
    pub fn purge_deleted_older_than(&self, cutoff_ms: i64) -> CoreResult<u32> {
        let catalog = lock(&self.catalog)?;
        let mut count = 0u32;
        for entry in self.scan_trash()? {
            if entry.deleted_at_ms < cutoff_ms {
                self.purge_entry(&catalog, &entry)?;
                count = count.saturating_add(1);
            }
        }
        Ok(count)
    }
    /// Registered names plus every name used by a note, sorted case-insensitively.
    pub fn list_notebooks(&self) -> CoreResult<Vec<NotebookSummary>> {
        let mut catalog = lock(&self.catalog)?;
        let mut counts = BTreeMap::<String, u64>::new();
        for p in self.scan(&mut catalog)? {
            if let Some(name) = p.state.notebook {
                *counts.entry(name).or_default() += 1;
            }
        }
        let mut statement = catalog
            .db
            .prepare("SELECT name FROM notebooks")
            .map_err(storage)?;
        let names = statement
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(storage)?;
        for name in names {
            counts.entry(name.map_err(storage)?).or_default();
        }
        let mut result: Vec<_> = counts
            .into_iter()
            .map(|(name, note_count)| NotebookSummary { name, note_count })
            .collect();
        result.sort_by(|a, b| {
            (a.name.to_lowercase(), &a.name).cmp(&(b.name.to_lowercase(), &b.name))
        });
        Ok(result)
    }
    /// Registers an (empty) notebook. Idempotent when it already exists.
    pub fn create_notebook(&self, name: String) -> CoreResult<()> {
        validation::notebook_name(&name)?;
        lock(&self.catalog)?
            .db
            .execute("INSERT OR IGNORE INTO notebooks VALUES (?1)", [&name])
            .map_err(storage)?;
        Ok(())
    }
    /// Removes only the registry entry. Notes must be moved out first.
    pub fn delete_notebook(&self, name: String) -> CoreResult<()> {
        validation::notebook_name(&name)?;
        let mut catalog = lock(&self.catalog)?;
        if self
            .scan(&mut catalog)?
            .iter()
            .any(|p| p.state.notebook.as_deref() == Some(name.as_str()))
        {
            return Err(invalid("Notebook still contains notes"));
        }
        catalog
            .db
            .execute("DELETE FROM notebooks WHERE name=?1", [&name])
            .map_err(storage)?;
        Ok(())
    }
    /// Renames the registry entry, then submits one SetNotebook per member note,
    /// each a separate durable operation with a fresh ID through that note's
    /// serialized writer. Not atomic across notes: on error, retrying the same call
    /// moves the remainder (moved notes no longer match `old_name`). Renaming onto an
    /// existing name merges the two notebooks.
    pub fn rename_notebook(self: &Arc<Self>, old_name: String, new_name: String) -> CoreResult<()> {
        validation::notebook_name(&old_name)?;
        validation::notebook_name(&new_name)?;
        if old_name == new_name {
            return Ok(());
        }
        let members: Vec<Id> = {
            let mut catalog = lock(&self.catalog)?;
            let tx = catalog.db.transaction().map_err(storage)?;
            let registered = tx
                .execute("DELETE FROM notebooks WHERE name=?1", [&old_name])
                .map_err(storage)?;
            if registered > 0 {
                tx.execute("INSERT OR IGNORE INTO notebooks VALUES (?1)", [&new_name])
                    .map_err(storage)?;
            }
            tx.commit().map_err(storage)?;
            self.scan(&mut catalog)?
                .into_iter()
                .filter(|p| p.state.notebook.as_deref() == Some(old_name.as_str()))
                .map(|p| p.state.note_id)
                .collect()
        };
        for note_id in members {
            // open_note shares an already-open live writer, so this serializes with
            // the app's own submissions to the same note.
            let note = self.open_note(note_id)?;
            let snapshot = note.snapshot()?;
            if snapshot.notebook.as_deref() != Some(old_name.as_str()) {
                continue;
            }
            let head = snapshot.metadata_head.operation_id;
            note.submit(Command {
                operation_id: new_id(),
                dependencies: vec![head.clone()],
                body: CommandBody::SetNotebook {
                    notebook: Some(new_name.clone()),
                    base: Base::Operation { operation_id: head },
                },
            })?;
        }
        Ok(())
    }
}
struct Runtime {
    db: Option<Connection>,
    state: State,
    stream: Id,
    sequence: u64,
    events: VecDeque<ChangeEvent>,
}
impl Runtime {
    fn db(&self) -> CoreResult<&Connection> {
        self.db.as_ref().ok_or_else(closed)
    }
    fn cursor(&self) -> EventCursor {
        EventCursor {
            stream_id: self.stream.clone(),
            sequence: self.sequence,
        }
    }
}
#[derive(uniffi::Object)]
pub struct Note {
    library: Arc<SyncrLibrary>,
    path: PathBuf,
    runtime: Mutex<Runtime>,
}
#[uniffi::export]
impl Note {
    pub fn snapshot(&self) -> CoreResult<NoteSnapshot> {
        let r = lock(&self.runtime)?;
        r.db()?;
        Ok(r.state.snapshot(&self.library.author, r.cursor()))
    }
    pub fn get_samples(&self, mark_id: Id) -> CoreResult<Vec<u8>> {
        let r = lock(&self.runtime)?;
        let db = r.db()?;
        validation::id(&mark_id)?;
        db.query_row(
            "SELECT bytes FROM samples WHERE mark_id=?1",
            [mark_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(storage)?
        .ok_or_else(missing)
    }
    pub fn submit(&self, mut command: Command) -> CoreResult<Receipt> {
        let mut r = lock(&self.runtime)?;
        r.db()?;
        validation::id(&command.operation_id)?;
        command.dependencies.sort();
        command.dependencies.dedup();
        let bytes = canonical::command(&command, &r.state.note_id, &self.library.author)?;
        if let Some((old, mut receipt)) = store::lookup(r.db()?, &command.operation_id)? {
            if old != bytes {
                return Err(collision());
            }
            receipt.duplicate = true;
            return Ok(receipt);
        }
        validation::command(&command)?;
        for dep in &command.dependencies {
            if store::lookup(r.db()?, dep)?.is_none() {
                return Err(dependency());
            }
        }
        let mut candidate = r.state.clone();
        let kind = candidate.apply(&command, &self.library.author)?;
        candidate.sequence = candidate
            .sequence
            .checked_add(1)
            .ok_or_else(|| crate::error::limit("Local sequence exhausted"))?;
        let event_sequence = r.sequence.checked_add(1).ok_or_else(internal)?;
        let receipt = Receipt {
            operation_id: command.operation_id.clone(),
            local_sequence: candidate.sequence,
            cursor: EventCursor {
                stream_id: r.stream.clone(),
                sequence: event_sequence,
            },
            duplicate: false,
        };
        let mark_effects = candidate.mark_effects_since(&r.state);
        let tx =
            r.db.as_mut()
                .ok_or_else(closed)?
                .transaction()
                .map_err(storage)?;
        store::save(&tx, &candidate)?;
        if !mark_effects.is_empty() {
            tx.execute(
                "INSERT INTO mark_effects VALUES (?1,?2)",
                params![command.operation_id, store::json(&mark_effects)?],
            )
            .map_err(storage)?;
        }
        if let CommandBody::Marks { mutations } = &command.body {
            for m in mutations {
                if let MarkMutation::AddStroke {
                    mark_id, samples, ..
                } = m
                {
                    tx.execute(
                        "INSERT INTO samples VALUES (?1,?2)",
                        params![mark_id, samples],
                    )
                    .map_err(storage)?;
                }
            }
        }
        store::record(
            &tx,
            &candidate,
            &self.library.author,
            &bytes,
            &command,
            &receipt,
            store::now_ms(),
        )?;
        store::fault("command_before_commit");
        tx.commit().map_err(storage)?;
        store::fault("command_after_commit");
        r.state = candidate;
        r.sequence = event_sequence;
        r.events.push_back(ChangeEvent {
            cursor: receipt.cursor.clone(),
            operation_id: command.operation_id,
            kind,
        });
        if r.events.len() > 256 {
            r.events.pop_front();
        }
        Ok(receipt)
    }
    pub fn lookup_operation(&self, operation_id: Id) -> CoreResult<OperationLookup> {
        let r = lock(&self.runtime)?;
        let db = r.db()?;
        validation::id(&operation_id)?;
        Ok(match store::lookup(db, &operation_id)? {
            Some((_, receipt)) => OperationLookup::Committed { receipt },
            None => OperationLookup::NotRecorded,
        })
    }
    pub fn changes_since(&self, cursor: EventCursor, limit: u32) -> CoreResult<ChangePage> {
        let r = lock(&self.runtime)?;
        r.db()?;
        validation::id(&cursor.stream_id)?;
        if !(1..=256).contains(&limit) {
            return Err(invalid("Event page limit must be in [1,256]"));
        }
        let oldest = r
            .events
            .front()
            .map(|e| e.cursor.sequence - 1)
            .unwrap_or(r.sequence);
        if cursor.stream_id != r.stream || cursor.sequence > r.sequence || cursor.sequence < oldest
        {
            return Ok(ChangePage::ResyncRequired);
        }
        let items: Vec<_> = r
            .events
            .iter()
            .filter(|e| e.cursor.sequence > cursor.sequence)
            .take(limit as usize)
            .cloned()
            .collect();
        let next_cursor = items.last().map(|e| e.cursor.clone()).unwrap_or(cursor);
        let has_more = next_cursor.sequence < r.sequence;
        Ok(ChangePage::Events {
            items,
            next_cursor,
            has_more,
        })
    }
    pub fn export_copy(&self, destination_path: String) -> CoreResult<ExportReceipt> {
        let r = lock(&self.runtime)?;
        let db = r.db()?;
        path_input(
            &destination_path,
            "Export destination must be an app-owned filesystem path",
        )?;
        let destination = Path::new(&destination_path);
        if destination.exists() {
            return Err(collision());
        }
        let parent = destination
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        let temp = store::temporary(parent, ".syncr-export-")?;
        let result = (|| {
            store::backup(db, &temp)?;
            store::publish(&temp, destination)?;
            Ok(ExportReceipt {
                note_id: r.state.note_id.clone(),
                exported_local_sequence: r.state.sequence,
            })
        })();
        store::cleanup(&temp);
        result
    }
    pub fn close_note(&self) -> CoreResult<()> {
        let mut r = lock(&self.runtime)?;
        let Some(db) = r.db.as_ref() else {
            return Ok(());
        };
        store::checkpoint(db)?;
        store::sync_directory(self.path.parent().ok_or_else(internal)?)?;
        let db = r.db.take().ok_or_else(closed)?;
        if let Err((db, error)) = db.close() {
            r.db = Some(db);
            return Err(storage(error));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn setup() -> (
        tempfile::TempDir,
        Arc<SyncrLibrary>,
        CreateNoteRequest,
        Arc<Note>,
    ) {
        let root = tempfile::tempdir().unwrap();
        let library = open_library(root.path().to_str().unwrap().into()).unwrap();
        let request = CreateNoteRequest {
            operation_id: new_id(),
            note_id: new_id(),
            first_sheet_id: new_id(),
            title: "Failure fixture".into(),
            notebook: None,
        };
        library.create_note(request.clone()).unwrap();
        let note = library.open_note(request.note_id.clone()).unwrap();
        (root, library, request, note)
    }
    #[test]
    fn actual_sqlite_full_rolls_back_projection_samples_log_history_and_events() {
        let (root, library, request, note) = setup();
        let before = note.snapshot().unwrap();
        let command = crate::failure_tests::large_add(&note, &request);
        {
            let r = note.runtime.lock().unwrap();
            let db = r.db().unwrap();
            store::checkpoint(db).unwrap();
            let count: u32 = db
                .pragma_query_value(None, "page_count", |r| r.get(0))
                .unwrap();
            db.pragma_update(None, "max_page_count", count).unwrap();
        }
        assert!(matches!(
            note.submit(command.clone()),
            Err(CoreError::StorageFull {
                retryable: true,
                ..
            })
        ));
        assert_eq!(note.snapshot().unwrap(), before);
        assert_eq!(
            note.lookup_operation(command.operation_id.clone()).unwrap(),
            OperationLookup::NotRecorded
        );
        let mark = match &command.body {
            CommandBody::Marks { mutations } => match &mutations[0] {
                MarkMutation::AddStroke { mark_id, .. } => mark_id.clone(),
                _ => unreachable!(),
            },
            _ => unreachable!(),
        };
        assert!(matches!(
            note.get_samples(mark),
            Err(CoreError::NotFound { .. })
        ));
        note.close_note().unwrap();
        drop(note);
        drop(library);
        let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
        let note = lib.open_note(request.note_id).unwrap();
        assert_eq!(note.snapshot().unwrap().local_sequence, 1);
        assert_eq!(note.submit(command).unwrap().local_sequence, 2);
    }
    #[test]
    fn stale_inverse_heads_abort_the_entire_batch() {
        for redo in [false, true] {
            let (_root, _lib, request, note) = setup();
            let mut c = crate::failure_tests::large_add(&note, &request);
            if let CommandBody::Marks { mutations } = &mut c.body {
                let mut second = mutations[0].clone();
                if let MarkMutation::AddStroke { mark_id, .. } = &mut second {
                    *mark_id = new_id();
                }
                mutations.push(second);
            }
            note.submit(c.clone()).unwrap();
            let expected_head = if redo {
                let before = note.snapshot().unwrap();
                let undo = Command {
                    operation_id: new_id(),
                    dependencies: before.undo_dependencies,
                    body: CommandBody::Undo {
                        target_operation_id: c.operation_id.clone(),
                    },
                };
                note.submit(undo.clone()).unwrap();
                undo.operation_id
            } else {
                c.operation_id.clone()
            };
            let foreign = new_id();
            {
                // Simulate a future foreign-author head without weakening the checked inverse.
                let mut r = note.runtime.lock().unwrap();
                r.state.strokes[1].head.operation_id = foreign.clone();
            }
            let before = note.snapshot().unwrap();
            let mut expected = vec![foreign.clone(), expected_head];
            expected.sort();
            let dependencies = if redo {
                &before.redo_dependencies
            } else {
                &before.undo_dependencies
            };
            assert_eq!(dependencies, &expected);
            let undo = Command {
                operation_id: new_id(),
                dependencies: dependencies.clone(),
                body: if redo {
                    CommandBody::Redo {
                        target_operation_id: c.operation_id,
                    }
                } else {
                    CommandBody::Undo {
                        target_operation_id: c.operation_id,
                    }
                },
            };
            // Insert a durable dependency so contextual dependency lookup doesn't mask the head check.
            {
                let r = note.runtime.lock().unwrap();
                let receipt = Receipt {
                    operation_id: foreign,
                    local_sequence: 99,
                    cursor: r.cursor(),
                    duplicate: false,
                };
                let mut fake_state = r.state.clone();
                fake_state.sequence = 99;
                store::record(
                    r.db().unwrap(),
                    &fake_state,
                    "test",
                    b"test",
                    &"foreign",
                    &receipt,
                    0,
                )
                .unwrap();
            }
            assert!(matches!(
                note.submit(undo),
                Err(CoreError::StaleBase { .. })
            ));
            assert_eq!(note.snapshot().unwrap(), before);
        }
    }
    #[test]
    fn close_checkpoint_failure_keeps_handle_open_and_export_failure_is_not_success() {
        let (root, _lib, request, note) = setup();
        let reader = Connection::open(&note.path).unwrap();
        reader
            .execute_batch("BEGIN; SELECT state FROM document;")
            .unwrap();
        let c = Command {
            operation_id: new_id(),
            dependencies: vec![request.operation_id.clone()],
            body: CommandBody::RenameNote {
                title: "Pending checkpoint".into(),
                base: Base::Operation {
                    operation_id: request.operation_id,
                },
            },
        };
        note.submit(c).unwrap();
        note.runtime
            .lock()
            .unwrap()
            .db()
            .unwrap()
            .busy_timeout(std::time::Duration::ZERO)
            .unwrap();
        assert!(matches!(
            note.close_note(),
            Err(CoreError::NoteLocked {
                retryable: true,
                ..
            })
        ));
        assert_eq!(note.snapshot().unwrap().local_sequence, 2);
        reader.execute_batch("ROLLBACK").unwrap();
        let blocked = root.path().join("not-a-directory");
        std::fs::write(&blocked, b"sentinel").unwrap();
        assert!(matches!(
            note.export_copy(blocked.join("copy.syncr").to_str().unwrap().into()),
            Err(CoreError::StorageIo { .. })
        ));
        assert_eq!(std::fs::read(&blocked).unwrap(), b"sentinel");
        assert!(!std::fs::read_dir(root.path()).unwrap().any(|e| {
            e.unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".syncr-export-")
        }));
        note.close_note().unwrap();
    }
    #[test]
    fn interrupted_index_update_is_recovered_from_published_metadata() {
        let root = tempfile::tempdir().unwrap();
        let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
        lib.catalog.lock().unwrap().db.execute_batch("CREATE TRIGGER fail_index BEFORE INSERT ON notes BEGIN SELECT RAISE(FAIL,'test'); END;").unwrap();
        let request = CreateNoteRequest {
            operation_id: new_id(),
            note_id: new_id(),
            first_sheet_id: new_id(),
            title: "Lost creation response".into(),
            notebook: None,
        };
        // Deliberately lose the successful reply while the cache is still unwritable.
        lib.create_note(request.clone()).unwrap();
        assert!(matches!(
            lib.lookup_creation(request.operation_id.clone()).unwrap(),
            OperationLookup::Committed { .. }
        ));
        assert!(lib.create_note(request).unwrap().duplicate);
        assert_eq!(lib.list_notes().unwrap().len(), 1);
    }
    #[test]
    fn delete_folds_an_unfolded_wal_so_restore_keeps_every_commit() {
        let (root, library, request, note) = setup();
        let command = crate::failure_tests::large_add(&note, &request);
        note.submit(command.clone()).unwrap();
        let before = note.snapshot().unwrap();
        // Simulate a process that died with the commit only in the WAL: leak the connection.
        {
            let mut r = note.runtime.lock().unwrap();
            let db = r.db.take().unwrap();
            db.pragma_update(None, "wal_autocheckpoint", 0).unwrap();
            std::mem::forget(db);
        }
        let wal = store::sidecar(&note.path, "-wal");
        assert!(std::fs::metadata(&wal).unwrap().len() > 0);
        // The handle is no longer live (closed), so deleting is allowed.
        library.delete_note(request.note_id.clone()).unwrap();
        assert!(!note.path.exists());
        assert!(!std::fs::metadata(&wal).is_ok_and(|m| m.len() > 0));
        library.restore_note(request.note_id.clone()).unwrap();
        let reopened = library.open_note(request.note_id.clone()).unwrap();
        let mut after = reopened.snapshot().unwrap();
        after.cursor = before.cursor.clone();
        assert_eq!(after, before);
        assert!(reopened.submit(command).unwrap().duplicate);
        reopened.close_note().unwrap();
        drop(root);
    }
    #[test]
    fn note_retains_library_lock_and_connections_use_full_wal() {
        let (root, library, request, note) = setup();
        let author = library.author_id();
        for db in [
            &library.catalog.lock().unwrap().db,
            note.runtime.lock().unwrap().db().unwrap(),
        ] {
            assert_eq!(
                db.pragma_query_value::<String, _>(None, "journal_mode", |r| r.get(0))
                    .unwrap(),
                "wal"
            );
            assert_eq!(
                db.pragma_query_value::<u32, _>(None, "synchronous", |r| r.get(0))
                    .unwrap(),
                2
            );
        }
        drop(library);
        assert!(matches!(
            open_library(root.path().to_str().unwrap().into()),
            Err(CoreError::NoteLocked { .. })
        ));
        note.close_note().unwrap();
        drop(note);
        let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
        assert_eq!(lib.author_id(), author);
        assert_eq!(
            lib.open_note(request.note_id)
                .unwrap()
                .snapshot()
                .unwrap()
                .local_sequence,
            1
        );
    }
}
