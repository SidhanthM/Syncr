//! Two-way sync: merge, install and rebase between in-process libraries with different authors.
use crate::{library::sync::sha256_hex, *};
use std::{path::Path, process::Command as ProcessCommand, sync::Arc};

fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
}
fn png() -> Vec<u8> {
    include_bytes!("../fixtures/image-v2/pixel.png").to_vec()
}
fn blob(x: f32) -> Vec<u8> {
    encode_samples(SampleChannels {
        x: vec![x, x + 1.0],
        y: vec![2.0, 3.0],
        pressure: vec![0.5, 0.6],
        elapsed_ms: vec![0, 4],
        tilt: None,
        orientation: None,
    })
    .unwrap()
}
fn brush() -> Brush {
    Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Ink)
}
fn base(op: &str) -> Base {
    Base::Operation {
        operation_id: op.into(),
    }
}
fn marks(m: Vec<MarkMutation>, deps: Vec<Id>) -> Command {
    Command {
        operation_id: new_id(),
        dependencies: deps,
        body: CommandBody::Marks { mutations: m },
    }
}

struct Device {
    root: tempfile::TempDir,
    lib: Arc<SyncrLibrary>,
    note: Arc<Note>,
}
fn device_with(copy: &Path, note_id: &str) -> Device {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    lib.install_copy(path(copy)).unwrap();
    let note = lib.open_note(note_id.into()).unwrap();
    Device { root, lib, note }
}
/// Two devices holding the same new note.
fn pair() -> (Device, Device, CreateNoteRequest) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Sync".into(),
        notebook: None,
        kind: NoteKind::Note,
    };
    lib.create_note(request.clone()).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    let a = Device { root, lib, note };
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("c.syncr");
    a.note.export_copy(path(&copy)).unwrap();
    let b = device_with(&copy, &request.note_id);
    (a, b, request)
}
fn stroke(d: &Device, r: &CreateNoteRequest, x: f32) -> Id {
    let id = new_id();
    d.note
        .submit(marks(
            vec![MarkMutation::AddStroke {
                mark_id: id.clone(),
                sheet_id: r.first_sheet_id.clone(),
                samples: blob(x),
                brush: brush(),
            }],
            vec![r.operation_id.clone()],
        ))
        .unwrap();
    id
}
fn head(d: &Device, mark: &str) -> Id {
    d.note
        .snapshot()
        .unwrap()
        .strokes
        .into_iter()
        .find(|s| s.id == mark)
        .unwrap()
        .head
        .operation_id
}
fn transform(d: &Device, mark: &str, tx: f64) -> Command {
    let h = head(d, mark);
    marks(
        vec![MarkMutation::TransformStroke {
            mark_id: mark.into(),
            base: base(&h),
            transform: Affine {
                a: 1.0,
                b: 0.0,
                c: 0.0,
                d: 1.0,
                tx,
                ty: 0.0,
            },
        }],
        vec![h],
    )
}
fn delete(d: &Device, mark: &str) {
    let h = head(d, mark);
    d.note
        .submit(marks(
            vec![MarkMutation::DeleteStroke {
                mark_id: mark.into(),
                base: base(&h),
            }],
            vec![h],
        ))
        .unwrap();
}
fn undo(d: &Device, redo: bool) {
    let s = d.note.snapshot().unwrap();
    d.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: if redo {
                s.redo_dependencies
            } else {
                s.undo_dependencies
            },
            body: if redo {
                CommandBody::Redo {
                    target_operation_id: s.redo_target.unwrap(),
                }
            } else {
                CommandBody::Undo {
                    target_operation_id: s.undo_target.unwrap(),
                }
            },
        })
        .unwrap();
}
fn rename(d: &Device, title: &str) {
    let s = d.note.snapshot().unwrap();
    d.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: vec![s.metadata_head.operation_id.clone()],
            body: CommandBody::RenameNote {
                title: title.into(),
                base: base(&s.metadata_head.operation_id),
            },
        })
        .unwrap();
}
fn text(d: &Device, r: &CreateNoteRequest, body: &str) -> Id {
    let id = new_id();
    d.note
        .submit(marks(
            vec![MarkMutation::AddText {
                mark_id: id.clone(),
                sheet_id: r.first_sheet_id.clone(),
                role: TextRole::Document,
                origin_x: 0.0,
                origin_y: 0.0,
                width: 800.0,
                text: body.into(),
                style: TextStyle {
                    size: BrushSize::Medium,
                    colour: InkColour::Ink,
                },
            }],
            vec![r.operation_id.clone()],
        ))
        .unwrap();
    id
}
fn splice(d: &Device, block: &str, offset: u32, insert: &str) {
    let b = d
        .note
        .snapshot()
        .unwrap()
        .text_blocks
        .into_iter()
        .find(|b| b.id == block)
        .unwrap();
    d.note
        .submit(marks(
            vec![MarkMutation::SpliceText {
                mark_id: block.into(),
                base: base(&b.head.operation_id),
                offset,
                delete: 0,
                insert: insert.into(),
            }],
            vec![b.head.operation_id],
        ))
        .unwrap();
}
fn companion(d: &Device, block: &str, picture: bool) {
    let anchor = new_id();
    let item = new_id();
    let mut m = vec![CompanionMutation {
        id: anchor.clone(),
        base: Base::Absent,
        tombstone: false,
        body: CompanionBody::Anchor {
            target: AnchorTarget::Text {
                block_id: block.into(),
                start: 0,
                end: 4,
                detached: false,
            },
        },
    }];
    m.push(CompanionMutation {
        id: item,
        base: Base::Absent,
        tombstone: false,
        body: if picture {
            CompanionBody::Picture {
                anchor_id: anchor,
                title: "Box".into(),
                svg: filter_picture("<svg viewBox=\"0 0 320 200\"><g data-step=\"1\" data-caption=\"Start\"><line x1=\"10\" y1=\"20\" x2=\"100\" y2=\"20\" stroke=\"var(--ink)\"/><text x=\"10\" y=\"40\">Label</text></g></svg>".into()).unwrap(),
            }
        } else {
            CompanionBody::MarginNote {
                anchor_id: anchor,
                kind: MarginKind::Flag,
                text: "Why?".into(),
                reader: "Reader".into(),
                state: MarginState::Pinned,
                sources: vec![],
            }
        },
    });
    let dep = d
        .note
        .snapshot()
        .unwrap()
        .text_blocks
        .into_iter()
        .find(|b| b.id == block)
        .unwrap()
        .head
        .operation_id;
    d.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: vec![dep],
            body: CommandBody::CompanionMarks { mutations: m },
        })
        .unwrap();
}
fn image(d: &Device, r: &CreateNoteRequest) -> Id {
    let id = new_id();
    d.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: vec![r.operation_id.clone()],
            body: CommandBody::ImageMarks {
                mutations: vec![ImageMutation::Add {
                    image_id: id.clone(),
                    bytes: png(),
                    media_type: ImageMediaType::Png,
                    pixel_width: 1,
                    pixel_height: 1,
                    placement: ImagePlacement::Canvas {
                        sheet_id: r.first_sheet_id.clone(),
                        x: 1.0,
                        y: 2.0,
                        width: 10.0,
                        height: 10.0,
                    },
                }],
            },
        })
        .unwrap();
    id
}
/// Copies `from` to a file and merges it into `to`.
fn merge(from: &Device, to: &Device) -> MergeReport {
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("copy.syncr");
    from.note.export_copy(path(&copy)).unwrap();
    to.note.merge_copy(path(&copy)).unwrap()
}
fn norm(d: &Device) -> NoteSnapshot {
    let mut s = d.note.snapshot().unwrap();
    s.cursor = EventCursor {
        stream_id: String::new(),
        sequence: 0,
    };
    s.local_sequence = 0;
    s.undo_target = None;
    s.redo_target = None;
    s.undo_dependencies.clear();
    s.redo_dependencies.clear();
    s.strokes.sort_by(|a, b| a.id.cmp(&b.id));
    s.text_blocks.sort_by(|a, b| a.id.cmp(&b.id));
    s.companion_items.sort_by(|a, b| a.id.cmp(&b.id));
    s.images.sort_by(|a, b| a.id.cmp(&b.id));
    s
}
fn converge(a: &Device, b: &Device) {
    let r1 = merge(a, b);
    let r2 = merge(b, a);
    assert!(
        r1.rejected.is_empty() && r2.rejected.is_empty(),
        "{r1:?} {r2:?}"
    );
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    assert_eq!(norm(a), norm(b));
}

#[test]
fn sha256_known_vectors() {
    assert_eq!(
        sha256_hex(b""),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(
        sha256_hex(b"abc"),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    assert_eq!(
        sha256_hex(&[b'a'; 1000]),
        "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3"
    );
}

#[test]
fn digest_is_order_independent_cached_and_changes_on_commit() {
    let (a, b, r) = pair();
    let d0 = a.note.sync_digest().unwrap();
    assert_eq!(d0.operation_count, 1);
    assert_eq!(d0, b.note.sync_digest().unwrap());
    let s1 = stroke(&a, &r, 1.0);
    let d1 = a.note.sync_digest().unwrap();
    assert_eq!(d1.operation_count, 2);
    assert_ne!(d0.digest, d1.digest);
    assert_eq!(a.lib.note_sync_digest(r.note_id.clone()).unwrap(), d1);
    let _ = stroke(&b, &r, 5.0);
    let _ = s1;
    converge(&a, &b);
    assert_eq!(a.note.sync_digest().unwrap().operation_count, 3);
    // A closed note reports the same digest read-only.
    let d = a.note.sync_digest().unwrap();
    a.note.close_note().unwrap();
    assert_eq!(a.lib.note_sync_digest(r.note_id.clone()).unwrap(), d);
}

#[test]
fn merges_every_kind_of_operation_both_ways() {
    let (a, b, r) = pair();
    // Device A: strokes, transform, delete, text, companion margin note, rename.
    let s1 = stroke(&a, &r, 1.0);
    let s2 = stroke(&a, &r, 10.0);
    a.note.submit(transform(&a, &s1, 5.0)).unwrap();
    delete(&a, &s2);
    let block = text(&a, &r, "Hello cache world");
    splice(&a, &block, 5, ", big");
    companion(&a, &block, false);
    rename(&a, "From A");
    // Device B: its own strokes, a picture needs a text block it has not seen yet.
    let s3 = stroke(&b, &r, 20.0);
    let img = image(&b, &r);
    undo(&b, false);
    undo(&b, true);
    let first = merge(&a, &b);
    assert_eq!(first.duplicates, 0);
    assert!(first.applied >= 8, "{first:?}");
    assert_eq!(merge(&a, &b).applied, 0);
    companion(&b, &block, true);
    merge(&b, &a);
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    assert_eq!(norm(&a), norm(&b));
    let snap = a.note.snapshot().unwrap();
    assert_eq!(snap.title, "From A");
    assert!(snap.strokes.iter().any(|s| s.id == s3));
    assert!(snap.strokes.iter().find(|s| s.id == s2).unwrap().tombstone);
    assert_eq!(snap.companion_items.len(), 4);
    // Samples and image bytes arrived with the operations.
    assert_eq!(b.note.get_samples(s1.clone()).unwrap(), blob(1.0));
    assert_eq!(a.note.get_samples(s3).unwrap(), blob(20.0));
    assert_eq!(a.note.get_image_bytes(img).unwrap(), png());
    // Authors are kept: B's view of A's stroke names A's author.
    let author = a.lib.author_id();
    assert_eq!(
        b.note
            .snapshot()
            .unwrap()
            .strokes
            .iter()
            .find(|s| s.id == s1)
            .unwrap()
            .author_id,
        author
    );
}

#[test]
fn merge_is_idempotent_and_converges_with_notebook_and_archive() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    let s = a.note.snapshot().unwrap();
    a.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: vec![s.metadata_head.operation_id.clone()],
            body: CommandBody::SetNotebook {
                notebook: Some("Work".into()),
                base: base(&s.metadata_head.operation_id),
            },
        })
        .unwrap();
    stroke(&b, &r, 2.0);
    converge(&a, &b);
    assert_eq!(a.note.snapshot().unwrap().notebook.as_deref(), Some("Work"));
    let again = merge(&a, &b);
    assert_eq!((again.applied, again.rejected.len()), (0, 0));
    assert!(again.duplicates >= 3);
}

#[test]
fn conflicting_move_is_rejected_on_the_merger_and_kept_in_the_report() {
    let (a, b, r) = pair();
    let s = stroke(&a, &r, 1.0);
    merge(&a, &b);
    let mac = transform(&a, &s, 100.0);
    a.note.submit(mac.clone()).unwrap();
    let phone = transform(&b, &s, 7.0);
    b.note.submit(phone.clone()).unwrap();
    let report = merge(&b, &a);
    assert_eq!(report.applied, 0);
    assert_eq!(report.rejected.len(), 1);
    let rej = &report.rejected[0];
    assert_eq!(rej.operation_id, phone.operation_id);
    assert_eq!(rej.author_id, b.lib.author_id());
    assert_eq!(rej.reason, "stale base");
    assert!(!rej.canonical_hex.is_empty() && rej.payload_json.contains("TransformStroke"));
    // A keeps its version.
    let t = a
        .note
        .snapshot()
        .unwrap()
        .strokes
        .into_iter()
        .find(|v| v.id == s)
        .unwrap()
        .transform;
    assert_eq!(t.tx, 100.0);
}

#[test]
fn a_dependent_of_a_rejected_operation_is_rejected_too_and_the_merge_continues() {
    let (a, b, r) = pair();
    let s = stroke(&a, &r, 1.0);
    merge(&a, &b);
    a.note.submit(transform(&a, &s, 100.0)).unwrap();
    b.note.submit(transform(&b, &s, 7.0)).unwrap();
    undo(&b, false);
    let fresh = stroke(&b, &r, 50.0);
    let report = merge(&b, &a);
    assert!(!report.rejected.is_empty());
    assert!(
        a.note
            .snapshot()
            .unwrap()
            .strokes
            .iter()
            .any(|v| v.id == fresh)
    );
    assert_eq!(report.applied, 1);
}

#[test]
fn rebase_keeps_nonconflicting_local_ops_and_reports_the_rest() {
    let (mac, phone, r) = pair();
    let s = stroke(&mac, &r, 1.0);
    merge(&mac, &phone);
    mac.note.submit(transform(&mac, &s, 100.0)).unwrap();
    let mac_only = stroke(&mac, &r, 2.0);
    let conflicting = transform(&phone, &s, 7.0);
    phone.note.submit(conflicting.clone()).unwrap();
    let keep = stroke(&phone, &r, 3.0);
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("mac.syncr");
    mac.note.export_copy(path(&copy)).unwrap();
    let before = std::fs::read(phone.root.path().join(format!("{}.syncr", r.note_id))).ok();
    assert!(before.is_some());
    // The note must be closed.
    assert!(matches!(
        phone.lib.rebase_onto_copy(r.note_id.clone(), path(&copy)),
        Err(CoreError::NoteLocked { .. })
    ));
    phone.note.close_note().unwrap();
    drop(phone.note.clone());
    let report = phone
        .lib
        .rebase_onto_copy(r.note_id.clone(), path(&copy))
        .unwrap();
    assert_eq!(report.rejected.len(), 1);
    assert_eq!(report.rejected[0].operation_id, conflicting.operation_id);
    assert_eq!(report.applied, 1);
    let reopened = phone.lib.open_note(r.note_id.clone()).unwrap();
    let snap = reopened.snapshot().unwrap();
    for id in [&s, &mac_only, &keep] {
        assert!(snap.strokes.iter().any(|v| &v.id == id));
    }
    assert_eq!(
        snap.strokes
            .iter()
            .find(|v| v.id == s)
            .unwrap()
            .transform
            .tx,
        100.0
    );
    // Backup exists and still holds the conflicting local op.
    let backups: Vec<_> = std::fs::read_dir(phone.root.path().join(".backups"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    assert_eq!(backups.len(), 1);
    let b = rusqlite::Connection::open(&backups[0]).unwrap();
    let n: u32 = b
        .query_row(
            "SELECT COUNT(*) FROM operations WHERE id=?1",
            [&conflicting.operation_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(n, 1);
    // After the rebase, both sides converge with one more merge of the phone's own ops.
    let after = Device {
        root: tempfile::tempdir().unwrap(),
        lib: phone.lib.clone(),
        note: reopened,
    };
    let again = merge(&after, &mac);
    assert!(again.rejected.is_empty());
    assert_eq!(
        after.note.sync_digest().unwrap(),
        mac.note.sync_digest().unwrap()
    );
    // No staging files are left.
    assert!(std::fs::read_dir(phone.root.path()).unwrap().all(|e| {
        !e.unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".syncr-rebase-")
    }));
}

#[test]
fn hostile_copies_are_refused_and_change_nothing() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    let before = a.note.sync_digest().unwrap();
    let tmp = tempfile::tempdir().unwrap();
    // Garbage file.
    let garbage = tmp.path().join("g.syncr");
    std::fs::write(&garbage, b"this is not a database at all, definitely not").unwrap();
    assert!(a.note.merge_copy(path(&garbage)).is_err());
    assert!(a.lib.install_copy(path(&garbage)).is_err());
    // A different note.
    let other_root = tempfile::tempdir().unwrap();
    let other_lib = open_library(path(other_root.path())).unwrap();
    let mut other = r.clone();
    other.operation_id = new_id();
    other.note_id = new_id();
    other_lib.create_note(other.clone()).unwrap();
    let other_note = other_lib.open_note(other.note_id.clone()).unwrap();
    let wrong = tmp.path().join("w.syncr");
    other_note.export_copy(path(&wrong)).unwrap();
    assert!(a.note.merge_copy(path(&wrong)).is_err());
    // Same note ID, different creation operation.
    let forged_root = tempfile::tempdir().unwrap();
    let forged_lib = open_library(path(forged_root.path())).unwrap();
    let mut forged = r.clone();
    forged.operation_id = new_id();
    forged_lib.create_note(forged.clone()).unwrap();
    let forged_note = forged_lib.open_note(forged.note_id.clone()).unwrap();
    let f = tmp.path().join("f.syncr");
    forged_note.export_copy(path(&f)).unwrap();
    assert!(a.note.merge_copy(path(&f)).is_err());
    // Newer schema than supported.
    let newer = tmp.path().join("n.syncr");
    b.note.export_copy(path(&newer)).unwrap();
    {
        let db = rusqlite::Connection::open(&newer).unwrap();
        db.pragma_update(None, "user_version", 99).unwrap();
    }
    assert!(matches!(
        a.note.merge_copy(path(&newer)),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert_eq!(a.note.sync_digest().unwrap(), before);
    // Install never overwrites.
    let ok = tmp.path().join("ok.syncr");
    b.note.export_copy(path(&ok)).unwrap();
    assert!(matches!(
        a.lib.install_copy(path(&ok)),
        Err(CoreError::IdCollision { .. })
    ));
    assert_eq!(a.lib.list_notes().unwrap().len(), 1);
}

#[test]
fn tampered_canonical_bytes_are_rejected() {
    let (a, b, r) = pair();
    let s = stroke(&a, &r, 1.0);
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("t.syncr");
    a.note.export_copy(path(&copy)).unwrap();
    {
        // Change the stored stroke's samples in the payload so canonical no longer matches.
        let db = rusqlite::Connection::open(&copy).unwrap();
        let payload: String = db
            .query_row(
                "SELECT payload FROM operations WHERE local_sequence=?1",
                [2u64.to_be_bytes().as_slice()],
                |r| r.get(0),
            )
            .unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&payload).unwrap();
        v["body"]["Marks"]["mutations"][0]["AddStroke"]["samples"] = serde_json::json!(blob(9.0));
        db.execute(
            "UPDATE operations SET payload=?1 WHERE local_sequence=?2",
            rusqlite::params![v.to_string(), 2u64.to_be_bytes().as_slice()],
        )
        .unwrap();
    }
    let report = b.note.merge_copy(path(&copy)).unwrap();
    assert_eq!(report.applied, 0);
    assert_eq!(report.rejected.len(), 1);
    assert_eq!(report.rejected[0].reason, "canonical mismatch");
    assert!(!b.note.snapshot().unwrap().strokes.iter().any(|v| v.id == s));
}

#[test]
fn install_places_a_copy_and_refuses_deleted_notes() {
    let (a, _b, r) = pair();
    stroke(&a, &r, 1.0);
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("c.syncr");
    a.note.export_copy(path(&copy)).unwrap();
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let summary = lib.install_copy(path(&copy)).unwrap();
    assert_eq!(summary.id, r.note_id);
    assert_eq!(summary.local_sequence, 2);
    lib.delete_note(r.note_id.clone()).unwrap();
    assert!(matches!(
        lib.install_copy(path(&copy)),
        Err(CoreError::IdCollision { .. })
    ));
    lib.purge_note(r.note_id.clone()).unwrap();
    assert!(matches!(
        lib.install_copy(path(&copy)),
        Err(CoreError::IdCollision { .. })
    ));
}

// ---- crash points in rebase --------------------------------------------------------------

const CRASH_NOTE: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAW";

/// Child entry point: builds a mac copy and a diverged phone note in `SYNCR_REBASE_ROOT`, then
/// rebases it, dying at `SYNCR_CRASH_STAGE`.
#[test]
fn rebase_crash_probe() {
    let Ok(root) = std::env::var("SYNCR_REBASE_ROOT") else {
        return;
    };
    let root = Path::new(&root);
    let lib = open_library(path(&root.join("phone"))).unwrap();
    lib.rebase_onto_copy(CRASH_NOTE.into(), path(&root.join("mac.syncr")))
        .unwrap();
}

#[test]
fn rebase_survives_a_crash_at_every_step() {
    for stage in [
        "rebase_after_backup",
        "rebase_before_replace",
        "rebase_after_replace",
    ] {
        let (mac, phone, mut r) = {
            let root = tempfile::tempdir().unwrap();
            let lib = open_library(path(root.path())).unwrap();
            r_fixed(&lib);
            let note = lib.open_note(CRASH_NOTE.into()).unwrap();
            let a = Device { root, lib, note };
            let tmp = tempfile::tempdir().unwrap();
            let copy = tmp.path().join("c.syncr");
            a.note.export_copy(path(&copy)).unwrap();
            let b = device_with(&copy, CRASH_NOTE);
            let r = CreateNoteRequest {
                operation_id: a
                    .note
                    .snapshot()
                    .unwrap()
                    .sheets
                    .first()
                    .map(|_| String::new())
                    .unwrap(),
                note_id: CRASH_NOTE.into(),
                first_sheet_id: a.note.snapshot().unwrap().sheets[0].id.clone(),
                title: "x".into(),
                notebook: None,
                kind: NoteKind::Note,
            };
            (a, b, r)
        };
        // The creation operation ID is the first logged operation; dependencies use it.
        r.operation_id = CRASH_CREATE.into();
        let s = stroke(&mac, &r, 1.0);
        merge(&mac, &phone);
        mac.note.submit(transform(&mac, &s, 100.0)).unwrap();
        phone.note.submit(transform(&phone, &s, 7.0)).unwrap();
        let keep = stroke(&phone, &r, 3.0);
        let work = tempfile::tempdir().unwrap();
        mac.note
            .export_copy(path(&work.path().join("mac.syncr")))
            .unwrap();
        let phone_digest = phone.note.sync_digest().unwrap();
        phone.note.close_note().unwrap();
        let phone_dir = work.path().join("phone");
        std::fs::create_dir_all(&phone_dir).unwrap();
        let src = phone.root.path().join(format!("{CRASH_NOTE}.syncr"));
        let old_bytes = std::fs::read(&src).unwrap();
        drop(phone.note);
        drop(phone.lib);
        for f in std::fs::read_dir(phone.root.path()).unwrap() {
            let f = f.unwrap();
            if f.path().is_file() {
                std::fs::copy(f.path(), phone_dir.join(f.file_name())).unwrap();
            }
        }
        let out = ProcessCommand::new(std::env::current_exe().unwrap())
            .args(["--exact", "sync_tests::rebase_crash_probe", "--nocapture"])
            .env("SYNCR_REBASE_ROOT", work.path())
            .env("SYNCR_CRASH_STAGE", stage)
            .output()
            .unwrap();
        assert_eq!(
            out.status.code(),
            Some(91),
            "{stage}: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        // Reopen: either the old file or the rebased one, never anything else, and a backup.
        let lib = open_library(path(&phone_dir)).unwrap();
        let note = lib.open_note(CRASH_NOTE.into()).unwrap();
        let digest = note.sync_digest().unwrap();
        let ids: Vec<_> = note
            .snapshot()
            .unwrap()
            .strokes
            .iter()
            .map(|v| v.id.clone())
            .collect();
        assert!(ids.contains(&s) && ids.contains(&keep), "{stage}");
        if stage == "rebase_after_replace" {
            assert_ne!(digest, phone_digest, "{stage}");
        } else {
            assert_eq!(digest, phone_digest, "{stage}");
            let _ = &old_bytes;
        }
        assert_eq!(
            std::fs::read_dir(phone_dir.join(".backups"))
                .unwrap()
                .count(),
            1,
            "{stage}"
        );
        assert!(std::fs::read_dir(&phone_dir).unwrap().all(|e| {
            !e.unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".syncr-rebase-")
        }));
    }
}

const CRASH_CREATE: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
fn r_fixed(lib: &SyncrLibrary) {
    lib.create_note(CreateNoteRequest {
        operation_id: CRASH_CREATE.into(),
        note_id: CRASH_NOTE.into(),
        first_sheet_id: new_id(),
        title: "Crash".into(),
        notebook: None,
        kind: NoteKind::Note,
    })
    .unwrap();
}

#[test]
fn two_hundred_random_exchanges_converge_without_duplicates() {
    let (a, b, r) = pair();
    let mut seed = 0x2545F4914F6CDD1Du64;
    let mut next = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for round in 0..200u32 {
        for dev in [&a, &b] {
            if next() % 2 == 0 {
                stroke(dev, &r, round as f32);
            }
        }
        match next() % 3 {
            0 => {
                merge(&a, &b);
            }
            1 => {
                merge(&b, &a);
            }
            _ => {
                merge(&a, &b);
                merge(&b, &a);
            }
        }
    }
    converge(&a, &b);
    let n = a.note.sync_digest().unwrap().operation_count;
    assert_eq!(n, b.note.sync_digest().unwrap().operation_count);
    let again = merge(&a, &b);
    assert_eq!(again.applied, 0);
}

// ---- a panic inside the core must not end sync --------------------------------------------

#[test]
fn poisoned_locks_recover_and_sync_continues() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("a.syncr");
    a.note.export_copy(path(&copy)).unwrap();

    // The catalog lock, then the open note's lock; the note's in-memory state is also left
    // damaged (a stroke missing, a stale digest, a stale sequence) as a mid-update panic could.
    crate::library::tests::poison_catalog(&b.lib);
    crate::library::tests::poison_note(&b.note);

    // Every sync call works again, and the state is what the file holds.
    let report = b.note.merge_copy(path(&copy)).unwrap();
    assert_eq!((report.applied, report.rejected.len()), (1, 0));
    assert_eq!(b.note.snapshot().unwrap().strokes.len(), 1);
    assert_eq!(b.note.sync_digest().unwrap(), a.note.sync_digest().unwrap());
    assert_eq!(
        b.lib.note_sync_digest(r.note_id.clone()).unwrap(),
        a.note.sync_digest().unwrap()
    );
    // Writes after the recovery commit normally.
    stroke(&b, &r, 2.0);
    assert_eq!(b.note.snapshot().unwrap().strokes.len(), 2);
    assert_eq!(b.lib.list_notes().unwrap().len(), 1);

    // Poisoned again, and a copy still installs and rebases.
    crate::library::tests::poison_catalog(&b.lib);
    let c = tempfile::tempdir().unwrap();
    let other = open_library(path(c.path())).unwrap();
    let copy2 = tmp.path().join("b.syncr");
    b.note.export_copy(path(&copy2)).unwrap();
    other.install_copy(path(&copy2)).unwrap();
    b.note.close_note().unwrap();
    b.lib
        .rebase_onto_copy(r.note_id.clone(), path(&copy))
        .unwrap();
}

// ---- delta sync -----------------------------------------------------------------------

/// Delta from `from` for what `to` lacks, merged into `to`. Returns the receipt and report.
fn delta(from: &Device, to: &Device) -> (DeltaReceipt, MergeReport) {
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d.syncr-delta");
    let heads = to.note.sync_heads().unwrap();
    let receipt = from.note.export_delta(heads, path(&file)).unwrap();
    assert_eq!(file.exists(), receipt.anchored);
    let report = if receipt.anchored {
        let merged = to.note.merge_delta(path(&file)).unwrap();
        assert!(merged.complete, "{merged:?}");
        merged.report
    } else {
        MergeReport {
            applied: 0,
            duplicates: 0,
            rejected: vec![],
        }
    };
    (receipt, report)
}

#[test]
fn a_delta_carries_only_the_new_operations_and_converges() {
    let (a, b, r) = pair();
    let s1 = stroke(&a, &r, 1.0);
    converge(&a, &b);
    // New on A: a stroke, a move, an image (bytes travel as an extra), a rename and text.
    let s2 = stroke(&a, &r, 2.0);
    a.note.submit(transform(&a, &s1, 5.0)).unwrap();
    let img = image(&a, &r);
    rename(&a, "Delta");
    let block = text(&a, &r, "Hello delta");
    splice(&a, &block, 5, ",");
    let (receipt, report) = delta(&a, &b);
    assert!(receipt.anchored);
    assert_eq!(
        (report.applied, report.rejected.len()),
        (receipt.operations, 0)
    );
    assert!(report.applied >= 6, "{report:?}");
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    assert_eq!(norm(&a), norm(&b));
    assert_eq!(b.note.get_samples(s2).unwrap(), blob(2.0));
    assert_eq!(b.note.get_image_bytes(img).unwrap(), png());
    // A repeat offers nothing new.
    let (_, again) = delta(&a, &b);
    assert_eq!((again.applied, again.rejected.len()), (0, 0));
}

#[test]
fn deltas_both_ways_after_independent_edits_converge() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    converge(&a, &b);
    stroke(&a, &r, 2.0);
    rename(&a, "A side");
    stroke(&b, &r, 3.0);
    stroke(&b, &r, 4.0);
    let (_, to_b) = delta(&a, &b);
    let (_, to_a) = delta(&b, &a);
    assert!(to_b.rejected.is_empty() && to_a.rejected.is_empty());
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    assert_eq!(norm(&a), norm(&b));
    assert_eq!(delta(&a, &b).1.applied, 0);
}

#[test]
fn a_delta_without_heads_is_not_written_and_a_stranger_gets_everything() {
    let (a, _b, r) = pair();
    stroke(&a, &r, 1.0);
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d");
    let none = a.note.export_delta(vec![], path(&file)).unwrap();
    assert!(!none.anchored && !file.exists());
    let stranger = AuthorHead {
        author_id: new_id(),
        newest_id: new_id(),
    };
    let all = a.note.export_delta(vec![stranger], path(&file)).unwrap();
    assert!(all.anchored && all.operations == 2 && file.exists());
}

#[test]
fn a_delta_missing_an_earlier_operation_reports_it_and_the_copy_fallback_converges() {
    let (a, b, r) = pair();
    let s1 = stroke(&a, &r, 1.0);
    std::thread::sleep(std::time::Duration::from_millis(5));
    // B never got s1; A's move of it depends on it. B claims it has A's work up to just after s1.
    a.note.submit(transform(&a, &s1, 5.0)).unwrap();
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d");
    let ms = s1.parse::<ulid::Ulid>().unwrap().timestamp_ms();
    let claim = AuthorHead {
        author_id: a.lib.author_id(),
        newest_id: ulid::Ulid::from_parts(ms + 1, 0).to_string(),
    };
    let receipt = a.note.export_delta(vec![claim], path(&file)).unwrap();
    assert_eq!(receipt.operations, 1);
    let merged = b.note.merge_delta(path(&file)).unwrap();
    let report = merged.report;
    assert!(!merged.complete);
    assert_eq!((report.applied, report.rejected.len()), (0, 1));
    assert_eq!(report.rejected[0].reason, "missing dependency");
    assert_ne!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    converge(&a, &b);
}

#[test]
fn a_delta_that_skips_an_independent_operation_is_reported_incomplete() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    std::thread::sleep(std::time::Duration::from_millis(5));
    stroke(&a, &r, 2.0);
    // B claims to have A's work up to just before A's newest operation but holds none of it: the
    // delta carries only the newest, which applies cleanly yet leaves B short of A's count.
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d");
    let head = a.note.sync_heads().unwrap().remove(0);
    let ms = head.newest_id.parse::<ulid::Ulid>().unwrap().timestamp_ms();
    let claim = AuthorHead {
        author_id: head.author_id,
        newest_id: ulid::Ulid::from_parts(ms - 1, 0).to_string(),
    };
    let receipt = a.note.export_delta(vec![claim], path(&file)).unwrap();
    assert_eq!(receipt.operations, 1);
    let merged = b.note.merge_delta(path(&file)).unwrap();
    assert_eq!(
        (merged.report.applied, merged.report.rejected.len()),
        (1, 0)
    );
    assert!(!merged.complete);
    converge(&a, &b);
}

#[test]
fn hostile_deltas_are_refused_and_change_nothing() {
    let (a, b, r) = pair();
    let (_, other_b, _) = pair();
    stroke(&a, &r, 1.0);
    converge(&a, &b);
    stroke(&a, &r, 2.0);
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d");
    let receipt = a
        .note
        .export_delta(b.note.sync_heads().unwrap(), path(&file))
        .unwrap();
    assert!(receipt.anchored);
    let good = std::fs::read(&file).unwrap();
    let before = b.note.sync_digest().unwrap();
    let try_bytes = |bytes: &[u8], target: &Device| {
        let p = tmp.path().join("h");
        std::fs::write(&p, bytes).unwrap();
        target.note.merge_delta(path(&p))
    };
    assert!(try_bytes(b"", &b).is_err());
    assert!(try_bytes(b"SYDL1\n", &b).is_err());
    assert!(try_bytes(b"not a delta at all", &b).is_err());
    for cut in [10, good.len() / 2, good.len() - 1] {
        assert!(try_bytes(&good[..cut], &b).is_err(), "cut at {cut}");
    }
    let mut huge = good.clone();
    huge[6..10].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(try_bytes(&huge, &b).is_err());
    // A delta of another note.
    assert!(try_bytes(&good, &other_b).is_err());
    assert_eq!(b.note.sync_digest().unwrap(), before);
    // A flipped byte near the end is never applied as a valid operation.
    let mut flipped = good.clone();
    let at = good.len() - 40;
    flipped[at] ^= 0xff;
    if let Ok(report) = try_bytes(&flipped, &b) {
        assert_eq!(report.report.applied, 0, "{report:?}");
    }
    assert_eq!(b.note.sync_digest().unwrap(), before);
    // The good one still applies.
    let merged = b.note.merge_delta(path(&file)).unwrap();
    assert!(merged.complete);
    let report = merged.report;
    assert_eq!((report.applied, report.rejected.len()), (1, 0));
}

#[test]
fn heads_read_from_closed_notes_and_name_each_author_once() {
    let (a, b, r) = pair();
    stroke(&a, &r, 1.0);
    stroke(&a, &r, 2.0);
    stroke(&b, &r, 3.0);
    converge(&a, &b);
    let open = a.note.sync_heads().unwrap();
    assert_eq!(open.len(), 2);
    assert!(open.iter().any(|h| h.author_id == a.lib.author_id()));
    a.note.close_note().unwrap();
    assert_eq!(a.lib.note_sync_heads(r.note_id.clone()).unwrap(), open);
    assert!(a.lib.note_sync_heads(new_id()).is_err());
}

/// The 10 MB fixture of the brief: what one announce costs, whole copy against delta.
#[test]
fn a_one_stroke_change_to_a_ten_megabyte_note_sends_a_few_hundred_bytes() {
    let (a, b, r) = pair();
    let big = |seed: f32| {
        let n = 20_000usize;
        encode_samples(SampleChannels {
            x: (0..n).map(|i| seed + i as f32 * 0.01).collect(),
            y: (0..n).map(|i| (i as f32 * 0.02).sin() * 50.0).collect(),
            pressure: (0..n).map(|i| ((i * 7) % 100) as f32 / 100.0).collect(),
            elapsed_ms: (0..n as u32).map(|i| i * 4).collect(),
            tilt: None,
            orientation: None,
        })
        .unwrap()
    };
    for i in 0..6 {
        a.note
            .submit(marks(
                vec![MarkMutation::AddStroke {
                    mark_id: new_id(),
                    sheet_id: r.first_sheet_id.clone(),
                    samples: big(i as f32),
                    brush: brush(),
                }],
                vec![r.operation_id.clone()],
            ))
            .unwrap();
    }
    converge(&a, &b);
    stroke(&a, &r, 99.0);
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("copy.syncr");
    a.note.export_copy(path(&copy)).unwrap();
    let copy_bytes = std::fs::metadata(&copy).unwrap().len();
    let (receipt, report) = delta(&a, &b);
    eprintln!(
        "announce cost: copy {copy_bytes} bytes, delta {} bytes ({} operation)",
        receipt.bytes, receipt.operations
    );
    assert!(copy_bytes > 8_000_000, "fixture is {copy_bytes}");
    assert!(receipt.anchored && report.applied == 1);
    assert!(receipt.bytes < 2_000);
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
}

/// S.4: a big merge and a delta arrive while the pen keeps committing strokes on another thread.
/// Nothing may panic or poison a lock, and the note ends up holding every operation exactly once.
#[test]
fn big_merges_and_deltas_mid_stroke_neither_panic_nor_lose_operations() {
    let (a, b, r) = pair();
    let big = |seed: f32| {
        let n = 20_000usize;
        encode_samples(SampleChannels {
            x: (0..n).map(|i| seed + i as f32 * 0.01).collect(),
            y: (0..n).map(|i| (i as f32 * 0.02).sin() * 50.0).collect(),
            pressure: vec![0.5; n],
            elapsed_ms: (0..n as u32).map(|i| i * 4).collect(),
            tilt: None,
            orientation: None,
        })
        .unwrap()
    };
    for i in 0..6 {
        a.note
            .submit(marks(
                vec![MarkMutation::AddStroke {
                    mark_id: new_id(),
                    sheet_id: r.first_sheet_id.clone(),
                    samples: big(i as f32),
                    brush: brush(),
                }],
                vec![r.operation_id.clone()],
            ))
            .unwrap();
    }
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("big.syncr");
    a.note.export_copy(path(&copy)).unwrap();
    let pen = {
        let note = b.note.clone();
        let sheet = r.first_sheet_id.clone();
        let creation = r.operation_id.clone();
        std::thread::spawn(move || {
            for i in 0..150 {
                note.submit(marks(
                    vec![MarkMutation::AddStroke {
                        mark_id: new_id(),
                        sheet_id: sheet.clone(),
                        samples: blob(i as f32),
                        brush: brush(),
                    }],
                    vec![creation.clone()],
                ))
                .unwrap();
            }
        })
    };
    let report = b.note.merge_copy(path(&copy)).unwrap();
    assert_eq!((report.applied, report.rejected.len()), (6, 0));
    // A delta of A's next stroke arrives meanwhile, and B reads its heads and digest as the link does.
    stroke(&a, &r, 7.0);
    let file = tmp.path().join("d");
    let heads = b.note.sync_heads().unwrap();
    let sent = a.note.export_delta(heads, path(&file)).unwrap();
    assert!(sent.anchored);
    let merged = b.note.merge_delta(path(&file)).unwrap();
    assert_eq!(merged.report.rejected.len(), 0);
    let _ = (
        b.note.sync_digest().unwrap(),
        b.lib.note_sync_heads(r.note_id.clone()).unwrap(),
    );
    pen.join().expect("the pen thread must not panic");
    assert_eq!(b.note.snapshot().unwrap().strokes.len(), 6 + 1 + 150);
    merge(&b, &a);
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
}
