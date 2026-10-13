// Error mapping is internal and covered in src/error.rs; this external test
// checks that all 13 contract identities keep detail/retryable and Display.
use syncr_core::CoreError;
#[test]
fn all_frozen_error_variants_have_safe_fields() {
    let errors = [
        CoreError::InvalidInput {
            detail: "invalid".into(),
            retryable: false,
        },
        CoreError::UnsupportedVersion {
            detail: "unsupported".into(),
            retryable: false,
        },
        CoreError::NotFound {
            detail: "missing".into(),
            retryable: false,
        },
        CoreError::PermissionDenied {
            detail: "denied".into(),
            retryable: false,
        },
        CoreError::StorageFull {
            detail: "full".into(),
            retryable: true,
        },
        CoreError::StorageIo {
            detail: "io".into(),
            retryable: true,
        },
        CoreError::NoteLocked {
            detail: "locked".into(),
            retryable: true,
        },
        CoreError::StaleBase {
            detail: "stale".into(),
            retryable: false,
        },
        CoreError::MissingDependency {
            detail: "dependency".into(),
            retryable: false,
        },
        CoreError::ResourceLimit {
            detail: "limit".into(),
            retryable: false,
        },
        CoreError::IdCollision {
            detail: "collision".into(),
            retryable: false,
        },
        CoreError::Closed {
            detail: "closed".into(),
            retryable: false,
        },
        CoreError::Internal {
            detail: "internal".into(),
            retryable: false,
        },
    ];
    let diagnostics = [
        "invalid",
        "unsupported",
        "missing",
        "denied",
        "full",
        "io",
        "locked",
        "stale",
        "dependency",
        "limit",
        "collision",
        "closed",
        "internal",
    ];
    assert_eq!(errors.len(), 13);
    for (error, diagnostic) in errors.into_iter().zip(diagnostics) {
        assert_eq!(error.to_string(), diagnostic);
    }
}
