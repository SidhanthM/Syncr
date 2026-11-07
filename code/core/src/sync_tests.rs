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
    Brush {
        family: BrushFamily::Pen,
        size: BrushSize::Medium,
        colour: InkColour::Ink,
    }
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
