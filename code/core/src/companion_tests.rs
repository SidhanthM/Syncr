use crate::*;
use std::sync::Arc;

fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    Arc<Note>,
    CreateNoteRequest,
    Id,
) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    let r = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Companion".into(),
        notebook: None,
        kind: NoteKind::Note,
    };
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    let block = new_id();
    note.submit(Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddText {
                mark_id: block.clone(),
                sheet_id: r.first_sheet_id.clone(),
                role: TextRole::Document,
                origin_x: 0.0,
                origin_y: 0.0,
                width: 1400.0,
                text: "Above.\nA cache is always fresh.\nBelow.".into(),
                style: TextStyle {
                    size: BrushSize::Medium,
                    colour: InkColour::Ink,
                },
            }],
        },
    })
    .unwrap();
    (root, lib, note, r, block)
}
fn command(mutations: Vec<CompanionMutation>, deps: Vec<Id>) -> Command {
    Command {
        operation_id: new_id(),
        dependencies: deps,
        body: CommandBody::CompanionMarks { mutations },
    }
}
fn anchor(block: &str, id: &str) -> CompanionMutation {
    CompanionMutation {
        id: id.into(),
        base: Base::Absent,
        tombstone: false,
        body: CompanionBody::Anchor {
            target: AnchorTarget::Text {
                block_id: block.into(),
                start: 7,
                end: 31,
                detached: false,
            },
        },
    }
}
fn margin(id: &str, a: &str) -> CompanionMutation {
    CompanionMutation {
        id: id.into(),
        base: Base::Absent,
        tombstone: false,
        body: CompanionBody::MarginNote {
            anchor_id: a.into(),
            kind: MarginKind::Flag,
            text: "How is this invalidated?".into(),
            reader: "Staff engineer".into(),
            state: MarginState::Pinned,
            sources: vec![],
        },
    }
}
fn splice(note: &Note, block: &str, offset: u32, delete: u32, insert: &str) {
    let b = note
        .snapshot()
        .unwrap()
        .text_blocks
        .into_iter()
        .find(|b| b.id == block)
        .unwrap();
    note.submit(Command {
        operation_id: new_id(),
        dependencies: vec![b.head.operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::SpliceText {
                mark_id: block.into(),
                base: Base::Operation {
                    operation_id: b.head.operation_id,
                },
                offset,
                delete,
                insert: insert.into(),
            }],
        },
    })
    .unwrap();
}
fn agent_undo(note: &Note, redo: bool) {
    let s = note.agent_snapshot().unwrap();
    note.submit_agent(Command {
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
#[test]
fn anchors_follow_splices_and_agent_history_is_separate() {
    let (_root, _lib, note, _r, block) = setup();
    let a = new_id();
    let m = new_id();
    let user_target = note.snapshot().unwrap().undo_target;
    let c = command(
        vec![anchor(&block, &a), margin(&m, &a)],
        vec![
            note.snapshot().unwrap().text_blocks[0]
                .head
                .operation_id
                .clone(),
        ],
    );
    note.submit_agent(c.clone()).unwrap();
    assert!(note.submit_agent(c).unwrap().duplicate);
    assert_eq!(note.snapshot().unwrap().undo_target, user_target);
    assert_ne!(
        note.agent_author_id(),
        note.snapshot().unwrap().text_blocks[0].author_id
    );
    splice(&note, &block, 0, 0, "new\n");
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text {
                start: 11,
                end: 35,
                detached: false,
                ..
            }
        }
    ));
    agent_undo(&note, false);
    assert!(
        note.snapshot()
            .unwrap()
            .companion_items
            .iter()
            .all(|v| v.tombstone)
    );
    agent_undo(&note, true);
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text { start: 11, .. }
        }
    ));
    splice(&note, &block, 11, 24, "");
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text {
                start: 11,
                end: 11,
                detached: true,
                ..
            }
        }
    ));
    let s = note.snapshot().unwrap();
    note.submit(Command {
        operation_id: new_id(),
        dependencies: s.undo_dependencies,
        body: CommandBody::Undo {
            target_operation_id: s.undo_target.unwrap(),
        },
    })
    .unwrap();
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text {
                start: 11,
                end: 35,
                detached: false,
                ..
            }
        }
    ));
}
#[test]
fn agent_cannot_rewrite_text_and_bad_batches_are_atomic() {
    let (_root, _lib, note, _r, block) = setup();
    let before = note.snapshot().unwrap();
    let b = &before.text_blocks[0];
    assert!(
        note.submit_agent(Command {
            operation_id: new_id(),
            dependencies: vec![b.head.operation_id.clone()],
            body: CommandBody::Marks {
                mutations: vec![MarkMutation::SpliceText {
                    mark_id: block.clone(),
                    base: Base::Operation {
                        operation_id: b.head.operation_id.clone()
                    },
                    offset: 0,
                    delete: 0,
                    insert: "write my note".into()
                }]
            }
        })
        .is_err()
    );
    let a = new_id();
    let mut bad = anchor(&block, &a);
    if let CompanionBody::Anchor {
        target: AnchorTarget::Text { end, .. },
    } = &mut bad.body
    {
        *end = 999999;
    }
    assert!(
        note.submit_agent(command(vec![bad], vec![b.head.operation_id.clone()]))
            .is_err()
    );
    assert_eq!(note.snapshot().unwrap(), before);
}
#[test]
fn threads_settings_and_lazy_version_survive_restart_and_export() {
    let (root, lib, note, r, block) = setup();
    let db = rusqlite::Connection::open(root.path().join(format!("{}.syncr", r.note_id))).unwrap();
    assert_eq!(
        db.pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap(),
        4
    );
    let a = new_id();
    let m = new_id();
    let msg = new_id();
    let c = command(
        vec![
            anchor(&block, &a),
            margin(&m, &a),
            CompanionMutation {
                id: msg,
                base: Base::Absent,
                tombstone: false,
                body: CompanionBody::ThreadMessage {
                    margin_id: m,
                    order: 0,
                    who: MessageWho::Companion,
                    text: "A durable reply".into(),
                    sources: vec![CompanionSource {
                        title: "Source".into(),
                        url: "https://example.com/source".into(),
                    }],
                },
            },
        ],
        vec![
            note.snapshot().unwrap().text_blocks[0]
                .head
                .operation_id
                .clone(),
        ],
    );
    note.submit_agent(c).unwrap();
    let s = CompanionSettings {
        tell: "Be brutal".into(),
        mode: CompanionMode::ThinksWithYou,
        web: false,
        active_reader: "editor".into(),
        colour: InkColour::Blue,
    };
    note.submit(Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::SetCompanionSettings {
            settings: s.clone(),
            base: Base::Operation {
                operation_id: r.operation_id.clone(),
            },
        },
    })
    .unwrap();
    splice(&note, &block, 0, 0, "x");
    assert_eq!(
        db.pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap(),
        5
    );
    assert!(crate::store::check_version_supported(&db, 4).is_err());
    let exported = tempfile::tempdir().unwrap();
    note.export_copy(
        exported
            .path()
            .join(format!("{}.syncr", r.note_id))
            .to_str()
            .unwrap()
            .into(),
    )
    .unwrap();
    let snapshot = note.snapshot().unwrap();
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    drop(db);
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    assert_eq!(
        note.snapshot().unwrap().companion_items,
        snapshot.companion_items
    );
    assert_eq!(
        note.snapshot()
            .unwrap()
            .companion_settings
            .unwrap()
            .settings,
        s
    );
    let imported = open_library(exported.path().to_str().unwrap().into()).unwrap();
    let n = imported.open_note(r.note_id).unwrap();
    assert_eq!(
        n.snapshot().unwrap().companion_items,
        snapshot.companion_items
    );
}
