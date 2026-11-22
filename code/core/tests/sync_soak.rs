//! Randomised delta sync against concurrent editor calls from several threads, as the phone does it (link part K.6).
//!
//! On 2026-11-16 a physical phone panicked under delta sync and poisoned the library lock; the panic was never seen again.
//! This runs two libraries (the phone's and the Mac's) with the threads the phone has: two editors on the phone (a pen
//! and a text editor), one on the Mac, a sync thread exporting and merging deltas and whole copies both ways, and a
//! reader taking digests, snapshots and note lists. Any panic in any thread, any `Internal` error, or any call that takes
//! more than a minute (a deadlock) fails it.
//!
//!   cargo test --release --test sync_soak -- --nocapture                         about 6 seconds
//!   SYNCR_SOAK_SECONDS=600 cargo test --release --test sync_soak -- --ignored --nocapture   the ten-minute soak
mod common;
use common::*;
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::{Duration, Instant},
};
use syncr_core::*;

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
}

#[derive(Default)]
struct Counters {
    submits: AtomicU64,
    stale: AtomicU64,
    deltas: AtomicU64,
    copies: AtomicU64,
    rejected: AtomicU64,
    reads: AtomicU64,
    slowest_ms: AtomicU64,
}

/// An error that is a normal outcome of racing edits; anything else is a failure of the core.
fn expected(error: &CoreError) -> bool {
    !matches!(
        error,
        CoreError::Internal { .. } | CoreError::StorageIo { .. }
    )
}

fn timed<T>(c: &Counters, f: impl FnOnce() -> T) -> T {
    let start = Instant::now();
    let out = f();
    c.slowest_ms
        .fetch_max(start.elapsed().as_millis() as u64, Ordering::Relaxed);
    out
}

fn editor(
    note: Arc<Note>,
    sheet: String,
    creation: String,
    seed: u64,
    text: bool,
    c: Arc<Counters>,
    stop: Arc<AtomicBool>,
) {
    let mut rng = Rng(seed | 1);
    let mut block: Option<String> = None;
    while !stop.load(Ordering::Relaxed) {
        let result = timed(&c, || -> CoreResult<()> {
            let snapshot = note.snapshot()?;
            if text {
                match &block {
                    None => {
                        let id = new_id();
                        note.submit(Command {
                            operation_id: new_id(),
                            dependencies: vec![creation.clone()],
                            body: CommandBody::Marks {
                                mutations: vec![MarkMutation::AddText {
                                    mark_id: id.clone(),
                                    sheet_id: sheet.clone(),
                                    role: TextRole::Free,
                                    origin_x: rng.below(500) as f32,
                                    origin_y: rng.below(500) as f32,
                                    width: 800.0,
                                    text: "soak".into(),
                                    style: TextStyle {
                                        size: BrushSize::Medium,
                                        colour: InkColour::Ink,
                                    },
                                }],
                            },
                        })?;
                        block = Some(id);
                    }
                    Some(id) => {
                        let Some(view) = snapshot.text_blocks.iter().find(|b| &b.id == id) else {
                            return Ok(());
                        };
                        let len = view.text.chars().count() as u64;
                        note.submit(Command {
                            operation_id: new_id(),
                            dependencies: vec![view.head.operation_id.clone()],
                            body: CommandBody::Marks {
                                mutations: vec![MarkMutation::SpliceText {
                                    mark_id: id.clone(),
                                    base: base(&view.head.operation_id),
                                    offset: rng.below(len + 1) as u32,
                                    delete: 0,
                                    insert: "ab".into(),
                                }],
                            },
                        })?;
                    }
                }
                return Ok(());
            }
            let live: Vec<_> = snapshot.strokes.iter().filter(|s| !s.tombstone).collect();
            let roll = rng.below(10);
            if (roll < 5 && live.len() < 150) || live.is_empty() {
                note.submit(Command {
                    operation_id: new_id(),
                    dependencies: vec![creation.clone()],
                    body: CommandBody::Marks {
                        mutations: vec![MarkMutation::AddStroke {
                            mark_id: new_id(),
                            sheet_id: sheet.clone(),
                            samples: blob(),
                            brush: brush(),
                        }],
                    },
                })?;
            } else if roll < 8 {
                let s = live[rng.below(live.len() as u64) as usize];
                note.submit(Command {
                    operation_id: new_id(),
                    dependencies: vec![s.head.operation_id.clone()],
                    body: CommandBody::Marks {
                        mutations: vec![MarkMutation::TransformStroke {
                            mark_id: s.id.clone(),
                            base: base(&s.head.operation_id),
                            transform: Affine {
                                a: 1.0,
                                b: 0.0,
                                c: 0.0,
                                d: 1.0,
                                tx: rng.below(100) as f64,
                                ty: 0.0,
                            },
                        }],
                    },
                })?;
            } else {
                let s = live[rng.below(live.len() as u64) as usize];
                note.submit(Command {
                    operation_id: new_id(),
                    dependencies: vec![s.head.operation_id.clone()],
                    body: CommandBody::Marks {
                        mutations: vec![MarkMutation::DeleteStroke {
                            mark_id: s.id.clone(),
                            base: base(&s.head.operation_id),
                        }],
                    },
                })?;
            }
            Ok(())
        });
        match result {
            Ok(()) => {
                c.submits.fetch_add(1, Ordering::Relaxed);
            }
            Err(e) if expected(&e) => {
                c.stale.fetch_add(1, Ordering::Relaxed);
            }
            Err(e) => panic!("editor call failed: {e:?}"),
        }
        // A pen draws a few strokes a second and the editor types a few letters: far less than a tight loop, and the note stays a size
        // the phone could hold.
        thread::sleep(Duration::from_millis(40 + rng.below(120)));
    }
}

/// One delta from `from` to `to` (what `to` lacks), or a whole copy every so often.
fn sync_once(from: &Note, to: &Note, dir: &std::path::Path, whole: bool, c: &Counters) {
    let file = dir.join(if whole { "c.syncr" } else { "d.syncr-delta" });
    let _ = std::fs::remove_file(&file);
    let step = timed(c, || -> CoreResult<()> {
        if whole {
            from.export_copy(path(&file))?;
            let report = to.merge_copy(path(&file))?;
            c.copies.fetch_add(1, Ordering::Relaxed);
            c.rejected
                .fetch_add(report.rejected.len() as u64, Ordering::Relaxed);
        } else {
            let receipt = from.export_delta(to.sync_heads()?, path(&file))?;
            if receipt.anchored {
                let merged = to.merge_delta(path(&file))?;
                c.deltas.fetch_add(1, Ordering::Relaxed);
                c.rejected
                    .fetch_add(merged.report.rejected.len() as u64, Ordering::Relaxed);
            }
        }
        Ok(())
    });
    if let Err(e) = step {
        assert!(expected(&e), "sync failed: {e:?}");
    }
}

fn soak(seconds: u64) {
    let phone_root = tempfile::tempdir().unwrap();
    let mac_root = tempfile::tempdir().unwrap();
    let phone = open_library(path(phone_root.path())).unwrap();
    let mac = open_library(path(mac_root.path())).unwrap();
    let request = request(None);
    phone.create_note(request.clone()).unwrap();
    let a = phone.open_note(request.note_id.clone()).unwrap();
    let seed = tempfile::tempdir().unwrap();
    let copy = seed.path().join("seed.syncr"); // never inside a library root: every .syncr file there is a published note
    a.export_copy(path(&copy)).unwrap();
    mac.install_copy(path(&copy)).unwrap();
    let b = mac.open_note(request.note_id.clone()).unwrap();

    let counters = Arc::new(Counters::default());
    let stop = Arc::new(AtomicBool::new(false));
    let sheet = request.first_sheet_id.clone();
    let creation = request.operation_id.clone();
    let mut threads = vec![];
    for (name, note, seed, text) in [
        ("phone-pen", a.clone(), 11u64, false),
        ("phone-text", a.clone(), 23, true),
        ("mac-editor", b.clone(), 37, false),
    ] {
        let (sheet, creation, c, stop) = (
            sheet.clone(),
            creation.clone(),
            counters.clone(),
            stop.clone(),
        );
        threads.push(
            thread::Builder::new()
                .name(name.into())
                .spawn(move || editor(note, sheet, creation, seed, text, c, stop))
                .unwrap(),
        );
    }
    {
        let (a, b, c, stop) = (a.clone(), b.clone(), counters.clone(), stop.clone());
        let dir = tempfile::tempdir().unwrap();
        threads.push(
            thread::Builder::new()
                .name("sync".into())
                .spawn(move || {
                    let mut rng = Rng(99);
                    let mut n = 0u64;
                    while !stop.load(Ordering::Relaxed) {
                        n += 1;
                        let whole = n.is_multiple_of(17);
                        if rng.below(2) == 0 {
                            sync_once(&a, &b, dir.path(), whole, &c);
                            sync_once(&b, &a, dir.path(), whole, &c);
                        } else {
                            sync_once(&b, &a, dir.path(), whole, &c);
                            sync_once(&a, &b, dir.path(), whole, &c);
                        }
                        thread::sleep(Duration::from_millis(20 + rng.below(80)));
                    }
                })
                .unwrap(),
        );
    }
    {
        let (a, phone, id, c, stop) = (
            a.clone(),
            phone.clone(),
            request.note_id.clone(),
            counters.clone(),
            stop.clone(),
        );
        threads.push(
            thread::Builder::new()
                .name("reader".into())
                .spawn(move || {
                    while !stop.load(Ordering::Relaxed) {
                        timed(&c, || {
                            phone.list_notes().unwrap();
                            phone.note_sync_digest(id.clone()).unwrap();
                            a.snapshot().unwrap();
                            a.sync_digest().unwrap();
                        });
                        c.reads.fetch_add(1, Ordering::Relaxed);
                        thread::sleep(Duration::from_millis(15));
                    }
                })
                .unwrap(),
        );
    }

    let end = Instant::now() + Duration::from_secs(seconds);
    let mut next_report = Instant::now() + Duration::from_secs(60);
    while Instant::now() < end {
        thread::sleep(Duration::from_millis(250));
        // A thread that ended early panicked.
        assert!(
            threads.iter().all(|t| !t.is_finished()),
            "a soak thread ended early (it panicked)"
        );
        if seconds >= 120 && Instant::now() >= next_report {
            next_report += Duration::from_secs(60);
            println!(
                "soak: submits={} stale={} deltas={} copies={} rejected={} reads={} slowest-call={}ms",
                counters.submits.load(Ordering::Relaxed),
                counters.stale.load(Ordering::Relaxed),
                counters.deltas.load(Ordering::Relaxed),
                counters.copies.load(Ordering::Relaxed),
                counters.rejected.load(Ordering::Relaxed),
                counters.reads.load(Ordering::Relaxed),
                counters.slowest_ms.load(Ordering::Relaxed)
            );
        }
    }
    stop.store(true, Ordering::Relaxed);
    for t in threads {
        let name = t.thread().name().unwrap_or("?").to_owned();
        t.join()
            .unwrap_or_else(|_| panic!("soak thread {name} panicked"));
    }
    // The libraries still work: one more whole-copy round both ways and a digest from each.
    let dir = tempfile::tempdir().unwrap();
    sync_once(&a, &b, dir.path(), true, &counters);
    sync_once(&b, &a, dir.path(), true, &counters);
    let (da, db) = (a.sync_digest().unwrap(), b.sync_digest().unwrap());
    println!(
        "soak done: {seconds}s submits={} stale={} deltas={} copies={} rejected={} reads={} slowest-call={}ms; phone ops={} mac ops={} digests {}",
        counters.submits.load(Ordering::Relaxed),
        counters.stale.load(Ordering::Relaxed),
        counters.deltas.load(Ordering::Relaxed),
        counters.copies.load(Ordering::Relaxed),
        counters.rejected.load(Ordering::Relaxed),
        counters.reads.load(Ordering::Relaxed),
        counters.slowest_ms.load(Ordering::Relaxed),
        da.operation_count,
        db.operation_count,
        if da.digest == db.digest {
            "equal"
        } else {
            "differ (rejected operations are kept apart)"
        }
    );
    assert!(
        counters.submits.load(Ordering::Relaxed) > 0 && counters.deltas.load(Ordering::Relaxed) > 0,
        "the soak did no work"
    );
    assert!(
        counters.slowest_ms.load(Ordering::Relaxed) < 60_000,
        "a call took over a minute"
    );
    // The panic log, if any thread panicked, is in the library folder; none must exist.
    assert!(
        !phone_root.path().join(".core-panic.log").exists()
            && !mac_root.path().join(".core-panic.log").exists(),
        "a panic was recorded"
    );
}

#[test]
fn a_short_soak_finds_no_panic() {
    soak(6);
}

#[test]
#[ignore = "ten minutes: SYNCR_SOAK_SECONDS=600 cargo test --release --test sync_soak -- --ignored --nocapture"]
fn the_long_soak_finds_no_panic() {
    let seconds = std::env::var("SYNCR_SOAK_SECONDS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(600);
    soak(seconds);
}
