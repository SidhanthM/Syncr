//! Strokes keep the look they were drawn with (look-and-ink-v1 part 1): additive tags, a
//! note's version rises only on first use, and nothing about another stroke changes a mesh.
mod common;
use common::{base, brush, path};
use std::sync::Arc;
use syncr_core::*;

fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    CreateNoteRequest,
    Arc<Note>,
) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let r = common::request(None);
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    (root, lib, r, note)
}

/// A curved 40-sample stroke with varying pressure, so looks and feels all show in the mesh.
fn curve() -> Vec<u8> {
    let n = 40usize;
    encode_samples(SampleChannels {
        x: (0..n).map(|i| 100.0 + i as f32 * 9.0).collect(),
        y: (0..n)
            .map(|i| 300.0 + (i as f32 * 0.25).sin() * 60.0)
            .collect(),
        pressure: (0..n).map(|i| 0.2 + 0.6 * (i as f32 / n as f32)).collect(),
        elapsed_ms: (0..n).map(|i| i as u32 * 8).collect(),
        tilt: None,
        orientation: None,
    })
    .unwrap()
}

fn add(note: &Note, r: &CreateNoteRequest, brush: Brush) -> (Id, Result<Receipt, CoreError>) {
    let id = new_id();
    let receipt = note.submit(Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: id.clone(),
                sheet_id: r.first_sheet_id.clone(),
                samples: curve(),
                brush,
            }],
        },
    });
    (id, receipt)
}

fn version(root: &std::path::Path, note_id: &str) -> u32 {
    let db = rusqlite::Connection::open(root.join(format!("{note_id}.syncr"))).unwrap();
    db.pragma_query_value(None, "user_version", |r| r.get(0))
        .unwrap()
}

fn mesh_of(note: &Note, id: &str) -> StrokeMesh {
    let snap = note.snapshot().unwrap();
    let stroke = snap.strokes.iter().find(|s| s.id == id).unwrap();
    tessellate_stroke(
        note.get_samples(id.into()).unwrap(),
        default_stroke_style(stroke.brush.clone()),
        stroke.transform.clone(),
    )
    .unwrap()
}

fn with(look: Option<PenLook>) -> Brush {
    Brush { look, ..brush() }
}

#[test]
fn a_plain_stroke_leaves_the_note_at_version_three_and_a_new_look_raises_it_once() {
    let (root, lib, r, note) = setup();
    add(&note, &r, brush()).1.unwrap();
    add(&note, &r, with(Some(PenLook::Fountain))).1.unwrap(); // Fountain spelled out is not new
    assert_eq!(version(root.path(), &r.note_id), 3);
    let (ballpoint, receipt) = add(&note, &r, with(Some(PenLook::Ballpoint)));
    receipt.unwrap();
    assert_eq!(version(root.path(), &r.note_id), 8);
    add(&note, &r, brush()).1.unwrap();
    assert_eq!(version(root.path(), &r.note_id), 8); // never lowered
    let snap = note.snapshot().unwrap();
    let stored = snap.strokes.iter().find(|s| s.id == ballpoint).unwrap();
    assert_eq!(stored.brush.look, Some(PenLook::Ballpoint));
    note.close_note().unwrap();
    drop((note, lib));
    // The v8 note reopens and keeps its looks.
    let again = open_library(path(root.path())).unwrap();
    let note = again.open_note(r.note_id.clone()).unwrap();
    let snap = note.snapshot().unwrap();
    assert_eq!(
        snap.strokes
            .iter()
            .find(|s| s.id == ballpoint)
            .unwrap()
            .brush
            .look,
        Some(PenLook::Ballpoint)
    );
}

#[test]
fn feel_is_stored_as_drawn_and_retries_are_duplicates() {
    let (root, _lib, r, note) = setup();
    let b = Brush {
        feel: Some(PenFeel {
            pressure: 80,
            smoothing: 20,
            taper: false,
        }),
        ..brush()
    };
    let id = new_id();
    let command = Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: id.clone(),
                sheet_id: r.first_sheet_id.clone(),
                samples: curve(),
                brush: b.clone(),
            }],
        },
    };
    assert!(!note.submit(command.clone()).unwrap().duplicate);
    assert!(note.submit(command.clone()).unwrap().duplicate);
    // Same ID, other feel: different canonical bytes.
    let mut other = command.clone();
    if let CommandBody::Marks { mutations } = &mut other.body
        && let MarkMutation::AddStroke { brush, .. } = &mut mutations[0]
    {
        brush.feel = Some(PenFeel {
            pressure: 81,
            smoothing: 20,
            taper: false,
        });
    }
    assert!(matches!(
        note.submit(other),
        Err(CoreError::IdCollision { .. })
    ));
    assert_eq!(note.snapshot().unwrap().strokes[0].brush, b);
    assert_eq!(version(root.path(), &r.note_id), 8);
}

#[test]
fn bad_feel_and_highlighter_looks_are_refused_atomically() {
    let (_root, _lib, r, note) = setup();
    for bad in [
        Brush {
            feel: Some(PenFeel {
                pressure: 101,
                smoothing: 0,
                taper: true,
            }),
            ..brush()
        },
        Brush {
            family: BrushFamily::Highlighter,
            look: Some(PenLook::Ballpoint),
            ..brush()
        },
    ] {
        assert!(matches!(
            add(&note, &r, bad).1,
            Err(CoreError::InvalidInput { .. })
        ));
    }
    assert!(note.snapshot().unwrap().strokes.is_empty());
}

#[test]
fn switching_pens_never_changes_an_existing_strokes_mesh() {
    let (_root, _lib, r, note) = setup();
    let (first, receipt) = add(&note, &r, brush());
    receipt.unwrap();
    let before = mesh_of(&note, &first);
    for look in [PenLook::Ballpoint, PenLook::Fineliner, PenLook::Fountain] {
        add(&note, &r, with(Some(look))).1.unwrap();
    }
    add(
        &note,
        &r,
        Brush {
            feel: Some(PenFeel {
                pressure: 100,
                smoothing: 100,
                taper: false,
            }),
            ..brush()
        },
    )
    .1
    .unwrap();
    assert_eq!(mesh_of(&note, &first), before); // byte-identical
}

#[test]
fn each_look_and_feel_tessellates_differently_and_deterministically() {
    let (_root, _lib, r, note) = setup();
    let ids: Vec<Id> = [
        brush(),
        with(Some(PenLook::Ballpoint)),
        with(Some(PenLook::Fineliner)),
        Brush {
            feel: Some(PenFeel {
                pressure: 50,
                smoothing: 50,
                taper: true,
            }),
            ..brush()
        },
    ]
    .into_iter()
    .map(|b| {
        let (id, receipt) = add(&note, &r, b);
        receipt.unwrap();
        id
    })
    .collect();
    let meshes: Vec<_> = ids.iter().map(|id| mesh_of(&note, id)).collect();
    for i in 0..meshes.len() {
        assert_eq!(meshes[i], mesh_of(&note, &ids[i]));
        for j in i + 1..meshes.len() {
            assert_ne!(meshes[i].vertices, meshes[j].vertices, "{i} vs {j}");
        }
    }
    // No look and no feel is the historical Mac style, exactly.
    assert_eq!(
        default_stroke_style(brush()),
        default_stroke_style(with(Some(PenLook::Fountain)))
    );
    assert_eq!(default_stroke_style(brush()).smoothing_window_ms, 20);
    // The phone's tuned smoothing at default maps to 8 ms.
    let stored = default_stroke_style(Brush {
        feel: Some(PenFeel {
            pressure: 50,
            smoothing: 50,
            taper: true,
        }),
        ..brush()
    });
    assert_eq!(stored.smoothing_window_ms, 8);
    assert!((stored.pressure_damping_seconds - 0.009).abs() < 1e-6);
}

#[test]
fn a_phone_ballpoint_stroke_tessellates_the_same_on_the_mac() {
    // The Mac receives the stroke through the durable commit wire, then stores and draws it.
    let b = Brush {
        look: Some(PenLook::Ballpoint),
        feel: Some(PenFeel {
            pressure: 50,
            smoothing: 50,
            taper: true,
        }),
        ..brush()
    };
    let commit = StrokeCommit {
        note_id: new_id(),
        sheet_id: new_id(),
        stroke_id: new_id(),
        brush: b.clone(),
        space: CoordinateSpace::Canvas,
        samples: curve(),
    };
    let frames = encode_stroke_commit(commit.clone()).unwrap();
    let reassembler = StrokeReassembler::new();
    let mut out = None;
    for f in frames {
        out = reassembler.accept(f, 1).unwrap().or(out);
    }
    let received = out.expect("stroke completes");
    assert_eq!(received.brush, b);
    let identity = Affine {
        a: 1.0,
        b: 0.0,
        c: 0.0,
        d: 1.0,
        tx: 0.0,
        ty: 0.0,
    };
    assert_eq!(
        tessellate_stroke(commit.samples, default_stroke_style(b), identity.clone()).unwrap(),
        tessellate_stroke(
            received.samples,
            default_stroke_style(received.brush),
            identity
        )
        .unwrap()
    );
}

#[test]
fn looks_survive_merge_and_a_restyle_can_change_them_on_purpose() {
    let (root, _lib, r, note) = setup();
    let (id, receipt) = add(&note, &r, with(Some(PenLook::Fineliner)));
    receipt.unwrap();
    let copy = root.path().join("copy.syncr.export");
    note.export_copy(path(&copy)).unwrap();
    let other = tempfile::tempdir().unwrap();
    let other_lib = open_library(path(other.path())).unwrap();
    other_lib.install_copy(path(&copy)).unwrap();
    let theirs = other_lib.open_note(r.note_id.clone()).unwrap();
    let snap = theirs.snapshot().unwrap();
    assert_eq!(snap.strokes[0].brush.look, Some(PenLook::Fineliner));
    assert_eq!(version(other.path(), &r.note_id), 8);
    // Restyling is an explicit operation and keeps the look field it is given.
    let head = snap.strokes[0].head.operation_id.clone();
    theirs
        .submit(Command {
            operation_id: new_id(),
            dependencies: vec![head.clone()],
            body: CommandBody::Marks {
                mutations: vec![MarkMutation::RestyleStroke {
                    mark_id: id.clone(),
                    base: base(&head),
                    brush: with(Some(PenLook::Ballpoint)),
                }],
            },
        })
        .unwrap();
    assert_eq!(
        theirs.snapshot().unwrap().strokes[0].brush.look,
        Some(PenLook::Ballpoint)
    );
    let _ = Arc::strong_count(&theirs);
}
