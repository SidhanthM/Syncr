//! The per-note SQLite file: schema, versioning, the immutable operation log and the
//! projection. No receipt is returned before its transaction commits.
use crate::{
    error::{internal, invalid, io, locked, missing, storage, storage_io, unsupported},
    fault::{crash_point, fail_point},
    files,
    reducer::State,
    validation, *,
};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};

/// `SYNC` in ASCII, stored as the SQLite application ID of every note.
const APPLICATION_ID: u32 = 0x53594e43;
/// v1: original offline schema. v2: `operations.committed_at_ms` (display-only wall clock).
/// v3: the note is one canvas; every sheet has a fixed origin in the projection.
pub(crate) const NOTE_VERSION: u32 = 3;
/// v4: text blocks. Only a committed text mutation raises a canvas note to v4.
pub(crate) const TEXT_NOTE_VERSION: u32 = 4;
/// v5: anchored companion items and per-note companion settings. Lazy on commit.
pub(crate) const COMPANION_NOTE_VERSION: u32 = 5;
/// v6: filtered SVG picture bodies, lazy on first picture commit.
pub(crate) const PICTURE_NOTE_VERSION: u32 = 6;
/// v7: immutable image bytes and image marks, lazy on first image commit.
pub(crate) const IMAGE_NOTE_VERSION: u32 = 7;
/// Copies of notes taken just before their v3 upgrade, inside the library root:
/// `<root>/.backups/<NOTE_ID>.v<old version>.<unix ms>.syncr`.
pub(crate) const BACKUP_DIR: &str = ".backups";
pub(crate) const BACKUP_TEMP_PREFIX: &str = ".syncr-backup-";

const NOTE_SCHEMA: &str = "
    CREATE TABLE document(singleton INTEGER PRIMARY KEY CHECK(singleton=1), state TEXT NOT NULL);
    CREATE TABLE operations(id TEXT PRIMARY KEY, local_sequence BLOB NOT NULL UNIQUE, note_id TEXT NOT NULL, author_id TEXT NOT NULL, payload_version INTEGER NOT NULL, canonical BLOB NOT NULL, payload TEXT NOT NULL, receipt TEXT NOT NULL, committed_at_ms INTEGER);
    CREATE TABLE samples(mark_id TEXT PRIMARY KEY, bytes BLOB NOT NULL);
    CREATE TABLE mark_effects(operation_id TEXT PRIMARY KEY, effects TEXT NOT NULL);";

/// Wall-clock Unix milliseconds. Display only, never identity.
pub(crate) fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}
fn ulid_ms(id: &str) -> i64 {
    id.parse::<ulid::Ulid>()
        .map_or(0, |u| i64::try_from(u.timestamp_ms()).unwrap_or(i64::MAX))
}
pub(crate) fn json<T: Serialize>(value: &T) -> CoreResult<String> {
    serde_json::to_string(value).map_err(|_| internal())
}
pub(crate) fn parse<T: DeserializeOwned>(value: &str) -> CoreResult<T> {
    serde_json::from_str(value).map_err(|_| internal())
}
fn user_version(db: &Connection) -> CoreResult<u32> {
    db.pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(storage)
}
/// WAL with FULL sync: every committed transaction survives power loss.
pub(crate) fn configure(db: &Connection) -> CoreResult<()> {
    db.pragma_update(None, "journal_mode", "WAL")
        .map_err(storage)?;
    db.pragma_update(None, "synchronous", "FULL")
        .map_err(storage)?;
    Ok(())
}
/// Opens a published note, checking its version before anything is written.
pub(crate) fn open(path: &Path, writable: bool) -> CoreResult<Connection> {
    if !path.is_file() {
        return Err(missing());
    }
    let flags = if writable {
        OpenFlags::SQLITE_OPEN_READ_WRITE
    } else {
        OpenFlags::SQLITE_OPEN_READ_ONLY
    };
    let db = Connection::open_with_flags(path, flags).map_err(storage)?;
    check_version(&db)?;
    if writable {
        configure(&db)?;
    }
    Ok(db)
}
/// Returns the note schema version. Older versions stay readable read-only;
/// `open_note` upgrades them (see [upgrade]) before writing.
pub(crate) fn check_version(db: &Connection) -> CoreResult<u32> {
    let version = check_version_supported(db, IMAGE_NOTE_VERSION)?;
    if version >= COMPANION_NOTE_VERSION {
        let marked: bool = db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='companion_format')", [], |r| r.get(0)).map_err(storage)?;
        if !marked {
            return Err(unsupported("Unsupported note schema version"));
        }
    }
    if version >= PICTURE_NOTE_VERSION {
        let marked: bool = db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='picture_format')", [], |r| r.get(0)).map_err(storage)?;
        if !marked {
            return Err(unsupported("Unsupported note schema version"));
        }
    }
    if version >= IMAGE_NOTE_VERSION {
        let marked: bool = db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='images')",
                [],
                |r| r.get(0),
            )
            .map_err(storage)?;
        if !marked {
            return Err(unsupported("Unsupported note schema version"));
        }
    }
    Ok(version)
}
pub(crate) fn check_version_supported(db: &Connection, maximum: u32) -> CoreResult<u32> {
    let version = user_version(db)?;
    if version > maximum {
        return Err(unsupported("Unsupported note schema version"));
    }
    let app: u32 = db
        .pragma_query_value(None, "application_id", |r| r.get(0))
        .map_err(storage)?;
    if version == 0 || app != APPLICATION_ID {
        return Err(invalid("Not a supported Syncr note"));
    }
    Ok(version)
}
/// Upgrades a v1/v2 note to v3 (one canvas) and returns the backup it took, or None when
/// the note is already v3. First a standalone copy of the unmodified note is published,
/// without overwrite, at `<backups>/<NOTE_ID>.v<old>.<ms>.syncr` by the same SQLite backup,
/// integrity check and no-overwrite publication as `export_copy`. Then ONE transaction adds
/// the v2 `committed_at_ms` column to a v1 note, fixes every sheet's origin from its
/// current order and old page height, rewrites the projection and sets version 3. No
/// operation, receipt, sample or mark effect is touched. Any failure rolls the
/// transaction back and leaves the note at its old version; the backup stays.
pub(crate) fn upgrade(db: &Connection, backups: &Path) -> CoreResult<Option<PathBuf>> {
    let version = check_version(db)?;
    if version >= NOTE_VERSION {
        return Ok(None);
    }
    let note_id = load(db)?.note_id;
    if !backups.is_dir() {
        std::fs::create_dir_all(backups).map_err(io)?;
        files::sync(files::parent(backups))?;
    }
    let destination = backups.join(format!("{note_id}.v{version}.{}.syncr", now_ms()));
    export(db, backups, BACKUP_TEMP_PREFIX, &destination)?;
    crash_point("upgrade_after_backup");
    let tx = db.unchecked_transaction().map_err(storage)?;
    if check_version(&tx)? == 1 {
        tx.execute_batch("ALTER TABLE operations ADD COLUMN committed_at_ms INTEGER;")
            .map_err(storage)?;
    }
    let mut state = load(&tx)?;
    state.place_sheets(state.legacy_page_format);
    save(&tx, &state)?;
    tx.pragma_update(None, "user_version", NOTE_VERSION)
        .map_err(storage)?;
    fail_point("upgrade_before_commit")?;
    crash_point("upgrade_before_commit");
    tx.commit().map_err(storage)?;
    Ok(Some(destination))
}
/// Rebuilds a note's projection from its immutable log alone, from empty, the way any
/// other replica would (pre-canvas AddSheet/ReorderSheets included), then places sheets.
#[cfg(test)]
pub(crate) fn replay(db: &Connection) -> CoreResult<State> {
    use crate::reducer::LegacyPageFormat;
    #[derive(serde::Deserialize)]
    struct Format {
        #[serde(default)]
        page_format: Option<LegacyPageFormat>,
    }
    let mut statement = db
        .prepare("SELECT author_id, canonical, payload FROM operations ORDER BY local_sequence")
        .map_err(storage)?;
    let rows = statement
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, Vec<u8>>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)?;
    let (_, canonical, payload) = rows.first().ok_or_else(internal)?;
    let request: CreateNoteRequest = parse(payload)?;
    // The creation tag says which page format a pre-canvas note had (see canonical.rs).
    let format = match canonical.get(6) {
        Some(0) => Some(LegacyPageFormat::Sheet16x10),
        Some(2) => Some(parse::<Format>(payload)?.page_format.ok_or_else(internal)?),
        _ => None,
    };
    let mut state = State::new(&request);
    for (author, _, payload) in &rows[1..] {
        state.apply_logged(&parse::<Command>(payload)?, author)?;
        state.sequence += 1;
    }
    state.place_sheets(format);
    Ok(state)
}
/// Display-only (created, modified) wall-clock times. Never identity. v1 notes recorded no
/// commit times, so they fall back to the ULID time of the note ID and latest operation.
pub(crate) fn times(db: &Connection, state: &State) -> CoreResult<(i64, i64)> {
    let column = if check_version(db)? >= 2 {
        "committed_at_ms"
    } else {
        "NULL"
    };
    let created: Option<i64> = db
        .query_row(
            &format!("SELECT {column} FROM operations WHERE id=?1"),
            [&state.creation],
            |r| r.get(0),
        )
        .map_err(storage)?;
    let (latest, modified): (String, Option<i64>) = db
        .query_row(
            &format!("SELECT id, {column} FROM operations ORDER BY local_sequence DESC LIMIT 1"),
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(storage)?;
    let created = created.unwrap_or_else(|| ulid_ms(&state.note_id));
    let modified = modified.unwrap_or_else(|| ulid_ms(&latest));
    Ok((created, modified.max(created)))
}
pub(crate) fn load(db: &Connection) -> CoreResult<State> {
    let value: String = db
        .query_row("SELECT state FROM document WHERE singleton=1", [], |r| {
            r.get(0)
        })
        .map_err(storage)?;
    let state: State = parse(&value)?;
    validation::id(&state.note_id)?;
    Ok(state)
}
pub(crate) fn save(db: &Connection, state: &State) -> CoreResult<()> {
    db.execute(
        "UPDATE document SET state=?1 WHERE singleton=1",
        [json(state)?],
    )
    .map_err(storage)?;
    Ok(())
}
/// The stored canonical bytes and original receipt of a committed operation.
pub(crate) fn lookup(db: &Connection, id: &str) -> CoreResult<Option<(Vec<u8>, Receipt)>> {
    let row: Option<(Vec<u8>, String)> = db
        .query_row(
            "SELECT canonical, receipt FROM operations WHERE id=?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(storage)?;
    row.map(|(canonical, receipt)| Ok((canonical, parse(&receipt)?)))
        .transpose()
}
/// Appends one immutable log entry. The local sequence is stored big-endian so BLOB order
/// is numeric order.
pub(crate) fn record<T: Serialize>(
    db: &Connection,
    state: &State,
    author: &str,
    canonical: &[u8],
    payload: &T,
    receipt: &Receipt,
    committed_at_ms: i64,
) -> CoreResult<()> {
    db.execute(
        "INSERT INTO operations(id, local_sequence, note_id, author_id, payload_version, canonical, payload, receipt, committed_at_ms) VALUES (?1,?2,?3,?4,1,?5,?6,?7,?8)",
        params![
            receipt.operation_id,
            state.sequence.to_be_bytes().as_slice(),
            state.note_id,
            author,
            canonical,
            json(payload)?,
            json(receipt)?,
            committed_at_ms
        ],
    )
    .map_err(storage)?;
    Ok(())
}
/// Writes a new note into the empty file at `path` and fsyncs it, ready for publication.
pub(crate) fn initialize(
    path: &Path,
    request: &CreateNoteRequest,
    author: &str,
    canonical: &[u8],
    receipt: &Receipt,
) -> CoreResult<()> {
    let mut db = Connection::open(path).map_err(storage)?;
    // A standalone committed database is published, never a live WAL file alone.
    db.pragma_update(None, "journal_mode", "DELETE")
        .map_err(storage)?;
    db.pragma_update(None, "synchronous", "FULL")
        .map_err(storage)?;
    let tx = db.transaction().map_err(storage)?;
    tx.execute_batch(NOTE_SCHEMA).map_err(storage)?;
    let state = State::new(request);
    tx.execute("INSERT INTO document VALUES (1,?1)", [json(&state)?])
        .map_err(storage)?;
    record(&tx, &state, author, canonical, request, receipt, now_ms())?;
    tx.pragma_update(None, "application_id", APPLICATION_ID)
        .map_err(storage)?;
    tx.pragma_update(None, "user_version", NOTE_VERSION)
        .map_err(storage)?;
    crash_point("create_before_commit");
    tx.commit().map_err(storage)?;
    crash_point("create_after_commit");
    db.close().map_err(|(_, e)| storage(e))?;
    files::sync(path)
}
/// Folds the WAL into the main file. `NoteLocked` while a reader pins the WAL.
pub(crate) fn checkpoint(db: &Connection) -> CoreResult<()> {
    let (busy, _, _): (u32, u32, u32) = db
        .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?))
        })
        .map_err(storage)?;
    if busy != 0 {
        return Err(locked("Note checkpoint is busy"));
    }
    Ok(())
}
/// Folds any WAL or hot rollback journal into the main file and closes it, so the main file
/// alone holds every committed transaction before it is moved. Refuses rather than move a
/// file whose journal could not be folded.
pub(crate) fn fold_journal(path: &Path) -> CoreResult<()> {
    let pending = || {
        files::sidecar(path, "-journal").exists()
            || std::fs::metadata(files::sidecar(path, "-wal")).is_ok_and(|m| m.len() > 0)
    };
    if pending() {
        let db = open(path, true)?;
        checkpoint(&db)?;
        db.close().map_err(|(_, e)| storage(e))?;
    }
    if pending() {
        return Err(storage_io(
            "Note journal could not be folded before moving",
            true,
        ));
    }
    Ok(())
}
/// Publishes a verified standalone copy of `source` at `destination`, never overwriting.
/// The copy is staged in `staging_dir` (which must be on the destination's filesystem) and
/// the staging file is always removed.
pub(crate) fn export(
    source: &Connection,
    staging_dir: &Path,
    temp_prefix: &str,
    destination: &Path,
) -> CoreResult<()> {
    let temp = files::temporary(staging_dir, temp_prefix)?;
    let result = backup(source, &temp).and_then(|()| files::publish(&temp, destination));
    files::discard_temporary(&temp);
    result
}
/// Copies `source` into `destination` with SQLite's online backup, verifies the copy and
/// leaves it as a closed, fsynced DELETE-journal database.
fn backup(source: &Connection, destination: &Path) -> CoreResult<()> {
    let mut output = Connection::open(destination).map_err(storage)?;
    rusqlite::backup::Backup::new(source, &mut output)
        .map_err(storage)?
        .run_to_completion(128, Duration::ZERO, None)
        .map_err(storage)?;
    check_version(&output)?;
    load(&output)?;
    let integrity: String = output
        .query_row("PRAGMA integrity_check", [], |r| r.get(0))
        .map_err(storage)?;
    if integrity != "ok" {
        return Err(internal());
    }
    // The copy may inherit WAL mode; a standalone file must not depend on a sidecar.
    output
        .pragma_update(None, "journal_mode", "DELETE")
        .map_err(storage)?;
    output.close().map_err(|(_, e)| storage(e))?;
    files::sync(destination)
}
