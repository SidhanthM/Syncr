//! The library: a root directory of published `<NOTE_ID>.syncr` files plus
//! `library.sqlite3` (installation author, notes cache, notebook registry, purge records).
//! Every library operation holds the catalog mutex, so library calls are serialized; each
//! open note serializes its own writes.
//!
//! This module is the exported API. Its methods delegate to the submodule named after the
//! area they belong to. Every UniFFI export stays here because UniFFI emits metadata in
//! symbol order per codegen unit (one per module), so moving an export into a submodule
//! reorders the generated bindings.
mod catalog;
mod note;
mod notebooks;
pub(crate) mod sync;
#[cfg(test)]
mod tests;
mod trash;

use crate::{
    error::{internal, invalid, io, locked, storage, unsupported},
    files, store, validation, *,
};
use catalog::Catalog;
use fs2::FileExt;
use rusqlite::{Connection, OptionalExtension};
use std::{
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard},
};

/// v1: installation + notes cache. v2: `notebooks` registry (names only; note
/// metadata stays authoritative for membership). v3: `purged` record of permanently
/// deleted note/creation IDs so a stale creation retry cannot recreate them.
const LIBRARY_VERSION: u32 = 3;
/// Every version only adds tables, so this one idempotent script migrates any older library.
const LIBRARY_SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS installation(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, local_sequence BLOB NOT NULL, path TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS notebooks(name TEXT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS purged(note_id TEXT PRIMARY KEY, creation_operation_id TEXT, purged_at_ms INTEGER NOT NULL);";
const LIBRARY_DB: &str = "library.sqlite3";
const WRITER_LOCK: &str = ".syncr-writer.lock";

#[derive(uniffi::Object)]
pub struct SyncrLibrary {
    author: Id,
    root: PathBuf,
    catalog: Mutex<Catalog>,
    // Held for the library's lifetime; open notes keep the library, and so the lock, alive.
    _lock: File,
}

#[derive(uniffi::Object)]
pub struct Note {
    library: Arc<SyncrLibrary>,
    path: PathBuf,
    runtime: Mutex<note::Runtime>,
}

fn lock<T>(value: &Mutex<T>) -> CoreResult<MutexGuard<'_, T>> {
    value.lock().map_err(|_| internal())
}

/// Rejects paths the core cannot use directly, such as Android SAF `content:` URIs.
fn path_input(path: &str, diagnostic: &str) -> CoreResult<()> {
    if path.is_empty() || path.starts_with("content:") {
        return Err(invalid(diagnostic));
    }
    Ok(())
}

#[uniffi::export]
pub fn open_library(root_path: String) -> CoreResult<Arc<SyncrLibrary>> {
    path_input(
        &root_path,
        "Library root must be an app-owned filesystem path",
    )?;
    let root = Path::new(&root_path);
    std::fs::create_dir_all(root).map_err(io)?;
    let writer = acquire_writer_lock(root)?;
    let (db, author) = open_catalog(root)?;
    remove_unpublished_temporaries(root)?;
    let library = Arc::new(SyncrLibrary {
        author,
        root: root.into(),
        catalog: Mutex::new(Catalog::new(db)),
        _lock: writer,
    });
    library.scan(&mut *lock(&library.catalog)?)?;
    Ok(library)
}

/// One writer per library root, across processes. Advisory: it protects cooperating core
/// instances, not against other apps.
fn acquire_writer_lock(root: &Path) -> CoreResult<File> {
    let writer = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(root.join(WRITER_LOCK))
        .map_err(io)?;
    writer.try_lock_exclusive().map_err(|error| {
        if error.kind() == std::io::ErrorKind::WouldBlock {
            locked("Library is already open by a writer")
        } else {
            io(error)
        }
    })?;
    Ok(writer)
}

/// Opens `library.sqlite3`, creating or migrating it, and returns it with the persistent
/// installation author ID. A newer schema is refused before anything is written.
fn open_catalog(root: &Path) -> CoreResult<(Connection, Id)> {
    let mut db = Connection::open(root.join(LIBRARY_DB)).map_err(storage)?;
    let version: u32 = db
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(storage)?;
    if version > LIBRARY_VERSION {
        return Err(unsupported("Unsupported library schema version"));
    }
    store::configure(&db)?;
    let tx = db.transaction().map_err(storage)?;
    tx.execute_batch(LIBRARY_SCHEMA).map_err(storage)?;
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
    Ok((db, author))
}

/// Removes temporaries left by a crash: unpublished note creations and pre-upgrade backup
/// copies. Neither was ever reported as saved. Only our reserved prefixes are touched.
fn remove_unpublished_temporaries(root: &Path) -> CoreResult<()> {
    files::remove_prefixed(root, catalog::CREATE_TEMP_PREFIX)?;
    files::remove_prefixed(root, sync::REBASE_PREFIX)?;
    files::sync(root)?;
    let backups = root.join(store::BACKUP_DIR);
    if backups.is_dir() {
        files::remove_prefixed(&backups, store::BACKUP_TEMP_PREFIX)?;
    }
    Ok(())
}

#[uniffi::export]
impl SyncrLibrary {
    pub fn author_id(&self) -> Id {
        self.author.clone()
    }
    pub fn list_notes(&self) -> CoreResult<Vec<NoteSummary>> {
        catalog::list_notes(self)
    }
    pub fn create_note(&self, request: CreateNoteRequest) -> CoreResult<Receipt> {
        catalog::create_note(self, request)
    }
    pub fn open_note(self: &Arc<Self>, note_id: Id) -> CoreResult<Arc<Note>> {
        catalog::open_note(self, note_id)
    }
    /// Committed for notes in the library AND in Recently deleted (the creation happened and
    /// its record still exists; lookup never restores the note). NotRecorded once purged.
    pub fn lookup_creation(&self, operation_id: Id) -> CoreResult<OperationLookup> {
        catalog::lookup_creation(self, operation_id)
    }
    /// Moves a closed note into Recently deleted with one no-overwrite rename. Its WAL is
    /// folded first so the moved file holds every commit. `NoteLocked` while a live handle
    /// has it open. Idempotent: an already-deleted note returns Ok.
    pub fn delete_note(&self, note_id: Id) -> CoreResult<()> {
        trash::delete_note(self, note_id)
    }
    /// Recently deleted, newest deletion first.
    pub fn list_deleted_notes(&self) -> CoreResult<Vec<DeletedNoteSummary>> {
        trash::list_deleted_notes(self)
    }
    /// Moves a deleted note back to `<root>/<note-id>.syncr` without overwriting. A library
    /// note with the same ID (or file name) returns `IdCollision`. Idempotent: a note that is
    /// already back in the library returns Ok.
    pub fn restore_note(&self, note_id: Id) -> CoreResult<()> {
        trash::restore_note(self, note_id)
    }
    /// Permanently removes a deleted note's file and SQLite sidecars. Only notes in Recently
    /// deleted can be purged (`InvalidInput` for a library note). Idempotent once purged.
    pub fn purge_note(&self, note_id: Id) -> CoreResult<()> {
        trash::purge_note(self, note_id)
    }
    /// Purges every Recently deleted note deleted strictly before `cutoff_ms`. Returns how
    /// many were purged. Stops at the first failure; already purged ones stay purged.
    pub fn purge_deleted_older_than(&self, cutoff_ms: i64) -> CoreResult<u32> {
        trash::purge_deleted_older_than(self, cutoff_ms)
    }
    /// Registered names plus every name used by a note, sorted case-insensitively.
    pub fn list_notebooks(&self) -> CoreResult<Vec<NotebookSummary>> {
        notebooks::list_notebooks(self)
    }
    /// Registers an (empty) notebook. Idempotent when it already exists.
    pub fn create_notebook(&self, name: String) -> CoreResult<()> {
        notebooks::create_notebook(self, name)
    }
    /// Removes only the registry entry. Notes must be moved out first.
    pub fn delete_notebook(&self, name: String) -> CoreResult<()> {
        notebooks::delete_notebook(self, name)
    }
    /// Renames the registry entry, then submits one SetNotebook per member note,
    /// each a separate durable operation with a fresh ID through that note's
    /// serialized writer. Not atomic across notes: on error, retrying the same call
    /// moves the remainder (moved notes no longer match `old_name`). Renaming onto an
    /// existing name merges the two notebooks.
    pub fn rename_notebook(self: &Arc<Self>, old_name: String, new_name: String) -> CoreResult<()> {
        notebooks::rename_notebook(self, old_name, new_name)
    }
    /// Places a validated copy of a note this library does not have, never overwriting.
    /// `IdCollision` when the note is published, in Recently deleted or purged. The copy is
    /// not migrated; an old-format note upgrades when first opened.
    pub fn install_copy(&self, copy_path: String) -> CoreResult<NoteSummary> {
        sync::install_copy(self, copy_path)
    }
    /// Phone side of a conflict: rebuilds the closed note as the copy plus this file's own
    /// operations that still apply, after a backup. `rejected` holds the local operations
    /// that were dropped. `NoteLocked` while a handle is open.
    pub fn rebase_onto_copy(
        self: &Arc<Self>,
        note_id: Id,
        copy_path: String,
    ) -> CoreResult<MergeReport> {
        sync::rebase_onto_copy(self, note_id, copy_path)
    }
    /// `Note::sync_digest` for a note that may be closed (read-only when closed).
    pub fn note_sync_digest(&self, note_id: Id) -> CoreResult<SyncDigest> {
        sync::note_sync_digest(self, note_id)
    }
}

#[uniffi::export]
impl Note {
    pub fn snapshot(&self) -> CoreResult<NoteSnapshot> {
        note::snapshot(self)
    }
    pub fn get_samples(&self, mark_id: Id) -> CoreResult<Vec<u8>> {
        note::get_samples(self, mark_id)
    }
    /// Original immutable PNG/JPEG container, also available for tombstoned marks.
    pub fn get_image_bytes(&self, image_id: Id) -> CoreResult<Vec<u8>> {
        note::get_image_bytes(self, image_id)
    }
    pub fn submit(&self, command: Command) -> CoreResult<Receipt> {
        note::submit(self, command)
    }
    pub fn lookup_operation(&self, operation_id: Id) -> CoreResult<OperationLookup> {
        note::lookup_operation(self, operation_id)
    }
    pub fn changes_since(&self, cursor: EventCursor, limit: u32) -> CoreResult<ChangePage> {
        note::changes_since(self, cursor, limit)
    }
    pub fn export_copy(&self, destination_path: String) -> CoreResult<ExportReceipt> {
        note::export_copy(self, destination_path)
    }
    /// Operation count and SHA-256 over the sorted operation IDs; cached until the next commit.
    pub fn sync_digest(&self) -> CoreResult<SyncDigest> {
        sync::digest(self)
    }
    /// Appends every operation of `copy_path` this note lacks, under its stored author.
    /// Rejected operations are reported, not applied. See `tasks/contracts/sync-v1.md`.
    pub fn merge_copy(&self, copy_path: String) -> CoreResult<MergeReport> {
        sync::merge_copy(self, copy_path)
    }
    pub fn close_note(&self) -> CoreResult<()> {
        note::close_note(self)
    }
    /// Stable companion undo owner, distinct from this installation's user author.
    pub fn agent_author_id(&self) -> Id {
        let id: ulid::Ulid = self
            .library
            .author
            .parse()
            .expect("validated installation author");
        ulid::Ulid::from(u128::from(id) ^ 1).to_string()
    }
    pub fn agent_snapshot(&self) -> CoreResult<NoteSnapshot> {
        let r = lock(&self.runtime)?;
        r.db()?;
        Ok(r.state.snapshot(&self.agent_author_id(), r.cursor()))
    }
    /// The only agent text write is insertion of an existing drawing's embed line.
    pub fn submit_agent(&self, command: Command) -> CoreResult<Receipt> {
        match &command.body {
            CommandBody::ImageMarks { mutations }
                if mutations
                    .iter()
                    .all(|m| matches!(m, ImageMutation::MarkSeen { .. })) => {}
            CommandBody::CompanionMarks { .. }
            | CommandBody::Undo { .. }
            | CommandBody::Redo { .. } => {}
            CommandBody::Marks { mutations } if mutations.len() == 1 => {
                let MarkMutation::SpliceText {
                    delete: 0, insert, ..
                } = &mutations[0]
                else {
                    return Err(invalid("Agent cannot edit user text"));
                };
                let id = insert
                    .strip_prefix("\n![[syncr-drawing:")
                    .and_then(|s| s.strip_suffix("]]\n"))
                    .ok_or_else(|| invalid("Agent can insert only a drawing embed"))?;
                validation::id(id)?;
                let r = lock(&self.runtime)?;
                r.db()?;
                if !r.state.companion_items.iter().any(|v| {
                    v.id == id
                        && !v.tombstone
                        && matches!(
                            v.body,
                            CompanionBody::Drawing { .. } | CompanionBody::Picture { .. }
                        )
                }) {
                    return Err(invalid("Drawing embed must reference a drawing"));
                }
            }
            _ => return Err(invalid("Agent cannot edit user text or settings")),
        }
        note::submit_as(self, command, &self.agent_author_id())
    }
}
