//! Live pen preview (`mac-api.md` §3, `shared.md` §6): the lossy "preview" frame codec, the
//! receiver state machine and the sender's bounded queue. Pure and deterministic: no sockets, no
//! clocks (callers pass `now_ms`), no disk. The durable stroke-commit kind lives in `commit.rs`.
//!
//! Preview frames are for pixels, not records. The complete stroke the phone saves is
//! authoritative; a preview may be dropped at any time, and a receiver never invents geometry.
//!
//! Wire layout (little-endian). Header, 68 bytes:
//!
//! ```text
//!  0  u8   0x50 'P'          frame kind: preview (durable chunks start with 0x43 'C')
//!  1  u8   1                 protocol version
//!  2  u8   body kind         1 Begin, 2 Samples, 3 End, 4 Cancel, 5 Hover
//!  3  u8   0                 flags, reserved, must be zero
//!  4  u32  session epoch
//!  8  [16] note id           raw ULID bytes
//! 24  [16] sheet id
//! 40  u64  sender time ms    sender's monotonic clock; only differences are meaningful
//! 48  u32  sequence          per stroke (Begin = 0 by convention); Hover: its own counter
//! 52  [16] stroke id         all zero for Hover
//! ```
//!
//! Bodies: Begin = brush (`brush.rs`: family u8, size u8, colour u8, plus optional custom ink and
//! feel bytes), coordinate space u8 (0 canvas, 1 view-relative),
//! viewport revision u32. Samples = first index u32, count u16 (1 or more), channel mask u8
//! (bit 0 tilt, bit 1 orientation), then x, y, pressure (f32), elapsed ms (u32), tilt, orientation
//! as `count` values each. End = total sample count u32. Cancel = empty. Hover = x f32, y f32.
//!
//! Every frame is at most 64 KiB, enforced on encode and decode. Decoding treats all bytes as
//! hostile: every length is checked before it is used, every float must be finite, and trailing
//! bytes are an error.
use crate::{
    Brush, CoreError, CoreResult, Id, SampleChannels,
    error::{invalid, limit, unsupported},
    samples, validation,
};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    sync::{Arc, Mutex, MutexGuard},
};

pub const PREVIEW_PROTOCOL_VERSION: u8 = 1;
pub const MAX_FRAME_BYTES: usize = 64 * 1024;
pub const MAX_QUEUED_PREVIEW_BYTES: usize = 256 * 1024;
/// A begun, not ended preview with no frame this long is dropped. Android sends no MOVE events
/// while the pen is held still, so this must be long.
pub const LIVE_IDLE_EXPIRY_MS: u64 = 5_000;
/// An ended preview is held until commit, reset or this long after End.
pub const ENDED_HOLD_MS: u64 = 10_000;
pub const MAX_LIVE_PREVIEWS: usize = 8;
pub const MAX_TERMINAL_IDS: usize = 1024;
pub const MAX_PREVIEW_SAMPLES: u32 = 1_000_000;
/// Durable chunk frames waiting in a sender queue. A push beyond this is refused (retryable),
/// never silently dropped.
pub const MAX_QUEUED_DURABLE_BYTES: usize = 48 * 1024 * 1024;

pub(crate) const KIND_PREVIEW: u8 = 0x50;
const HEADER: usize = 68;
const SAMPLES_FIXED: usize = 7;

const BODY_BEGIN: u8 = 1;
const BODY_SAMPLES: u8 = 2;
const BODY_END: u8 = 3;
const BODY_CANCEL: u8 = 4;
const BODY_HOVER: u8 = 5;

/// Coordinates of a stroke's samples: note canvas units, or units of the Mac's 4800 x 3000
/// window (view-relative, before the viewport is applied).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CoordinateSpace {
    Canvas,
    ViewRelative,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum PreviewBody {
    Begin {
        brush: Brush,
        viewport_revision: u32,
        space: CoordinateSpace,
    },
    /// `first_index` is the index of the first sample of `channels` within the stroke.
    Samples {
        first_index: u32,
        channels: SampleChannels,
    },
    End {
        total_samples: u32,
    },
    Cancel,
    Hover {
        x: f32,
        y: f32,
    },
}

/// `stroke_id` is `None` exactly for `Hover`. `sequence` increases within a stroke (Hover keeps its
/// own counter); gaps are allowed because the sender queue merges frames, and sample indexes, not
/// sequence numbers, detect lost samples.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PreviewFrame {
    pub epoch: u32,
    pub note_id: Id,
    pub sheet_id: Id,
    pub stroke_id: Option<Id>,
    pub sequence: u32,
    pub sent_ms: u64,
    pub body: PreviewBody,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum FrameKind {
    Preview,
    StrokeChunk,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PreviewLimits {
    pub protocol_version: u8,
    pub max_frame_bytes: u32,
    pub max_queued_preview_bytes: u32,
    pub live_idle_expiry_ms: u32,
    pub ended_hold_ms: u32,
    pub max_live_previews: u32,
    pub max_terminal_ids: u32,
    pub max_preview_samples: u32,
    pub chunk_data_bytes: u32,
    pub max_stroke_sample_bytes: u32,
    pub max_reassembly_bytes: u32,
}

#[uniffi::export]
pub fn preview_limits() -> PreviewLimits {
    PreviewLimits {
        protocol_version: PREVIEW_PROTOCOL_VERSION,
        max_frame_bytes: MAX_FRAME_BYTES as u32,
        max_queued_preview_bytes: MAX_QUEUED_PREVIEW_BYTES as u32,
        live_idle_expiry_ms: LIVE_IDLE_EXPIRY_MS as u32,
        ended_hold_ms: ENDED_HOLD_MS as u32,
        max_live_previews: MAX_LIVE_PREVIEWS as u32,
        max_terminal_ids: MAX_TERMINAL_IDS as u32,
        max_preview_samples: MAX_PREVIEW_SAMPLES,
        chunk_data_bytes: crate::commit::CHUNK_DATA as u32,
        max_stroke_sample_bytes: samples::MAX_SAMPLE_BYTES as u32,
        max_reassembly_bytes: crate::commit::MAX_REASSEMBLY_BYTES as u32,
    }
}

/// Tells the socket owner which decoder a received frame belongs to, from its first byte.
#[uniffi::export]
pub fn frame_kind(bytes: Vec<u8>) -> CoreResult<FrameKind> {
    match bytes.first() {
        Some(&KIND_PREVIEW) => Ok(FrameKind::Preview),
        Some(&crate::commit::KIND_CHUNK) => Ok(FrameKind::StrokeChunk),
        Some(_) => Err(invalid("Unknown frame kind")),
        None => Err(invalid("Empty frame")),
    }
}

// ---------------------------------------------------------------- shared byte helpers

pub(crate) fn id_bytes(id: &str) -> CoreResult<[u8; 16]> {
    validation::id(id)?;
    id.parse::<ulid::Ulid>()
        .map(|u| u.to_bytes())
        .map_err(|_| invalid("Invalid ID"))
}

pub(crate) fn bytes_id(bytes: &[u8]) -> Id {
    let mut raw = [0u8; 16];
    raw.copy_from_slice(&bytes[..16]);
    ulid::Ulid::from_bytes(raw).to_string()
}

pub(crate) struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub(crate) fn new(bytes: &'a [u8]) -> Self {
        Reader { bytes, pos: 0 }
    }
    pub(crate) fn take(&mut self, n: usize) -> CoreResult<&'a [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&e| e <= self.bytes.len())
            .ok_or_else(|| invalid("Truncated frame"))?;
        let out = &self.bytes[self.pos..end];
        self.pos = end;
        Ok(out)
    }
    pub(crate) fn u8(&mut self) -> CoreResult<u8> {
        Ok(self.take(1)?[0])
    }
    pub(crate) fn u16(&mut self) -> CoreResult<u16> {
        let b = self.take(2)?;
        Ok(u16::from_le_bytes([b[0], b[1]]))
    }
    pub(crate) fn u32(&mut self) -> CoreResult<u32> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }
    pub(crate) fn u64(&mut self) -> CoreResult<u64> {
        let b = self.take(8)?;
        let mut raw = [0u8; 8];
        raw.copy_from_slice(b);
        Ok(u64::from_le_bytes(raw))
    }
    pub(crate) fn f32(&mut self) -> CoreResult<f32> {
        let v = f32::from_bits(self.u32()?);
        if v.is_finite() {
            Ok(v)
        } else {
            Err(invalid("Non-finite value in frame"))
        }
    }
    pub(crate) fn remaining(&self) -> usize {
        self.bytes.len() - self.pos
    }
    pub(crate) fn finish(&self) -> CoreResult<()> {
        if self.remaining() == 0 {
            Ok(())
        } else {
            Err(invalid("Trailing bytes after frame"))
        }
    }
}

pub(crate) fn brush_bytes(b: &Brush) -> Vec<u8> {
    let mut out = Vec::with_capacity(crate::brush::MAX_BRUSH_BYTES);
    crate::brush::encode(b, &mut out);
    out
}

pub(crate) fn read_brush(r: &mut Reader) -> CoreResult<Brush> {
    crate::brush::decode(r)
}

pub(crate) fn space_byte(s: CoordinateSpace) -> u8 {
    match s {
        CoordinateSpace::Canvas => 0,
        CoordinateSpace::ViewRelative => 1,
    }
}

pub(crate) fn read_space(r: &mut Reader) -> CoreResult<CoordinateSpace> {
    match r.u8()? {
        0 => Ok(CoordinateSpace::Canvas),
        1 => Ok(CoordinateSpace::ViewRelative),
        _ => Err(invalid("Unknown coordinate space")),
    }
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    // A poisoned lock only means another thread panicked mid-update; the state is plain data.
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// ---------------------------------------------------------------- codec

fn samples_len(channels: &SampleChannels) -> usize {
    let n = channels.x.len();
    n * 16
        + n * 4 * usize::from(channels.tilt.is_some())
        + n * 4 * usize::from(channels.orientation.is_some())
}

/// Exact encoded length of a frame, without allocating it.
fn body_len(body: &PreviewBody) -> usize {
    match body {
        PreviewBody::Begin { brush, .. } => 5 + brush_bytes(brush).len(),
        PreviewBody::Samples { channels, .. } => SAMPLES_FIXED + samples_len(channels),
        PreviewBody::End { .. } => 4,
        PreviewBody::Cancel => 0,
        PreviewBody::Hover { .. } => 8,
    }
}

#[uniffi::export]
pub fn encode_preview_frame(frame: PreviewFrame) -> CoreResult<Vec<u8>> {
    encode_frame(&frame)
}

pub(crate) fn encode_frame(frame: &PreviewFrame) -> CoreResult<Vec<u8>> {
    let hover = matches!(frame.body, PreviewBody::Hover { .. });
    if hover != frame.stroke_id.is_none() {
        return Err(invalid("Only Hover frames omit the stroke ID"));
    }
    if let PreviewBody::Samples { channels, .. } = &frame.body {
        // Reject oversize before the sample validator or an allocation sees it.
        if channels.x.len() > (MAX_FRAME_BYTES / 16) {
            return Err(limit("Preview frame exceeds 64 KiB"));
        }
    }
    let total = HEADER + body_len(&frame.body);
    if total > MAX_FRAME_BYTES {
        return Err(limit("Preview frame exceeds 64 KiB"));
    }
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&[
        KIND_PREVIEW,
        PREVIEW_PROTOCOL_VERSION,
        match frame.body {
            PreviewBody::Begin { .. } => BODY_BEGIN,
            PreviewBody::Samples { .. } => BODY_SAMPLES,
            PreviewBody::End { .. } => BODY_END,
            PreviewBody::Cancel => BODY_CANCEL,
            PreviewBody::Hover { .. } => BODY_HOVER,
        },
        0,
    ]);
    out.extend_from_slice(&frame.epoch.to_le_bytes());
    out.extend_from_slice(&id_bytes(&frame.note_id)?);
    out.extend_from_slice(&id_bytes(&frame.sheet_id)?);
    out.extend_from_slice(&frame.sent_ms.to_le_bytes());
    out.extend_from_slice(&frame.sequence.to_le_bytes());
    match &frame.stroke_id {
        Some(id) => out.extend_from_slice(&id_bytes(id)?),
        None => out.extend_from_slice(&[0u8; 16]),
    }
    match &frame.body {
        PreviewBody::Begin {
            brush,
            viewport_revision,
            space,
        } => {
            out.extend_from_slice(&brush_bytes(brush));
            out.push(space_byte(*space));
            out.extend_from_slice(&viewport_revision.to_le_bytes());
        }
        PreviewBody::Samples {
            first_index,
            channels,
        } => {
            samples::validate(channels)?;
            let n = channels.x.len();
            if n > usize::from(u16::MAX) || first_index.checked_add(n as u32).is_none() {
                return Err(limit("Sample index overflow"));
            }
            out.extend_from_slice(&first_index.to_le_bytes());
            out.extend_from_slice(&(n as u16).to_le_bytes());
            out.push(
                u8::from(channels.tilt.is_some()) | (u8::from(channels.orientation.is_some()) << 1),
            );
            for values in [&channels.x, &channels.y, &channels.pressure] {
                for v in values {
                    out.extend_from_slice(&v.to_le_bytes());
                }
            }
            for v in &channels.elapsed_ms {
                out.extend_from_slice(&v.to_le_bytes());
            }
            for values in [channels.tilt.as_ref(), channels.orientation.as_ref()]
                .into_iter()
                .flatten()
            {
                for v in values {
                    out.extend_from_slice(&v.to_le_bytes());
                }
            }
        }
        PreviewBody::End { total_samples } => out.extend_from_slice(&total_samples.to_le_bytes()),
        PreviewBody::Cancel => {}
        PreviewBody::Hover { x, y } => {
            if !x.is_finite() || !y.is_finite() {
                return Err(invalid("Hover position must be finite"));
            }
            out.extend_from_slice(&x.to_le_bytes());
            out.extend_from_slice(&y.to_le_bytes());
        }
    }
    debug_assert_eq!(out.len(), total);
    Ok(out)
}

#[uniffi::export]
pub fn decode_preview_frame(bytes: Vec<u8>) -> CoreResult<PreviewFrame> {
    decode_frame(&bytes)
}

pub(crate) fn decode_frame(bytes: &[u8]) -> CoreResult<PreviewFrame> {
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(limit("Preview frame exceeds 64 KiB"));
    }
    let mut r = Reader::new(bytes);
    if r.u8()? != KIND_PREVIEW {
        return Err(invalid("Not a preview frame"));
    }
    if r.u8()? != PREVIEW_PROTOCOL_VERSION {
        return Err(unsupported("Unsupported preview protocol version"));
    }
    let kind = r.u8()?;
    if r.u8()? != 0 {
        return Err(invalid("Reserved preview flags must be zero"));
    }
    let epoch = r.u32()?;
    let note_id = bytes_id(r.take(16)?);
    let sheet_id = bytes_id(r.take(16)?);
    let sent_ms = r.u64()?;
    let sequence = r.u32()?;
    let stroke = r.take(16)?;
    let stroke_id = if kind == BODY_HOVER {
        if stroke.iter().any(|&b| b != 0) {
            return Err(invalid("Hover frames carry no stroke ID"));
        }
        None
    } else {
        Some(bytes_id(stroke))
    };
    let body = match kind {
        BODY_BEGIN => {
            let brush = read_brush(&mut r)?;
            let space = read_space(&mut r)?;
            PreviewBody::Begin {
                brush,
                viewport_revision: r.u32()?,
                space,
            }
        }
        BODY_SAMPLES => {
            let first_index = r.u32()?;
            let n = usize::from(r.u16()?);
            let mask = r.u8()?;
            if n == 0 {
                return Err(invalid("Samples frame must not be empty"));
            }
            if mask & !0b11 != 0 {
                return Err(invalid("Invalid sample channel mask"));
            }
            if first_index.checked_add(n as u32).is_none() {
                return Err(invalid("Sample index overflow"));
            }
            let tilt = mask & 1 != 0;
            let orientation = mask & 2 != 0;
            let expect = n * 16 + n * 4 * usize::from(tilt) + n * 4 * usize::from(orientation);
            // Exact length is known before any channel array is allocated.
            if r.remaining() != expect {
                return Err(invalid("Sample frame length does not match its count"));
            }
            let f32s = |r: &mut Reader| -> CoreResult<Vec<f32>> {
                let raw = r.take(n * 4)?;
                Ok(raw
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                    .collect())
            };
            let x = f32s(&mut r)?;
            let y = f32s(&mut r)?;
            let pressure = f32s(&mut r)?;
            let elapsed_ms = r
                .take(n * 4)?
                .chunks_exact(4)
                .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                .collect();
            let tilt = if tilt { Some(f32s(&mut r)?) } else { None };
            let orientation = if orientation {
                Some(f32s(&mut r)?)
            } else {
                None
            };
            let channels = SampleChannels {
                x,
                y,
                pressure,
                elapsed_ms,
                tilt,
                orientation,
            };
            samples::validate(&channels)?; // finite, ranges, nondecreasing time
            PreviewBody::Samples {
                first_index,
                channels,
            }
        }
        BODY_END => PreviewBody::End {
            total_samples: r.u32()?,
        },
        BODY_CANCEL => PreviewBody::Cancel,
        BODY_HOVER => PreviewBody::Hover {
            x: r.f32()?,
            y: r.f32()?,
        },
        _ => return Err(invalid("Unknown preview body kind")),
    };
    r.finish()?;
    Ok(PreviewFrame {
        epoch,
        note_id,
        sheet_id,
        stroke_id,
        sequence,
        sent_ms,
        body,
    })
}

// ---------------------------------------------------------------- receiver

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PreviewDropReason {
    /// The sender cancelled the stroke.
    Cancelled,
    /// Samples, End or Cancel arrived for a stroke whose Begin was never seen.
    MissingBegin,
    /// Sample indexes skipped, or End's total did not match what arrived.
    Gap,
    /// No frame for 5 s while live, or 10 s since End without a commit.
    Expired,
    /// The session ended or a newer epoch started.
    Reset,
    /// The durable stroke arrived; draw that instead.
    Committed,
    /// Inconsistent frames within one stroke (changed note or sheet, samples after End,
    /// channel set or time going backwards).
    InvalidData,
    /// Too many live previews or too many samples in one preview.
    LimitExceeded,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum PreviewEvent {
    StrokeStarted {
        stroke_id: Id,
        note_id: Id,
        sheet_id: Id,
        brush: Brush,
        viewport_revision: u32,
        space: CoordinateSpace,
    },
    /// `sent_ms` is the sender's monotonic time of the frame (earliest merged frame).
    SamplesAppended {
        stroke_id: Id,
        first_index: u32,
        channels: SampleChannels,
        sent_ms: u64,
    },
    StrokeEnded {
        stroke_id: Id,
        total_samples: u32,
    },
    /// Remove this stroke's preview and never invent its geometry. Emitted once per stroke.
    Dropped {
        stroke_id: Id,
        reason: PreviewDropReason,
    },
    HoverMoved {
        note_id: Id,
        sheet_id: Id,
        x: f32,
        y: f32,
        sent_ms: u64,
    },
}

struct Live {
    note_id: Id,
    sheet_id: Id,
    last_sequence: u32,
    next_index: u32,
    last_elapsed: u32,
    /// (tilt, orientation) fixed by the first Samples frame.
    channel_set: Option<(bool, bool)>,
    /// Receiver time of the accepted End; `None` while the stroke is live.
    ended_ms: Option<u64>,
    last_frame_ms: u64,
    started_order: u64,
}

struct RecvState {
    epoch: u32,
    live: HashMap<Id, Live>,
    terminal: HashSet<Id>,
    terminal_order: VecDeque<Id>,
    hover_sequence: Option<u32>,
    counter: u64,
}

impl RecvState {
    fn remember(&mut self, id: &Id) {
        if self.terminal.insert(id.clone()) {
            self.terminal_order.push_back(id.clone());
            while self.terminal_order.len() > MAX_TERMINAL_IDS {
                if let Some(old) = self.terminal_order.pop_front() {
                    self.terminal.remove(&old);
                }
            }
        }
    }

    fn drop_stroke(&mut self, id: &Id, reason: PreviewDropReason) -> PreviewEvent {
        self.live.remove(id);
        self.remember(id);
        PreviewEvent::Dropped {
            stroke_id: id.clone(),
            reason,
        }
    }

    fn clear(&mut self, epoch: u32) -> Vec<PreviewEvent> {
        let mut ids: Vec<(u64, Id)> = self
            .live
            .iter()
            .map(|(k, v)| (v.started_order, k.clone()))
            .collect();
        ids.sort();
        self.live.clear();
        self.terminal.clear();
        self.terminal_order.clear();
        self.hover_sequence = None;
        self.epoch = epoch;
        ids.into_iter()
            .map(|(_, stroke_id)| PreviewEvent::Dropped {
                stroke_id,
                reason: PreviewDropReason::Reset,
            })
            .collect()
    }

    fn handle(&mut self, f: PreviewFrame, now_ms: u64) -> Vec<PreviewEvent> {
        let mut events = vec![];
        if f.epoch < self.epoch {
            return events;
        }
        if f.epoch > self.epoch {
            events = self.clear(f.epoch);
        }
        let Some(id) = f.stroke_id.clone() else {
            // Hover: latest wins, no stroke.
            if let PreviewBody::Hover { x, y } = f.body
                && self.hover_sequence.is_none_or(|s| f.sequence > s)
            {
                self.hover_sequence = Some(f.sequence);
                events.push(PreviewEvent::HoverMoved {
                    note_id: f.note_id,
                    sheet_id: f.sheet_id,
                    x,
                    y,
                    sent_ms: f.sent_ms,
                });
            }
            return events;
        };
        if self.terminal.contains(&id) {
            return events;
        }
        if let PreviewBody::Begin {
            brush,
            viewport_revision,
            space,
        } = f.body
        {
            if self.live.contains_key(&id) {
                return events; // duplicate Begin
            }
            if self.live.len() >= MAX_LIVE_PREVIEWS
                && let Some(oldest) = self
                    .live
                    .iter()
                    .min_by_key(|(_, v)| (v.last_frame_ms, v.started_order))
                    .map(|(k, _)| k.clone())
            {
                events.push(self.drop_stroke(&oldest, PreviewDropReason::LimitExceeded));
            }
            self.counter += 1;
            self.live.insert(
                id.clone(),
                Live {
                    note_id: f.note_id.clone(),
                    sheet_id: f.sheet_id.clone(),
                    last_sequence: f.sequence,
                    next_index: 0,
                    last_elapsed: 0,
                    channel_set: None,
                    ended_ms: None,
                    last_frame_ms: now_ms,
                    started_order: self.counter,
                },
            );
            events.push(PreviewEvent::StrokeStarted {
                stroke_id: id,
                note_id: f.note_id,
                sheet_id: f.sheet_id,
                brush,
                viewport_revision,
                space,
            });
            return events;
        }
        let Some(live) = self.live.get_mut(&id) else {
            self.remember(&id);
            if !matches!(f.body, PreviewBody::Cancel) {
                events.push(PreviewEvent::Dropped {
                    stroke_id: id,
                    reason: PreviewDropReason::MissingBegin,
                });
            }
            return events;
        };
        if f.sequence <= live.last_sequence {
            return events; // duplicate or stale
        }
        if live.note_id != f.note_id || live.sheet_id != f.sheet_id {
            events.push(self.drop_stroke(&id, PreviewDropReason::InvalidData));
            return events;
        }
        live.last_sequence = f.sequence;
        live.last_frame_ms = now_ms;
        let verdict: Result<Option<PreviewEvent>, PreviewDropReason> = match f.body {
            PreviewBody::Samples {
                first_index,
                channels,
            } => {
                let n = channels.x.len() as u32;
                let set = (channels.tilt.is_some(), channels.orientation.is_some());
                if live.ended_ms.is_some() {
                    Err(PreviewDropReason::InvalidData)
                } else if first_index != live.next_index {
                    Err(PreviewDropReason::Gap)
                } else if live.next_index.saturating_add(n) > MAX_PREVIEW_SAMPLES {
                    Err(PreviewDropReason::LimitExceeded)
                } else if live.channel_set.is_some_and(|s| s != set)
                    || channels
                        .elapsed_ms
                        .first()
                        .is_some_and(|&t| t < live.last_elapsed)
                {
                    Err(PreviewDropReason::InvalidData)
                } else {
                    live.channel_set = Some(set);
                    live.next_index += n;
                    live.last_elapsed = channels.elapsed_ms.last().copied().unwrap_or(0);
                    Ok(Some(PreviewEvent::SamplesAppended {
                        stroke_id: id.clone(),
                        first_index,
                        channels,
                        sent_ms: f.sent_ms,
                    }))
                }
            }
            PreviewBody::End { total_samples } => {
                if live.ended_ms.is_some() {
                    Ok(None)
                } else if total_samples != live.next_index {
                    Err(PreviewDropReason::Gap)
                } else {
                    live.ended_ms = Some(now_ms);
                    Ok(Some(PreviewEvent::StrokeEnded {
                        stroke_id: id.clone(),
                        total_samples,
                    }))
                }
            }
            PreviewBody::Cancel => Err(PreviewDropReason::Cancelled),
            // Begin and Hover were handled above.
            PreviewBody::Begin { .. } | PreviewBody::Hover { .. } => Ok(None),
        };
        match verdict {
            Ok(event) => events.extend(event),
            Err(reason) => events.push(self.drop_stroke(&id, reason)),
        }
        events
    }
}

/// Turns received preview frames into renderer events. No I/O; the caller supplies `now_ms`
/// (any monotonic clock of the receiver, used only for expiry).
#[derive(uniffi::Object)]
pub struct PreviewReceiver {
    state: Mutex<RecvState>,
}

#[uniffi::export]
impl PreviewReceiver {
    #[uniffi::constructor]
    pub fn new(epoch: u32) -> Arc<Self> {
        Arc::new(PreviewReceiver {
            state: Mutex::new(RecvState {
                epoch,
                live: HashMap::new(),
                terminal: HashSet::new(),
                terminal_order: VecDeque::new(),
                hover_sequence: None,
                counter: 0,
            }),
        })
    }

    /// Decodes one frame and applies it. A frame that fails to decode returns the typed error
    /// and changes nothing. Frames of an older epoch are ignored (no events); a newer epoch
    /// drops every live preview (`Reset`) and is adopted.
    pub fn accept(&self, frame: Vec<u8>, now_ms: u64) -> CoreResult<Vec<PreviewEvent>> {
        let decoded = decode_frame(&frame)?;
        Ok(lock(&self.state).handle(decoded, now_ms))
    }

    /// Expires live previews with no frame for 5 s, and ended previews 10 s after End.
    pub fn tick(&self, now_ms: u64) -> Vec<PreviewEvent> {
        let mut s = lock(&self.state);
        let mut expired: Vec<(u64, Id)> = s
            .live
            .iter()
            .filter(|(_, v)| match v.ended_ms {
                None => now_ms.saturating_sub(v.last_frame_ms) >= LIVE_IDLE_EXPIRY_MS,
                Some(e) => now_ms.saturating_sub(e) >= ENDED_HOLD_MS,
            })
            .map(|(k, v)| (v.started_order, k.clone()))
            .collect();
        expired.sort();
        expired
            .into_iter()
            .map(|(_, id)| s.drop_stroke(&id, PreviewDropReason::Expired))
            .collect()
    }

    /// The durable stroke exists: drop its preview (if any) and ignore its late frames.
    pub fn commit(&self, stroke_id: Id) -> CoreResult<Vec<PreviewEvent>> {
        validation::id(&stroke_id)?;
        let mut s = lock(&self.state);
        Ok(if s.live.contains_key(&stroke_id) {
            vec![s.drop_stroke(&stroke_id, PreviewDropReason::Committed)]
        } else {
            s.remember(&stroke_id);
            vec![]
        })
    }

    /// Disconnect or new session: drops every preview, forgets terminal IDs and adopts `epoch`.
    pub fn reset(&self, epoch: u32) -> Vec<PreviewEvent> {
        lock(&self.state).clear(epoch)
    }

    pub fn epoch(&self) -> u32 {
        lock(&self.state).epoch
    }

    pub fn live_count(&self) -> u32 {
        lock(&self.state).live.len() as u32
    }
}

// ---------------------------------------------------------------- sender queue

struct Item {
    frame: PreviewFrame,
    size: usize,
}

#[derive(Default)]
struct QueueState {
    preview: VecDeque<Item>,
    preview_bytes: usize,
    durable: VecDeque<Vec<u8>>,
    durable_bytes: usize,
    dropped: u64,
}

fn can_merge(a: &Item, b: &Item) -> bool {
    match (&a.frame.body, &b.frame.body) {
        (
            PreviewBody::Samples {
                first_index: ai,
                channels: ac,
            },
            PreviewBody::Samples {
                first_index: bi,
                channels: bc,
            },
        ) => {
            a.frame.stroke_id == b.frame.stroke_id
                && a.frame.epoch == b.frame.epoch
                && *bi as u64 == *ai as u64 + ac.x.len() as u64
                && ac.tilt.is_some() == bc.tilt.is_some()
                && ac.orientation.is_some() == bc.orientation.is_some()
                && ac.x.len() + bc.x.len() <= usize::from(u16::MAX)
                && a.size + b.size - HEADER - SAMPLES_FIXED <= MAX_FRAME_BYTES
        }
        _ => false,
    }
}

fn merge(a: &mut Item, b: Item) {
    if let (PreviewBody::Samples { channels: ac, .. }, PreviewBody::Samples { channels: bc, .. }) =
        (&mut a.frame.body, b.frame.body)
    {
        ac.x.extend(bc.x);
        ac.y.extend(bc.y);
        ac.pressure.extend(bc.pressure);
        ac.elapsed_ms.extend(bc.elapsed_ms);
        if let (Some(t), Some(u)) = (&mut ac.tilt, bc.tilt) {
            t.extend(u);
        }
        if let (Some(t), Some(u)) = (&mut ac.orientation, bc.orientation) {
            t.extend(u);
        }
        a.size = HEADER + body_len(&a.frame.body);
    }
}

impl QueueState {
    /// Merges adjacent-in-stroke Samples frames. Lossless: same samples, same order, each result
    /// frame at most 64 KiB. The merged frame keeps the first frame's sequence and send time.
    fn coalesce(&mut self) {
        let old = std::mem::take(&mut self.preview);
        let mut out: VecDeque<Item> = VecDeque::with_capacity(old.len());
        let mut last: HashMap<Id, usize> = HashMap::new();
        for item in old {
            if matches!(item.frame.body, PreviewBody::Samples { .. })
                && let Some(id) = item.frame.stroke_id.clone()
            {
                if let Some(&i) = last.get(&id)
                    && can_merge(&out[i], &item)
                {
                    merge(&mut out[i], item);
                    continue;
                }
                last.insert(id, out.len());
            }
            out.push_back(item);
        }
        self.preview = out;
        self.preview_bytes = self.preview.iter().map(|i| i.size).sum();
    }

    fn drop_front(&mut self, samples_only: bool) -> bool {
        let pos = if samples_only {
            self.preview
                .iter()
                .position(|i| matches!(i.frame.body, PreviewBody::Samples { .. }))
        } else if self.preview.is_empty() {
            None
        } else {
            Some(0)
        };
        let Some(pos) = pos else { return false };
        if let Some(item) = self.preview.remove(pos) {
            self.preview_bytes -= item.size;
            self.dropped += 1;
        }
        true
    }

    /// Under pressure: coalesce first; then drop the oldest Samples frames (the receiver sees a
    /// sample-index gap and drops that preview); only if nothing else is left, the oldest frame.
    fn relieve(&mut self) {
        if self.preview_bytes <= MAX_QUEUED_PREVIEW_BYTES {
            return;
        }
        self.coalesce();
        while self.preview_bytes > MAX_QUEUED_PREVIEW_BYTES && self.drop_front(true) {}
        while self.preview_bytes > MAX_QUEUED_PREVIEW_BYTES && self.drop_front(false) {}
    }
}

/// The pen thread pushes, the socket owner pops. Pushing takes a short lock, never touches disk
/// or the network, and never waits for the consumer. At most 256 KiB of preview is queued.
#[derive(uniffi::Object)]
pub struct PreviewSendQueue {
    state: Mutex<QueueState>,
}

#[uniffi::export]
impl PreviewSendQueue {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(PreviewSendQueue {
            state: Mutex::new(QueueState::default()),
        })
    }

    /// Validates the frame (same rules as encoding) and queues it. A queued Hover replaces any
    /// older queued Hover. Over budget, older preview data is merged and then dropped.
    pub fn push_preview(&self, frame: PreviewFrame) -> CoreResult<()> {
        let size = encode_frame(&frame)?.len();
        let mut s = lock(&self.state);
        if matches!(frame.body, PreviewBody::Hover { .. }) {
            let before = s.preview.len();
            s.preview
                .retain(|i| !matches!(i.frame.body, PreviewBody::Hover { .. }));
            if s.preview.len() != before {
                s.preview_bytes = s.preview.iter().map(|i| i.size).sum();
            }
        }
        s.preview.push_back(Item { frame, size });
        s.preview_bytes += size;
        s.relieve();
        Ok(())
    }

    /// Queues every chunk frame of a durable stroke, in order. Never dropped or merged. If the
    /// durable backlog would exceed its bound, nothing is queued and the error is retryable.
    pub fn push_commit(&self, commit: crate::commit::StrokeCommit) -> CoreResult<()> {
        let frames = crate::commit::encode_chunks(&commit)?;
        let total: usize = frames.iter().map(Vec::len).sum();
        let mut s = lock(&self.state);
        if s.durable_bytes + total > MAX_QUEUED_DURABLE_BYTES {
            return Err(CoreError::ResourceLimit {
                detail: "Durable send backlog is full".into(),
                retryable: true,
            });
        }
        s.durable_bytes += total;
        s.durable.extend(frames);
        Ok(())
    }

    /// Next encoded frame to send: preview first (it is perishable), then durable chunks.
    pub fn pop(&self) -> Option<Vec<u8>> {
        let mut s = lock(&self.state);
        while let Some(item) = s.preview.pop_front() {
            s.preview_bytes -= item.size;
            if let Ok(bytes) = encode_frame(&item.frame) {
                return Some(bytes);
            }
        }
        let bytes = s.durable.pop_front()?;
        s.durable_bytes -= bytes.len();
        Some(bytes)
    }

    pub fn queued_preview_bytes(&self) -> u64 {
        lock(&self.state).preview_bytes as u64
    }

    pub fn queued_durable_bytes(&self) -> u64 {
        lock(&self.state).durable_bytes as u64
    }

    /// Preview frames discarded under pressure since creation (merged frames are not drops).
    pub fn dropped_preview_frames(&self) -> u64 {
        lock(&self.state).dropped
    }

    /// Discards queued preview (disconnect). Durable frames stay queued.
    pub fn clear_preview(&self) {
        let mut s = lock(&self.state);
        s.preview.clear();
        s.preview_bytes = 0;
    }
}
