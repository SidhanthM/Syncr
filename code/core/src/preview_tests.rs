//! Preview codec, receiver, sender queue and stroke-commit chunking.
use crate::{
    commit::{CHUNK_DATA, crc32},
    preview::{MAX_FRAME_BYTES, MAX_LIVE_PREVIEWS, MAX_QUEUED_PREVIEW_BYTES, MAX_TERMINAL_IDS},
    *,
};
use std::path::Path;

const NOTE: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
const SHEET: &str = "01BX5ZZKBKACTAV9WEVGEMMVRY";
const S1: &str = "01H8XGJWBWBAQ4Z4V9FQ8Q0K3D";

fn stroke(n: u32) -> Id {
    ulid::Ulid::from_parts(1_700_000_000_000, u128::from(n)).to_string()
}
fn brush() -> Brush {
    Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Blue)
}
/// `n` samples starting at logical index `start`; time advances 2 ms per sample.
fn chans(start: u32, n: u32, extra: bool) -> SampleChannels {
    let idx = |i: u32| (start + i) as f32;
    SampleChannels {
        x: (0..n).map(|i| idx(i) * 0.5).collect(),
        y: (0..n).map(|i| idx(i) * -0.25 + 3.0).collect(),
        pressure: (0..n).map(|i| ((start + i) % 100) as f32 / 100.0).collect(),
        elapsed_ms: (0..n).map(|i| (start + i) * 2).collect(),
        tilt: extra.then(|| (0..n).map(|i| (i % 10) as f32 * 0.1).collect()),
        orientation: extra.then(|| (0..n).map(|i| (i % 10) as f32 * 0.2 - 1.0).collect()),
    }
}
fn frame(stroke_id: Option<Id>, seq: u32, body: PreviewBody) -> PreviewFrame {
    PreviewFrame {
        epoch: 7,
        note_id: NOTE.into(),
        sheet_id: SHEET.into(),
        stroke_id,
        sequence: seq,
        sent_ms: 1000 + u64::from(seq),
        body,
    }
}
fn begin(id: &Id, seq: u32) -> PreviewFrame {
    frame(
        Some(id.clone()),
        seq,
        PreviewBody::Begin {
            brush: brush(),
            viewport_revision: 3,
            space: CoordinateSpace::Canvas,
        },
    )
}
fn samples_frame(id: &Id, seq: u32, first: u32, n: u32) -> PreviewFrame {
    frame(
        Some(id.clone()),
        seq,
        PreviewBody::Samples {
            first_index: first,
            channels: chans(first, n, false),
        },
    )
}
fn end(id: &Id, seq: u32, total: u32) -> PreviewFrame {
    frame(
        Some(id.clone()),
        seq,
        PreviewBody::End {
            total_samples: total,
        },
    )
}
fn cancel(id: &Id, seq: u32) -> PreviewFrame {
    frame(Some(id.clone()), seq, PreviewBody::Cancel)
}
fn hover(seq: u32, x: f32) -> PreviewFrame {
    frame(None, seq, PreviewBody::Hover { x, y: 2.0 })
}
fn enc(f: &PreviewFrame) -> Vec<u8> {
    encode_preview_frame(f.clone()).unwrap()
}
fn feed(r: &PreviewReceiver, f: &PreviewFrame, now: u64) -> Vec<PreviewEvent> {
    r.accept(enc(f), now).unwrap()
}
fn dropped(id: &Id, reason: PreviewDropReason) -> PreviewEvent {
    PreviewEvent::Dropped {
        stroke_id: id.clone(),
        reason,
    }
}
fn is_invalid(e: &CoreError) -> bool {
    matches!(e, CoreError::InvalidInput { .. })
}
fn is_limit(e: &CoreError) -> bool {
    matches!(e, CoreError::ResourceLimit { .. })
}

// ------------------------------------------------------------------ codec

/// Link vector: the Begin body for a Ballpoint stroke in a custom ink with a stored feel. The brush is
/// `[0x12, size, 5, l, c, h (f32 LE), pressure, smoothing, taper]`; a plain brush stays three bytes.
#[test]
fn begin_carries_the_look_custom_ink_and_feel_after_the_original_three_bytes() {
    let id = S1.to_string();
    let plain = enc(&begin(&id, 0));
    let mut b = brush();
    b.colour = InkColour::Ink; // the fallback colour of a custom ink is not carried
    b.look = Some(PenLook::Ballpoint);
    b.custom_ink = Some(Oklch {
        l: 0.5,
        c: 0.125,
        h: 90.0,
    });
    b.feel = Some(PenFeel {
        pressure: 50,
        smoothing: 20,
        taper: false,
    });
    let extended = enc(&frame(
        Some(id.clone()),
        0,
        PreviewBody::Begin {
            brush: b.clone(),
            viewport_revision: 3,
            space: CoordinateSpace::Canvas,
        },
    ));
    assert_eq!(extended.len(), plain.len() + 15);
    let mut expected = vec![0x12, 1, 5];
    for v in [0.5f32, 0.125, 90.0] {
        expected.extend_from_slice(&v.to_le_bytes());
    }
    expected.extend_from_slice(&[50, 20, 0]);
    assert_eq!(&extended[68..68 + 18], expected.as_slice());
    let PreviewBody::Begin { brush: got, .. } = decode_preview_frame(extended).unwrap().body else {
        panic!("not a Begin")
    };
    assert_eq!(got, b);
    // A plain brush is exactly the original encoding.
    assert_eq!(&plain[68..71], &[0, 1, 3]);
}

#[test]
fn every_body_round_trips() {
    let id = S1.to_string();
    let frames = vec![
        begin(&id, 0),
        frame(
            Some(id.clone()),
            1,
            PreviewBody::Begin {
                brush: Brush::plain(
                    BrushFamily::Highlighter,
                    BrushSize::Large,
                    InkColour::Orange,
                ),
                viewport_revision: u32::MAX,
                space: CoordinateSpace::ViewRelative,
            },
        ),
        samples_frame(&id, 2, 0, 5),
        frame(
            Some(id.clone()),
            3,
            PreviewBody::Samples {
                first_index: 5,
                channels: chans(5, 4, true),
            },
        ),
        end(&id, 4, 9),
        cancel(&id, 5),
        hover(6, -12.5),
    ];
    for f in frames {
        let bytes = enc(&f);
        assert_eq!(frame_kind(bytes.clone()).unwrap(), FrameKind::Preview);
        assert_eq!(decode_preview_frame(bytes).unwrap(), f);
    }
}

#[test]
fn golden_vectors_do_not_drift() {
    let id = S1.to_string();
    let named: Vec<(&str, PreviewFrame)> = vec![
        ("begin", begin(&id, 0)),
        (
            "begin-view-relative-highlighter",
            frame(
                Some(id.clone()),
                0,
                PreviewBody::Begin {
                    brush: Brush::plain(
                        BrushFamily::Highlighter,
                        BrushSize::Large,
                        InkColour::Orange,
                    ),
                    viewport_revision: 258,
                    space: CoordinateSpace::ViewRelative,
                },
            ),
        ),
        ("samples-core", samples_frame(&id, 1, 0, 2)),
        (
            "samples-tilt-orientation",
            frame(
                Some(id.clone()),
                2,
                PreviewBody::Samples {
                    first_index: 2,
                    channels: chans(2, 2, true),
                },
            ),
        ),
        ("end", end(&id, 3, 4)),
        ("cancel", cancel(&id, 4)),
        ("hover", hover(9, 10.5)),
    ];
    let mut lines: Vec<String> = named
        .iter()
        .map(|(n, f)| format!("{n} {}", hex(&enc(f))))
        .collect();
    // Durable chunk: a one-sample stroke is a single chunk.
    let commit = StrokeCommit {
        note_id: NOTE.into(),
        sheet_id: SHEET.into(),
        stroke_id: id.clone(),
        brush: brush(),
        space: CoordinateSpace::Canvas,
        samples: encode_samples(chans(0, 1, false)).unwrap(),
    };
    let chunks = encode_stroke_commit(commit).unwrap();
    assert_eq!(chunks.len(), 1);
    lines.push(format!("stroke-chunk {}", hex(&chunks[0])));
    let text = lines.join("\n") + "\n";
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/preview-v1/golden.txt");
    if std::env::var_os("SYNCR_UPDATE_GOLDEN").is_some() {
        std::fs::write(&path, &text).unwrap();
    }
    let golden = std::fs::read_to_string(&path).expect("fixtures/preview-v1/golden.txt");
    assert_eq!(
        text, golden,
        "wire encoding changed; bump the version instead"
    );
    // The checked-in bytes decode back to the same frames.
    for (line, (_, f)) in golden.lines().zip(&named) {
        let bytes = unhex(line.split(' ').nth(1).unwrap());
        assert_eq!(&decode_preview_frame(bytes).unwrap(), f);
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn header_layout_is_as_documented() {
    let b = enc(&begin(&S1.to_string(), 0));
    assert_eq!(&b[..4], &[0x50, 1, 1, 0]);
    assert_eq!(&b[4..8], &7u32.to_le_bytes());
    assert_eq!(b.len(), 68 + 8);
    let h = enc(&hover(1, 0.0));
    assert_eq!(&h[52..68], &[0u8; 16]);
}

#[test]
fn malformed_input_is_rejected_with_typed_errors() {
    let id = S1.to_string();
    let good = enc(&samples_frame(&id, 1, 0, 3));
    // Empty and every truncation of valid frames: error, never panic.
    for f in [
        enc(&begin(&id, 0)),
        good.clone(),
        enc(&end(&id, 2, 3)),
        enc(&hover(1, 1.0)),
    ] {
        for cut in 0..f.len() {
            assert!(
                is_invalid(&decode_preview_frame(f[..cut].to_vec()).unwrap_err()),
                "cut {cut}"
            );
        }
    }
    let mut v = good.clone();
    v[1] = 2;
    assert!(matches!(
        decode_preview_frame(v).unwrap_err(),
        CoreError::UnsupportedVersion { .. }
    ));
    let mut v = good.clone();
    v[1] = 0;
    assert!(matches!(
        decode_preview_frame(v).unwrap_err(),
        CoreError::UnsupportedVersion { .. }
    ));
    let mut v = good.clone();
    v[0] = 0x43; // durable kind handed to the preview decoder
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v[2] = 9;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v[3] = 1;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v.push(0);
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Count lies about the payload.
    let mut v = good.clone();
    v[72] = 4;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v[72] = 0;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Reserved mask bits.
    let mut v = good.clone();
    v[74] = 4;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Index overflow.
    let mut v = good.clone();
    v[68..72].copy_from_slice(&u32::MAX.to_le_bytes());
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Non-finite coordinate, pressure out of range, time going backwards.
    let nan = f32::NAN.to_le_bytes();
    let mut v = good.clone();
    v[75..79].copy_from_slice(&nan);
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v[75 + 6 * 4..75 + 7 * 4].copy_from_slice(&f32::INFINITY.to_le_bytes());
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    v[75 + 6 * 4..75 + 7 * 4].copy_from_slice(&2.0f32.to_le_bytes());
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = good.clone();
    let t = 75 + 9 * 4;
    v[t..t + 4].copy_from_slice(&99u32.to_le_bytes()); // first time after the rest
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Hover: NaN, infinite, with a stroke ID.
    let h = enc(&hover(1, 1.0));
    let mut v = h.clone();
    v[68..72].copy_from_slice(&nan);
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = h.clone();
    v[72..76].copy_from_slice(&f32::NEG_INFINITY.to_le_bytes());
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    let mut v = h.clone();
    v[60] = 1;
    assert!(is_invalid(&decode_preview_frame(v).unwrap_err()));
    // Unknown brush and space values.
    let b = enc(&begin(&id, 0));
    for (at, val) in [(68, 4u8), (69, 3), (70, 6), (71, 2)] {
        let mut v = b.clone();
        v[at] = val;
        assert!(
            is_invalid(&decode_preview_frame(v).unwrap_err()),
            "byte {at}"
        );
    }
    // Encoding refuses what decoding refuses.
    assert!(is_invalid(
        &encode_preview_frame(hover(1, f32::NAN)).unwrap_err()
    ));
    let mut bad = samples_frame(&id, 1, 0, 2);
    if let PreviewBody::Samples { channels, .. } = &mut bad.body {
        channels.x[0] = f32::INFINITY;
    }
    assert!(is_invalid(&encode_preview_frame(bad).unwrap_err()));
    let mut f = hover(1, 1.0);
    f.stroke_id = Some(id.clone());
    assert!(is_invalid(&encode_preview_frame(f).unwrap_err()));
    let mut f = end(&id, 1, 1);
    f.stroke_id = None;
    assert!(is_invalid(&encode_preview_frame(f).unwrap_err()));
    let mut f = end(&id, 1, 1);
    f.note_id = "not-an-id".into();
    assert!(is_invalid(&encode_preview_frame(f).unwrap_err()));
    let empty = frame(
        Some(id.clone()),
        1,
        PreviewBody::Samples {
            first_index: 0,
            channels: chans(0, 0, false),
        },
    );
    assert!(is_invalid(&encode_preview_frame(empty).unwrap_err()));
}

#[test]
fn sixty_four_kib_boundary_is_exact() {
    let id = S1.to_string();
    // 68 header + 7 fixed + 16 per sample: 4091 samples = 65,531 bytes fit, 4092 do not.
    let fits = enc(&samples_frame(&id, 1, 0, 4091));
    assert_eq!(fits.len(), 68 + 7 + 4091 * 16);
    assert!(fits.len() <= MAX_FRAME_BYTES);
    assert_eq!(decode_preview_frame(fits.clone()).unwrap().sequence, 1);
    assert!(is_limit(
        &encode_preview_frame(samples_frame(&id, 1, 0, 4092)).unwrap_err()
    ));
    assert!(is_limit(
        &encode_preview_frame(samples_frame(&id, 1, 0, 60_000)).unwrap_err()
    ));
    // Decode: exactly 64 KiB passes the size gate (and fails later as malformed); one more is a limit.
    let mut at = fits.clone();
    at.resize(MAX_FRAME_BYTES, 0);
    assert!(is_invalid(&decode_preview_frame(at.clone()).unwrap_err()));
    at.push(0);
    assert!(is_limit(&decode_preview_frame(at).unwrap_err()));
    // A full durable chunk is exactly 64 KiB and accepted.
    let blob = encode_samples(chans(0, 20_000, false)).unwrap();
    let frames = encode_stroke_commit(StrokeCommit {
        note_id: NOTE.into(),
        sheet_id: SHEET.into(),
        stroke_id: id,
        brush: brush(),
        space: CoordinateSpace::Canvas,
        samples: blob,
    })
    .unwrap();
    assert_eq!(frames[0].len(), MAX_FRAME_BYTES);
    assert!(frames.iter().all(|f| f.len() <= MAX_FRAME_BYTES));
    let mut over = frames[0].clone();
    over.push(0);
    let r = StrokeReassembler::new();
    assert!(is_limit(&r.accept(over, 0).unwrap_err()));
}

/// Deterministic pseudo-random bytes and mutations of valid frames never panic.
#[test]
fn hostile_bytes_never_panic() {
    let mut state = 0x2545_F491_4F6C_DD1Du64;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    let id = S1.to_string();
    let seeds = [
        enc(&begin(&id, 0)),
        enc(&samples_frame(&id, 1, 0, 6)),
        enc(&hover(1, 3.0)),
        encode_stroke_commit(StrokeCommit {
            note_id: NOTE.into(),
            sheet_id: SHEET.into(),
            stroke_id: id.clone(),
            brush: brush(),
            space: CoordinateSpace::Canvas,
            samples: encode_samples(chans(0, 3, false)).unwrap(),
        })
        .unwrap()
        .remove(0),
    ];
    let receiver = PreviewReceiver::new(7);
    let reasm = StrokeReassembler::new();
    for i in 0..30_000u64 {
        let mut b = if i % 3 == 0 {
            (0..(next() % 200)).map(|_| next() as u8).collect()
        } else {
            seeds[(next() % 4) as usize].clone()
        };
        if i % 3 != 0 && !b.is_empty() {
            for _ in 0..1 + next() % 3 {
                let at = (next() as usize) % b.len();
                b[at] = next() as u8;
            }
            if next() % 4 == 0 {
                b.truncate((next() as usize) % (b.len() + 1));
            }
        }
        let _ = decode_preview_frame(b.clone());
        let _ = receiver.accept(b.clone(), i);
        let _ = reasm.accept(b.clone(), i);
        let _ = frame_kind(b);
    }
    receiver.tick(1_000_000);
}

#[test]
fn crc32_matches_the_standard_check_value() {
    assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    assert_eq!(crc32(b""), 0);
}

// ------------------------------------------------------------------ receiver

#[test]
fn receiver_happy_path_and_hover() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    let ev = feed(&r, &begin(&id, 0), 10);
    assert!(
        matches!(&ev[..], [PreviewEvent::StrokeStarted { stroke_id, viewport_revision: 3, space: CoordinateSpace::Canvas, .. }] if *stroke_id == id)
    );
    let ev = feed(&r, &samples_frame(&id, 1, 0, 3), 20);
    assert!(
        matches!(&ev[..], [PreviewEvent::SamplesAppended { first_index: 0, sent_ms: 1001, channels, .. }] if channels.x.len() == 3)
    );
    let ev = feed(&r, &samples_frame(&id, 2, 3, 2), 30);
    assert!(matches!(
        &ev[..],
        [PreviewEvent::SamplesAppended { first_index: 3, .. }]
    ));
    let ev = feed(&r, &end(&id, 3, 5), 40);
    assert_eq!(
        ev,
        vec![PreviewEvent::StrokeEnded {
            stroke_id: id.clone(),
            total_samples: 5
        }]
    );
    // Hover: latest wins, stale and duplicate hovers are ignored.
    assert_eq!(feed(&r, &hover(5, 1.0), 50).len(), 1);
    assert!(feed(&r, &hover(5, 9.0), 50).is_empty());
    assert!(feed(&r, &hover(4, 9.0), 50).is_empty());
    let ev = feed(&r, &hover(6, 2.0), 50);
    assert!(matches!(&ev[..], [PreviewEvent::HoverMoved { x, .. }] if *x == 2.0));
}

#[test]
fn old_epoch_is_ignored_and_new_epoch_resets() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    let mut old = begin(&stroke(1), 0);
    old.epoch = 6;
    assert!(feed(&r, &old, 0).is_empty());
    let mut old_samples = samples_frame(&id, 1, 0, 2);
    old_samples.epoch = 3;
    assert!(feed(&r, &old_samples, 0).is_empty());
    assert_eq!(r.live_count(), 1);
    let mut newer = begin(&stroke(2), 0);
    newer.epoch = 8;
    let ev = feed(&r, &newer, 5);
    assert_eq!(ev[0], dropped(&id, PreviewDropReason::Reset));
    assert!(matches!(ev[1], PreviewEvent::StrokeStarted { .. }));
    assert_eq!(r.epoch(), 8);
    // The previous epoch is now old.
    assert!(feed(&r, &begin(&id, 0), 6).is_empty());
}

#[test]
fn cancel_is_terminal_for_the_stroke_id() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    feed(&r, &samples_frame(&id, 1, 0, 2), 1);
    assert_eq!(
        feed(&r, &cancel(&id, 2), 2),
        vec![dropped(&id, PreviewDropReason::Cancelled)]
    );
    assert!(feed(&r, &samples_frame(&id, 3, 2, 2), 3).is_empty());
    assert!(feed(&r, &end(&id, 4, 4), 3).is_empty());
    assert!(
        feed(&r, &begin(&id, 5), 3).is_empty(),
        "no resurrection by a new Begin"
    );
    assert_eq!(r.live_count(), 0);
    // Cancel for a stroke never seen: nothing to drop, but terminal.
    let other = stroke(9);
    assert!(feed(&r, &cancel(&other, 1), 4).is_empty());
    assert!(feed(&r, &samples_frame(&other, 2, 0, 1), 4).is_empty());
}

#[test]
fn gap_and_missing_begin_drop_without_inventing_geometry() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    feed(&r, &samples_frame(&id, 1, 0, 3), 1);
    // Samples 3..5 lost.
    let ev = feed(&r, &samples_frame(&id, 3, 5, 2), 2);
    assert_eq!(ev, vec![dropped(&id, PreviewDropReason::Gap)]);
    assert!(
        feed(&r, &samples_frame(&id, 4, 7, 1), 3).is_empty(),
        "dropped once, then silent"
    );
    // End with a wrong total is a gap too.
    let b = stroke(2);
    feed(&r, &begin(&b, 0), 4);
    feed(&r, &samples_frame(&b, 1, 0, 3), 4);
    assert_eq!(
        feed(&r, &end(&b, 2, 9), 5),
        vec![dropped(&b, PreviewDropReason::Gap)]
    );
    // Missing Begin: one drop, later frames silent.
    let c = stroke(3);
    assert_eq!(
        feed(&r, &samples_frame(&c, 1, 0, 2), 6),
        vec![dropped(&c, PreviewDropReason::MissingBegin)]
    );
    assert!(feed(&r, &samples_frame(&c, 2, 2, 2), 6).is_empty());
    assert!(feed(&r, &end(&c, 3, 4), 6).is_empty());
    assert_eq!(r.live_count(), 0);
}

#[test]
fn inconsistent_frames_drop_the_preview() {
    let r = PreviewReceiver::new(7);
    // Changed sheet.
    let a = S1.to_string();
    feed(&r, &begin(&a, 0), 0);
    let mut f = samples_frame(&a, 1, 0, 1);
    f.sheet_id = NOTE.into();
    assert_eq!(
        feed(&r, &f, 1),
        vec![dropped(&a, PreviewDropReason::InvalidData)]
    );
    // Samples after End.
    let b = stroke(2);
    feed(&r, &begin(&b, 0), 0);
    feed(&r, &samples_frame(&b, 1, 0, 1), 0);
    feed(&r, &end(&b, 2, 1), 0);
    assert_eq!(
        feed(&r, &samples_frame(&b, 3, 1, 1), 0),
        vec![dropped(&b, PreviewDropReason::InvalidData)]
    );
    // Channel set changes mid stroke; time goes backwards between frames.
    let c = stroke(3);
    feed(&r, &begin(&c, 0), 0);
    feed(&r, &samples_frame(&c, 1, 0, 2), 0);
    let with_tilt = frame(
        Some(c.clone()),
        2,
        PreviewBody::Samples {
            first_index: 2,
            channels: chans(2, 2, true),
        },
    );
    assert_eq!(
        feed(&r, &with_tilt, 0),
        vec![dropped(&c, PreviewDropReason::InvalidData)]
    );
    let d = stroke(4);
    feed(&r, &begin(&d, 0), 0);
    feed(&r, &samples_frame(&d, 1, 0, 2), 0);
    let mut back = samples_frame(&d, 2, 2, 1);
    if let PreviewBody::Samples { channels, .. } = &mut back.body {
        channels.elapsed_ms = vec![0];
    }
    assert_eq!(
        feed(&r, &back, 0),
        vec![dropped(&d, PreviewDropReason::InvalidData)]
    );
}

#[test]
fn duplicate_and_stale_frames_are_ignored() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    assert!(feed(&r, &begin(&id, 0), 0).is_empty());
    assert_eq!(feed(&r, &samples_frame(&id, 1, 0, 2), 0).len(), 1);
    assert!(feed(&r, &samples_frame(&id, 1, 0, 2), 0).is_empty());
    assert_eq!(r.live_count(), 1);
}

#[test]
fn commit_blocks_late_frames() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    feed(&r, &samples_frame(&id, 1, 0, 2), 0);
    assert_eq!(
        r.commit(id.clone()).unwrap(),
        vec![dropped(&id, PreviewDropReason::Committed)]
    );
    assert!(feed(&r, &samples_frame(&id, 2, 2, 2), 1).is_empty());
    assert!(feed(&r, &end(&id, 3, 4), 1).is_empty());
    assert!(feed(&r, &begin(&id, 4), 1).is_empty());
    // Commit before any preview arrives also blocks it.
    let early = stroke(5);
    assert!(r.commit(early.clone()).unwrap().is_empty());
    assert!(feed(&r, &begin(&early, 0), 2).is_empty());
    assert!(is_invalid(&r.commit("nope".into()).unwrap_err()));
    assert_eq!(r.live_count(), 0);
}

#[test]
fn live_previews_expire_after_5_s_idle_and_survive_a_still_pen() {
    let r = PreviewReceiver::new(7);
    let a = S1.to_string();
    let b = stroke(2);
    feed(&r, &begin(&a, 0), 1000);
    feed(&r, &samples_frame(&a, 1, 0, 1), 1000);
    feed(&r, &begin(&b, 0), 1100);
    // Pen held still for 1 s (and far beyond the old 250 ms): nothing expires.
    assert!(r.tick(2000).is_empty());
    assert!(r.tick(5999).is_empty());
    assert_eq!(r.live_count(), 2);
    // Moving again continues the same stroke.
    assert_eq!(feed(&r, &samples_frame(&a, 2, 1, 1), 2000).len(), 1);
    assert!(r.tick(6099).is_empty());
    assert_eq!(r.tick(6100), vec![dropped(&b, PreviewDropReason::Expired)]);
    assert!(r.tick(6999).is_empty());
    assert_eq!(r.tick(7000), vec![dropped(&a, PreviewDropReason::Expired)]);
    assert!(r.tick(99_999).is_empty());
    // Expired strokes stay dead.
    assert!(feed(&r, &samples_frame(&a, 3, 2, 1), 8000).is_empty());
    // A clock that steps backwards does not expire anything.
    let c = stroke(3);
    feed(&r, &begin(&c, 0), 50_000);
    assert!(r.tick(10).is_empty());
}

#[test]
fn ended_previews_are_held_until_commit_reset_or_10_s() {
    let r = PreviewReceiver::new(7);
    let a = S1.to_string();
    let b = stroke(2);
    let c = stroke(3);
    for id in [&a, &b, &c] {
        feed(&r, &begin(id, 0), 0);
        feed(&r, &samples_frame(id, 1, 0, 2), 100);
        feed(&r, &end(id, 2, 2), 1000);
    }
    // Far past the live idle limit, still held: a slow commit must not flicker.
    assert!(r.tick(10_999).is_empty());
    assert_eq!(r.live_count(), 3);
    // Commit before the hold limit replaces it.
    assert_eq!(
        r.commit(a.clone()).unwrap(),
        vec![dropped(&a, PreviewDropReason::Committed)]
    );
    // Hold limit: 10 s after End.
    assert_eq!(
        r.tick(11_000),
        vec![
            dropped(&b, PreviewDropReason::Expired),
            dropped(&c, PreviewDropReason::Expired)
        ]
    );
    assert!(feed(&r, &samples_frame(&b, 3, 2, 1), 11_001).is_empty());
    // Reset clears an ended preview at once.
    let d = stroke(4);
    feed(&r, &begin(&d, 0), 20_000);
    feed(&r, &end(&d, 1, 0), 20_001);
    assert_eq!(r.reset(8), vec![dropped(&d, PreviewDropReason::Reset)]);
}

#[test]
fn reset_clears_everything() {
    let r = PreviewReceiver::new(7);
    let a = S1.to_string();
    let b = stroke(2);
    feed(&r, &begin(&a, 0), 0);
    feed(&r, &begin(&b, 0), 0);
    feed(&r, &hover(3, 1.0), 0);
    r.commit(stroke(8)).unwrap();
    let ev = r.reset(9);
    assert_eq!(
        ev,
        vec![
            dropped(&a, PreviewDropReason::Reset),
            dropped(&b, PreviewDropReason::Reset)
        ]
    );
    assert_eq!(r.live_count(), 0);
    assert_eq!(r.epoch(), 9);
    assert!(r.reset(9).is_empty());
    // Old epoch frames are ignored; the new epoch starts with fresh hover and stroke memory.
    assert!(feed(&r, &begin(&a, 0), 1).is_empty());
    let mut f = hover(1, 1.0);
    f.epoch = 9;
    assert_eq!(feed(&r, &f, 1).len(), 1);
    let mut f = begin(&stroke(8), 0);
    f.epoch = 9;
    assert_eq!(feed(&r, &f, 1).len(), 1);
}

#[test]
fn receiver_memory_is_bounded() {
    let r = PreviewReceiver::new(7);
    // Live previews: the least recently active one is evicted.
    for i in 0..MAX_LIVE_PREVIEWS as u32 {
        feed(&r, &begin(&stroke(i), 0), u64::from(i));
    }
    assert_eq!(r.live_count(), MAX_LIVE_PREVIEWS as u32);
    feed(&r, &samples_frame(&stroke(0), 1, 0, 1), 100); // stroke 0 is now fresh
    let ev = feed(&r, &begin(&stroke(100), 0), 101);
    assert_eq!(ev[0], dropped(&stroke(1), PreviewDropReason::LimitExceeded));
    assert!(matches!(ev[1], PreviewEvent::StrokeStarted { .. }));
    assert_eq!(r.live_count(), MAX_LIVE_PREVIEWS as u32);
    // Terminal IDs: remembered up to the cap, oldest forgotten first.
    let r = PreviewReceiver::new(7);
    for i in 0..(MAX_TERMINAL_IDS as u32 + 10) {
        r.commit(stroke(i)).unwrap();
    }
    assert!(
        !feed(&r, &begin(&stroke(0), 0), 0).is_empty(),
        "oldest was forgotten"
    );
    assert!(feed(&r, &begin(&stroke(MAX_TERMINAL_IDS as u32 + 9), 0), 0).is_empty());
    // Samples per preview.
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    let mut seq = 1;
    let mut first = 0u32;
    let mut last = vec![];
    while first < 1_000_000 {
        let n = 4000.min(1_000_000 - first);
        last = feed(&r, &samples_frame(&id, seq, first, n), 0);
        assert!(matches!(&last[..], [PreviewEvent::SamplesAppended { .. }]));
        first += n;
        seq += 1;
    }
    assert_eq!(last.len(), 1);
    assert_eq!(
        feed(&r, &samples_frame(&id, seq, first, 1), 0),
        vec![dropped(&id, PreviewDropReason::LimitExceeded)]
    );
}

#[test]
fn receiver_rejects_bad_bytes_without_changing_state() {
    let r = PreviewReceiver::new(7);
    let id = S1.to_string();
    feed(&r, &begin(&id, 0), 0);
    assert!(r.accept(vec![1, 2, 3], 1).is_err());
    assert!(r.accept(vec![0; MAX_FRAME_BYTES + 1], 1).is_err());
    assert_eq!(r.live_count(), 1);
    assert_eq!(feed(&r, &samples_frame(&id, 1, 0, 1), 2).len(), 1);
}

// ------------------------------------------------------------------ sender queue

fn drain(q: &PreviewSendQueue) -> Vec<PreviewFrame> {
    let mut out = vec![];
    while let Some(b) = q.pop() {
        assert!(b.len() <= MAX_FRAME_BYTES);
        out.push(decode_preview_frame(b).unwrap());
    }
    out
}

#[test]
fn queue_is_fifo_and_empty_pops_none() {
    let q = PreviewSendQueue::new();
    assert!(q.pop().is_none());
    let id = S1.to_string();
    q.push_preview(begin(&id, 0)).unwrap();
    q.push_preview(samples_frame(&id, 1, 0, 2)).unwrap();
    q.push_preview(end(&id, 2, 2)).unwrap();
    let out = drain(&q);
    let kinds: Vec<_> = out.iter().map(|f| f.sequence).collect();
    assert_eq!(kinds, vec![0, 1, 2]);
    assert_eq!(q.queued_preview_bytes(), 0);
    assert!(is_invalid(&q.push_preview(hover(1, f32::NAN)).unwrap_err()));
}

#[test]
fn coalescing_keeps_every_sample_in_order() {
    let q = PreviewSendQueue::new();
    let a = S1.to_string();
    let b = stroke(2);
    q.push_preview(begin(&a, 0)).unwrap();
    q.push_preview(begin(&b, 0)).unwrap();
    // Interleave two strokes in many small frames: ~290 KiB queued before merging.
    let per = 2u32;
    let frames_per_stroke = 1400u32;
    for i in 0..frames_per_stroke {
        q.push_preview(samples_frame(&a, 1 + i, i * per, per))
            .unwrap();
        q.push_preview(samples_frame(&b, 1 + i, i * per, per))
            .unwrap();
    }
    q.push_preview(end(&a, 5000, frames_per_stroke * per))
        .unwrap();
    q.push_preview(end(&b, 5000, frames_per_stroke * per))
        .unwrap();
    assert!(q.queued_preview_bytes() as usize <= MAX_QUEUED_PREVIEW_BYTES);
    assert_eq!(q.dropped_preview_frames(), 0, "merging alone was enough");
    let out = drain(&q);
    // Fewer frames than pushed, every sample present once and in order, frames still valid.
    assert!(out.len() < 2 + 2 * 1400 + 2);
    for id in [&a, &b] {
        let mut next = 0;
        let mut xs: Vec<f32> = vec![];
        for f in out.iter().filter(|f| f.stroke_id.as_ref() == Some(id)) {
            if let PreviewBody::Samples {
                first_index,
                channels,
            } = &f.body
            {
                assert_eq!(*first_index, next);
                next += channels.x.len() as u32;
                xs.extend(&channels.x);
            }
        }
        assert_eq!(next, frames_per_stroke * per);
        let expect = chans(0, next, false).x;
        assert_eq!(xs, expect);
    }
    // Per-stroke order: Begin first, End last.
    for id in [&a, &b] {
        let mine: Vec<_> = out
            .iter()
            .filter(|f| f.stroke_id.as_ref() == Some(id))
            .collect();
        assert!(matches!(
            mine.first().unwrap().body,
            PreviewBody::Begin { .. }
        ));
        assert!(matches!(mine.last().unwrap().body, PreviewBody::End { .. }));
        assert!(mine.windows(2).all(|w| w[0].sequence < w[1].sequence));
    }
    // And the receiver takes the merged stream without a gap.
    let r = PreviewReceiver::new(7);
    for f in &out {
        let ev = feed(&r, f, 0);
        assert!(
            !ev.iter().any(|e| matches!(e, PreviewEvent::Dropped { .. })),
            "{ev:?}"
        );
    }
}

#[test]
fn coalesced_frames_respect_the_frame_limit_and_channel_sets() {
    let q = PreviewSendQueue::new();
    let id = S1.to_string();
    // Frames with tilt/orientation never merge into core-only frames.
    for i in 0..2000u32 {
        q.push_preview(samples_frame(&id, 1 + i * 2, i * 3, 3))
            .unwrap();
    }
    q.push_preview(frame(
        Some(id.clone()),
        9000,
        PreviewBody::Samples {
            first_index: 6000,
            channels: chans(6000, 3, true),
        },
    ))
    .unwrap();
    let out = drain(&q);
    let total: usize = out
        .iter()
        .map(|f| match &f.body {
            PreviewBody::Samples { channels, .. } => channels.x.len(),
            _ => 0,
        })
        .sum();
    assert_eq!(total, 6003);
}

#[test]
fn queue_never_drops_by_age() {
    // The queue has no clock: frames wait indefinitely and only the byte budget applies.
    let q = PreviewSendQueue::new();
    let id = S1.to_string();
    q.push_preview(begin(&id, 0)).unwrap();
    q.push_preview(samples_frame(&id, 1, 0, 10)).unwrap();
    assert_eq!(q.dropped_preview_frames(), 0);
    assert_eq!(drain(&q).len(), 2);
}

#[test]
fn hover_keeps_only_the_newest() {
    let q = PreviewSendQueue::new();
    let id = S1.to_string();
    q.push_preview(hover(1, 1.0)).unwrap();
    q.push_preview(begin(&id, 0)).unwrap();
    q.push_preview(hover(2, 2.0)).unwrap();
    q.push_preview(hover(3, 3.0)).unwrap();
    let out = drain(&q);
    assert_eq!(out.len(), 2);
    assert!(matches!(out[1].body, PreviewBody::Hover { x, .. } if x == 3.0));
}

#[test]
fn overflow_drops_oldest_samples_and_the_receiver_sees_the_gap() {
    let q = PreviewSendQueue::new();
    let id = S1.to_string();
    q.push_preview(begin(&id, 0)).unwrap();
    // 8 x 4000 samples = 8 x ~64 KB: far over 256 KiB even when merged.
    for i in 0..8u32 {
        q.push_preview(samples_frame(&id, 1 + i, i * 4000, 4000))
            .unwrap();
        assert!(q.queued_preview_bytes() as usize <= MAX_QUEUED_PREVIEW_BYTES);
    }
    q.push_preview(end(&id, 20, 32_000)).unwrap();
    assert!(q.dropped_preview_frames() > 0);
    let out = drain(&q);
    assert!(
        matches!(out[0].body, PreviewBody::Begin { .. }),
        "Begin survives"
    );
    assert!(
        matches!(out.last().unwrap().body, PreviewBody::End { .. }),
        "End survives"
    );
    let r = PreviewReceiver::new(7);
    let mut events = vec![];
    for f in &out {
        events.extend(feed(&r, f, 0));
    }
    assert!(
        events.contains(&dropped(&id, PreviewDropReason::Gap)),
        "{events:?}"
    );
    assert_eq!(r.live_count(), 0);
}

#[test]
fn overflow_with_only_control_frames_still_stays_bounded() {
    let q = PreviewSendQueue::new();
    for i in 0..6000u32 {
        q.push_preview(begin(&stroke(i), 0)).unwrap();
        assert!(q.queued_preview_bytes() as usize <= MAX_QUEUED_PREVIEW_BYTES);
    }
    assert!(q.dropped_preview_frames() > 0);
}

fn commit_of(id: &Id, n: u32) -> StrokeCommit {
    StrokeCommit {
        note_id: NOTE.into(),
        sheet_id: SHEET.into(),
        stroke_id: id.clone(),
        brush: brush(),
        space: CoordinateSpace::ViewRelative,
        samples: encode_samples(chans(0, n, true)).unwrap(),
    }
}

#[test]
fn commit_frames_are_never_dropped_and_yield_to_preview() {
    let q = PreviewSendQueue::new();
    let id = S1.to_string();
    let commit = commit_of(&id, 100_000); // ~2.4 MB: dozens of chunks
    let chunks = encode_stroke_commit(commit.clone()).unwrap();
    assert!(chunks.len() > 30);
    q.push_commit(commit).unwrap();
    assert_eq!(
        q.queued_durable_bytes() as usize,
        chunks.iter().map(Vec::len).sum::<usize>()
    );
    // Flood preview far past its budget: durable data is untouched.
    let live = stroke(2);
    q.push_preview(begin(&live, 0)).unwrap();
    for i in 0..20u32 {
        q.push_preview(samples_frame(&live, 1 + i, i * 4000, 4000))
            .unwrap();
    }
    let mut popped_durable = vec![];
    let mut saw_durable = false;
    while let Some(b) = q.pop() {
        match frame_kind(b.clone()).unwrap() {
            FrameKind::Preview => assert!(!saw_durable, "preview must come before durable"),
            FrameKind::StrokeChunk => {
                saw_durable = true;
                popped_durable.push(b);
            }
        }
    }
    assert_eq!(popped_durable, chunks);
    // Hover and clear_preview do not touch durable frames either.
    let q = PreviewSendQueue::new();
    q.push_commit(commit_of(&id, 10)).unwrap();
    q.push_preview(hover(1, 1.0)).unwrap();
    q.clear_preview();
    assert_eq!(q.queued_preview_bytes(), 0);
    assert!(q.pop().is_some());
}

// ------------------------------------------------------------------ chunking

#[test]
fn sixteen_mib_stroke_round_trips() {
    let id = S1.to_string();
    // 16 MiB of samples exactly: 12 bytes header + 16 bytes x 1,048,575 = 16,777,212 (core channels).
    let n = (16 * 1024 * 1024 - 12) / 16;
    let big = StrokeCommit {
        note_id: NOTE.into(),
        sheet_id: SHEET.into(),
        stroke_id: id.clone(),
        brush: brush(),
        space: CoordinateSpace::Canvas,
        samples: {
            let c = SampleChannels {
                x: (0..n).map(|i| (i % 4800) as f32).collect(),
                y: (0..n).map(|i| (i % 3000) as f32).collect(),
                pressure: (0..n).map(|i| (i % 100) as f32 / 100.0).collect(),
                elapsed_ms: (0..n).collect(),
                tilt: None,
                orientation: None,
            };
            encode_samples(c).unwrap()
        },
    };
    assert!(big.samples.len() > 16 * 1024 * 1024 - 16);
    let frames = encode_stroke_commit(big.clone()).unwrap();
    assert!(frames.len() > 250);
    assert!(frames.iter().all(|f| f.len() <= MAX_FRAME_BYTES));
    let r = StrokeReassembler::new();
    let mut result = None;
    for (i, f) in frames.iter().enumerate() {
        let got = r.accept(f.clone(), i as u64).unwrap();
        if i + 1 < frames.len() {
            assert!(got.is_none(), "no partial stroke");
            assert!(r.in_progress());
        } else {
            result = got;
        }
    }
    assert_eq!(result.unwrap(), big);
    assert!(!r.in_progress());
}

#[test]
fn strokes_above_16_mib_are_refused() {
    let id = S1.to_string();
    let n = (16 * 1024 * 1024) / 16 + 1;
    let c = SampleChannels {
        x: vec![0.0; n],
        y: vec![0.0; n],
        pressure: vec![0.5; n],
        elapsed_ms: vec![0; n],
        tilt: None,
        orientation: None,
    };
    assert!(encode_samples(c).is_err());
    // A hostile header claiming a larger payload is a non-retryable limit.
    let good = encode_stroke_commit(commit_of(&id, 3)).unwrap().remove(0);
    let mut v = good.clone();
    v[20..24].copy_from_slice(&(20u32 * 1024 * 1024).to_le_bytes());
    let r = StrokeReassembler::new();
    assert!(matches!(
        r.accept(v, 0).unwrap_err(),
        CoreError::ResourceLimit {
            retryable: false,
            ..
        }
    ));
}

fn multi_chunk() -> (StrokeCommit, Vec<Vec<u8>>) {
    let c = commit_of(&S1.to_string(), 12_000); // ~288 KB: 5 chunks
    let frames = encode_stroke_commit(c.clone()).unwrap();
    assert!(frames.len() >= 4);
    (c, frames)
}

#[test]
fn chunks_may_arrive_out_of_order_and_duplicated() {
    let (c, frames) = multi_chunk();
    let r = StrokeReassembler::new();
    let order: Vec<usize> = {
        let mut o: Vec<usize> = (0..frames.len()).rev().collect();
        o.insert(1, o[0]); // duplicate of the first delivered chunk
        o
    };
    let mut result = None;
    for (t, i) in order.iter().enumerate() {
        let got = r.accept(frames[*i].clone(), t as u64).unwrap();
        if got.is_some() {
            assert_eq!(t, order.len() - 1);
            result = got;
        }
    }
    assert_eq!(result.unwrap(), c);
}

#[test]
fn missing_chunk_never_yields_and_the_transfer_can_be_abandoned() {
    let (_, frames) = multi_chunk();
    let r = StrokeReassembler::new();
    for (i, f) in frames.iter().enumerate() {
        if i == 1 {
            continue;
        }
        assert!(r.accept(f.clone(), 0).unwrap().is_none());
    }
    assert!(r.in_progress());
    // Another stroke is refused, retryably, while this one holds the slot.
    let other = encode_stroke_commit(commit_of(&stroke(2), 3))
        .unwrap()
        .remove(0);
    assert!(matches!(
        r.accept(other.clone(), 100).unwrap_err(),
        CoreError::ResourceLimit {
            retryable: true,
            ..
        }
    ));
    // The late chunk completes it.
    assert!(r.accept(frames[1].clone(), 200).unwrap().is_some());
    // A stalled transfer is abandoned after 30 s of silence...
    let r = StrokeReassembler::new();
    r.accept(frames[0].clone(), 0).unwrap();
    assert!(r.accept(other.clone(), 29_999).is_err());
    assert!(r.accept(other.clone(), 59_999).unwrap().is_some());
    // ...or at once by reset.
    let r = StrokeReassembler::new();
    r.accept(frames[0].clone(), 0).unwrap();
    r.reset();
    assert!(!r.in_progress());
    assert!(r.accept(other, 1).unwrap().is_some());
}

#[test]
fn corrupted_payload_is_rejected_and_frees_the_slot() {
    let (c, frames) = multi_chunk();
    let r = StrokeReassembler::new();
    let mut bad = frames.clone();
    bad[2][100] ^= 0x01;
    let mut err = None;
    for f in &bad {
        match r.accept(f.clone(), 0) {
            Ok(None) => {}
            Ok(Some(_)) => panic!("corrupt stroke accepted"),
            Err(e) => err = Some(e),
        }
    }
    assert!(is_invalid(&err.unwrap()));
    assert!(!r.in_progress());
    // The same stroke, retransmitted clean, now succeeds.
    let mut got = None;
    for f in &frames {
        got = r.accept(f.clone(), 1).unwrap();
    }
    assert_eq!(got.unwrap(), c);
}

#[test]
fn malformed_chunks_are_typed_errors() {
    let (_, frames) = multi_chunk();
    let f = frames[0].clone();
    let r = StrokeReassembler::new();
    for cut in 0..40 {
        assert!(is_invalid(&r.accept(f[..cut].to_vec(), 0).unwrap_err()));
    }
    assert!(is_invalid(
        &r.accept(f[..f.len() - 1].to_vec(), 0).unwrap_err()
    ));
    let mut v = f.clone();
    v[1] = 9;
    assert!(matches!(
        r.accept(v, 0).unwrap_err(),
        CoreError::UnsupportedVersion { .. }
    ));
    let mut v = f.clone();
    v[0] = 0x50;
    assert!(is_invalid(&r.accept(v, 0).unwrap_err()));
    let mut v = f.clone();
    v[32..36].copy_from_slice(&99u32.to_le_bytes()); // index out of range
    assert!(is_invalid(&r.accept(v, 0).unwrap_err()));
    let mut v = f.clone();
    v[28..32].copy_from_slice(&1u32.to_le_bytes()); // count inconsistent with length
    assert!(is_invalid(&r.accept(v, 0).unwrap_err()));
    assert!(!r.in_progress(), "malformed chunks never start a transfer");
    // A header that disagrees with the transfer in progress.
    r.accept(f.clone(), 0).unwrap();
    let mut v = frames[1].clone();
    v[24] ^= 1; // different CRC
    assert!(is_invalid(&r.accept(v, 0).unwrap_err()));
    assert!(r.in_progress());
}

#[test]
fn commit_payload_is_validated() {
    let id = S1.to_string();
    let mut c = commit_of(&id, 3);
    c.samples[0] = 0;
    assert!(is_invalid(&encode_stroke_commit(c).unwrap_err()));
    let mut c = commit_of(&id, 3);
    c.stroke_id = "x".into();
    assert!(is_invalid(&encode_stroke_commit(c).unwrap_err()));
    // A sender that CRCs garbage still cannot get it through: payload must parse and its
    // sample blob must decode.
    let mut payload = vec![0u8; 40];
    payload[36..40].copy_from_slice(&0u32.to_le_bytes());
    let mut f = vec![0x43, 1, 1, 0];
    f.extend_from_slice(&ulid::Ulid::from_string(&id).unwrap().to_bytes());
    f.extend_from_slice(&40u32.to_le_bytes());
    f.extend_from_slice(&crc32(&payload).to_le_bytes());
    f.extend_from_slice(&1u32.to_le_bytes());
    f.extend_from_slice(&0u32.to_le_bytes());
    f.extend_from_slice(&payload);
    assert!(is_invalid(
        &StrokeReassembler::new().accept(f, 0).unwrap_err()
    ));
    assert_eq!(CHUNK_DATA, 65_500);
}

#[test]
fn frame_kinds_are_distinguishable_by_the_first_byte() {
    let id = S1.to_string();
    assert_eq!(frame_kind(enc(&hover(1, 1.0))).unwrap(), FrameKind::Preview);
    let chunk = encode_stroke_commit(commit_of(&id, 2)).unwrap().remove(0);
    assert_eq!(frame_kind(chunk.clone()).unwrap(), FrameKind::StrokeChunk);
    assert!(is_invalid(&frame_kind(vec![]).unwrap_err()));
    assert!(is_invalid(&frame_kind(vec![0]).unwrap_err()));
    assert!(is_invalid(&decode_preview_frame(chunk).unwrap_err()));
    let l = preview_limits();
    assert_eq!(l.max_frame_bytes, 65_536);
    assert_eq!(l.max_queued_preview_bytes, 262_144);
    assert_eq!((l.live_idle_expiry_ms, l.ended_hold_ms), (5_000, 10_000));
}
