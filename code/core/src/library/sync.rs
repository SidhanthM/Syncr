//! Two-way sync: the operation-set digest, merging a copy's operations into a note, installing
//! a copy, and the phone's rebase onto a Mac copy. An operation has the same canonical bytes
//! on every device, so merging is "append what the log lacks, under the original author,
//! through the normal reducer". Nothing is ever deleted from a log.
use super::{Note, SyncrLibrary, catalog, lock, note, path_input};
use crate::{
    canonical,
    error::{collision, internal, invalid, storage, unsupported},
    fault::crash_point,
    files, store, validation, *,
};
use rusqlite::{Connection, OptionalExtension};
use std::{path::Path, sync::Arc};

/// Prefix of rebase staging files in the library root; removed on the next open.
pub(super) const REBASE_PREFIX: &str = ".syncr-rebase-";

// ---- SHA-256 (no dependency may be added) ------------------------------------------------

const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

fn compress(h: &mut [u32; 8], block: &[u8]) {
    let mut w = [0u32; 64];
    for (i, c) in block.chunks_exact(4).enumerate() {
        w[i] = u32::from_be_bytes([c[0], c[1], c[2], c[3]]);
    }
    for i in 16..64 {
        let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
        let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16]
            .wrapping_add(s0)
            .wrapping_add(w[i - 7])
            .wrapping_add(s1);
    }
    let mut v = *h;
    for i in 0..64 {
        let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
        let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
        let t1 = v[7]
            .wrapping_add(s1)
            .wrapping_add(ch)
            .wrapping_add(K[i])
            .wrapping_add(w[i]);
        let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
        let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
        let t2 = s0.wrapping_add(maj);
        v = [
            t1.wrapping_add(t2),
            v[0],
            v[1],
            v[2],
            v[3].wrapping_add(t1),
            v[4],
            v[5],
            v[6],
        ];
    }
    for (a, b) in h.iter_mut().zip(v) {
        *a = a.wrapping_add(b);
    }
}

pub(crate) fn sha256_hex(data: &[u8]) -> String {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let mut blocks = data.chunks_exact(64);
    for block in &mut blocks {
        compress(&mut h, block);
    }
    let mut tail = blocks.remainder().to_vec();
    tail.push(0x80);
    while tail.len() % 64 != 56 {
        tail.push(0);
    }
    tail.extend_from_slice(&((data.len() as u64) * 8).to_be_bytes());
    for block in tail.chunks_exact(64) {
        compress(&mut h, block);
    }
    h.iter().map(|v| format!("{v:08x}")).collect()
}

pub(super) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ---- digest ------------------------------------------------------------------------------

fn compute_digest(db: &Connection) -> CoreResult<SyncDigest> {
    let mut statement = db.prepare("SELECT id FROM operations").map_err(storage)?;
    let mut ids = statement
        .query_map([], |r| r.get::<_, String>(0))
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)?;
    ids.sort();
    let mut text = String::with_capacity(ids.len() * 27);
    for id in &ids {
        text.push_str(id);
        text.push('\n');
    }
    Ok(SyncDigest {
        operation_count: ids.len() as u64,
        digest: sha256_hex(text.as_bytes()),
    })
}

pub(super) fn digest(note: &Note) -> CoreResult<SyncDigest> {
    let mut r = lock(&note.runtime)?;
    if let Some(d) = &r.digest {
        return Ok(d.clone());
    }
    let d = compute_digest(r.db()?)?;
    r.digest = Some(d.clone());
    Ok(d)
}

pub(super) fn note_sync_digest(library: &SyncrLibrary, note_id: Id) -> CoreResult<SyncDigest> {
    validation::id(&note_id)?;
    let mut cat = lock(&library.catalog)?;
    if let Some(open) = cat.open_handle(&note_id)? {
        drop(cat);
        return digest(&open);
    }
    let published = library
        .find_published(&mut cat, &note_id)?
        .ok_or_else(crate::error::missing)?;
    let db = store::open(&published.path, false)?;
    let result = compute_digest(&db);
    drop(db);
    files::remove_idle_sidecars(&published.path);
    result
}

// ---- merge -------------------------------------------------------------------------------

/// Opens a copy read-only after the normal version and application checks. Nothing is
/// written to it and it is never migrated: operations from before the canvas format are
/// refused (`UnsupportedVersion`).
fn open_copy(copy_path: &str) -> CoreResult<Connection> {
    path_input(copy_path, "Copy must be an app-owned filesystem path")?;
    let db = store::open(Path::new(copy_path), false)?;
    if store::check_version(&db)? < store::NOTE_VERSION {
        return Err(unsupported("Copy predates the canvas format"));
    }
    Ok(db)
}

/// Errors that reject one operation; anything else (storage, locks, internal) aborts.
fn rejection(error: &CoreError) -> Option<&'static str> {
    Some(match error {
        CoreError::StaleBase { .. } => "stale base",
        CoreError::MissingDependency { .. } => "missing dependency",
        CoreError::InvalidInput { .. } => "invalid input",
        CoreError::IdCollision { .. } => "id collision",
        CoreError::NotFound { .. } => "not found",
        CoreError::ResourceLimit { .. } => "resource limit",
        CoreError::InvalidText { .. } => "invalid text",
        CoreError::InvalidTextRange { .. } => "invalid text range",
        CoreError::DocumentTextExists { .. } => "document text exists",
        _ => return None,
    })
}

pub(super) struct LogRow {
    pub(super) id: Id,
    pub(super) author: Id,
    pub(super) canonical: Vec<u8>,
    pub(super) payload: String,
}

fn log_rows(copy: &Connection) -> CoreResult<Vec<LogRow>> {
    let mut statement = copy
        .prepare("SELECT id, author_id, canonical, payload FROM operations ORDER BY local_sequence")
        .map_err(storage)?;
    statement
        .query_map([], |r| {
            Ok(LogRow {
                id: r.get(0)?,
                author: r.get(1)?,
                canonical: r.get(2)?,
                payload: r.get(3)?,
            })
        })
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)
}

/// Which side table a left-out byte string lives in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Extra {
    Samples,
    Image,
}

/// Where left-out bytes come from: a copy's tables, or the extras a delta carries.
pub(super) type Fetch<'a> = dyn FnMut(Extra, &str) -> CoreResult<Option<Vec<u8>>> + 'a;

/// Reads left-out bytes from a database's `samples` and `images` tables.
pub(super) fn table_fetch(
    db: &Connection,
) -> impl FnMut(Extra, &str) -> CoreResult<Option<Vec<u8>>> + '_ {
    move |kind, key| match kind {
        Extra::Samples => db
            .query_row("SELECT bytes FROM samples WHERE mark_id=?1", [key], |r| {
                r.get(0)
            })
            .optional()
            .map_err(storage),
        Extra::Image => {
            let has_table: bool = db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='images')",
                    [],
                    |r| r.get(0),
                )
                .map_err(storage)?;
            if !has_table {
                return Ok(None);
            }
            db.query_row("SELECT bytes FROM images WHERE image_id=?1", [key], |r| {
                r.get(0)
            })
            .optional()
            .map_err(storage)
        }
    }
}

/// Puts back the bytes the stored payload leaves out: stroke samples (kept in `samples`
/// when the payload has none) and image bytes (always skipped in payload JSON).
pub(super) fn fill_bytes(command: &mut Command, fetch: &mut Fetch) -> CoreResult<()> {
    match &mut command.body {
        CommandBody::Marks { mutations } => {
            for m in mutations {
                if let MarkMutation::AddStroke {
                    mark_id, samples, ..
                } = m
                    && samples.is_empty()
                {
                    *samples = fetch(Extra::Samples, mark_id)?.unwrap_or_default();
                }
            }
        }
        CommandBody::ImageMarks { mutations } => {
            for m in mutations {
                if let ImageMutation::Add {
                    image_id, bytes, ..
                } = m
                    && bytes.is_empty()
                {
                    *bytes = fetch(Extra::Image, image_id)?.unwrap_or_default();
                }
            }
        }
        _ => {}
    }
    Ok(())
}

/// Applies one stored operation of another device's log to `note`, counting it in `report`.
pub(super) fn apply_row(
    note: &Note,
    note_id: &Id,
    row: &LogRow,
    fetch: &mut Fetch,
    report: &mut MergeReport,
) -> CoreResult<()> {
    let reject = |reason: String| RejectedOperation {
        operation_id: row.id.clone(),
        author_id: row.author.clone(),
        payload_json: row.payload.clone(),
        canonical_hex: hex(&row.canonical),
        reason,
    };
    let have = {
        let r = lock(&note.runtime)?;
        store::lookup(r.db()?, &row.id)?
    };
    if let Some((stored, _)) = have {
        if stored == row.canonical {
            report.duplicates += 1;
        } else {
            report.rejected.push(reject("id collision".into()));
        }
        return Ok(());
    }
    let Ok(mut command) = store::parse::<Command>(&row.payload) else {
        report.rejected.push(reject("unreadable payload".into()));
        return Ok(());
    };
    fill_bytes(&mut command, fetch)?;
    let recomputed = validation::id(&row.author)
        .and_then(|()| canonical::command(&command, note_id, &row.author));
    if !matches!(&recomputed, Ok(c) if *c == row.canonical) {
        report.rejected.push(reject("canonical mismatch".into()));
        return Ok(());
    }
    match note::submit_checked(note, command, &row.author, false) {
        Ok(receipt) if receipt.duplicate => report.duplicates += 1,
        Ok(_) => report.applied += 1,
        Err(e) => match rejection(&e) {
            Some(reason) => report.rejected.push(reject(reason.into())),
            None => return Err(e),
        },
    }
    Ok(())
}

fn merge_from(note: &Note, copy: &Connection) -> CoreResult<MergeReport> {
    let (note_id, creation) = {
        let r = lock(&note.runtime)?;
        r.db()?;
        (r.state.note_id.clone(), r.state.creation.clone())
    };
    let copy_state = store::load(copy)?;
    if copy_state.note_id != note_id || copy_state.creation != creation {
        return Err(invalid("Copy is a different note"));
    }
    let copy_creation = store::lookup(copy, &creation)?.ok_or_else(internal)?.0;
    let own_creation = {
        let r = lock(&note.runtime)?;
        store::lookup(r.db()?, &creation)?.ok_or_else(internal)?.0
    };
    if copy_creation != own_creation {
        return Err(invalid("Copy has a different creation operation"));
    }
    let mut report = MergeReport {
        applied: 0,
        duplicates: 0,
        rejected: vec![],
    };
    let mut fetch = table_fetch(copy);
    for row in log_rows(copy)? {
        if row.id == creation {
            continue;
        }
        apply_row(note, &note_id, &row, &mut fetch, &mut report)?;
    }
    Ok(report)
}

pub(super) fn merge_copy(note: &Note, copy_path: String) -> CoreResult<MergeReport> {
    let copy = open_copy(&copy_path)?;
    let report = merge_from(note, &copy);
    drop(copy);
    files::remove_idle_sidecars(Path::new(&copy_path));
    report
}

// ---- install -----------------------------------------------------------------------------

pub(super) fn install_copy(library: &SyncrLibrary, copy_path: String) -> CoreResult<NoteSummary> {
    path_input(&copy_path, "Copy must be an app-owned filesystem path")?;
    let db = store::open(Path::new(&copy_path), false)?;
    let state = store::load(&db)?;
    store::lookup(&db, &state.creation)?.ok_or_else(internal)?;
    let integrity: String = db
        .query_row("PRAGMA quick_check", [], |r| r.get(0))
        .map_err(storage)?;
    if integrity != "ok" {
        return Err(invalid("Copy failed its integrity check"));
    }
    let mut cat = lock(&library.catalog)?;
    if library
        .scan(&mut cat)?
        .iter()
        .any(|p| p.state.note_id == state.note_id)
        || library.is_deleted_or_purged(&cat, &state.note_id)?
    {
        return Err(collision());
    }
    let destination = library.note_path(&state.note_id);
    let published = store::export(
        &db,
        &library.root,
        catalog::CREATE_TEMP_PREFIX,
        &destination,
    );
    drop(db);
    files::remove_idle_sidecars(Path::new(&copy_path));
    published?;
    drop(cat);
    catalog::list_notes(library)?
        .into_iter()
        .find(|n| n.id == state.note_id)
        .ok_or_else(internal)
}

// ---- rebase ------------------------------------------------------------------------------

pub(super) fn rebase_onto_copy(
    library: &Arc<SyncrLibrary>,
    note_id: Id,
    copy_path: String,
) -> CoreResult<MergeReport> {
    validation::id(&note_id)?;
    let copy = open_copy(&copy_path)?;
    let mut cat = lock(&library.catalog)?;
    if cat.open_handle(&note_id)?.is_some() {
        return Err(crate::error::locked("Close the note before rebasing it"));
    }
    let published = library
        .find_published(&mut cat, &note_id)?
        .ok_or_else(crate::error::missing)?;
    if store::load(&copy)?.note_id != note_id {
        return Err(invalid("Copy is a different note"));
    }
    store::fold_journal(&published.path)?;
    let local = store::open(&published.path, false)?;
    if store::check_version(&local)? < store::NOTE_VERSION {
        return Err(unsupported("Note predates the canvas format"));
    }
    // 1. The backup comes first, and nothing else happens if it cannot be made.
    let backups = library.root.join(store::BACKUP_DIR);
    if !backups.is_dir() {
        std::fs::create_dir_all(&backups).map_err(crate::error::io)?;
        files::sync(&library.root)?;
    }
    let backup = backups.join(format!("{note_id}.rebase.{}.syncr", store::now_ms()));
    store::export(&local, &backups, store::BACKUP_TEMP_PREFIX, &backup)?;
    crash_point("rebase_after_backup");
    // 2. Stage the copy, merge the local operations into it, publish a standalone file.
    let stage = library
        .root
        .join(format!("{REBASE_PREFIX}stage-{}", new_id()));
    let final_file = library
        .root
        .join(format!("{REBASE_PREFIX}final-{}", new_id()));
    let result = (|| {
        store::export(&copy, &library.root, REBASE_PREFIX, &stage)?;
        let db = store::open(&stage, true)?;
        let state = store::load(&db)?;
        let staging = Note::new(library.clone(), stage.clone(), db, state);
        let report = merge_from(&staging, &local)?;
        {
            let r = lock(&staging.runtime)?;
            store::export(r.db()?, &library.root, REBASE_PREFIX, &final_file)?;
        }
        note::close_note(&staging)?;
        crash_point("rebase_before_replace");
        files::remove_idle_sidecars(&published.path);
        std::fs::rename(&final_file, &published.path).map_err(crate::error::io)?;
        files::sync(&library.root)?;
        crash_point("rebase_after_replace");
        Ok(report)
    })();
    drop(local);
    files::discard_temporary(&stage);
    files::discard_temporary(&final_file);
    let _ = library.scan(&mut cat);
    result
}
