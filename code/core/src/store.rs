//! SQLite and filesystem primitives. No successful receipt precedes COMMIT/publication.
use crate::{
    error::{internal, io, missing, storage, unsupported},
    reducer::State,
    *,
};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
use std::{
    fs::File,
    path::{Path, PathBuf},
    time::Duration,
};

const APPLICATION_ID: u32 = 0x53594e43;
/// v1: original offline schema. v2: `operations.committed_at_ms` (display-only wall clock).
/// v3: the note is one canvas; every sheet has a fixed origin in the projection.
pub(crate) const NOTE_VERSION: u32 = 3;
/// Copies of notes taken just before their v3 upgrade, inside the library root:
/// `<root>/.backups/<NOTE_ID>.v<old version>.<unix ms>.syncr`.
pub(crate) const BACKUP_DIR: &str = ".backups";
pub(crate) const BACKUP_TEMP_PREFIX: &str = ".syncr-backup-";
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
pub(crate) fn configure(db: &Connection) -> CoreResult<()> {
    db.pragma_update(None, "journal_mode", "WAL")
        .map_err(storage)?;
    db.pragma_update(None, "synchronous", "FULL")
        .map_err(storage)?;
    Ok(())
}
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
    let version: u32 = db
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(storage)?;
    if version > NOTE_VERSION {
        return Err(unsupported("Unsupported note schema version"));
    }
    let app: u32 = db
        .pragma_query_value(None, "application_id", |r| r.get(0))
        .map_err(storage)?;
    if version == 0 || app != APPLICATION_ID {
        return Err(crate::error::invalid("Not a supported Syncr note"));
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
        sync_directory(parent(backups))?;
    }
    let destination = backups.join(format!("{note_id}.v{version}.{}.syncr", now_ms()));
    let temp = temporary(backups, BACKUP_TEMP_PREFIX)?;
    let published = backup(db, &temp).and_then(|()| publish(&temp, &destination));
    cleanup(&temp);
    published?;
    fault("upgrade_after_backup");
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
    fault("upgrade_before_commit");
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
/// Display-only (created, modified) wall-clock times. Never identity.
pub(crate) fn times(db: &Connection, state: &State) -> CoreResult<(i64, i64)> {
    let v2 = check_version(db)? >= 2;
    let time = |sql: &str, arg: &[&dyn rusqlite::ToSql]| -> CoreResult<(String, Option<i64>)> {
        db.query_row(sql, arg, |r| {
            Ok((r.get(0)?, if v2 { r.get(1)? } else { None }))
        })
        .map_err(storage)
    };
    let column = if v2 { "committed_at_ms" } else { "NULL" };
    let (_, created) = time(
        &format!("SELECT id, {column} FROM operations WHERE id=?1"),
        &[&state.creation],
    )?;
    let (latest, modified) = time(
        &format!("SELECT id, {column} FROM operations ORDER BY local_sequence DESC LIMIT 1"),
        &[],
    )?;
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
    crate::validation::id(&state.note_id)?;
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
pub(crate) fn lookup(db: &Connection, id: &str) -> CoreResult<Option<(Vec<u8>, Receipt)>> {
    let value: Option<(Vec<u8>, String)> = db
        .query_row(
            "SELECT canonical, receipt FROM operations WHERE id=?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(storage)?;
    value
        .map(|(bytes, value)| Ok((bytes, parse(&value)?)))
        .transpose()
}
pub(crate) fn record<T: Serialize>(
    db: &Connection,
    state: &State,
    author: &str,
    bytes: &[u8],
    payload: &T,
    receipt: &Receipt,
    committed_at_ms: i64,
) -> CoreResult<()> {
    db.execute("INSERT INTO operations(id, local_sequence, note_id, author_id, payload_version, canonical, payload, receipt, committed_at_ms) VALUES (?1,?2,?3,?4,1,?5,?6,?7,?8)",
        params![receipt.operation_id, state.sequence.to_be_bytes().as_slice(), state.note_id, author, bytes, json(payload)?, json(receipt)?, committed_at_ms]).map_err(storage)?;
    Ok(())
}
pub(crate) fn initialize(
    path: &Path,
    request: &CreateNoteRequest,
    author: &str,
    bytes: &[u8],
    receipt: &Receipt,
) -> CoreResult<()> {
    let mut db = Connection::open(path).map_err(storage)?;
    // A standalone committed database is published, never a live WAL file alone.
    db.pragma_update(None, "journal_mode", "DELETE")
        .map_err(storage)?;
    db.pragma_update(None, "synchronous", "FULL")
        .map_err(storage)?;
    let tx = db.transaction().map_err(storage)?;
    tx.execute_batch("CREATE TABLE document(singleton INTEGER PRIMARY KEY CHECK(singleton=1), state TEXT NOT NULL);
        CREATE TABLE operations(id TEXT PRIMARY KEY, local_sequence BLOB NOT NULL UNIQUE, note_id TEXT NOT NULL, author_id TEXT NOT NULL, payload_version INTEGER NOT NULL, canonical BLOB NOT NULL, payload TEXT NOT NULL, receipt TEXT NOT NULL, committed_at_ms INTEGER);
        CREATE TABLE samples(mark_id TEXT PRIMARY KEY, bytes BLOB NOT NULL);
        CREATE TABLE mark_effects(operation_id TEXT PRIMARY KEY, effects TEXT NOT NULL);").map_err(storage)?;
    let state = State::new(request);
    tx.execute("INSERT INTO document VALUES (1,?1)", [json(&state)?])
        .map_err(storage)?;
    record(&tx, &state, author, bytes, request, receipt, now_ms())?;
    tx.pragma_update(None, "application_id", APPLICATION_ID)
        .map_err(storage)?;
    tx.pragma_update(None, "user_version", NOTE_VERSION)
        .map_err(storage)?;
    fault("create_before_commit");
    tx.commit().map_err(storage)?;
    fault("create_after_commit");
    db.close().map_err(|(_, e)| storage(e))?;
    sync_file(path)
}
pub(crate) fn checkpoint(db: &Connection) -> CoreResult<()> {
    let (busy, _, _): (u32, u32, u32) = db
        .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?))
        })
        .map_err(storage)?;
    if busy != 0 {
        return Err(CoreError::NoteLocked {
            detail: "Note checkpoint is busy".into(),
            retryable: true,
        });
    }
    Ok(())
}
pub(crate) fn sync_directory(path: &Path) -> CoreResult<()> {
    File::open(path).and_then(|f| f.sync_all()).map_err(io)
}
fn sync_file(path: &Path) -> CoreResult<()> {
    File::open(path).and_then(|f| f.sync_all()).map_err(io)
}
pub(crate) fn temporary(parent: &Path, prefix: &str) -> CoreResult<PathBuf> {
    let path = parent.join(format!("{prefix}{}", new_id()));
    File::options()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(io)?;
    Ok(path)
}
/// Atomically publishes `temp` at `destination` without ever replacing an existing
/// destination. The rename consumes `temp`, so no temporary alias remains afterwards.
pub(crate) fn publish(temp: &Path, destination: &Path) -> CoreResult<()> {
    sync_file(temp)?;
    rename_noreplace(temp, destination).map_err(rename_error)?;
    sync_directory(parent(destination))?;
    fault("after_publication");
    Ok(())
}
fn rename_error(e: std::io::Error) -> CoreError {
    match e.kind() {
        std::io::ErrorKind::AlreadyExists => crate::error::collision(),
        _ if is_unsupported(&e) => CoreError::StorageIo {
            detail: "Filesystem does not support atomic no-overwrite publication".into(),
            retryable: false,
        },
        _ => io(e),
    }
}
fn parent(path: &Path) -> &Path {
    path.parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."))
}
/// Atomically moves a closed, published file to another directory on the same filesystem
/// without replacing an existing destination (same no-overwrite rename as [publish]; never a
/// hard link on Android). Both directories are synced before success. A crash leaves the file
/// at exactly one of the two names.
pub(crate) fn move_noreplace(from: &Path, to: &Path) -> CoreResult<()> {
    rename_noreplace(from, to).map_err(rename_error)?;
    sync_directory(parent(to))?;
    if parent(from) != parent(to) {
        sync_directory(parent(from))?;
    }
    Ok(())
}
pub(crate) fn sidecar(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(suffix);
    PathBuf::from(name)
}
/// Folds any WAL or hot rollback journal into the main file and closes it, so the main file
/// alone holds every committed transaction before it is moved. Refuses rather than move a
/// file whose journal could not be folded.
pub(crate) fn fold_journal(path: &Path) -> CoreResult<()> {
    let pending = |p: &Path| {
        sidecar(p, "-journal").exists()
            || std::fs::metadata(sidecar(p, "-wal")).is_ok_and(|m| m.len() > 0)
    };
    if pending(path) {
        let db = open(path, true)?;
        checkpoint(&db)?;
        db.close().map_err(|(_, e)| storage(e))?;
    }
    if pending(path) {
        return Err(CoreError::StorageIo {
            detail: "Note journal could not be folded before moving".into(),
            retryable: true,
        });
    }
    Ok(())
}
/// Best-effort removal of sidecars that hold no data (a shared-memory index and an empty
/// WAL left by read-only scans) beside a file that was just moved away. Never a non-empty WAL.
pub(crate) fn remove_idle_sidecars(path: &Path) {
    let wal = sidecar(path, "-wal");
    if std::fs::metadata(&wal).is_ok_and(|m| m.len() == 0) {
        let _ = std::fs::remove_file(wal);
    }
    let _ = std::fs::remove_file(sidecar(path, "-shm"));
}
/// Removes a file and its SQLite sidecars. A missing file is not an error.
pub(crate) fn remove_with_sidecars(path: &Path) -> CoreResult<()> {
    for suffix in ["-wal", "-shm", "-journal", ""] {
        match std::fs::remove_file(sidecar(path, suffix)) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(io(e)),
            _ => {}
        }
    }
    Ok(())
}
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
fn is_unsupported(e: &std::io::Error) -> bool {
    matches!(
        e.raw_os_error(),
        Some(libc::EINVAL | libc::ENOSYS | libc::ENOTSUP)
    ) || e.raw_os_error() == Some(libc::EOPNOTSUPP)
}
#[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
fn is_unsupported(_: &std::io::Error) -> bool {
    false
}
#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
fn c_path(path: &Path) -> std::io::Result<std::ffi::CString> {
    use std::os::unix::ffi::OsStrExt;
    std::ffi::CString::new(path.as_os_str().as_bytes())
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidInput, e))
}
// Android SELinux denies hard links in app data, so Linux-family targets use
// renameat2(RENAME_NOREPLACE). The raw syscall avoids bionic's API-30 wrapper.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn rename_noreplace(from: &Path, to: &Path) -> std::io::Result<()> {
    let (from, to) = (c_path(from)?, c_path(to)?);
    #[allow(clippy::unnecessary_cast)]
    let flags = libc::RENAME_NOREPLACE as libc::c_uint;
    // SAFETY: both paths are valid NUL-terminated strings that outlive the call.
    let rc = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            from.as_ptr(),
            libc::AT_FDCWD,
            to.as_ptr(),
            flags,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}
#[cfg(target_os = "macos")]
fn rename_noreplace(from: &Path, to: &Path) -> std::io::Result<()> {
    let (from, to) = (c_path(from)?, c_path(to)?);
    // SAFETY: both paths are valid NUL-terminated strings that outlive the call.
    if unsafe { libc::renamex_np(from.as_ptr(), to.as_ptr(), libc::RENAME_EXCL) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}
// Other targets keep exclusive hard-link publication and remove the temporary alias.
#[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
fn rename_noreplace(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::hard_link(from, to)?;
    std::fs::remove_file(from)
}
pub(crate) fn cleanup(path: &Path) {
    for suffix in ["", "-wal", "-shm", "-journal"] {
        let _ = std::fs::remove_file(sidecar(path, suffix));
    }
}
pub(crate) fn backup(source: &Connection, destination: &Path) -> CoreResult<()> {
    let mut output = Connection::open(destination).map_err(storage)?;
    {
        let backup = rusqlite::backup::Backup::new(source, &mut output).map_err(storage)?;
        backup
            .run_to_completion(128, Duration::ZERO, None)
            .map_err(storage)?;
    }
    check_version(&output)?;
    load(&output)?;
    let integrity: String = output
        .query_row("PRAGMA integrity_check", [], |r| r.get(0))
        .map_err(storage)?;
    if integrity != "ok" {
        return Err(internal());
    }
    // Backup may inherit WAL mode. Close a checkpointed DELETE-mode standalone copy.
    output
        .pragma_update(None, "journal_mode", "DELETE")
        .map_err(storage)?;
    output.close().map_err(|(_, e)| storage(e))?;
    sync_file(destination)
}

#[cfg(not(test))]
fn fail_point(_: &str) -> CoreResult<()> {
    Ok(())
}
#[cfg(test)]
thread_local! {
    /// Test-only in-process failure: the named stage returns an error on this thread.
    pub(crate) static FAIL_AT: std::cell::Cell<Option<&'static str>> =
        const { std::cell::Cell::new(None) };
}
#[cfg(test)]
fn fail_point(stage: &str) -> CoreResult<()> {
    if FAIL_AT.with(|f| f.get()) == Some(stage) {
        return Err(internal());
    }
    Ok(())
}
#[cfg(not(test))]
pub(crate) fn fault(_: &str) {}
#[cfg(test)]
pub(crate) fn fault(stage: &str) {
    if std::env::var("SYNCR_CRASH_STAGE").ok().as_deref() == Some(stage) {
        std::process::exit(91);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn publish_moves_temp_into_place_without_leaving_an_alias() {
        let dir = tempfile::tempdir().unwrap();
        let temp = temporary(dir.path(), ".syncr-create-").unwrap();
        std::fs::write(&temp, b"new note").unwrap();
        let destination = dir.path().join("note.syncr");
        publish(&temp, &destination).unwrap();
        assert_eq!(std::fs::read(&destination).unwrap(), b"new note");
        assert!(!temp.exists());
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }
    #[test]
    fn publish_onto_existing_destination_is_a_collision_and_preserves_it() {
        let dir = tempfile::tempdir().unwrap();
        let destination = dir.path().join("note.syncr");
        std::fs::write(&destination, b"existing note").unwrap();
        let temp = temporary(dir.path(), ".syncr-create-").unwrap();
        std::fs::write(&temp, b"replacement").unwrap();
        assert!(matches!(
            publish(&temp, &destination),
            Err(CoreError::IdCollision {
                retryable: false,
                ..
            })
        ));
        assert_eq!(std::fs::read(&destination).unwrap(), b"existing note");
        assert_eq!(std::fs::read(&temp).unwrap(), b"replacement");
    }
}
