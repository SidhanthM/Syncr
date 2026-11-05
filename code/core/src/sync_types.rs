//! Two-way sync records (`tasks/contracts/sync-v1.md`).
use crate::Id;

/// Identity of a note's operation set: equal sets give equal digests whatever the log order.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct SyncDigest {
    pub operation_count: u64,
    /// SHA-256 (lowercase hex) over the sorted operation IDs, each followed by `\n`.
    pub digest: String,
}

/// An operation of a copy that was not applied, kept whole so nothing is lost.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct RejectedOperation {
    pub operation_id: Id,
    pub author_id: Id,
    pub payload_json: String,
    pub canonical_hex: String,
    pub reason: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MergeReport {
    pub applied: u32,
    pub duplicates: u32,
    pub rejected: Vec<RejectedOperation>,
}
