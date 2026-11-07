//! Discovering, creating and opening published notes. A scan reads every published note
//! file; the SQLite `notes` table it refreshes is a cache and never consulted as evidence.
use super::{Note, SyncrLibrary, lock};
use crate::{
    canonical,
    error::{collision, id_collision, internal, io, missing, storage},
    files, reducer, store, validation, *,
};
use rusqlite::{Connection, params};
use std::{
    collections::{BTreeMap, HashSet},
    path::PathBuf,
    sync::{Arc, Weak},
};

/// Prefix of a note being created. Publication renames it away, so a file still carrying
/// it after a crash was never reported as saved and is removed on the next open.
pub(super) const CREATE_TEMP_PREFIX: &str = ".syncr-create-";

/// One published note file as discovered by a scan.
pub(super) struct Published {
    pub(super) path: PathBuf,
    pub(super) state: reducer::State,
    created_at_ms: i64,
    modified_at_ms: i64,
}

pub(super) struct Catalog {
    pub(super) db: Connection,
    /// Handles given out by `open_note`, so a second open shares the live writer.
    notes: BTreeMap<Id, Weak<Note>>,
}

/// A catalog transaction rolls back when its panic unwinds, and the handle map only caches
/// weak references that `open_handle` re-checks, so nothing needs repair.
impl super::Recover for Catalog {}

impl Catalog {
    pub(super) fn new(db: Connection) -> Self {
        Catalog {
            db,
            notes: BTreeMap::new(),
        }
    }

    /// The open handle for `note_id`, if one is live. A closed or released handle is not.
    pub(super) fn open_handle(&self, note_id: &str) -> CoreResult<Option<Arc<Note>>> {
        let Some(note) = self.notes.get(note_id).and_then(Weak::upgrade) else {
            return Ok(None);
        };
        Ok(note.is_open()?.then_some(note))
    }

    fn refresh_index(&mut self, notes: &[Published]) -> CoreResult<()> {
        let tx = self.db.transaction().map_err(storage)?;
        tx.execute("DELETE FROM notes", []).map_err(storage)?;
        for Published { path, state, .. } in notes {
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
    }
}

impl SyncrLibrary {
    pub(super) fn note_path(&self, note_id: &str) -> PathBuf {
        self.root.join(format!("{note_id}.syncr"))
    }

    /// Reads every published note: regular `*.syncr` files directly in the root, under any
    /// name (exports included). Two files with one note ID are an `IdCollision`, never a
    /// silent pick.
    pub(super) fn scan(&self, catalog: &mut Catalog) -> CoreResult<Vec<Published>> {
        let mut paths = std::fs::read_dir(&self.root)
            .map_err(io)?
            .map(|e| e.map(|e| e.path()).map_err(io))
            .collect::<CoreResult<Vec<_>>>()?;
        paths.sort();
        let mut notes = Vec::new();
        let mut seen = HashSet::new();
        for path in paths {
            if path.extension().is_none_or(|e| e != "syncr") || !path.is_file() {
                continue;
            }
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
        // A failed cache refresh must not hide published notes or turn a durable save into
        // an error; every lookup reads note metadata, not the cache.
        let _ = catalog.refresh_index(&notes);
        Ok(notes)
    }

    pub(super) fn find_published(
        &self,
        catalog: &mut Catalog,
        note_id: &str,
    ) -> CoreResult<Option<Published>> {
        Ok(self
            .scan(catalog)?
            .into_iter()
            .find(|p| p.state.note_id == note_id))
    }

    /// A creation retry of a published note, or `IdCollision` if its note ID is taken.
    fn published_creation_retry(
        &self,
        catalog: &mut Catalog,
        request: &CreateNoteRequest,
    ) -> CoreResult<Option<Receipt>> {
        for Published { path, state, .. } in self.scan(catalog)? {
            if state.creation == request.operation_id {
                let db = store::open(&path, false)?;
                let stored = store::lookup(&db, &request.operation_id)?;
                return creation_retry(stored, request, &self.author).map(Some);
            }
            if state.note_id == request.note_id {
                return Err(collision());
            }
        }
        Ok(None)
    }
}

/// The original receipt for a creation retry, marked duplicate, or `IdCollision` when the
/// stored creation differs.
pub(super) fn creation_retry(
    stored: Option<(Vec<u8>, Receipt)>,
    request: &CreateNoteRequest,
    author: &str,
) -> CoreResult<Receipt> {
    let (canonical, mut receipt) = stored.ok_or_else(internal)?;
    if !canonical::creation_matches(&canonical, request, author)? {
        return Err(collision());
    }
    receipt.duplicate = true;
    Ok(receipt)
}

pub(super) fn list_notes(library: &SyncrLibrary) -> CoreResult<Vec<NoteSummary>> {
    let mut result: Vec<_> = library
        .scan(&mut *lock(&library.catalog)?)?
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

pub(super) fn create_note(
    library: &SyncrLibrary,
    request: CreateNoteRequest,
) -> CoreResult<Receipt> {
    validation::id(&request.operation_id)?;
    validation::id(&request.note_id)?;
    validation::id(&request.first_sheet_id)?;
    if let Some(name) = &request.notebook {
        validation::notebook_name(name)?;
    }
    let canonical = canonical::creation(&request, &library.author)?;
    let mut catalog = lock(&library.catalog)?;
    if let Some(receipt) = library.published_creation_retry(&mut catalog, &request)? {
        return Ok(receipt);
    }
    // A deleted note's creation still happened: a retry reports it and the note stays in
    // Recently deleted rather than being resurrected.
    if let Some(receipt) = library.trashed_creation_retry(&request)? {
        return Ok(receipt);
    }
    if catalog.purge_reserves(&request.note_id, &request.operation_id)? {
        return Err(id_collision(
            "This ID belongs to a permanently deleted note",
        ));
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
    // Build the note under a temporary name, then publish it with one no-overwrite rename,
    // so a crash never leaves a half-written note under its real name.
    let temp = files::temporary(&library.root, CREATE_TEMP_PREFIX)?;
    let published = store::initialize(&temp, &request, &library.author, &canonical, &receipt)
        .and_then(|()| files::publish(&temp, &library.note_path(&request.note_id)))
        .and_then(|()| library.scan(&mut catalog).map(drop));
    files::discard_temporary(&temp);
    published.map(|()| receipt)
}

pub(super) fn open_note(library: &Arc<SyncrLibrary>, note_id: Id) -> CoreResult<Arc<Note>> {
    validation::id(&note_id)?;
    let mut catalog = lock(&library.catalog)?;
    if let Some(note) = catalog.open_handle(&note_id)? {
        return Ok(note);
    }
    let path = library
        .find_published(&mut catalog, &note_id)?
        .ok_or_else(missing)?
        .path;
    let db = store::open(&path, true)?;
    // A note written before the canvas amendment becomes a canvas here, after a backup
    // copy; failure leaves it unchanged and unopened.
    store::upgrade(&db, &library.root.join(store::BACKUP_DIR))?;
    let state = store::load(&db)?;
    let note = Note::new(library.clone(), path, db, state);
    catalog.notes.insert(note_id, Arc::downgrade(&note));
    Ok(note)
}

pub(super) fn lookup_creation(
    library: &SyncrLibrary,
    operation_id: Id,
) -> CoreResult<OperationLookup> {
    validation::id(&operation_id)?;
    let mut catalog = lock(&library.catalog)?;
    for Published { path, state, .. } in library.scan(&mut catalog)? {
        if state.creation == operation_id {
            let db = store::open(&path, false)?;
            let (_, receipt) = store::lookup(&db, &operation_id)?.ok_or_else(internal)?;
            return Ok(OperationLookup::Committed { receipt });
        }
    }
    Ok(match library.trashed_creation_receipt(&operation_id)? {
        Some(receipt) => OperationLookup::Committed { receipt },
        None => OperationLookup::NotRecorded,
    })
}
