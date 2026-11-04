use crate::*;
use std::sync::Arc;
fn png() -> Vec<u8> {
    include_bytes!("../fixtures/image-v2/pixel.png").to_vec()
}
fn placement(sheet: &str) -> ImagePlacement {
    ImagePlacement::Canvas {
        sheet_id: sheet.into(),
        x: 10.0,
        y: 20.0,
        width: 100.0,
        height: 100.0,
    }
}
fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    Arc<Note>,
    CreateNoteRequest,
) {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    let r = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Images".into(),
        notebook: None,
    };
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    (root, lib, note, r)
}
fn command(mutations: Vec<ImageMutation>, dependencies: Vec<Id>) -> Command {
    Command {
        operation_id: new_id(),
        dependencies,
        body: CommandBody::ImageMarks { mutations },
    }
}
fn add(id: &str, sheet: &str) -> ImageMutation {
    ImageMutation::Add {
        image_id: id.into(),
        bytes: png(),
        media_type: ImageMediaType::Png,
        pixel_width: 1,
        pixel_height: 1,
        placement: placement(sheet),
    }
}
fn base(op: &str) -> Base {
    Base::Operation {
        operation_id: op.into(),
    }
}
fn history(note: &Note, redo: bool) {
    let s = note.snapshot().unwrap();
    note.submit(Command {
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
fn image_container_validation_and_limits() {
    crate::image::container(&png(), &ImageMediaType::Png, 1, 1).unwrap();
    let jpeg = include_bytes!("../fixtures/image-v2/pixel.jpg");
    crate::image::container(jpeg, &ImageMediaType::Jpeg, 1, 1).unwrap();
    for bytes in [
        vec![],
        vec![0; 100],
        png()[..20].to_vec(),
        [png(), vec![0]].concat(),
    ] {
        assert!(crate::image::container(&bytes, &ImageMediaType::Png, 1, 1).is_err());
    }
    assert!(crate::image::container(&png(), &ImageMediaType::Jpeg, 1, 1).is_err());
    for (w, h) in [(0, 1), (1, 0), (2, 1), (2401, 1)] {
        assert!(crate::image::container(&png(), &ImageMediaType::Png, w, h).is_err());
    }
    assert!(matches!(
        crate::image::container(&vec![0; 8 * 1024 * 1024 + 1], &ImageMediaType::Png, 1, 1),
        Err(CoreError::ResourceLimit { .. })
    ));
    for i in 0..png().len() {
        let _ = crate::image::container(&png()[..i], &ImageMediaType::Png, 1, 1);
    }
    for i in 0..jpeg.len() {
        let _ = crate::image::container(&jpeg[..i], &ImageMediaType::Jpeg, 1, 1);
    }
    for bad in [
        ImagePlacement::Canvas {
            sheet_id: new_id(),
            x: f64::NAN,
            y: 0.0,
            width: 1.0,
            height: 1.0,
        },
        ImagePlacement::Text {
            anchor_id: new_id(),
            width: 0.0,
            height: 1.0,
        },
    ] {
        assert!(crate::image::placement(&bad).is_err());
    }
}
#[test]
fn image_add_move_delete_restore_undo_redo_seen_reopen_and_export() {
    let (root, lib, note, r) = setup();
    let id = new_id();
    let add = command(
        vec![add(&id, &r.first_sheet_id)],
        vec![r.operation_id.clone()],
    );
    note.submit(add.clone()).unwrap();
    assert!(note.submit(add.clone()).unwrap().duplicate);
    assert_eq!(note.get_image_bytes(id.clone()).unwrap(), png());
    let seen = command(
        vec![ImageMutation::MarkSeen {
            image_id: id.clone(),
            base: base(&add.operation_id),
        }],
        vec![add.operation_id.clone()],
    );
    assert!(note.submit(seen.clone()).is_err());
    note.submit_agent(seen.clone()).unwrap();
    assert!(note.submit_agent(seen).unwrap().duplicate);
    assert!(note.snapshot().unwrap().images[0].seen_by_companion);
    assert!(note.agent_snapshot().unwrap().undo_target.is_none());
    history(&note, false);
    assert!(
        note.snapshot().unwrap().images[0].tombstone
            && note.snapshot().unwrap().images[0].seen_by_companion
    );
    history(&note, true);
    assert!(
        !note.snapshot().unwrap().images[0].tombstone
            && note.snapshot().unwrap().images[0].seen_by_companion
    );
    for kind in 0..3 {
        let v = note.snapshot().unwrap().images[0].clone();
        let deps = vec![v.head.operation_id.clone(), r.operation_id.clone()];
        let m = match kind {
            0 => ImageMutation::Move {
                image_id: id.clone(),
                base: base(&v.head.operation_id),
                placement: ImagePlacement::Canvas {
                    sheet_id: r.first_sheet_id.clone(),
                    x: 80.0,
                    y: 90.0,
                    width: 200.0,
                    height: 180.0,
                },
            },
            1 => ImageMutation::Delete {
                image_id: id.clone(),
                base: base(&v.head.operation_id),
            },
            _ => ImageMutation::Restore {
                image_id: id.clone(),
                base: base(&v.head.operation_id),
            },
        };
        let c = command(vec![m], deps);
        note.submit(c.clone()).unwrap();
        let after = note.snapshot().unwrap().images[0].clone();
        assert!(note.submit(c).unwrap().duplicate);
        history(&note, false);
        assert_eq!(note.snapshot().unwrap().images[0].placement, v.placement);
        history(&note, true);
        assert_eq!(
            note.snapshot().unwrap().images[0].placement,
            after.placement
        );
        assert!(note.snapshot().unwrap().images[0].seen_by_companion);
    }
    let export_root = tempfile::tempdir().unwrap();
    let exported = export_root.path().join("export.syncr");
    note.export_copy(exported.to_str().unwrap().into()).unwrap();
    note.close_note().unwrap();
    let reopened = lib.open_note(r.note_id.clone()).unwrap();
    assert_eq!(reopened.get_image_bytes(id.clone()).unwrap(), png());
    assert!(reopened.snapshot().unwrap().images[0].seen_by_companion);
    let db = rusqlite::Connection::open(root.path().join(format!("{}.syncr", r.note_id))).unwrap();
    assert_eq!(crate::store::check_version(&db).unwrap(), 7);
    let payload: String = db
        .query_row(
            "SELECT payload FROM operations WHERE id=?1",
            [add.operation_id.clone()],
            |row| row.get(0),
        )
        .unwrap();
    assert!(!payload.contains("bytes"));
    let replay = crate::store::replay(&db).unwrap();
    assert_eq!(replay.images, reopened.snapshot().unwrap().images);
    let imported_root = tempfile::tempdir().unwrap();
    std::fs::copy(&exported, imported_root.path().join("imported.syncr")).unwrap();
    let imported = open_library(imported_root.path().to_str().unwrap().into()).unwrap();
    let imported_note = imported.open_note(r.note_id).unwrap();
    assert_eq!(imported_note.get_image_bytes(id).unwrap(), png());
    assert_eq!(
        imported_note.snapshot().unwrap().images,
        reopened.snapshot().unwrap().images
    );
}
#[test]
fn image_namespace_dependencies_atomicity_and_capacity() {
    let (_root, _lib, note, r) = setup();
    let id = new_id();
    let first = command(
        vec![add(&id, &r.first_sheet_id)],
        vec![r.operation_id.clone()],
    );
    assert!(
        note.submit(command(vec![add(&id, &r.first_sheet_id)], vec![]))
            .is_err()
    );
    assert!(note.submit_agent(first.clone()).is_err());
    note.submit(first.clone()).unwrap();
    assert!(
        note.submit(command(
            vec![add(&id, &r.first_sheet_id)],
            vec![r.operation_id.clone()]
        ))
        .is_err()
    );
    let snapshot = note.snapshot().unwrap();
    assert!(
        note.submit(command(
            vec![
                add(&new_id(), &r.first_sheet_id),
                ImageMutation::Delete {
                    image_id: new_id(),
                    base: base(&first.operation_id)
                }
            ],
            vec![first.operation_id.clone(), r.operation_id.clone()]
        ))
        .is_err()
    );
    assert_eq!(note.snapshot().unwrap(), snapshot);
    let mut state = crate::reducer::State::new(&r);
    state.images = vec![snapshot.images[0].clone(); 200];
    assert!(matches!(
        state.apply_images(
            &[add(&new_id(), &r.first_sheet_id)],
            &new_id(),
            &new_id(),
            &[r.operation_id.as_str()].into_iter().collect()
        ),
        Err(CoreError::ResourceLimit { .. })
    ));
    let collision = Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id],
        body: CommandBody::CompanionMarks {
            mutations: vec![CompanionMutation {
                id,
                base: Base::Absent,
                tombstone: false,
                body: CompanionBody::Anchor {
                    target: AnchorTarget::Canvas {
                        sheet_id: r.first_sheet_id,
                        x: 0.0,
                        y: 0.0,
                        width: 1.0,
                        height: 1.0,
                    },
                },
            }],
        },
    };
    assert!(matches!(
        note.submit(collision),
        Err(CoreError::IdCollision { .. })
    ));
}
#[test]
fn image_independent_canonical_vectors() {
    let id = |n| format!("{n:026}");
    for (name, m) in [
        ("add", add(&id(4), &id(5))),
        (
            "delete",
            ImageMutation::Delete {
                image_id: id(4),
                base: Base::Absent,
            },
        ),
        (
            "restore",
            ImageMutation::Restore {
                image_id: id(4),
                base: Base::Absent,
            },
        ),
        (
            "move",
            ImageMutation::Move {
                image_id: id(4),
                base: Base::Absent,
                placement: ImagePlacement::Text {
                    anchor_id: id(5),
                    width: 200.0,
                    height: 100.0,
                },
            },
        ),
        (
            "seen",
            ImageMutation::MarkSeen {
                image_id: id(4),
                base: Base::Absent,
            },
        ),
    ] {
        let c = Command {
            operation_id: id(3),
            dependencies: vec![],
            body: CommandBody::ImageMarks { mutations: vec![m] },
        };
        let got = crate::canonical::command(&c, &id(1), &id(2))
            .unwrap()
            .iter()
            .map(|v| format!("{v:02x}"))
            .collect::<String>();
        let expected = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join(format!("fixtures/image-v2/{name}.hex")),
        )
        .unwrap();
        assert_eq!(got, expected.trim());
    }
}

#[test]
fn text_image_anchor_follows_edits_and_versions_never_downgrade() {
    let (root, _lib, note, r) = setup();
    let db = rusqlite::Connection::open(root.path().join(format!("{}.syncr", r.note_id))).unwrap();
    assert_eq!(crate::store::check_version(&db).unwrap(), 3);
    assert!(
        note.submit(command(vec![add(&new_id(), &new_id())], vec![]))
            .is_err()
    );
    assert_eq!(crate::store::check_version(&db).unwrap(), 3);
    let block = new_id();
    let text_op = new_id();
    note.submit(Command {
        operation_id: text_op.clone(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddText {
                mark_id: block.clone(),
                sheet_id: r.first_sheet_id,
                role: TextRole::Document,
                origin_x: 0.0,
                origin_y: 0.0,
                width: 400.0,
                text: "Passage".into(),
                style: TextStyle {
                    size: BrushSize::Medium,
                    colour: InkColour::Ink,
                },
            }],
        },
    })
    .unwrap();
    let anchor = new_id();
    let anchor_op = new_id();
    note.submit(Command {
        operation_id: anchor_op.clone(),
        dependencies: vec![text_op.clone()],
        body: CommandBody::CompanionMarks {
            mutations: vec![CompanionMutation {
                id: anchor.clone(),
                base: Base::Absent,
                tombstone: false,
                body: CompanionBody::Anchor {
                    target: AnchorTarget::Text {
                        block_id: block.clone(),
                        start: 0,
                        end: 7,
                        detached: false,
                    },
                },
            }],
        },
    })
    .unwrap();
    let id = new_id();
    note.submit(command(
        vec![ImageMutation::Add {
            image_id: id.clone(),
            bytes: png(),
            media_type: ImageMediaType::Png,
            pixel_width: 1,
            pixel_height: 1,
            placement: ImagePlacement::Text {
                anchor_id: anchor.clone(),
                width: 100.0,
                height: 100.0,
            },
        }],
        vec![anchor_op],
    ))
    .unwrap();
    let edit = Command {
        operation_id: new_id(),
        dependencies: vec![text_op.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::SpliceText {
                mark_id: block,
                base: base(&text_op),
                offset: 0,
                delete: 0,
                insert: "Above.\n".into(),
            }],
        },
    };
    note.submit(edit).unwrap();
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text {
                start: 7,
                end: 14,
                ..
            }
        }
    ));
    history(&note, false);
    assert!(matches!(
        &note.snapshot().unwrap().companion_items[0].body,
        CompanionBody::Anchor {
            target: AnchorTarget::Text {
                start: 0,
                end: 7,
                ..
            }
        }
    ));
    assert_eq!(note.get_image_bytes(id).unwrap(), png());
    let settings = CompanionSettings {
        tell: String::new(),
        mode: CompanionMode::Off,
        web: false,
        active_reader: String::new(),
        colour: InkColour::Ink,
    };
    note.submit(Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::SetCompanionSettings {
            settings,
            base: base(&r.operation_id),
        },
    })
    .unwrap();
    assert_eq!(crate::store::check_version(&db).unwrap(), 7);
}
