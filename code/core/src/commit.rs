//! Durable stroke commit over the wire (`shared.md` §6): one complete stroke at pen-up, split into
//! chunk frames of at most 64 KiB and reassembled atomically. This is a different message kind
//! from preview (first byte 0x43 'C', preview is 0x50 'P') and is never dropped by the sender
//! queue. It carries the stroke; the sequencer's operation is what makes it durable.
//!
//! Payload (what is chunked): note id [16], sheet id [16], brush (`brush.rs`: 3 bytes, up to 18 with a custom ink and feel),
//! coordinate space u8, sample blob length u32, then the SYNS sample blob (at most 16 MiB).
//!
//! Chunk frame (little-endian), 36-byte header then data:
//!
//! ```text
//!  0  u8   0x43 'C'    frame kind: durable chunk
//!  1  u8   1           protocol version
//!  2  u8   1           message: stroke chunk
//!  3  u8   0           flags, reserved, must be zero
//!  4  [16] stroke id   identifies the transfer
//! 20  u32  payload length
//! 24  u32  CRC-32 (IEEE) of the whole payload
//! 28  u32  chunk count  = ceil(payload length / 65500)
//! 32  u32  chunk index
//! 36  ...  data: 65500 bytes in every chunk but the last, which holds the remainder
//! ```
//!
//! A full chunk frame is exactly 64 KiB. The CRC detects corruption and truncation; it is not
//! authentication and offers no protection against a deliberate attacker. Security comes from
//! the transport (pinned TLS), not from this check.
//!
//! The reassembler holds one transfer at a time. A chunk of a different stroke while one is in
//! progress is refused with a retryable limit error; a transfer idle for 30 s is abandoned when
//! the next chunk arrives. Chunks may arrive in any order; duplicates are ignored; a transfer
//! with a missing chunk never completes and never yields anything.
use crate::{
    Brush, CoordinateSpace, CoreError, CoreResult, Id,
    error::{invalid, limit, unsupported},
    preview::{Reader, brush_bytes, bytes_id, id_bytes, read_brush, read_space, space_byte},
    samples,
};
use std::sync::{Arc, Mutex};

pub(crate) const KIND_CHUNK: u8 = 0x43;
const VERSION: u8 = 1;
const MSG_STROKE_CHUNK: u8 = 1;
const HEADER: usize = 36;
const PAYLOAD_META: usize = 40;
pub(crate) const CHUNK_DATA: usize = 64 * 1024 - HEADER;
pub(crate) const MAX_PAYLOAD: usize =
    samples::MAX_SAMPLE_BYTES + PAYLOAD_META + crate::brush::MAX_BRUSH_BYTES - 3;
pub(crate) const MAX_REASSEMBLY_BYTES: usize = 20 * 1024 * 1024;
pub const REASSEMBLY_IDLE_MS: u64 = 30_000;

/// One complete stroke. `samples` is a SYNS blob exactly as `encode_samples` makes it.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct StrokeCommit {
    pub note_id: Id,
    pub sheet_id: Id,
    pub stroke_id: Id,
    pub brush: Brush,
    pub space: CoordinateSpace,
    pub samples: Vec<u8>,
}

const fn crc_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 != 0 {
                0xEDB8_8320 ^ (c >> 1)
            } else {
                c >> 1
            };
            k += 1;
        }
        table[i] = c;
        i += 1;
    }
    table
}
static CRC_TABLE: [u32; 256] = crc_table();

/// CRC-32/ISO-HDLC (zlib, PNG). Corruption check only.
pub(crate) fn crc32(data: &[u8]) -> u32 {
    !data.iter().fold(!0u32, |c, &b| {
        CRC_TABLE[((c ^ u32::from(b)) & 0xff) as usize] ^ (c >> 8)
    })
}

fn limit_retryable(detail: &str) -> CoreError {
    CoreError::ResourceLimit {
        detail: detail.into(),
        retryable: true,
    }
}

/// Splits a stroke into chunk frames, in order. Fails with `InvalidInput` for bad IDs or a
/// sample blob that `decode_samples` would reject, and `ResourceLimit` above 16 MiB of samples.
#[uniffi::export]
pub fn encode_stroke_commit(commit: StrokeCommit) -> CoreResult<Vec<Vec<u8>>> {
    encode_chunks(&commit)
}

pub(crate) fn encode_chunks(commit: &StrokeCommit) -> CoreResult<Vec<Vec<u8>>> {
    let note = id_bytes(&commit.note_id)?;
    let sheet = id_bytes(&commit.sheet_id)?;
    let stroke = id_bytes(&commit.stroke_id)?;
    samples::decode(&commit.samples)?; // size limit and full validation
    let mut payload = Vec::with_capacity(PAYLOAD_META + commit.samples.len());
    payload.extend_from_slice(&note);
    payload.extend_from_slice(&sheet);
    payload.extend_from_slice(&brush_bytes(&commit.brush));
    payload.push(space_byte(commit.space));
    payload.extend_from_slice(&(commit.samples.len() as u32).to_le_bytes());
    payload.extend_from_slice(&commit.samples);
    let crc = crc32(&payload);
    let count = payload.len().div_ceil(CHUNK_DATA);
    let mut frames = Vec::with_capacity(count);
    for (index, data) in payload.chunks(CHUNK_DATA).enumerate() {
        let mut f = Vec::with_capacity(HEADER + data.len());
        f.extend_from_slice(&[KIND_CHUNK, VERSION, MSG_STROKE_CHUNK, 0]);
        f.extend_from_slice(&stroke);
        f.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        f.extend_from_slice(&crc.to_le_bytes());
        f.extend_from_slice(&(count as u32).to_le_bytes());
        f.extend_from_slice(&(index as u32).to_le_bytes());
        f.extend_from_slice(data);
        frames.push(f);
    }
    Ok(frames)
}

struct Chunk<'a> {
    stroke_id: Id,
    payload_len: usize,
    crc: u32,
    count: usize,
    index: usize,
    data: &'a [u8],
}

fn decode_chunk(bytes: &[u8]) -> CoreResult<Chunk<'_>> {
    if bytes.len() > crate::preview::MAX_FRAME_BYTES {
        return Err(limit("Chunk frame exceeds 64 KiB"));
    }
    let mut r = Reader::new(bytes);
    if r.u8()? != KIND_CHUNK {
        return Err(invalid("Not a durable chunk frame"));
    }
    if r.u8()? != VERSION {
        return Err(unsupported("Unsupported chunk protocol version"));
    }
    if r.u8()? != MSG_STROKE_CHUNK {
        return Err(invalid("Unknown durable message"));
    }
    if r.u8()? != 0 {
        return Err(invalid("Reserved chunk flags must be zero"));
    }
    let stroke_id = bytes_id(r.take(16)?);
    let payload_len = r.u32()? as usize;
    let crc = r.u32()?;
    let count = r.u32()? as usize;
    let index = r.u32()? as usize;
    if payload_len > MAX_PAYLOAD {
        return Err(limit("Stroke exceeds 16 MiB of samples"));
    }
    if payload_len < PAYLOAD_META || count != payload_len.div_ceil(CHUNK_DATA) || index >= count {
        return Err(invalid("Inconsistent chunk header"));
    }
    let expect = if index + 1 == count {
        payload_len - index * CHUNK_DATA
    } else {
        CHUNK_DATA
    };
    if r.remaining() != expect {
        return Err(invalid("Chunk data length does not match its header"));
    }
    let data = r.take(expect)?;
    Ok(Chunk {
        stroke_id,
        payload_len,
        crc,
        count,
        index,
        data,
    })
}

struct Transfer {
    stroke_id: Id,
    crc: u32,
    count: usize,
    buf: Vec<u8>,
    seen: Vec<bool>,
    received: usize,
    last_ms: u64,
}

fn parse_payload(stroke_id: Id, payload: &[u8]) -> CoreResult<StrokeCommit> {
    let mut r = Reader::new(payload);
    let note_id = bytes_id(r.take(16)?);
    let sheet_id = bytes_id(r.take(16)?);
    let brush = read_brush(&mut r)?;
    let space = read_space(&mut r)?;
    let len = r.u32()? as usize;
    if len != r.remaining() {
        return Err(invalid("Stroke payload length mismatch"));
    }
    let blob = r.take(len)?.to_vec();
    samples::decode(&blob)?;
    Ok(StrokeCommit {
        note_id,
        sheet_id,
        stroke_id,
        brush,
        space,
        samples: blob,
    })
}

/// Reassembles chunk frames into whole strokes. Yields a stroke only when every chunk has
/// arrived and the CRC matches; never a partial one. One transfer at a time, at most 20 MiB.
#[derive(uniffi::Object)]
pub struct StrokeReassembler {
    current: Mutex<Option<Transfer>>,
}

#[uniffi::export]
impl StrokeReassembler {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(StrokeReassembler {
            current: Mutex::new(None),
        })
    }

    /// `Ok(Some(stroke))` when this chunk completed a verified stroke, `Ok(None)` while more
    /// are needed. A chunk of another stroke while one is in progress gives a retryable
    /// `ResourceLimit`. A failed integrity check discards the transfer (`InvalidInput`).
    pub fn accept(&self, frame: Vec<u8>, now_ms: u64) -> CoreResult<Option<StrokeCommit>> {
        let chunk = decode_chunk(&frame)?;
        let mut guard = self.current.lock().unwrap_or_else(|e| e.into_inner());
        if guard
            .as_ref()
            .is_some_and(|t| now_ms.saturating_sub(t.last_ms) >= REASSEMBLY_IDLE_MS)
        {
            *guard = None;
        }
        match guard.as_ref() {
            Some(t) if t.stroke_id != chunk.stroke_id => {
                return Err(limit_retryable("Another stroke transfer is in progress"));
            }
            Some(t)
                if t.crc != chunk.crc
                    || t.count != chunk.count
                    || t.buf.len() != chunk.payload_len =>
            {
                return Err(invalid("Chunk does not match its transfer"));
            }
            Some(_) => {}
            None => {
                if chunk.payload_len > MAX_REASSEMBLY_BYTES {
                    return Err(limit_retryable("Reassembly exceeds 20 MiB"));
                }
                *guard = Some(Transfer {
                    stroke_id: chunk.stroke_id.clone(),
                    crc: chunk.crc,
                    count: chunk.count,
                    buf: vec![0; chunk.payload_len],
                    seen: vec![false; chunk.count],
                    received: 0,
                    last_ms: now_ms,
                });
            }
        }
        let t = guard.as_mut().expect("transfer present");
        t.last_ms = now_ms;
        if !t.seen[chunk.index] {
            let start = chunk.index * CHUNK_DATA;
            t.buf[start..start + chunk.data.len()].copy_from_slice(chunk.data);
            t.seen[chunk.index] = true;
            t.received += 1;
        }
        if t.received < t.count {
            return Ok(None);
        }
        let done = guard.take().expect("transfer present");
        if crc32(&done.buf) != done.crc {
            return Err(invalid("Stroke integrity check failed"));
        }
        parse_payload(done.stroke_id, &done.buf).map(Some)
    }

    /// Abandons any transfer in progress (disconnect).
    pub fn reset(&self) {
        *self.current.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    pub fn in_progress(&self) -> bool {
        self.current
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some()
    }
}
