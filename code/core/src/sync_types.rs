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

/// What `export_delta` wrote. Without `anchored` nothing was written.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DeltaReceipt {
    pub anchored: bool,
    pub operations: u32,
    pub bytes: u64,
    pub exported_local_sequence: u64,
}

impl DeltaReceipt {
    pub(crate) fn unanchored(exported_local_sequence: u64) -> Self {
        Self {
            anchored: false,
            operations: 0,
            bytes: 0,
            exported_local_sequence,
        }
    }
}

/// The newest operation a log holds from one author.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct AuthorHead {
    pub author_id: Id,
    pub newest_id: Id,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DeltaMerge {
    pub report: MergeReport,
    pub complete: bool,
}
