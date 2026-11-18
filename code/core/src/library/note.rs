//! An open note: the single serialized writer for one note file, plus its in-memory
//! projection and change-event ring.
use super::{Note, SyncrLibrary, lock, path_input};
use crate::{
    canonical,
    error::{closed, collision, dependency, internal, invalid, limit, missing, storage},
    fault, files,
    reducer::{MarkEffect, State},
    store, validation, *,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use std::{
    collections::VecDeque,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

/// Change events kept per open handle; an older cursor gets `ResyncRequired`.
const EVENT_RING: usize = 256;
/// Largest page `changes_since` returns.
const MAX_EVENT_PAGE: u32 = 256;

pub(super) struct Runtime {
    /// `None` once closed; every call then returns `Closed`.
    pub(super) db: Option<Connection>,
    pub(super) state: State,
    /// Event stream of this handle. A reopen starts a new stream, so cursors from an
    /// earlier handle (or persisted receipts) force a resync.
    stream: Id,
    sequence: u64,
    events: VecDeque<ChangeEvent>,
    /// Cached `sync_digest`; cleared on every commit.
    pub(super) digest: Option<SyncDigest>,
}

impl Runtime {
    pub(super) fn db(&self) -> CoreResult<&Connection> {
        self.db.as_ref().ok_or_else(closed)
    }
    pub(super) fn cursor(&self) -> EventCursor {
        EventCursor {
            stream_id: self.stream.clone(),
            sequence: self.sequence,
        }
    }
}

/// A command changes the live state only after its transaction commits, and an unwinding
/// panic rolls the transaction back, so the file is always whole. The memory copy can differ
/// only if the panic fell between the commit and the assignment, so it is read back from the
/// file. The event ring and the digest cache are dropped: a new stream makes every
/// outstanding cursor ask for a resync, which is the contract for a changed stream.
impl super::Recover for Runtime {
    fn recover(&mut self) {
        if let Some(db) = &self.db
            && let Ok(mut state) = store::load(db)
        {
            state.text_effects.clear();
            self.state = state;
        }
        self.digest = None;
        self.events.clear();
        self.sequence = 0;
        self.stream = new_id();
    }
}

impl Note {
    pub(super) fn new(
        library: Arc<SyncrLibrary>,
        path: PathBuf,
        db: Connection,
        state: State,
    ) -> Arc<Self> {
        Arc::new(Note {
            library,
            path,
            runtime: Mutex::new(Runtime {
                db: Some(db),
                state,
                stream: new_id(),
                sequence: 0,
                events: VecDeque::new(),
                digest: None,
            }),
        })
    }
    pub(super) fn is_open(&self) -> CoreResult<bool> {
        Ok(lock(&self.runtime)?.db.is_some())
    }
}

/// Writes everything a command commits, inside its transaction: projection, samples, mark
/// effects and the immutable log entry.
fn write_commit(
    tx: &Transaction,
    candidate: &State,
    command: &Command,
    canonical: &[u8],
    receipt: &Receipt,
    mark_effects: &[MarkEffect],
    author: &str,
) -> CoreResult<()> {
    if !candidate.text_effects.is_empty() {
        tx.execute_batch("CREATE TABLE IF NOT EXISTS text_effects(operation_id TEXT PRIMARY KEY, effects TEXT NOT NULL);")
            .map_err(storage)?;
        tx.execute(
            "INSERT INTO text_effects VALUES (?1,?2)",
            params![command.operation_id, store::json(&candidate.text_effects)?],
        )
        .map_err(storage)?;
        if store::check_version(tx)? < store::TEXT_NOTE_VERSION {
            tx.pragma_update(None, "user_version", store::TEXT_NOTE_VERSION)
                .map_err(storage)?;
        }
    }
    if matches!(
        command.body,
        CommandBody::CompanionMarks { .. } | CommandBody::SetCompanionSettings { .. }
    ) {
        tx.execute_batch("CREATE TABLE IF NOT EXISTS companion_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO companion_format VALUES (1);").map_err(storage)?;
        if store::check_version(tx)? < store::COMPANION_NOTE_VERSION {
            tx.pragma_update(None, "user_version", store::COMPANION_NOTE_VERSION)
                .map_err(storage)?;
        }
        if let CommandBody::CompanionMarks { mutations } = &command.body
            && mutations
                .iter()
                .any(|m| matches!(m.body, CompanionBody::Picture { .. }))
        {
            tx.execute_batch("CREATE TABLE IF NOT EXISTS picture_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO picture_format VALUES (1);").map_err(storage)?;
            if store::check_version(tx)? < store::PICTURE_NOTE_VERSION {
                tx.pragma_update(None, "user_version", store::PICTURE_NOTE_VERSION)
                    .map_err(storage)?;
            }
        }
    }
    if let CommandBody::ImageMarks { mutations } = &command.body {
        tx.execute_batch("CREATE TABLE IF NOT EXISTS companion_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO companion_format VALUES (1); CREATE TABLE IF NOT EXISTS picture_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO picture_format VALUES (1); CREATE TABLE IF NOT EXISTS images(image_id TEXT PRIMARY KEY, bytes BLOB NOT NULL);").map_err(storage)?;
        for mutation in mutations {
            if let ImageMutation::Add {
                image_id, bytes, ..
            } = mutation
            {
                tx.execute(
                    "INSERT INTO images VALUES (?1,?2)",
                    params![image_id, bytes],
                )
                .map_err(storage)?;
            }
        }
        if store::check_version(tx)? < store::IMAGE_NOTE_VERSION {
            tx.pragma_update(None, "user_version", store::IMAGE_NOTE_VERSION)
                .map_err(storage)?;
        }
    }
    if let CommandBody::Marks { mutations } = &command.body
        && mutations.iter().any(|m| match m {
            MarkMutation::AddStroke { brush, .. } | MarkMutation::RestyleStroke { brush, .. } => {
                brush.is_extended()
            }
            _ => false,
        })
        && store::check_version(tx)? < store::BRUSH_NOTE_VERSION
    {
        // The chain below v8 is marker-checked, so a lower note gets every marker at once.
        tx.execute_batch("CREATE TABLE IF NOT EXISTS companion_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO companion_format VALUES (1); CREATE TABLE IF NOT EXISTS picture_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO picture_format VALUES (1); CREATE TABLE IF NOT EXISTS images(image_id TEXT PRIMARY KEY, bytes BLOB NOT NULL); CREATE TABLE IF NOT EXISTS brush_format(singleton INTEGER PRIMARY KEY CHECK(singleton=1)); INSERT OR IGNORE INTO brush_format VALUES (1);").map_err(storage)?;
        tx.pragma_update(None, "user_version", store::BRUSH_NOTE_VERSION)
            .map_err(storage)?;
    }
    store::save(tx, candidate)?;
    if !mark_effects.is_empty() {
        tx.execute(
            "INSERT INTO mark_effects VALUES (?1,?2)",
            params![command.operation_id, store::json(&mark_effects)?],
        )
        .map_err(storage)?;
    }
    if let CommandBody::Marks { mutations } = &command.body {
        for mutation in mutations {
            if let MarkMutation::AddStroke {
                mark_id, samples, ..
            } = mutation
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
        tx,
        candidate,
        author,
        canonical,
        command,
        receipt,
        store::now_ms(),
    )
}

pub(super) fn snapshot(note: &Note) -> CoreResult<NoteSnapshot> {
    let r = lock(&note.runtime)?;
    r.db()?;
    Ok(r.state.snapshot(&note.library.author, r.cursor()))
}

pub(super) fn get_samples(note: &Note, mark_id: Id) -> CoreResult<Vec<u8>> {
    let r = lock(&note.runtime)?;
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

pub(super) fn get_image_bytes(note: &Note, image_id: Id) -> CoreResult<Vec<u8>> {
    let r = lock(&note.runtime)?;
    let db = r.db()?;
    validation::id(&image_id)?;
    if !r.state.images.iter().any(|v| v.id == image_id) {
        return Err(missing());
    }
    db.query_row(
        "SELECT bytes FROM images WHERE image_id=?1",
        [image_id],
        |r| r.get(0),
    )
    .optional()
    .map_err(storage)?
    .ok_or_else(missing)
}

pub(super) fn submit(note: &Note, command: Command) -> CoreResult<Receipt> {
    submit_as(note, command, &note.library.author)
}
pub(super) fn submit_as(note: &Note, command: Command, author: &str) -> CoreResult<Receipt> {
    submit_checked(note, command, author, true)
}
/// `enforce_author_policy` is false only for operations merged from another copy of the
/// note: they already passed admission where they were made.
pub(super) fn submit_checked(
    note: &Note,
    mut command: Command,
    author: &str,
    enforce_author_policy: bool,
) -> CoreResult<Receipt> {
    let mut r = lock(&note.runtime)?;
    let db = r.db()?;
    validation::id(&command.operation_id)?;
    command.dependencies.sort();
    command.dependencies.dedup();
    validation::id(author)?;
    if enforce_author_policy
        && let CommandBody::ImageMarks { mutations } = &command.body
        && mutations
            .iter()
            .any(|m| matches!(m, ImageMutation::MarkSeen { .. }))
        && author != note.agent_author_id()
    {
        return Err(invalid("Only the companion author may mark an image seen"));
    }
    let canonical = canonical::command(&command, &r.state.note_id, author)?;
    // Idempotency comes before admission, so a committed command still retries as a
    // duplicate after the rules tighten (e.g. pre-canvas AddSheet).
    if let Some((stored, mut receipt)) = store::lookup(db, &command.operation_id)? {
        if stored != canonical {
            return Err(collision());
        }
        receipt.duplicate = true;
        return Ok(receipt);
    }
    validation::command(&command)?;
    for dep in &command.dependencies {
        if store::lookup(db, dep)?.is_none() {
            return Err(dependency());
        }
    }
    // Apply to a copy; it replaces the live state only after the transaction commits.
    let mut candidate = r.state.clone();
    let kind = candidate.apply(&command, author)?;
    candidate.sequence = candidate
        .sequence
        .checked_add(1)
        .ok_or_else(|| limit("Local sequence exhausted"))?;
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
    write_commit(
        &tx,
        &candidate,
        &command,
        &canonical,
        &receipt,
        &mark_effects,
        author,
    )?;
    fault::crash_point("command_before_commit");
    tx.commit().map_err(storage)?;
    fault::crash_point("command_after_commit");
    candidate.text_effects.clear();
    r.digest = None;
    r.state = candidate;
    r.sequence = event_sequence;
    r.events.push_back(ChangeEvent {
        cursor: receipt.cursor.clone(),
        operation_id: command.operation_id,
        kind,
    });
    if r.events.len() > EVENT_RING {
        r.events.pop_front();
    }
    Ok(receipt)
}

pub(super) fn lookup_operation(note: &Note, operation_id: Id) -> CoreResult<OperationLookup> {
    let r = lock(&note.runtime)?;
    let db = r.db()?;
    validation::id(&operation_id)?;
    Ok(match store::lookup(db, &operation_id)? {
        Some((_, receipt)) => OperationLookup::Committed { receipt },
        None => OperationLookup::NotRecorded,
    })
}

pub(super) fn changes_since(
    note: &Note,
    cursor: EventCursor,
    limit: u32,
) -> CoreResult<ChangePage> {
    let r = lock(&note.runtime)?;
    r.db()?;
    validation::id(&cursor.stream_id)?;
    if !(1..=MAX_EVENT_PAGE).contains(&limit) {
        return Err(invalid("Event page limit must be in [1,256]"));
    }
    // The oldest cursor the ring can still continue from.
    let oldest = r
        .events
        .front()
        .map(|e| e.cursor.sequence - 1)
        .unwrap_or(r.sequence);
    if cursor.stream_id != r.stream || cursor.sequence > r.sequence || cursor.sequence < oldest {
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

pub(super) fn export_copy(note: &Note, destination_path: String) -> CoreResult<ExportReceipt> {
    let r = lock(&note.runtime)?;
    let db = r.db()?;
    path_input(
        &destination_path,
        "Export destination must be an app-owned filesystem path",
    )?;
    let destination = Path::new(&destination_path);
    if destination.exists() {
        return Err(collision());
    }
    store::export(
        db,
        files::parent(destination),
        ".syncr-export-",
        destination,
    )?;
    Ok(ExportReceipt {
        note_id: r.state.note_id.clone(),
        exported_local_sequence: r.state.sequence,
    })
}

pub(super) fn close_note(note: &Note) -> CoreResult<()> {
    let mut r = lock(&note.runtime)?;
    let Some(db) = r.db.as_ref() else {
        return Ok(());
    };
    // Fold the WAL first: a failed checkpoint leaves the handle open and usable.
    store::checkpoint(db)?;
    files::sync(note.path.parent().ok_or_else(internal)?)?;
    let db = r.db.take().ok_or_else(closed)?;
    if let Err((db, error)) = db.close() {
        r.db = Some(db);
        return Err(storage(error));
    }
    Ok(())
}
