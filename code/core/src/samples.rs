//! SYNS v1 sample blobs: the immutable, exact pen samples of one stroke.
//!
//! Layout (little-endian): `SYNS`, u16 version 1, u16 channel mask, u32 count, then each
//! present channel as `count` 4-byte values in order x, y, pressure (f32), elapsed_ms
//! (u32), tilt, orientation (f32). The four core channels are always present.
use crate::{
    CoreResult, SampleChannels,
    error::{invalid, limit, unsupported},
};

pub const MAX_SAMPLE_BYTES: usize = 16 * 1024 * 1024;
const MAGIC: &[u8; 4] = b"SYNS";
const VERSION: u16 = 1;
const HEADER: usize = 12;
const CORE_CHANNELS: u16 = 0x0f;
const TILT: u16 = 0x10;
const ORIENTATION: u16 = 0x20;

fn encoded_length(count: usize, tilt: bool, orientation: bool) -> CoreResult<usize> {
    if count == 0 {
        return Err(invalid("Samples must not be empty"));
    }
    let channels = 4 + usize::from(tilt) + usize::from(orientation);
    let length = count
        .checked_mul(channels * 4)
        .and_then(|n| n.checked_add(HEADER))
        .ok_or_else(|| limit("Sample layout exceeds size limit"))?;
    if length > MAX_SAMPLE_BYTES {
        return Err(limit("Sample blob exceeds 16 MiB"));
    }
    Ok(length)
}

/// Checks channel lengths and value ranges; returns the encoded length.
pub(crate) fn validate(channels: &SampleChannels) -> CoreResult<usize> {
    let n = channels.x.len();
    let length = encoded_length(n, channels.tilt.is_some(), channels.orientation.is_some())?;
    if channels.y.len() != n
        || channels.pressure.len() != n
        || channels.elapsed_ms.len() != n
        || channels.tilt.as_ref().is_some_and(|v| v.len() != n)
        || channels.orientation.as_ref().is_some_and(|v| v.len() != n)
    {
        return Err(invalid("Sample channel lengths must match"));
    }
    if channels.x.iter().chain(&channels.y).any(|x| !x.is_finite()) {
        return Err(invalid("Sample coordinates must be finite"));
    }
    if channels
        .pressure
        .iter()
        .any(|x| !x.is_finite() || !(0.0..=1.0).contains(x))
    {
        return Err(invalid("Pressure must be finite and in [0,1]"));
    }
    if channels.elapsed_ms.windows(2).any(|v| v[0] > v[1]) {
        return Err(invalid("Sample times must be nondecreasing"));
    }
    if channels.tilt.as_ref().is_some_and(|v| {
        v.iter()
            .any(|x| !x.is_finite() || !(0.0..=std::f32::consts::FRAC_PI_2).contains(x))
    }) {
        return Err(invalid("Tilt must be finite and in [0,pi/2]"));
    }
    if channels.orientation.as_ref().is_some_and(|v| {
        v.iter()
            .any(|x| !x.is_finite() || !(-std::f32::consts::PI..=std::f32::consts::PI).contains(x))
    }) {
        return Err(invalid("Orientation must be finite and in [-pi,pi]"));
    }
    Ok(length)
}

#[uniffi::export]
pub fn encode_samples(channels: SampleChannels) -> CoreResult<Vec<u8>> {
    let length = validate(&channels)?;
    let mut mask = CORE_CHANNELS;
    if channels.tilt.is_some() {
        mask |= TILT;
    }
    if channels.orientation.is_some() {
        mask |= ORIENTATION;
    }
    let mut blob = Vec::with_capacity(length);
    blob.extend_from_slice(MAGIC);
    blob.extend_from_slice(&VERSION.to_le_bytes());
    blob.extend_from_slice(&mask.to_le_bytes());
    blob.extend_from_slice(&(channels.x.len() as u32).to_le_bytes());
    for values in [&channels.x, &channels.y, &channels.pressure] {
        for x in values {
            blob.extend_from_slice(&x.to_le_bytes());
        }
    }
    for time in &channels.elapsed_ms {
        blob.extend_from_slice(&time.to_le_bytes());
    }
    for values in [channels.tilt.as_ref(), channels.orientation.as_ref()]
        .into_iter()
        .flatten()
    {
        for x in values {
            blob.extend_from_slice(&x.to_le_bytes());
        }
    }
    Ok(blob)
}

#[uniffi::export]
pub fn decode_samples(blob: Vec<u8>) -> CoreResult<SampleChannels> {
    decode(&blob)
}

pub(crate) fn decode(blob: &[u8]) -> CoreResult<SampleChannels> {
    if blob.len() > MAX_SAMPLE_BYTES {
        return Err(limit("Sample blob exceeds 16 MiB"));
    }
    if blob.len() < HEADER || &blob[..4] != MAGIC {
        return Err(invalid("Invalid sample header"));
    }
    let version = u16::from_le_bytes([blob[4], blob[5]]);
    if version != VERSION {
        return Err(unsupported("Unsupported sample encoding version"));
    }
    let mask = u16::from_le_bytes([blob[6], blob[7]]);
    if mask & CORE_CHANNELS != CORE_CHANNELS || mask & !(CORE_CHANNELS | TILT | ORIENTATION) != 0 {
        return Err(invalid("Invalid sample channel mask"));
    }
    let n = u32::from_le_bytes([blob[8], blob[9], blob[10], blob[11]]) as usize;
    let tilt = mask & TILT != 0;
    let orientation = mask & ORIENTATION != 0;
    let length = encoded_length(n, tilt, orientation)?;
    // Check count, multiplication and exact length before allocating channel arrays.
    if length != blob.len() {
        return Err(invalid("Sample blob length does not match header"));
    }
    let mut offset = HEADER;
    let mut read_words = || {
        let start = offset;
        offset += n * 4;
        blob[start..offset]
            .chunks_exact(4)
            .map(|b| [b[0], b[1], b[2], b[3]])
    };
    let channels = SampleChannels {
        x: read_words().map(f32::from_le_bytes).collect(),
        y: read_words().map(f32::from_le_bytes).collect(),
        pressure: read_words().map(f32::from_le_bytes).collect(),
        elapsed_ms: read_words().map(u32::from_le_bytes).collect(),
        tilt: tilt.then(|| read_words().map(f32::from_le_bytes).collect()),
        orientation: orientation.then(|| read_words().map(f32::from_le_bytes).collect()),
    };
    validate(&channels)?;
    Ok(channels)
}
