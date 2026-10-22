//! The contract error identities (13 original, 3 additive text errors). Every variant carries a fixed, safe `detail`: never a
//! path, SQL text or note content, because hosts may log or show it.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{detail}")]
    InvalidInput { detail: String, retryable: bool },
    #[error("{detail}")]
    UnsupportedVersion { detail: String, retryable: bool },
    #[error("{detail}")]
    NotFound { detail: String, retryable: bool },
    #[error("{detail}")]
    PermissionDenied { detail: String, retryable: bool },
    #[error("{detail}")]
    StorageFull { detail: String, retryable: bool },
    #[error("{detail}")]
    StorageIo { detail: String, retryable: bool },
    #[error("{detail}")]
    NoteLocked { detail: String, retryable: bool },
    #[error("{detail}")]
    StaleBase { detail: String, retryable: bool },
    #[error("{detail}")]
    MissingDependency { detail: String, retryable: bool },
    #[error("{detail}")]
    ResourceLimit { detail: String, retryable: bool },
    #[error("{detail}")]
    IdCollision { detail: String, retryable: bool },
    #[error("{detail}")]
    Closed { detail: String, retryable: bool },
    #[error("{detail}")]
    Internal { detail: String, retryable: bool },
    #[error("{detail}")]
    InvalidText { detail: String, retryable: bool },
    #[error("{detail}")]
    InvalidTextRange { detail: String, retryable: bool },
    #[error("{detail}")]
    DocumentTextExists { detail: String, retryable: bool },
}
pub type CoreResult<T> = Result<T, CoreError>;

macro_rules! core_error {
    ($variant:ident, $detail:expr, $retryable:expr) => {
        CoreError::$variant {
            detail: String::from($detail),
            retryable: $retryable,
        }
    };
}

// Constructors. Only storage contention and I/O failures are retryable.

pub(crate) fn invalid(detail: &str) -> CoreError {
    core_error!(InvalidInput, detail, false)
}
pub(crate) fn invalid_text() -> CoreError {
    core_error!(
        InvalidText,
        "Text must not contain NUL or carriage returns",
        false
    )
}
pub(crate) fn text_range() -> CoreError {
    core_error!(
        InvalidTextRange,
        "Splice range must be in bounds on UTF-8 character boundaries",
        false
    )
}
pub(crate) fn document_exists() -> CoreError {
    core_error!(
        DocumentTextExists,
        "A note may have only one live document text block",
        false
    )
}
pub(crate) fn limit(detail: &str) -> CoreError {
    core_error!(ResourceLimit, detail, false)
}
pub(crate) fn unsupported(detail: &str) -> CoreError {
    core_error!(UnsupportedVersion, detail, false)
}
pub(crate) fn missing_dependency(detail: &str) -> CoreError {
    core_error!(MissingDependency, detail, false)
}
pub(crate) fn id_collision(detail: &str) -> CoreError {
    core_error!(IdCollision, detail, false)
}
pub(crate) fn locked(detail: &str) -> CoreError {
    core_error!(NoteLocked, detail, true)
}
pub(crate) fn storage_io(detail: &str, retryable: bool) -> CoreError {
    core_error!(StorageIo, detail, retryable)
}
pub(crate) fn internal() -> CoreError {
    core_error!(Internal, "Core state could not be read", false)
}
pub(crate) fn missing() -> CoreError {
    core_error!(NotFound, "Requested note or entity was not found", false)
}
pub(crate) fn stale() -> CoreError {
    core_error!(
        StaleBase,
        "The edit no longer matches the current state",
        false
    )
}
pub(crate) fn dependency() -> CoreError {
    missing_dependency("A required committed operation is missing from dependencies")
}
pub(crate) fn collision() -> CoreError {
    id_collision("This ID already identifies different data")
}
pub(crate) fn closed() -> CoreError {
    core_error!(Closed, "Note is closed", false)
}
fn storage_full() -> CoreError {
    core_error!(StorageFull, "Storage is full", true)
}
fn permission_denied() -> CoreError {
    core_error!(PermissionDenied, "Storage permission denied", false)
}

/// Maps a SQLite failure to its contract identity, dropping SQLite's own message.
pub(crate) fn storage(error: rusqlite::Error) -> CoreError {
    use rusqlite::ErrorCode as Code;
    match error.sqlite_error_code() {
        Some(Code::DiskFull) => storage_full(),
        Some(Code::PermissionDenied | Code::ReadOnly) => permission_denied(),
        Some(Code::DatabaseBusy | Code::DatabaseLocked) => locked("Note storage is busy"),
        _ => storage_io("Library storage operation failed", true),
    }
}

/// Maps a filesystem failure to its contract identity, dropping the OS message and path.
pub(crate) fn io(error: std::io::Error) -> CoreError {
    match error.kind() {
        std::io::ErrorKind::PermissionDenied => permission_denied(),
        std::io::ErrorKind::StorageFull => storage_full(),
        _ => storage_io("Library filesystem operation failed", true),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn storage_errors_are_typed_and_do_not_leak_sql_detail() {
        let full = rusqlite::Error::SqliteFailure(
            rusqlite::ffi::Error::new(rusqlite::ffi::SQLITE_FULL),
            Some("private note content".into()),
        );
        let error = storage(full);
        assert!(matches!(
            error,
            CoreError::StorageFull {
                retryable: true,
                ..
            }
        ));
        assert!(!error.to_string().contains("private"));
        let denied = rusqlite::Error::SqliteFailure(
            rusqlite::ffi::Error::new(rusqlite::ffi::SQLITE_READONLY),
            None,
        );
        assert!(matches!(
            storage(denied),
            CoreError::PermissionDenied { .. }
        ));
        assert!(matches!(
            io(std::io::Error::from(std::io::ErrorKind::StorageFull)),
            CoreError::StorageFull { .. }
        ));
    }
}
