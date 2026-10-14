//! UniFFI preserves each contract error identity and its detail/retryable fields.
//! Display (and generated exception message) remains a safe diagnostic.
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
}
pub type CoreResult<T> = Result<T, CoreError>;

pub(crate) fn invalid(detail: &str) -> CoreError {
    CoreError::InvalidInput {
        detail: detail.into(),
        retryable: false,
    }
}
pub(crate) fn limit(detail: &str) -> CoreError {
    CoreError::ResourceLimit {
        detail: detail.into(),
        retryable: false,
    }
}
pub(crate) fn unsupported(detail: &str) -> CoreError {
    CoreError::UnsupportedVersion {
        detail: detail.into(),
        retryable: false,
    }
}
pub(crate) fn internal() -> CoreError {
    CoreError::Internal {
        detail: "Core state could not be read".into(),
        retryable: false,
    }
}
pub(crate) fn missing() -> CoreError {
    CoreError::NotFound {
        detail: "Requested note or entity was not found".into(),
        retryable: false,
    }
}
pub(crate) fn stale() -> CoreError {
    CoreError::StaleBase {
        detail: "The edit no longer matches the current state".into(),
        retryable: false,
    }
}
pub(crate) fn dependency() -> CoreError {
    CoreError::MissingDependency {
        detail: "A required committed operation is missing from dependencies".into(),
        retryable: false,
    }
}
pub(crate) fn collision() -> CoreError {
    CoreError::IdCollision {
        detail: "This ID already identifies different data".into(),
        retryable: false,
    }
}
pub(crate) fn closed() -> CoreError {
    CoreError::Closed {
        detail: "Note is closed".into(),
        retryable: false,
    }
}
pub(crate) fn storage(error: rusqlite::Error) -> CoreError {
    use rusqlite::{Error, ErrorCode};
    match error {
        Error::SqliteFailure(e, _) if e.code == ErrorCode::DiskFull => CoreError::StorageFull {
            detail: "Storage is full".into(),
            retryable: true,
        },
        Error::SqliteFailure(e, _)
            if e.code == ErrorCode::PermissionDenied || e.code == ErrorCode::ReadOnly =>
        {
            CoreError::PermissionDenied {
                detail: "Storage permission denied".into(),
                retryable: false,
            }
        }
        Error::SqliteFailure(e, _)
            if e.code == ErrorCode::DatabaseBusy || e.code == ErrorCode::DatabaseLocked =>
        {
            CoreError::NoteLocked {
                detail: "Note storage is busy".into(),
                retryable: true,
            }
        }
        _ => CoreError::StorageIo {
            detail: "Library storage operation failed".into(),
            retryable: true,
        },
    }
}
pub(crate) fn io(error: std::io::Error) -> CoreError {
    if error.kind() == std::io::ErrorKind::PermissionDenied {
        CoreError::PermissionDenied {
            detail: "Storage permission denied".into(),
            retryable: false,
        }
    } else if error.kind() == std::io::ErrorKind::StorageFull {
        CoreError::StorageFull {
            detail: "Storage is full".into(),
            retryable: true,
        }
    } else {
        CoreError::StorageIo {
            detail: "Library filesystem operation failed".into(),
            retryable: true,
        }
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
