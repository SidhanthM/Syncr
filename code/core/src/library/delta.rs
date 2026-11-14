//! Incremental sync: a per-author version vector of a note's log, a delta file holding only the operations a peer
//! lacks, and merging one. A delta is an optimisation over a whole-note copy; the caller always
//! compares operation-set digests afterwards and falls back to a copy when they differ.
//!
//! File layout (all integers big-endian): `SYDL1\n`, note ID (length-prefixed), the sender's
//! operation count per author (u32 pairs, so the receiver can tell whether it ended up with
//! at least as many operations from each author), operation count (u32), then per operation: ID, author, canonical bytes, payload JSON (each length-prefixed),
//! extras count (u32) and per extra a kind byte (0 samples, 1 image), key and bytes.
use super::sync::{Extra, LogRow, apply_row, table_fetch, fill_bytes};
use super::{Note, SyncrLibrary, lock};
use crate::{
    error::{invalid, io, storage},
    files, store, validation, *,
};
use rusqlite::Connection;
use std::{collections::HashMap, path::Path};

const MAGIC: &[u8] = b"SYDL1\n";
/// A delta bigger than this is refused on read (the wire limit for a file is far lower).
const MAX_DELTA_BYTES: u64 = 64 * 1024 * 1024;
/// Authors a peer may list; more and the answer is a copy.
const MAX_HEADS: usize = 64;

fn put(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

struct Reader<'a>(&'a [u8]);
impl<'a> Reader<'a> {
    fn u32(&mut self) -> CoreResult<u32> {
        let (head, rest) = self
            .0
            .split_first_chunk::<4>()
            .ok_or_else(|| invalid("Delta is truncated"))?;
        self.0 = rest;
        Ok(u32::from_be_bytes(*head))
    }
    fn byte(&mut self) -> CoreResult<u8> {
        let (b, rest) = self
            .0
            .split_first()
            .ok_or_else(|| invalid("Delta is truncated"))?;
        self.0 = rest;
        Ok(*b)
    }
    fn bytes(&mut self) -> CoreResult<&'a [u8]> {
        let n = self.u32()? as usize;
        if n > self.0.len() {
            return Err(invalid("Delta is truncated"));
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }
    fn text(&mut self) -> CoreResult<String> {
        String::from_utf8(self.bytes()?.to_vec()).map_err(|_| invalid("Delta has invalid text"))
    }
}

/// Per author, the newest operation ID a log holds. Operation IDs are ULIDs and an author
/// creates them in time order, so this is a compact version vector: a peer that sends it
/// says "I have what each of these authors made up to here".
fn heads_of(db: &Connection) -> CoreResult<Vec<AuthorHead>> {
    let mut statement = db
        .prepare("SELECT author_id, MAX(id) FROM operations GROUP BY author_id ORDER BY author_id")
        .map_err(storage)?;
    statement
        .query_map([], |r| {
            Ok(AuthorHead {
                author_id: r.get(0)?,
                newest_id: r.get(1)?,
            })
        })
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)
}

pub(super) fn heads(note: &Note) -> CoreResult<Vec<AuthorHead>> {
    let r = lock(&note.runtime)?;
    heads_of(r.db()?)
}

pub(super) fn note_heads(library: &SyncrLibrary, note_id: Id) -> CoreResult<Vec<AuthorHead>> {
    validation::id(&note_id)?;
    let mut cat = lock(&library.catalog)?;
    if let Some(open) = cat.open_handle(&note_id)? {
        drop(cat);
        return heads(&open);
    }
    let published = library
        .find_published(&mut cat, &note_id)?
        .ok_or_else(crate::error::missing)?;
    let db = store::open(&published.path, false)?;
    let result = heads_of(&db);
    drop(db);
    files::remove_idle_sidecars(&published.path);
    result
}

fn author_counts(db: &Connection) -> CoreResult<Vec<(String, u32)>> {
    let mut statement = db
        .prepare("SELECT author_id, COUNT(*) FROM operations GROUP BY author_id ORDER BY author_id")
        .map_err(storage)?;
    statement
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(storage)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage)
}

/// Whether an operation may be unknown to a peer whose newest operation from the author is
/// `newest`. Operations made in the same millisecond may sort either way, so those are sent
/// (the peer skips one it has); the peer's own newest is not.
fn is_newer(id: &str, newest: &str) -> bool {
    let time = |s: &str| s.get(..10).map(str::to_owned);
    id > newest || (id != newest && time(id) == time(newest))
}

pub(super) fn export_delta(
    note: &Note,
    heads: Vec<AuthorHead>,
    destination_path: String,
) -> CoreResult<DeltaReceipt> {
    super::path_input(&destination_path, "Delta must be an app-owned filesystem path")?;
    let file_len = std::fs::metadata(&note.path).map(|m| m.len()).unwrap_or(0);
    let (out, count, sequence) = {
        let r = lock(&note.runtime)?;
        let db = r.db()?;
        let sequence = r.state.sequence;
        // Nothing is known of the peer: a copy is the right answer.
        if heads.is_empty() || heads.len() > MAX_HEADS {
            return Ok(DeltaReceipt::unanchored(sequence));
        }
        let known: HashMap<&str, &str> = heads
            .iter()
            .map(|h| (h.author_id.as_str(), h.newest_id.as_str()))
            .collect();
        let mut statement = db
            .prepare("SELECT id, author_id, canonical, payload FROM operations ORDER BY local_sequence")
            .map_err(storage)?;
        let rows = statement
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
            .map_err(storage)?;
        let rows = rows
            .into_iter()
            .filter(|row| match known.get(row.author.as_str()) {
                Some(newest) => is_newer(&row.id, newest),
                None => true,
            })
            .collect::<Vec<_>>();
        // Left-out bytes ride along, read from the same database.
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        put(&mut out, r.state.note_id.as_bytes());
        let counts = author_counts(db)?;
        out.extend_from_slice(&(counts.len() as u32).to_be_bytes());
        for (author, count) in &counts {
            put(&mut out, author.as_bytes());
            out.extend_from_slice(&count.to_be_bytes());
        }
        out.extend_from_slice(&(rows.len() as u32).to_be_bytes());
        let mut fetch = table_fetch(db);
        for row in &rows {
            put(&mut out, row.id.as_bytes());
            put(&mut out, row.author.as_bytes());
            put(&mut out, &row.canonical);
            put(&mut out, row.payload.as_bytes());
            let mut extras: Vec<(u8, String, Vec<u8>)> = vec![];
            if let Ok(mut command) = store::parse::<Command>(&row.payload) {
                let mut record = |kind: Extra, key: &str| {
                    let bytes = fetch(kind, key)?;
                    if let Some(b) = &bytes {
                        extras.push((kind as u8, key.to_string(), b.clone()));
                    }
                    Ok(bytes)
                };
                fill_bytes(&mut command, &mut record)?;
            }
            out.extend_from_slice(&(extras.len() as u32).to_be_bytes());
            for (kind, key, bytes) in extras {
                out.push(kind);
                put(&mut out, key.as_bytes());
                put(&mut out, &bytes);
            }
        }
        (out, rows.len() as u32, sequence)
    };
    // Not worth it: a copy of the note would be no bigger.
    if file_len > 0 && out.len() as u64 >= file_len {
        return Ok(DeltaReceipt::unanchored(sequence));
    }
    let destination = Path::new(&destination_path);
    std::fs::write(destination, &out).map_err(io)?;
    Ok(DeltaReceipt {
        anchored: true,
        operations: count,
        bytes: out.len() as u64,
        exported_local_sequence: sequence,
    })
}

struct DeltaRow {
    row: LogRow,
    extras: HashMap<(u8, String), Vec<u8>>,
}

fn parse(bytes: &[u8]) -> CoreResult<(Id, Vec<(String, u32)>, Vec<DeltaRow>)> {
    let Some(rest) = bytes.strip_prefix(MAGIC) else {
        return Err(invalid("Not a delta"));
    };
    let mut r = Reader(rest);
    let note_id = r.text()?;
    let mut counts = Vec::new();
    for _ in 0..r.u32()? {
        let author = r.text()?;
        counts.push((author, r.u32()?));
    }
    let count = r.u32()?;
    let mut rows = Vec::new();
    for _ in 0..count {
        let id = r.text()?;
        let author = r.text()?;
        let canonical = r.bytes()?.to_vec();
        let payload = r.text()?;
        let mut extras = HashMap::new();
        for _ in 0..r.u32()? {
            let kind = r.byte()?;
            let key = r.text()?;
            extras.insert((kind, key), r.bytes()?.to_vec());
        }
        rows.push(DeltaRow {
            row: LogRow {
                id,
                author,
                canonical,
                payload,
            },
            extras,
        });
    }
    Ok((note_id, counts, rows))
}

pub(super) fn merge_delta(note: &Note, delta_path: String) -> CoreResult<DeltaMerge> {
    super::path_input(&delta_path, "Delta must be an app-owned filesystem path")?;
    let path = Path::new(&delta_path);
    if std::fs::metadata(path).map_err(io)?.len() > MAX_DELTA_BYTES {
        return Err(crate::error::limit("Delta is too large"));
    }
    let bytes = std::fs::read(path).map_err(io)?;
    let (delta_note, counts, rows) = parse(&bytes)?;
    let (note_id, creation) = {
        let r = lock(&note.runtime)?;
        r.db()?;
        (r.state.note_id.clone(), r.state.creation.clone())
    };
    if delta_note != note_id {
        return Err(invalid("Delta is for a different note"));
    }
    let mut report = MergeReport {
        applied: 0,
        duplicates: 0,
        rejected: vec![],
    };
    for DeltaRow { row, extras } in rows {
        if row.id == creation {
            continue;
        }
        let mut fetch = |kind: Extra, key: &str| Ok(extras.get(&(kind as u8, key.to_string())).cloned());
        apply_row(note, &note_id, &row, &mut fetch, &mut report)?;
    }
    // Complete when this log now holds at least as many operations from each author as the sender's.
    let complete = {
        let r = lock(&note.runtime)?;
        let mine: HashMap<String, u32> = author_counts(r.db()?)?.into_iter().collect();
        counts
            .iter()
            .all(|(author, count)| mine.get(author).copied().unwrap_or(0) >= *count)
    };
    Ok(DeltaMerge { report, complete })
}
