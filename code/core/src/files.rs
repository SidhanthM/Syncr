//! Filesystem primitives: fsync, temporary files, no-overwrite publication and SQLite
//! sidecar handling. Every rename here refuses to replace an existing destination.
use crate::{
    error::{collision, io, storage_io},
    fault::crash_point,
    *,
};
use std::{
    fs::File,
    io::ErrorKind,
    path::{Path, PathBuf},
};

const SIDECAR_SUFFIXES: [&str; 3] = ["-wal", "-shm", "-journal"];

/// Flushes a file, or a directory's entries, to stable storage.
pub(crate) fn sync(path: &Path) -> CoreResult<()> {
    File::open(path).and_then(|f| f.sync_all()).map_err(io)
}

/// The directory containing `path`; `.` for a bare file name.
pub(crate) fn parent(path: &Path) -> &Path {
    path.parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."))
}

/// Reserves a new empty file `<parent>/<prefix><ULID>`.
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
    sync(temp)?;
    rename_noreplace(temp, destination).map_err(rename_error)?;
    sync(parent(destination))?;
    crash_point("after_publication");
    Ok(())
}

/// Atomically moves a closed, published file to another directory on the same filesystem
/// without replacing an existing destination (same no-overwrite rename as [publish]). Both
/// directories are synced before success, so a crash leaves the file at exactly one name.
pub(crate) fn move_noreplace(from: &Path, to: &Path) -> CoreResult<()> {
    rename_noreplace(from, to).map_err(rename_error)?;
    sync(parent(to))?;
    if parent(from) != parent(to) {
        sync(parent(from))?;
    }
    Ok(())
}

fn rename_error(e: std::io::Error) -> CoreError {
    if e.kind() == ErrorKind::AlreadyExists {
        collision()
    } else if is_unsupported(&e) {
        storage_io(
            "Filesystem does not support atomic no-overwrite publication",
            false,
        )
    } else {
        io(e)
    }
}

/// `<path><suffix>`, e.g. the `-wal` file beside a database.
pub(crate) fn sidecar(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(suffix);
    PathBuf::from(name)
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

/// Removes a file and its SQLite sidecars, sidecars first. A missing file is not an error.
pub(crate) fn remove_with_sidecars(path: &Path) -> CoreResult<()> {
    for suffix in SIDECAR_SUFFIXES.into_iter().chain([""]) {
        match std::fs::remove_file(sidecar(path, suffix)) {
            Err(e) if e.kind() != ErrorKind::NotFound => return Err(io(e)),
            _ => {}
        }
    }
    Ok(())
}

/// Best-effort removal of an unpublished temporary file and its sidecars. Used after both
/// success (the rename already consumed it) and failure.
pub(crate) fn discard_temporary(path: &Path) {
    for suffix in [""].into_iter().chain(SIDECAR_SUFFIXES) {
        let _ = std::fs::remove_file(sidecar(path, suffix));
    }
}

/// Removes every entry of `dir` whose name starts with `prefix`.
pub(crate) fn remove_prefixed(dir: &Path, prefix: &str) -> CoreResult<()> {
    for entry in std::fs::read_dir(dir).map_err(io)? {
        let entry = entry.map_err(io)?;
        if entry.file_name().to_string_lossy().starts_with(prefix) {
            std::fs::remove_file(entry.path()).map_err(io)?;
        }
    }
    Ok(())
}

// Android's SELinux policy denies hard links in app data, so publication is always a
// no-overwrite rename: renameat2(RENAME_NOREPLACE) on Linux/Android, renamex_np(RENAME_EXCL)
// on macOS. There is deliberately no check-then-rename fallback.

#[cfg(any(target_os = "linux", target_os = "android", target_os = "macos"))]
fn c_path(path: &Path) -> std::io::Result<std::ffi::CString> {
    use std::os::unix::ffi::OsStrExt;
    std::ffi::CString::new(path.as_os_str().as_bytes())
        .map_err(|e| std::io::Error::new(ErrorKind::InvalidInput, e))
}

/// The raw syscall avoids bionic's `renameat2` wrapper, which needs API 30.
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

/// Targets without a no-overwrite rename (none we ship) fall back to an exclusive hard
/// link plus unlinking the temporary alias.
#[cfg(not(any(target_os = "linux", target_os = "android", target_os = "macos")))]
fn rename_noreplace(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::hard_link(from, to)?;
    std::fs::remove_file(from)
}

/// Whether the filesystem lacks the no-overwrite rename. EOPNOTSUPP is a separate check
/// because it equals ENOTSUP on Linux, which would make a combined pattern unreachable.
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
