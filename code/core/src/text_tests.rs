//! Text contract acceptance; all libraries and note files are temporary.
use crate::{reducer::State, store, text::*, *};
use rusqlite::{Connection, OpenFlags};
use std::{path::Path, sync::Arc};

fn path(path: &Path) -> String {
    path.to_str().unwrap().into()
}
fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    CreateNoteRequest,
    Arc<Note>,
) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Text test".into(),
        notebook: None,
    };
    lib.create_note(request.clone()).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    (root, lib, request, note)
}
fn style() -> TextStyle {
    TextStyle {
        size: BrushSize::Medium,
        colour: InkColour::Ink,
    }
}
fn base(op: &str) -> Base {
    Base::Operation {
        operation_id: op.into(),
    }
}
fn marks(mutations: Vec<MarkMutation>, deps: Vec<Id>) -> Command {
    Command {
        operation_id: new_id(),
        dependencies: deps,
        body: CommandBody::Marks { mutations },
    }
}
fn add(request: &CreateNoteRequest, id: &str, role: TextRole, text: &str) -> MarkMutation {
    MarkMutation::AddText {
        mark_id: id.into(),
        sheet_id: request.first_sheet_id.clone(),
        role,
        origin_x: 10.0,
        origin_y: -20.0,
        width: 640.0,
        text: text.into(),
        style: style(),
    }
}
fn add_command(request: &CreateNoteRequest, id: &str, role: TextRole, text: &str) -> Command {
    marks(
        vec![add(request, id, role, text)],
        vec![request.operation_id.clone()],
    )
}
fn edit(note: &Note, mutation: impl FnOnce(&TextBlockView) -> MarkMutation) -> Command {
    let block = &note.snapshot().unwrap().text_blocks[0];
    marks(vec![mutation(block)], vec![block.head.operation_id.clone()])
}
fn history(note: &Note, redo: bool) -> Command {
    let s = note.snapshot().unwrap();
    Command {
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
    }
}
fn db(root: &Path, request: &CreateNoteRequest) -> Connection {
    Connection::open_with_flags(
        root.join(format!("{}.syncr", request.note_id)),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap()
}
fn version(db: &Connection) -> u32 {
    db.pragma_query_value(None, "user_version", |r| r.get(0))
        .unwrap()
}
fn reject(note: &Note, c: Command, check: impl FnOnce(CoreError) -> bool) {
    let before = note.snapshot().unwrap();
    let error = note.submit(c.clone()).unwrap_err();
    assert!(check(error));
    assert_eq!(note.snapshot().unwrap(), before);
    assert_eq!(
        note.lookup_operation(c.operation_id).unwrap(),
        OperationLookup::NotRecorded
    );
}
fn without_cursor(mut snapshot: NoteSnapshot) -> NoteSnapshot {
    snapshot.cursor = EventCursor {
        stream_id: String::new(),
        sequence: 0,
    };
    snapshot
}

#[test]
fn every_text_operation_retries_and_undoes_redoes_across_full_restarts() {
    let (root, lib, request, note) = setup();
    let id = new_id();
    let add = add_command(&request, &id, TextRole::Document, "Hello é🙂\n");
    let cursor = note.snapshot().unwrap().cursor;
    note.submit(add.clone()).unwrap();
    assert!(note.submit(add).unwrap().duplicate);
    let b = note.snapshot().unwrap().text_blocks[0].clone();
    assert_eq!(b.role, TextRole::Document);
    assert_eq!((b.origin_x, b.origin_y, b.width), (10.0, -20.0, 640.0));
    assert_eq!(b.author_id, lib.author_id());
    assert_eq!(b.head.confirmed_revision, None);
    match note.changes_since(cursor, 256).unwrap() {
        ChangePage::Events { items, .. } => assert_eq!(items[0].kind, ChangeKind::MarksChanged),
        _ => panic!("expected marks event"),
    }
    note.submit(history(&note, false)).unwrap();
    assert!(note.snapshot().unwrap().text_blocks[0].tombstone);
    note.submit(history(&note, true)).unwrap();
    assert!(!note.snapshot().unwrap().text_blocks[0].tombstone);
    note.close_note().unwrap();
    drop((note, lib));
    for step in 0..6 {
        let lib = open_library(path(root.path())).unwrap();
        let note = lib.open_note(request.note_id.clone()).unwrap();
        let before = note.snapshot().unwrap().text_blocks[0].clone();
        let c = edit(&note, |b| match step {
            0 => MarkMutation::SpliceText {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
                offset: 6,
                delete: 2,
                insert: "ñ字".into(),
            },
            1 => MarkMutation::SetTextWidth {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
                width: 800.0,
            },
            2 => MarkMutation::DeleteText {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
            },
            3 => MarkMutation::RestoreText {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
            },
            4 => MarkMutation::TransformText {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
                transform: Affine {
                    tx: 22.0,
                    ty: -17.0,
                    a: 2.0,
                    ..b.transform.clone()
                },
            },
            5 => MarkMutation::RestyleText {
                mark_id: id.clone(),
                base: base(&b.head.operation_id),
                style: TextStyle {
                    size: BrushSize::Large,
                    colour: InkColour::Blue,
                },
            },
            _ => unreachable!(),
        });
        note.submit(c.clone()).unwrap();
        let after = note.snapshot().unwrap().text_blocks[0].clone();
        match step {
            0 => assert_eq!(after.text, "Hello ñ字🙂\n"),
            1 => assert_eq!(after.width, 800.0),
            2 => assert!(after.tombstone),
            3 => assert!(!after.tombstone),
            4 => assert_eq!(
                (after.transform.a, after.transform.tx, after.transform.ty),
                (2.0, 22.0, -17.0)
            ),
            5 => assert_eq!(
                after.style,
                TextStyle {
                    size: BrushSize::Large,
                    colour: InkColour::Blue
                }
            ),
            _ => unreachable!(),
        }
        assert!(note.submit(c.clone()).unwrap().duplicate);
        let mut collision = c.clone();
        collision.dependencies.push(request.operation_id.clone());
        reject_collision(&note, collision);
        note.close_note().unwrap();
        drop((note, lib));
        let lib = open_library(path(root.path())).unwrap();
        let note = lib.open_note(request.note_id.clone()).unwrap();
        assert_eq!(note.snapshot().unwrap().text_blocks[0], after);
        let undo = history(&note, false);
        note.submit(undo.clone()).unwrap();
        let mut undone = note.snapshot().unwrap().text_blocks[0].clone();
        undone.head = before.head.clone();
        assert_eq!(undone, before);
        assert!(note.submit(undo).unwrap().duplicate);
        note.close_note().unwrap();
        drop((note, lib));
        let lib = open_library(path(root.path())).unwrap();
        let note = lib.open_note(request.note_id.clone()).unwrap();
        let redo = history(&note, true);
        note.submit(redo.clone()).unwrap();
        let mut redone = note.snapshot().unwrap().text_blocks[0].clone();
        redone.head = after.head.clone();
        assert_eq!(redone, after);
        assert!(note.submit(redo).unwrap().duplicate);
        assert!(note.submit(c).unwrap().duplicate);
        let database = db(root.path(), &request);
        assert_eq!(
            store::json(&store::replay(&database).unwrap()).unwrap(),
            store::json(&store::load(&database).unwrap()).unwrap()
        );
        note.close_note().unwrap();
    }
}
fn reject_collision(note: &Note, c: Command) {
    let before = note.snapshot().unwrap();
    assert!(matches!(note.submit(c), Err(CoreError::IdCollision { .. })));
    assert_eq!(note.snapshot().unwrap(), before);
}

#[test]
fn splice_ranges_are_utf8_byte_boundaries_and_errors_are_atomic() {
    let (_root, _lib, request, note) = setup();
    note.submit(add_command(&request, &new_id(), TextRole::Free, "aé🙂z"))
        .unwrap();
    for (offset, delete) in [
        (2, 0),
        (1, 1),
        (4, 0),
        (3, 1),
        (8, 1),
        (9, 0),
        (u32::MAX, u32::MAX),
    ] {
        let c = edit(&note, |b| MarkMutation::SpliceText {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
            offset,
            delete,
            insert: "x".into(),
        });
        reject(&note, c, |e| {
            matches!(
                e,
                CoreError::InvalidTextRange {
                    retryable: false,
                    ..
                }
            )
        });
    }
    for (offset, delete, insert, expected) in [
        (8, 0, "", "aé🙂z"),
        (1, 6, "字", "a字z"),
        (0, 5, "", ""),
        (0, 0, "🙂", "🙂"),
    ] {
        note.submit(edit(&note, |b| MarkMutation::SpliceText {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
            offset,
            delete,
            insert: insert.into(),
        }))
        .unwrap();
        assert_eq!(note.snapshot().unwrap().text_blocks[0].text, expected);
    }
}

#[test]
fn text_content_geometry_and_result_limits_are_typed() {
    let (root, _lib, request, note) = setup();
    for text in ["a\0b", "a\rb", "a\r\nb"] {
        reject(
            &note,
            add_command(&request, &new_id(), TextRole::Free, text),
            |e| {
                matches!(
                    e,
                    CoreError::InvalidText {
                        retryable: false,
                        ..
                    }
                )
            },
        );
    }
    for width in [0.0, -0.0, -1.0, f32::NAN, f32::INFINITY] {
        let mut m = add(&request, &new_id(), TextRole::Free, "");
        if let MarkMutation::AddText { width: w, .. } = &mut m {
            *w = width;
        }
        reject(
            &note,
            marks(vec![m], vec![request.operation_id.clone()]),
            |e| matches!(e, CoreError::InvalidInput { .. }),
        );
    }
    for (x, y) in [(f32::NAN, 0.0), (0.0, f32::NEG_INFINITY)] {
        let mut m = add(&request, &new_id(), TextRole::Free, "");
        if let MarkMutation::AddText {
            origin_x, origin_y, ..
        } = &mut m
        {
            *origin_x = x;
            *origin_y = y;
        }
        reject(
            &note,
            marks(vec![m], vec![request.operation_id.clone()]),
            |e| matches!(e, CoreError::InvalidInput { .. }),
        );
    }
    assert_eq!(version(&db(root.path(), &request)), 3);
    reject(
        &note,
        add_command(
            &request,
            &new_id(),
            TextRole::Free,
            &"a".repeat(MAX_TEXT_BYTES + 1),
        ),
        |e| matches!(e, CoreError::ResourceLimit { .. }),
    );
    note.submit(add_command(
        &request,
        &new_id(),
        TextRole::Free,
        &"é".repeat(MAX_TEXT_BYTES / 2),
    ))
    .unwrap();
    for insert in ["\0", "\r", "\r\n"] {
        reject(
            &note,
            edit(&note, |b| MarkMutation::SpliceText {
                mark_id: b.id.clone(),
                base: base(&b.head.operation_id),
                offset: 0,
                delete: 0,
                insert: insert.into(),
            }),
            |e| matches!(e, CoreError::InvalidText { .. }),
        );
    }
    reject(
        &note,
        edit(&note, |b| MarkMutation::SpliceText {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
            offset: 0,
            delete: 0,
            insert: "a".into(),
        }),
        |e| matches!(e, CoreError::ResourceLimit { .. }),
    );
    reject(
        &note,
        edit(&note, |b| MarkMutation::SetTextWidth {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
            width: f32::INFINITY,
        }),
        |e| matches!(e, CoreError::InvalidInput { .. }),
    );
    reject(
        &note,
        edit(&note, |b| MarkMutation::TransformText {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
            transform: Affine {
                a: 0.0,
                d: 0.0,
                ..b.transform.clone()
            },
        }),
        |e| matches!(e, CoreError::InvalidInput { .. }),
    );
}

#[test]
fn splice_history_and_effects_store_edits_not_whole_blocks() {
    let (root, _lib, request, note) = setup();
    let initial = "a".repeat(MAX_TEXT_BYTES);
    note.submit(add_command(
        &request,
        &new_id(),
        TextRole::Document,
        &initial,
    ))
    .unwrap();
    let c = edit(&note, |b| MarkMutation::SpliceText {
        mark_id: b.id.clone(),
        base: base(&b.head.operation_id),
        offset: 12,
        delete: 1,
        insert: "b".into(),
    });
    note.submit(c.clone()).unwrap();
    let database = db(root.path(), &request);
    let state = store::load(&database).unwrap();
    let action = state
        .histories
        .values()
        .next()
        .unwrap()
        .undo
        .last()
        .unwrap();
    assert!(store::json(action).unwrap().len() < 512);
    let payload: String = database
        .query_row(
            "SELECT payload FROM operations WHERE id=?1",
            [&c.operation_id],
            |r| r.get(0),
        )
        .unwrap();
    let effect: String = database
        .query_row(
            "SELECT effects FROM text_effects WHERE operation_id=?1",
            [&c.operation_id],
            |r| r.get(0),
        )
        .unwrap();
    assert!(payload.len() < 512 && effect.len() < 512);
    note.submit(history(&note, false)).unwrap();
    assert_eq!(note.snapshot().unwrap().text_blocks[0].text, initial);
    note.submit(history(&note, true)).unwrap();
    assert_eq!(&note.snapshot().unwrap().text_blocks[0].text[11..14], "aba");
}

#[test]
fn document_uniqueness_covers_add_restore_history_and_atomic_swaps() {
    let (_root, _lib, request, note) = setup();
    let a = new_id();
    note.submit(add_command(&request, &a, TextRole::Document, "first"))
        .unwrap();
    let b = new_id();
    reject(
        &note,
        add_command(&request, &b, TextRole::Document, "second"),
        |e| {
            matches!(
                e,
                CoreError::DocumentTextExists {
                    retryable: false,
                    ..
                }
            )
        },
    );
    let delete = edit(&note, |b| MarkMutation::DeleteText {
        mark_id: b.id.clone(),
        base: base(&b.head.operation_id),
    });
    note.submit(delete.clone()).unwrap();
    note.submit(add_command(&request, &b, TextRole::Document, "second"))
        .unwrap();
    reject(
        &note,
        edit(&note, |b| MarkMutation::RestoreText {
            mark_id: b.id.clone(),
            base: base(&b.head.operation_id),
        }),
        |e| matches!(e, CoreError::DocumentTextExists { .. }),
    );
    // Swap live documents in one gesture, even when restore appears before delete.
    let blocks = note.snapshot().unwrap().text_blocks;
    let swap = marks(
        vec![
            MarkMutation::RestoreText {
                mark_id: a.clone(),
                base: base(&blocks[0].head.operation_id),
            },
            MarkMutation::DeleteText {
                mark_id: b.clone(),
                base: base(&blocks[1].head.operation_id),
            },
        ],
        blocks.iter().map(|b| b.head.operation_id.clone()).collect(),
    );
    note.submit(swap).unwrap();
    note.submit(history(&note, false)).unwrap();
    assert!(note.snapshot().unwrap().text_blocks[0].tombstone);
    note.submit(history(&note, false)).unwrap(); // Undo second add.
    note.submit(history(&note, false)).unwrap(); // Undo first delete.
    assert!(!note.snapshot().unwrap().text_blocks[0].tombstone);
    // Another author can add a document after a deletion; undo must not restore two.
    let mut state = State::new(&request);
    let author = new_id();
    state
        .apply(&add_command(&request, &a, TextRole::Document, ""), &author)
        .unwrap();
    let head = state.text_blocks[0].head.operation_id.clone();
    let del = marks(
        vec![MarkMutation::DeleteText {
            mark_id: a,
            base: base(&head),
        }],
        vec![head],
    );
    state.apply(&del, &author).unwrap();
    state
        .apply(
            &add_command(&request, &b, TextRole::Document, ""),
            &new_id(),
        )
        .unwrap();
    let s = state.snapshot(
        &author,
        EventCursor {
            stream_id: new_id(),
            sequence: 0,
        },
    );
    let c = Command {
        operation_id: new_id(),
        dependencies: s.undo_dependencies,
        body: CommandBody::Undo {
            target_operation_id: del.operation_id,
        },
    };
    assert!(matches!(
        state.apply(&c, &author),
        Err(CoreError::DocumentTextExists { .. })
    ));
}

#[test]
fn text_dependencies_bases_and_entity_identity_match_strokes() {
    let (_root, _lib, request, note) = setup();
    let id = new_id();
    let mut add = add_command(&request, &id, TextRole::Free, "");
    let mut no_dep = add.clone();
    no_dep.dependencies.clear();
    reject(&note, no_dep, |e| {
        matches!(e, CoreError::MissingDependency { .. })
    });
    let mut wrong_sheet = add.clone();
    if let CommandBody::Marks { mutations } = &mut wrong_sheet.body
        && let MarkMutation::AddText { sheet_id, .. } = &mut mutations[0]
    {
        *sheet_id = new_id();
    }
    reject(&note, wrong_sheet, |e| {
        matches!(e, CoreError::NotFound { .. })
    });
    note.submit(add.clone()).unwrap();
    add.operation_id = new_id();
    reject(&note, add, |e| matches!(e, CoreError::IdCollision { .. }));
    let good = edit(&note, |b| MarkMutation::SetTextWidth {
        mark_id: id.clone(),
        base: base(&b.head.operation_id),
        width: 700.0,
    });
    let mut missing_dep = good.clone();
    missing_dep.dependencies.clear();
    reject(&note, missing_dep, |e| {
        matches!(e, CoreError::MissingDependency { .. })
    });
    for b in [
        Base::Absent,
        base(&request.operation_id),
        Base::Confirmed { revision: 1 },
    ] {
        let confirmed = matches!(b, Base::Confirmed { .. });
        let c = marks(
            vec![MarkMutation::SetTextWidth {
                mark_id: id.clone(),
                base: b,
                width: 700.0,
            }],
            vec![request.operation_id.clone()],
        );
        reject(&note, c, |e| {
            if confirmed {
                matches!(e, CoreError::InvalidInput { .. })
            } else {
                matches!(e, CoreError::StaleBase { .. })
            }
        });
    }
    let mut unknown = good.clone();
    unknown.dependencies.push(new_id());
    reject(&note, unknown, |e| {
        matches!(e, CoreError::MissingDependency { .. })
    });
    note.submit(good.clone()).unwrap();
    let mut stale = good.clone();
    stale.operation_id = new_id();
    reject(&note, stale, |e| matches!(e, CoreError::StaleBase { .. }));
    let mut changed = good;
    changed.body = CommandBody::Marks {
        mutations: vec![MarkMutation::DeleteText {
            mark_id: id,
            base: Base::Absent,
        }],
    };
    reject_collision(&note, changed);
}

fn stroke(request: &CreateNoteRequest, id: &str) -> MarkMutation {
    MarkMutation::AddStroke {
        mark_id: id.into(),
        sheet_id: request.first_sheet_id.clone(),
        samples: encode_samples(SampleChannels {
            x: vec![0.0],
            y: vec![0.0],
            pressure: vec![0.5],
            elapsed_ms: vec![0],
            tilt: None,
            orientation: None,
        })
        .unwrap(),
        brush: Brush::plain(BrushFamily::Pen, BrushSize::Small, InkColour::Ink),
    }
}
#[test]
fn mixed_batches_share_identity_history_dependencies_and_atomicity() {
    let (root, _lib, request, note) = setup();
    let a = new_id();
    let b = new_id();
    reject(
        &note,
        marks(
            vec![
                add(&request, &a, TextRole::Free, ""),
                MarkMutation::DeleteStroke {
                    mark_id: b.clone(),
                    base: Base::Absent,
                },
            ],
            vec![request.operation_id.clone()],
        ),
        |e| matches!(e, CoreError::NotFound { .. }),
    );
    assert_eq!(version(&db(root.path(), &request)), 3);
    reject(
        &note,
        marks(
            vec![add(&request, &a, TextRole::Free, ""), stroke(&request, &a)],
            vec![request.operation_id.clone()],
        ),
        |e| matches!(e, CoreError::InvalidInput { .. }),
    );
    note.submit(marks(
        vec![
            add(&request, &a, TextRole::Free, "word"),
            stroke(&request, &b),
        ],
        vec![request.operation_id.clone()],
    ))
    .unwrap();
    let head = note.snapshot().unwrap().text_blocks[0]
        .head
        .operation_id
        .clone();
    reject(
        &note,
        marks(
            vec![stroke(&request, &a)],
            vec![request.operation_id.clone()],
        ),
        |e| matches!(e, CoreError::IdCollision { .. }),
    );
    reject(&note, add_command(&request, &b, TextRole::Free, ""), |e| {
        matches!(e, CoreError::IdCollision { .. })
    });
    assert_eq!(note.snapshot().unwrap().undo_dependencies, vec![head]);
    note.submit(history(&note, false)).unwrap();
    let s = note.snapshot().unwrap();
    assert!(s.strokes[0].tombstone && s.text_blocks[0].tombstone);
    assert_eq!(
        s.redo_dependencies,
        vec![s.strokes[0].head.operation_id.clone()]
    );
    note.submit(history(&note, true)).unwrap();
    let s = note.snapshot().unwrap();
    assert!(!s.strokes[0].tombstone && !s.text_blocks[0].tombstone);
}

#[test]
fn block_limit_counts_tombstones_and_rejects_without_an_extra_block() {
    let (_root, lib, request, _note) = setup();
    let mut state = State::new(&request);
    state
        .apply(
            &add_command(&request, &new_id(), TextRole::Free, ""),
            &lib.author_id(),
        )
        .unwrap();
    let template = state.text_blocks[0].clone();
    state.text_blocks = (0..MAX_TEXT_BLOCKS - 1)
        .map(|_| TextBlockView {
            id: new_id(),
            tombstone: true,
            ..template.clone()
        })
        .collect();
    state
        .apply(
            &add_command(&request, &new_id(), TextRole::Free, ""),
            &lib.author_id(),
        )
        .unwrap();
    assert_eq!(state.text_blocks.len(), MAX_TEXT_BLOCKS);
    assert!(matches!(
        state.apply(
            &add_command(&request, &new_id(), TextRole::Free, ""),
            &lib.author_id()
        ),
        Err(CoreError::ResourceLimit { .. })
    ));
    assert_eq!(state.text_blocks.len(), MAX_TEXT_BLOCKS);
}

#[test]
fn v3_open_and_stroke_writes_do_not_promote_or_backup_and_v4_refuses_old_readers() {
    let (root, lib, request, note) = setup();
    assert_eq!(version(&db(root.path(), &request)), 3);
    note.submit(marks(
        vec![stroke(&request, &new_id())],
        vec![request.operation_id.clone()],
    ))
    .unwrap();
    note.close_note().unwrap();
    drop((note, lib));
    let file = root.path().join(format!("{}.syncr", request.note_id));
    let original = std::fs::read(&file).unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    assert!(note.snapshot().unwrap().text_blocks.is_empty());
    assert_eq!(std::fs::read(&file).unwrap(), original);
    assert_eq!(version(&db(root.path(), &request)), 3);
    assert!(!root.path().join(".backups").exists());
    reject(
        &note,
        add_command(&request, &new_id(), TextRole::Free, "\r"),
        |e| matches!(e, CoreError::InvalidText { .. }),
    );
    assert_eq!(version(&db(root.path(), &request)), 3);
    let database = Connection::open(&file).unwrap();
    database.execute_batch("CREATE TRIGGER reject_text_log BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'test'); END;").unwrap();
    let c = add_command(&request, &new_id(), TextRole::Document, "v4");
    reject(&note, c.clone(), |e| {
        matches!(e, CoreError::StorageIo { .. })
    });
    assert_eq!(version(&database), 3);
    let table: u32 = database
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE name='text_effects'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(table, 0);
    database
        .execute_batch("DROP TRIGGER reject_text_log")
        .unwrap();
    note.submit(c).unwrap();
    assert_eq!(version(&database), 4);
    assert!(matches!(
        store::check_version_supported(&database, 3),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert_eq!(store::check_version(&database).unwrap(), 4);
    note.submit(history(&note, false)).unwrap();
    assert_eq!(version(&database), 4); // Undo never downgrades a file.
    note.close_note().unwrap();
    drop((note, lib, database));
    let v4 = std::fs::read(&file).unwrap();
    let lib = open_library(path(root.path())).unwrap();
    lib.open_note(request.note_id)
        .unwrap()
        .close_note()
        .unwrap();
    assert_eq!(std::fs::read(&file).unwrap(), v4);
    assert!(!root.path().join(".backups").exists());
}

#[test]
fn v4_export_reimport_keeps_immutable_rows_text_and_samples() {
    let (root, lib, request, note) = setup();
    note.submit(marks(
        vec![
            add(&request, &new_id(), TextRole::Document, "Markdown\n🙂"),
            stroke(&request, &new_id()),
        ],
        vec![request.operation_id.clone()],
    ))
    .unwrap();
    note.submit(edit(&note, |b| MarkMutation::SpliceText {
        mark_id: b.id.clone(),
        base: base(&b.head.operation_id),
        offset: 0,
        delete: 0,
        insert: "# ".into(),
    }))
    .unwrap();
    let before = note.snapshot().unwrap();
    let output = tempfile::tempdir().unwrap();
    let target = output.path().join("export.syncr");
    let exported = note.export_copy(path(&target)).unwrap();
    assert_eq!(exported.exported_local_sequence, before.local_sequence);
    let copy_db = Connection::open(&target).unwrap();
    assert_eq!(version(&copy_db), 4);
    for table in ["operations", "samples", "mark_effects", "text_effects"] {
        assert_eq!(
            rows(&db(root.path(), &request), table),
            rows(&copy_db, table)
        );
    }
    let other = open_library(path(output.path())).unwrap();
    assert_ne!(other.author_id(), lib.author_id());
    let copied = other.open_note(request.note_id.clone()).unwrap();
    let after = copied.snapshot().unwrap();
    assert_eq!(after.text_blocks, before.text_blocks);
    assert_eq!(after.strokes, before.strokes);
    assert_eq!(after.undo_target, None);
    assert!(after.undo_dependencies.is_empty());
    assert_eq!(
        copied.get_samples(after.strokes[0].id.clone()).unwrap(),
        note.get_samples(before.strokes[0].id.clone()).unwrap()
    );
    copied.close_note().unwrap();
    drop((copied, other, copy_db));
    let imported = tempfile::tempdir().unwrap();
    let imported_file = imported.path().join("reimport.syncr");
    std::fs::copy(&target, &imported_file).unwrap();
    let original = std::fs::read(&imported_file).unwrap();
    let imported_lib = open_library(path(imported.path())).unwrap();
    let imported_note = imported_lib.open_note(request.note_id).unwrap();
    assert_eq!(
        imported_note.snapshot().unwrap().text_blocks,
        before.text_blocks
    );
    imported_note.close_note().unwrap();
    assert_eq!(std::fs::read(&imported_file).unwrap(), original);
}
fn rows(database: &Connection, table: &str) -> Vec<Vec<rusqlite::types::Value>> {
    let mut statement = database
        .prepare(&format!("SELECT * FROM {table} ORDER BY 1"))
        .unwrap();
    let count = statement.column_count();
    statement
        .query_map([], |r| {
            (0..count).map(|i| r.get(i)).collect::<Result<Vec<_>, _>>()
        })
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap()
}

#[test]
fn text_canonical_golden_vectors_and_budgets() {
    let vectors: serde_json::Value =
        serde_json::from_str(include_str!("../fixtures/text-v1/canonical.json")).unwrap();
    for case in vectors.as_array().unwrap() {
        let mut c: Command = serde_json::from_value(case["command"].clone()).unwrap();
        let bytes = crate::canonical::command(
            &c,
            case["note_id"].as_str().unwrap(),
            case["author_id"].as_str().unwrap(),
        )
        .unwrap();
        let hex: String = bytes.iter().map(|v| format!("{v:02x}")).collect();
        assert_eq!(hex, case["canonical_hex"], "{}", case["name"]);
        assert_eq!(bytes.len(), case["canonical_length"]);
        c.dependencies.sort();
        c.dependencies.dedup();
        assert_eq!(
            crate::canonical::command(
                &c,
                case["note_id"].as_str().unwrap(),
                case["author_id"].as_str().unwrap()
            )
            .unwrap(),
            bytes
        );
        if case["name"] == "AddText" {
            if let CommandBody::Marks { mutations } = &mut c.body
                && let MarkMutation::AddText { origin_x, .. } = &mut mutations[0]
            {
                *origin_x = 0.0;
            }
            assert_ne!(
                crate::canonical::command(
                    &c,
                    case["note_id"].as_str().unwrap(),
                    case["author_id"].as_str().unwrap()
                )
                .unwrap(),
                bytes
            );
        }
    }
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: String::new(),
        notebook: None,
    };
    let text = "a".repeat(MAX_TEXT_BYTES);
    let mut c = marks(
        (0..16)
            .map(|_| add(&request, &new_id(), TextRole::Free, &text))
            .collect(),
        vec![request.operation_id.clone()],
    );
    assert!(crate::canonical::command(&c, &request.note_id, &new_id()).is_ok());
    if let CommandBody::Marks { mutations } = &mut c.body {
        mutations.push(add(&request, &new_id(), TextRole::Free, "x"));
    }
    assert!(matches!(
        crate::canonical::command(&c, &request.note_id, &new_id()),
        Err(CoreError::ResourceLimit { .. })
    ));
}

#[test]
fn concurrent_text_retry_commits_once_and_reopen_keeps_receipt() {
    let (_root, lib, request, note) = setup();
    let c = add_command(&request, &new_id(), TextRole::Free, "threaded");
    let receipts = std::thread::scope(|scope| {
        let a = scope.spawn(|| note.submit(c.clone()).unwrap());
        let b = scope.spawn(|| note.submit(c.clone()).unwrap());
        [a.join().unwrap(), b.join().unwrap()]
    });
    assert_eq!(receipts.iter().filter(|r| !r.duplicate).count(), 1);
    assert_eq!(note.snapshot().unwrap().local_sequence, 2);
    note.close_note().unwrap();
    let reopened = lib.open_note(request.note_id).unwrap();
    let retry = reopened.submit(c).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, 2);
    assert_eq!(retry.cursor, receipts[0].cursor);
}

#[test]
fn another_authors_text_edit_makes_the_inverse_stale() {
    let (_root, lib, request, _note) = setup();
    let author = lib.author_id();
    let mut state = State::new(&request);
    let add = add_command(&request, &new_id(), TextRole::Free, "hello");
    state.apply(&add, &author).unwrap();
    let block = &state.text_blocks[0];
    let c = marks(
        vec![MarkMutation::SpliceText {
            mark_id: block.id.clone(),
            base: base(&block.head.operation_id),
            offset: 0,
            delete: 0,
            insert: "x".into(),
        }],
        vec![block.head.operation_id.clone()],
    );
    state.apply(&c, &new_id()).unwrap();
    let cursor = EventCursor {
        stream_id: new_id(),
        sequence: 0,
    };
    let s = state.snapshot(&author, cursor);
    assert_eq!(s.undo_dependencies, vec![c.operation_id]);
    assert!(matches!(
        state.apply(
            &Command {
                operation_id: new_id(),
                dependencies: s.undo_dependencies,
                body: CommandBody::Undo {
                    target_operation_id: add.operation_id
                }
            },
            &author
        ),
        Err(CoreError::StaleBase { .. })
    ));
}

#[test]
fn text_crash_probe() {
    let Ok(root) = std::env::var("SYNCR_TEXT_CRASH_ROOT") else {
        return;
    };
    let request: CreateNoteRequest =
        serde_json::from_str(&std::env::var("SYNCR_TEXT_CRASH_REQUEST").unwrap()).unwrap();
    let c: Command =
        serde_json::from_str(&std::env::var("SYNCR_TEXT_CRASH_COMMAND").unwrap()).unwrap();
    let lib = open_library(root).unwrap();
    lib.open_note(request.note_id).unwrap().submit(c).unwrap();
    panic!("crash point was not reached");
}
#[test]
fn text_version_log_effects_and_projection_commit_together_across_crashes() {
    for splice in [false, true] {
        for stage in ["command_before_commit", "command_after_commit"] {
            let (root, lib, request, note) = setup();
            if splice {
                note.submit(add_command(&request, &new_id(), TextRole::Free, "before"))
                    .unwrap();
            }
            let before = note.snapshot().unwrap();
            let c = if splice {
                edit(&note, |b| MarkMutation::SpliceText {
                    mark_id: b.id.clone(),
                    base: base(&b.head.operation_id),
                    offset: 0,
                    delete: 6,
                    insert: "after".into(),
                })
            } else {
                add_command(&request, &new_id(), TextRole::Free, "after")
            };
            note.close_note().unwrap();
            drop((note, lib));
            let result = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "text_tests::text_crash_probe"])
                .env("SYNCR_CRASH_STAGE", stage)
                .env("SYNCR_TEXT_CRASH_ROOT", root.path())
                .env("SYNCR_TEXT_CRASH_REQUEST", store::json(&request).unwrap())
                .env("SYNCR_TEXT_CRASH_COMMAND", store::json(&c).unwrap())
                .output()
                .unwrap();
            assert_eq!(
                result.status.code(),
                Some(91),
                "{}",
                String::from_utf8_lossy(&result.stderr)
            );
            let lib = open_library(path(root.path())).unwrap();
            let note = lib.open_note(request.note_id.clone()).unwrap();
            let committed = stage == "command_after_commit";
            let s = note.snapshot().unwrap();
            assert_eq!(
                s.local_sequence,
                before.local_sequence + u64::from(committed)
            );
            assert_eq!(
                version(&db(root.path(), &request)),
                if splice || committed { 4 } else { 3 }
            );
            if committed {
                assert_eq!(s.text_blocks[0].text, "after");
                assert!(note.submit(c).unwrap().duplicate);
                assert!(s.undo_target.is_some());
                let database = db(root.path(), &request);
                assert_eq!(
                    store::json(&store::replay(&database).unwrap()).unwrap(),
                    store::json(&store::load(&database).unwrap()).unwrap()
                );
            } else {
                assert_eq!(without_cursor(s), without_cursor(before));
                assert_eq!(
                    note.lookup_operation(c.operation_id).unwrap(),
                    OperationLookup::NotRecorded
                );
            }
        }
    }
}
