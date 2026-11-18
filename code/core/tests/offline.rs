mod common;
use base64::{Engine, engine::general_purpose::STANDARD};
use common::{base, blob, brush, path};
use std::{path::Path, sync::Arc};
use syncr_core::*;

fn json(name: &str) -> serde_json::Value {
    serde_json::from_slice(
        &std::fs::read(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("fixtures/offline-v1")
                .join(name),
        )
        .unwrap(),
    )
    .unwrap()
}
fn cmd(body: CommandBody, deps: &[&str]) -> Command {
    Command {
        operation_id: new_id(),
        dependencies: deps.iter().map(|s| (*s).into()).collect(),
        body,
    }
}
fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    CreateNoteRequest,
    Arc<Note>,
) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let r = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Private note".into(),
        notebook: None,
    };
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    (root, lib, r, note)
}
fn add(note: &Note, r: &CreateNoteRequest, marks: &[String]) -> Command {
    let c = cmd(
        CommandBody::Marks {
            mutations: marks
                .iter()
                .map(|id| MarkMutation::AddStroke {
                    mark_id: id.clone(),
                    sheet_id: r.first_sheet_id.clone(),
                    samples: blob(),
                    brush: brush(),
                })
                .collect(),
        },
        &[&r.operation_id],
    );
    note.submit(c.clone()).unwrap();
    c
}
#[test]
fn reviewed_atomicity_dependency_and_branch_fixture() {
    let root = tempfile::tempdir().unwrap();
    let input = json("atomicity.input.json");
    let expected = json("atomicity.expected.json");
    let request: CreateNoteRequest = serde_json::from_value(input["create"].clone()).unwrap();
    {
        open_library(path(root.path()))
            .unwrap()
            .create_note(request.clone())
            .unwrap();
    }
    let commands = input["commands"].as_array().unwrap();
    let expected = expected.as_array().unwrap();
    assert_eq!(commands.len(), expected.len());
    for (command, expected) in commands.iter().zip(expected) {
        // Every attempted action is separated by a full library/note restart.
        let lib = open_library(path(root.path())).unwrap();
        let note = lib.open_note(request.note_id.clone()).unwrap();
        let before = note.snapshot().unwrap();
        let mut command = command.clone();
        if let Some(mutations) = command
            .get_mut("body")
            .and_then(|v| v.get_mut("Marks"))
            .and_then(|v| v.get_mut("mutations"))
            .and_then(serde_json::Value::as_array_mut)
        {
            for mutation in mutations {
                if let Some(encoded) = mutation["AddStroke"]["samples"].as_str() {
                    let bytes = STANDARD.decode(encoded).unwrap();
                    mutation["AddStroke"]["samples"] = serde_json::to_value(bytes).unwrap();
                }
            }
        }
        let command: Command = serde_json::from_value(command).unwrap();
        let result = note.submit(command);
        let outcome = match result {
            Ok(r) => {
                if r.duplicate {
                    "Duplicate"
                } else {
                    "Committed"
                }
            }
            Err(CoreError::MissingDependency { .. }) => "MissingDependency",
            Err(CoreError::IdCollision { .. }) => "IdCollision",
            Err(CoreError::StaleBase { .. }) => "StaleBase",
            Err(CoreError::NotFound { .. }) => "NotFound",
            other => panic!("Unexpected fixture outcome: {other:?}"),
        };
        assert_eq!(outcome, expected["outcome"]);
        let snapshot = note.snapshot().unwrap();
        if !matches!(outcome, "Committed") {
            assert_eq!(snapshot, before);
        }
        assert_eq!(
            snapshot.local_sequence.to_string(),
            expected["local_sequence"]
        );
        assert_eq!(
            serde_json::to_value(
                snapshot
                    .strokes
                    .iter()
                    .map(|s| s.tombstone)
                    .collect::<Vec<_>>()
            )
            .unwrap(),
            expected["tombstones"]
        );
        assert_eq!(
            serde_json::to_value(&snapshot.undo_dependencies).unwrap(),
            expected["undo_dependencies"]
        );
        assert_eq!(
            serde_json::to_value(&snapshot.redo_dependencies).unwrap(),
            expected["redo_dependencies"]
        );
        assert_eq!(
            snapshot.sheets[0].creation_operation_id,
            request.operation_id
        );
        assert_eq!(
            serde_json::to_value(snapshot.undo_target).unwrap(),
            expected["undo_target"]
        );
        assert_eq!(
            serde_json::to_value(snapshot.redo_target).unwrap(),
            expected["redo_target"]
        );
        note.close_note().unwrap();
    }
    let db =
        rusqlite::Connection::open(root.path().join(format!("{}.syncr", request.note_id))).unwrap();
    assert_eq!(
        db.query_row::<u32, _, _>("SELECT COUNT(*) FROM operations", [], |r| r.get(0))
            .unwrap(),
        6
    );
    assert_eq!(
        db.query_row::<u32, _, _>("SELECT COUNT(*) FROM mark_effects", [], |r| r.get(0))
            .unwrap(),
        4
    );
}
#[test]
fn reviewed_workflow_fixture_survives_every_restart() {
    let root = tempfile::tempdir().unwrap();
    let input = json("workflow.input.json");
    let expected = json("workflow.expected.json");
    let ids: Vec<String> = serde_json::from_value(input["operation_ids"].clone()).unwrap();
    let marks: Vec<String> = serde_json::from_value(input["mark_ids"].clone()).unwrap();
    let samples = STANDARD
        .decode(input["samples_base64"].as_str().unwrap())
        .unwrap();
    for (i, expected) in expected.as_array().unwrap().iter().enumerate() {
        let lib = open_library(path(root.path())).unwrap();
        if i == 0 {
            lib.create_note(CreateNoteRequest {
                operation_id: ids[0].clone(),
                note_id: input["note_id"].as_str().unwrap().into(),
                first_sheet_id: input["sheet_id"].as_str().unwrap().into(),
                title: "Fixture".into(),
                notebook: None,
            })
            .unwrap();
        }
        let note = lib
            .open_note(input["note_id"].as_str().unwrap().into())
            .unwrap();
        if i > 0 {
            let body = match i {
                1 => CommandBody::Marks {
                    mutations: marks
                        .iter()
                        .map(|m| MarkMutation::AddStroke {
                            mark_id: m.clone(),
                            sheet_id: input["sheet_id"].as_str().unwrap().into(),
                            samples: samples.clone(),
                            brush: brush(),
                        })
                        .collect(),
                },
                2 => CommandBody::Marks {
                    mutations: marks
                        .iter()
                        .map(|m| MarkMutation::DeleteStroke {
                            mark_id: m.clone(),
                            base: base(&ids[1]),
                        })
                        .collect(),
                },
                3 => CommandBody::Undo {
                    target_operation_id: ids[2].clone(),
                },
                4 => CommandBody::Redo {
                    target_operation_id: ids[2].clone(),
                },
                _ => unreachable!(),
            };
            let c = Command {
                operation_id: ids[i].clone(),
                dependencies: vec![ids[i - 1].clone()],
                body,
            };
            let receipt = note.submit(c.clone()).unwrap();
            assert_eq!(
                receipt.local_sequence.to_string(),
                expected["local_sequence"]
            );
            assert!(note.submit(c).unwrap().duplicate);
        }
        let s = note.snapshot().unwrap();
        assert_eq!(
            serde_json::to_value(&s.undo_dependencies).unwrap(),
            expected["undo_dependencies"]
        );
        assert_eq!(
            serde_json::to_value(&s.redo_dependencies).unwrap(),
            expected["redo_dependencies"]
        );
        assert_eq!(s.sheets[0].creation_operation_id, ids[0]);
        assert_eq!(s.local_sequence.to_string(), expected["local_sequence"]);
        assert_eq!(
            serde_json::to_value(s.strokes.iter().map(|s| s.tombstone).collect::<Vec<_>>())
                .unwrap(),
            expected["tombstones"]
        );
        assert_eq!(
            s.undo_target,
            expected["undo_index"]
                .as_u64()
                .map(|i| ids[i as usize].clone())
        );
        assert_eq!(
            s.redo_target,
            expected["redo_index"]
                .as_u64()
                .map(|i| ids[i as usize].clone())
        );
        for stroke in &s.strokes {
            assert_eq!(note.get_samples(stroke.id.clone()).unwrap(), samples);
            assert_eq!(stroke.head.operation_id, ids[i]);
            assert_eq!(stroke.head.confirmed_revision, None);
        }
        note.close_note().unwrap();
    }
}
#[test]
fn canonical_idempotency_dependencies_atomic_batch_and_immutable_log() {
    let (root, lib, r, note) = setup();
    let a = new_id();
    let b = new_id();
    let mut c = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: a.clone(),
                sheet_id: r.first_sheet_id.clone(),
                samples: blob(),
                brush: brush(),
            }],
        },
        &[],
    );
    let initial = note.snapshot().unwrap();
    assert!(matches!(
        note.submit(c.clone()),
        Err(CoreError::MissingDependency { .. })
    ));
    assert_eq!(note.snapshot().unwrap(), initial);
    assert_eq!(
        note.lookup_operation(c.operation_id.clone()).unwrap(),
        OperationLookup::NotRecorded
    );
    c.dependencies = vec![r.operation_id.clone(), r.operation_id.clone()];
    let receipt = note.submit(c.clone()).unwrap();
    c.dependencies.pop();
    assert!(note.submit(c.clone()).unwrap().duplicate);
    assert_eq!(note.snapshot().unwrap().local_sequence, 2);
    let mut different = c.clone();
    if let CommandBody::Marks { mutations } = &mut different.body
        && let MarkMutation::AddStroke { samples, .. } = &mut mutations[0]
    {
        samples[12] ^= 1;
    }
    assert!(matches!(
        note.submit(different),
        Err(CoreError::IdCollision { .. })
    ));
    let mut batch = cmd(
        CommandBody::Marks {
            mutations: vec![
                MarkMutation::DeleteStroke {
                    mark_id: a.clone(),
                    base: base(&c.operation_id),
                },
                MarkMutation::DeleteStroke {
                    mark_id: b.clone(),
                    base: base(&c.operation_id),
                },
            ],
        },
        &[&c.operation_id],
    );
    let before = note.snapshot().unwrap();
    assert!(matches!(
        note.submit(batch.clone()),
        Err(CoreError::NotFound { .. })
    ));
    assert_eq!(note.snapshot().unwrap(), before);
    batch.dependencies.push(new_id());
    assert!(matches!(
        note.submit(batch),
        Err(CoreError::MissingDependency { .. })
    ));
    let stale = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::DeleteStroke {
                mark_id: a.clone(),
                base: base(&r.operation_id),
            }],
        },
        &[&r.operation_id],
    );
    assert!(matches!(
        note.submit(stale),
        Err(CoreError::StaleBase { .. })
    ));
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    // Simulate a lost/corrupt cache: valid published metadata still recovers creation.
    let db = rusqlite::Connection::open(root.path().join("library.sqlite3")).unwrap();
    db.execute("DELETE FROM notes", []).unwrap();
    drop(db);
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(lib.list_notes().unwrap()[0].local_sequence, 2);
    let note = lib.open_note(r.note_id.clone()).unwrap();
    assert_eq!(
        note.lookup_operation(c.operation_id.clone()).unwrap(),
        OperationLookup::Committed {
            receipt: receipt.clone()
        }
    );
    assert!(note.submit(c.clone()).unwrap().duplicate);
    let db = rusqlite::Connection::open(root.path().join(format!("{}.syncr", r.note_id))).unwrap();
    let (author, payload, canonical, seq): (String, String, Vec<u8>, Vec<u8>) = db
        .query_row(
            "SELECT author_id,payload,canonical,local_sequence FROM operations WHERE id=?1",
            [&c.operation_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .unwrap();
    assert_eq!(author, lib.author_id());
    assert_eq!(serde_json::from_str::<Command>(&payload).unwrap(), c);
    assert!(canonical.starts_with(b"SYNO\x01\x00"));
    assert_eq!(seq, 2u64.to_be_bytes());
    assert_eq!(note.get_samples(a).unwrap(), blob());
    let effects: String = db
        .query_row(
            "SELECT effects FROM mark_effects WHERE operation_id=?1",
            [&c.operation_id],
            |r| r.get(0),
        )
        .unwrap();
    let effects: serde_json::Value = serde_json::from_str(&effects).unwrap();
    assert!(effects[0]["before"].is_null());
    assert_eq!(effects[0]["after"]["head"]["operation_id"], c.operation_id);
}
#[test]
fn history_chains_branches_metadata_and_structure() {
    let (_root, _lib, r, note) = setup();
    let a = new_id();
    let add = add(&note, &r, std::slice::from_ref(&a));
    let transform = Affine {
        a: 2.0,
        b: 3.0,
        c: 4.0,
        d: 5.0,
        tx: -0.0,
        ty: 7.0,
    };
    let edit = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::TransformStroke {
                mark_id: a.clone(),
                base: base(&add.operation_id),
                transform: transform.clone(),
            }],
        },
        &[&add.operation_id],
    );
    note.submit(edit.clone()).unwrap();
    let undo = cmd(
        CommandBody::Undo {
            target_operation_id: edit.operation_id.clone(),
        },
        &[&edit.operation_id],
    );
    note.submit(undo.clone()).unwrap();
    let undo_add = cmd(
        CommandBody::Undo {
            target_operation_id: add.operation_id.clone(),
        },
        &[&undo.operation_id],
    );
    note.submit(undo_add.clone()).unwrap();
    assert!(note.snapshot().unwrap().strokes[0].tombstone);
    let redo_add = cmd(
        CommandBody::Redo {
            target_operation_id: add.operation_id.clone(),
        },
        &[&undo_add.operation_id],
    );
    note.submit(redo_add.clone()).unwrap();
    // Canvas amendment: structural commands are rejected and touch neither history nor state.
    let before_structure = note.snapshot().unwrap();
    let structure = cmd(
        CommandBody::AddSheet {
            sheet_id: new_id(),
            base: base(&r.operation_id),
        },
        &[&r.operation_id],
    );
    assert!(matches!(
        note.submit(structure),
        Err(CoreError::InvalidInput { .. })
    ));
    assert_eq!(note.snapshot().unwrap(), before_structure);
    let rename = cmd(
        CommandBody::RenameNote {
            title: "Renamed".into(),
            base: base(&r.operation_id),
        },
        &[&r.operation_id],
    );
    note.submit(rename).unwrap();
    assert_eq!(
        note.snapshot().unwrap().redo_target,
        Some(edit.operation_id.clone())
    );
    let redo = cmd(
        CommandBody::Redo {
            target_operation_id: edit.operation_id.clone(),
        },
        &[&redo_add.operation_id],
    );
    note.submit(redo.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.strokes[0].transform, transform);
    assert_eq!(s.strokes[0].transform.tx.to_bits(), (-0.0f64).to_bits());
    let stale_undo = cmd(
        CommandBody::Undo {
            target_operation_id: add.operation_id.clone(),
        },
        &[&redo.operation_id],
    );
    assert!(matches!(
        note.submit(stale_undo),
        Err(CoreError::StaleBase { .. })
    ));
    let undo = cmd(
        CommandBody::Undo {
            target_operation_id: edit.operation_id.clone(),
        },
        &[&redo.operation_id],
    );
    note.submit(undo.clone()).unwrap();
    let branch = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::RestyleStroke {
                mark_id: a.clone(),
                base: base(&undo.operation_id),
                brush: Brush {
                    colour: InkColour::Red,
                    ..brush()
                },
            }],
        },
        &[&undo.operation_id],
    );
    note.submit(branch).unwrap();
    assert_eq!(note.snapshot().unwrap().redo_target, None);
    let reorder = cmd(
        CommandBody::ReorderSheets {
            sheet_ids: vec![r.first_sheet_id.clone()],
            base: base(&r.operation_id),
        },
        &[&r.operation_id],
    );
    assert!(matches!(
        note.submit(reorder),
        Err(CoreError::InvalidInput { .. })
    ));
    // New ink names the (only) first sheet and still needs its creator as a dependency.
    let add_new = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: new_id(),
                sheet_id: r.first_sheet_id.clone(),
                samples: blob(),
                brush: brush(),
            }],
        },
        &[],
    );
    assert!(matches!(
        note.submit(add_new.clone()),
        Err(CoreError::MissingDependency { .. })
    ));
    let mut add_new = add_new;
    add_new.dependencies.push(r.operation_id.clone());
    note.submit(add_new).unwrap();
}
#[test]
fn event_pages_gap_reopen_and_closed_handles() {
    let (_root, lib, r, note) = setup();
    assert!(Arc::ptr_eq(
        &note,
        &lib.open_note(r.note_id.clone()).unwrap()
    ));
    let initial = note.snapshot().unwrap();
    let mut head = r.operation_id.clone();
    for i in 0..257 {
        let c = cmd(
            CommandBody::RenameNote {
                title: i.to_string(),
                base: base(&head),
            },
            &[&head],
        );
        note.submit(c.clone()).unwrap();
        head = c.operation_id;
    }
    assert_eq!(
        note.changes_since(initial.cursor.clone(), 256).unwrap(),
        ChangePage::ResyncRequired
    );
    let cursor = EventCursor {
        sequence: 1,
        ..initial.cursor.clone()
    };
    let ChangePage::Events {
        items,
        next_cursor,
        has_more,
    } = note.changes_since(cursor, 2).unwrap()
    else {
        panic!();
    };
    assert_eq!(items.len(), 2);
    assert_eq!(next_cursor.sequence, 3);
    assert!(has_more);
    let s = note.snapshot().unwrap();
    assert_eq!(
        note.changes_since(
            EventCursor {
                sequence: 258,
                ..s.cursor.clone()
            },
            1
        )
        .unwrap(),
        ChangePage::ResyncRequired
    );
    assert!(matches!(
        note.changes_since(s.cursor.clone(), 0),
        Err(CoreError::InvalidInput { .. })
    ));
    assert_eq!(
        note.changes_since(s.cursor.clone(), 1).unwrap(),
        ChangePage::Events {
            items: vec![],
            next_cursor: s.cursor.clone(),
            has_more: false
        }
    );
    note.close_note().unwrap();
    note.close_note().unwrap();
    assert!(matches!(note.snapshot(), Err(CoreError::Closed { .. })));
    assert!(matches!(
        note.submit(cmd(
            CommandBody::Undo {
                target_operation_id: head
            },
            &[]
        )),
        Err(CoreError::Closed { .. })
    ));
    assert!(matches!(
        note.lookup_operation(new_id()),
        Err(CoreError::Closed { .. })
    ));
    assert!(matches!(
        note.get_samples(new_id()),
        Err(CoreError::Closed { .. })
    ));
    assert!(matches!(
        note.changes_since(s.cursor.clone(), 1),
        Err(CoreError::Closed { .. })
    ));
    assert!(matches!(
        note.export_copy("unused".into()),
        Err(CoreError::Closed { .. })
    ));
    let reopened = lib.open_note(r.note_id).unwrap();
    assert_ne!(
        reopened.snapshot().unwrap().cursor.stream_id,
        s.cursor.stream_id
    );
    assert_eq!(
        reopened.changes_since(s.cursor, 1).unwrap(),
        ChangePage::ResyncRequired
    );
}
#[test]
fn live_wal_export_reopens_without_device_history_and_refuses_overwrite() {
    let (_root, lib, r, note) = setup();
    let marks = vec![new_id(), new_id()];
    let action = add(&note, &r, &marks);
    let output = tempfile::tempdir().unwrap();
    let destination = output.path().join("arbitrary-name.syncr");
    let export = note.export_copy(path(&destination)).unwrap();
    assert_eq!(export.exported_local_sequence, 2);
    let bytes = std::fs::read(&destination).unwrap();
    assert!(matches!(
        note.export_copy(path(&destination)),
        Err(CoreError::IdCollision { .. })
    ));
    assert_eq!(bytes, std::fs::read(&destination).unwrap());
    let other = open_library(path(output.path())).unwrap();
    assert_ne!(other.author_id(), lib.author_id());
    let copied = other.open_note(r.note_id.clone()).unwrap();
    let snapshot = copied.snapshot().unwrap();
    assert_eq!(snapshot.local_sequence, 2);
    assert_eq!(snapshot.undo_target, None);
    assert!(snapshot.undo_dependencies.is_empty());
    assert!(snapshot.redo_dependencies.is_empty());
    assert_eq!(snapshot.strokes[0].author_id, lib.author_id());
    assert_eq!(copied.get_samples(marks[0].clone()).unwrap(), blob());
    assert!(matches!(
        copied.submit(cmd(
            CommandBody::Undo {
                target_operation_id: action.operation_id
            },
            &[&snapshot.strokes[0].head.operation_id]
        )),
        Err(CoreError::StaleBase { .. })
    ));
    copied.close_note().unwrap();
    drop(copied);
    drop(other);
    let db = rusqlite::Connection::open(&destination).unwrap();
    db.pragma_update(None, "user_version", 5).unwrap();
    drop(db);
    let before = std::fs::read(&destination).unwrap();
    assert!(matches!(
        open_library(path(output.path())),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert_eq!(std::fs::read(&destination).unwrap(), before);
}
#[test]
fn restore_stacking_independent_heads_and_exact_inverse_state() {
    let (_root, _lib, r, note) = setup();
    let a = new_id();
    let b = new_id();
    let add = add(&note, &r, &[a.clone(), b.clone()]);
    let delete = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::DeleteStroke {
                mark_id: a.clone(),
                base: base(&add.operation_id),
            }],
        },
        &[&add.operation_id],
    );
    note.submit(delete.clone()).unwrap();
    let restore = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::RestoreStroke {
                mark_id: a.clone(),
                base: base(&delete.operation_id),
            }],
        },
        &[&delete.operation_id],
    );
    note.submit(restore.clone()).unwrap();
    // A newer global sequence does not stale an unrelated mark's exact head.
    let restyle = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::RestyleStroke {
                mark_id: b.clone(),
                base: base(&add.operation_id),
                brush: Brush::plain(BrushFamily::Highlighter, BrushSize::Large, InkColour::Blue),
            }],
        },
        &[&add.operation_id],
    );
    note.submit(restyle.clone()).unwrap();
    let transform = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::TransformStroke {
                mark_id: a.clone(),
                base: base(&restore.operation_id),
                transform: Affine {
                    a: 2.0,
                    b: 0.0,
                    c: 0.0,
                    d: 3.0,
                    tx: 20.0,
                    ty: 30.0,
                },
            }],
        },
        &[&restore.operation_id],
    );
    note.submit(transform.clone()).unwrap();
    let undo_transform = cmd(
        CommandBody::Undo {
            target_operation_id: transform.operation_id.clone(),
        },
        &[&transform.operation_id],
    );
    note.submit(undo_transform.clone()).unwrap();
    let undo_style = cmd(
        CommandBody::Undo {
            target_operation_id: restyle.operation_id.clone(),
        },
        &[&restyle.operation_id],
    );
    note.submit(undo_style).unwrap();
    let undo_restore = cmd(
        CommandBody::Undo {
            target_operation_id: restore.operation_id,
        },
        &[&undo_transform.operation_id],
    );
    note.submit(undo_restore).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(
        s.strokes.iter().map(|s| &s.id).collect::<Vec<_>>(),
        vec![&a, &b]
    );
    assert!(s.strokes[0].tombstone);
    assert!(!s.strokes[1].tombstone);
    assert_eq!(s.strokes[1].brush, brush());
    assert_eq!(
        s.strokes[0].transform,
        Affine {
            a: 1.0,
            b: 0.0,
            c: 0.0,
            d: 1.0,
            tx: 0.0,
            ty: 0.0
        }
    );
    assert_eq!(note.get_samples(a).unwrap(), blob());
    assert_eq!(note.get_samples(b).unwrap(), blob());
}
#[test]
fn aggregate_sample_limit_rejects_whole_gesture() {
    let (_root, _lib, r, note) = setup();
    let samples = encode_samples(SampleChannels {
        x: vec![0.0; 524288],
        y: vec![0.0; 524288],
        pressure: vec![1.0; 524288],
        elapsed_ms: vec![0; 524288],
        tilt: None,
        orientation: None,
    })
    .unwrap();
    let c = cmd(
        CommandBody::Marks {
            mutations: (0..2)
                .map(|_| MarkMutation::AddStroke {
                    mark_id: new_id(),
                    sheet_id: r.first_sheet_id.clone(),
                    samples: samples.clone(),
                    brush: brush(),
                })
                .collect(),
        },
        &[&r.operation_id],
    );
    let before = note.snapshot().unwrap();
    assert!(matches!(
        note.submit(c.clone()),
        Err(CoreError::ResourceLimit { .. })
    ));
    assert_eq!(note.snapshot().unwrap(), before);
    assert_eq!(
        note.lookup_operation(c.operation_id).unwrap(),
        OperationLookup::NotRecorded
    );
}
#[test]
fn concurrent_same_id_commits_once_and_limits_leave_no_sequence() {
    let (_root, _lib, r, note) = setup();
    let a = new_id();
    let c = cmd(
        CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: a,
                sheet_id: r.first_sheet_id,
                samples: blob(),
                brush: brush(),
            }],
        },
        &[&r.operation_id],
    );
    let workers: Vec<_> = (0..8)
        .map(|_| {
            let note = note.clone();
            let c = c.clone();
            std::thread::spawn(move || note.submit(c).unwrap())
        })
        .collect();
    let receipts: Vec<_> = workers.into_iter().map(|w| w.join().unwrap()).collect();
    assert_eq!(receipts.iter().filter(|r| !r.duplicate).count(), 1);
    let before = note.snapshot().unwrap();
    let huge = cmd(
        CommandBody::RenameNote {
            title: "x".repeat(65536),
            base: base(&r.operation_id),
        },
        &[&r.operation_id],
    );
    assert!(matches!(
        note.submit(huge),
        Err(CoreError::ResourceLimit { .. })
    ));
    assert_eq!(note.snapshot().unwrap(), before);
}
