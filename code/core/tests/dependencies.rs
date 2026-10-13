//! Public snapshot dependency regressions. No caller-side history or sheet index.
use std::{path::Path, sync::Arc};
use syncr_core::*;

fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
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
        title: "Dependencies".into(),
        page_format: PageFormat::Sheet16x10,
        notebook: None,
    };
    lib.create_note(request.clone()).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    (root, lib, request, note)
}
fn base(id: &str) -> Base {
    Base::Operation {
        operation_id: id.into(),
    }
}
fn command(body: CommandBody, dependencies: Vec<Id>) -> Command {
    Command {
        operation_id: new_id(),
        dependencies,
        body,
    }
}
fn add(mark_id: Id, sheet: &SheetView) -> MarkMutation {
    MarkMutation::AddStroke {
        mark_id,
        sheet_id: sheet.id.clone(),
        samples: encode_samples(SampleChannels {
            x: vec![1.0],
            y: vec![2.0],
            pressure: vec![0.5],
            elapsed_ms: vec![0],
            tilt: None,
            orientation: None,
        })
        .unwrap(),
        brush: Brush {
            family: BrushFamily::Pen,
            size: BrushSize::Medium,
            colour: InkColour::Ink,
        },
    }
}
fn history(snapshot: &NoteSnapshot, redo: bool) -> Command {
    if redo {
        command(
            CommandBody::Redo {
                target_operation_id: snapshot.redo_target.clone().unwrap(),
            },
            snapshot.redo_dependencies.clone(),
        )
    } else {
        command(
            CommandBody::Undo {
                target_operation_id: snapshot.undo_target.clone().unwrap(),
            },
            snapshot.undo_dependencies.clone(),
        )
    }
}
fn rejected(note: &Note, c: &Command, stale: bool) {
    let before = note.snapshot().unwrap();
    let result = note.submit(c.clone());
    if stale {
        assert!(
            matches!(result, Err(CoreError::StaleBase { .. })),
            "{result:?}"
        );
    } else {
        assert!(
            matches!(result, Err(CoreError::MissingDependency { .. })),
            "{result:?}"
        );
    }
    assert_eq!(note.snapshot().unwrap(), before);
    assert_eq!(
        note.lookup_operation(c.operation_id.clone()).unwrap(),
        OperationLookup::NotRecorded
    );
}

#[test]
fn every_sheet_retains_exact_creator_after_add_reorder_rename_and_full_restart() {
    let (root, lib, request, note) = setup();
    let mut expected = vec![(request.first_sheet_id.clone(), request.operation_id.clone())];
    for _ in 0..3 {
        let s = note.snapshot().unwrap();
        let c = command(
            CommandBody::AddSheet {
                sheet_id: new_id(),
                base: base(&s.structure_head.operation_id),
            },
            vec![s.structure_head.operation_id],
        );
        let CommandBody::AddSheet { sheet_id, .. } = &c.body else {
            unreachable!()
        };
        expected.push((sheet_id.clone(), c.operation_id.clone()));
        note.submit(c).unwrap();
    }
    expected.rotate_left(2);
    let s = note.snapshot().unwrap();
    let reorder = command(
        CommandBody::ReorderSheets {
            sheet_ids: expected.iter().map(|(id, _)| id.clone()).collect(),
            base: base(&s.structure_head.operation_id),
        },
        vec![s.structure_head.operation_id],
    );
    note.submit(reorder.clone()).unwrap();
    note.submit(command(
        CommandBody::RenameNote {
            title: "Renamed".into(),
            base: base(&request.operation_id),
        },
        vec![request.operation_id.clone()],
    ))
    .unwrap();
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.title, "Renamed");
    assert_eq!(s.structure_head.operation_id, reorder.operation_id);
    assert_eq!(
        s.sheets
            .iter()
            .map(|s| (s.id.clone(), s.creation_operation_id.clone()))
            .collect::<Vec<_>>(),
        expected
    );
    assert!(s.undo_dependencies.is_empty() && s.redo_dependencies.is_empty());
    for sheet in &s.sheets {
        let body = CommandBody::Marks {
            mutations: vec![add(new_id(), sheet)],
        };
        // Neither absence, latest structure head, nor another sheet's creator is valid.
        for deps in [
            vec![],
            vec![s.structure_head.operation_id.clone()],
            vec![
                s.sheets
                    .iter()
                    .find(|other| other.id != sheet.id)
                    .unwrap()
                    .creation_operation_id
                    .clone(),
            ],
        ] {
            rejected(&note, &command(body.clone(), deps), false);
        }
        let c = command(body, vec![sheet.creation_operation_id.clone()]);
        note.submit(c.clone()).unwrap();
        assert!(note.submit(c).unwrap().duplicate);
    }
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    assert_eq!(note.snapshot().unwrap().strokes.len(), expected.len());
    let db =
        rusqlite::Connection::open(root.path().join(format!("{}.syncr", request.note_id))).unwrap();
    assert_eq!(
        db.pragma_query_value::<u32, _>(None, "user_version", |r| r.get(0))
            .unwrap(),
        2
    );
}

#[test]
fn exact_history_dependencies_ignore_unrelated_marks_and_survive_restart_branches_and_retries() {
    let (root, lib, request, note) = setup();
    let sheet = note.snapshot().unwrap().sheets[0].clone();
    // More unrelated marks than the maximum affected action; no all-notebook list.
    for count in [512, 512, 128] {
        note.submit(command(
            CommandBody::Marks {
                mutations: (0..count).map(|_| add(new_id(), &sheet)).collect(),
            },
            vec![sheet.creation_operation_id.clone()],
        ))
        .unwrap();
    }
    // Give unrelated marks many distinct current heads as well.
    for stroke in note.snapshot().unwrap().strokes.iter().take(32) {
        note.submit(command(
            CommandBody::Marks {
                mutations: vec![MarkMutation::DeleteStroke {
                    mark_id: stroke.id.clone(),
                    base: base(&stroke.head.operation_id),
                }],
            },
            vec![stroke.head.operation_id.clone()],
        ))
        .unwrap();
    }
    let marks: Vec<_> = (0..3).map(|_| new_id()).collect();
    let action = command(
        CommandBody::Marks {
            mutations: marks.iter().map(|id| add(id.clone(), &sheet)).collect(),
        },
        vec![sheet.creation_operation_id.clone()],
    );
    note.submit(action.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.undo_target, Some(action.operation_id.clone()));
    assert_eq!(s.undo_dependencies, vec![action.operation_id.clone()]); // repeated heads deduplicate
    assert!(s.redo_dependencies.is_empty());
    let edit = command(
        CommandBody::Marks {
            mutations: marks[..2]
                .iter()
                .map(|id| MarkMutation::DeleteStroke {
                    mark_id: id.clone(),
                    base: base(&action.operation_id),
                })
                .collect(),
        },
        s.undo_dependencies.clone(),
    );
    note.submit(edit.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.undo_dependencies, vec![edit.operation_id.clone()]); // includes tombstones
    let staged_undo = history(&s, false);
    let mut missing = staged_undo.clone();
    missing.operation_id = new_id();
    missing.dependencies.clear();
    rejected(&note, &missing, false);
    let mut wrong = staged_undo.clone();
    wrong.operation_id = new_id();
    wrong.dependencies = vec![action.operation_id.clone()];
    rejected(&note, &wrong, false);
    note.submit(staged_undo.clone()).unwrap();
    let s = note.snapshot().unwrap();
    let mut heads = vec![
        staged_undo.operation_id.clone(),
        action.operation_id.clone(),
    ];
    heads.sort();
    assert_eq!(s.undo_dependencies, heads); // only two of three marks changed head
    assert_eq!(s.redo_dependencies, vec![staged_undo.operation_id.clone()]);
    let stale_redo = history(&s, true);
    let undo_add = history(&s, false);
    note.submit(undo_add.clone()).unwrap();
    let before_restart = note.snapshot().unwrap();
    assert_eq!(
        before_restart.redo_target,
        Some(action.operation_id.clone())
    );
    assert_eq!(
        before_restart.redo_dependencies,
        vec![undo_add.operation_id.clone()]
    );
    assert!(
        before_restart
            .strokes
            .iter()
            .filter(|s| marks.contains(&s.id))
            .all(|s| s.tombstone)
    );
    rejected(&note, &stale_redo, true); // target is no longer top
    // A committed immutable retry must not acquire the newer heads/target.
    assert!(note.submit(staged_undo.clone()).unwrap().duplicate);
    assert_eq!(note.snapshot().unwrap(), before_restart);
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(request.note_id).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.undo_target, before_restart.undo_target);
    assert_eq!(s.redo_target, before_restart.redo_target);
    assert_eq!(s.undo_dependencies, before_restart.undo_dependencies);
    assert_eq!(s.redo_dependencies, before_restart.redo_dependencies);
    // Reordering/renaming changes no history witness, even across restart.
    note.submit(command(
        CommandBody::RenameNote {
            title: "Still redoable".into(),
            base: base(&s.metadata_head.operation_id),
        },
        vec![s.metadata_head.operation_id.clone()],
    ))
    .unwrap();
    note.submit(command(
        CommandBody::ReorderSheets {
            sheet_ids: vec![sheet.id.clone()],
            base: base(&s.structure_head.operation_id),
        },
        vec![s.structure_head.operation_id.clone()],
    ))
    .unwrap();
    assert_eq!(
        note.snapshot().unwrap().redo_dependencies,
        s.redo_dependencies
    );
    let redo_add = history(&s, true);
    note.submit(redo_add.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.redo_target, Some(edit.operation_id.clone()));
    assert_eq!(s.redo_dependencies, vec![redo_add.operation_id.clone()]);
    // Same target as the original staged redo, but old heads: do not rebase it.
    rejected(&note, &stale_redo, false);
    let redo_edit = history(&s, true);
    note.submit(redo_edit.clone()).unwrap();
    assert_eq!(
        note.snapshot().unwrap().undo_dependencies,
        vec![redo_edit.operation_id.clone()]
    );
    note.submit(history(&note.snapshot().unwrap(), false))
        .unwrap();
    let abandoned_redo = history(&note.snapshot().unwrap(), true);
    let branch = command(
        CommandBody::Marks {
            mutations: vec![add(new_id(), &sheet)],
        },
        vec![sheet.creation_operation_id],
    );
    note.submit(branch.clone()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(s.undo_dependencies, vec![branch.operation_id]);
    assert_eq!(s.redo_target, None);
    assert!(s.redo_dependencies.is_empty());
    rejected(&note, &abandoned_redo, true);
    note.close_note().unwrap();
}
