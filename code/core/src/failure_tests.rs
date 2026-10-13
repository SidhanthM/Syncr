//! Faults are test-only. No production environment-controlled failure injection.
use crate::*;
use std::{path::Path, process::Command as ProcessCommand, sync::Arc};
const CREATE: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
const NOTE: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAW";
const SHEET: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAX";
const ADD: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAY";
const MARK: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAZ";
fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
}
fn request() -> CreateNoteRequest {
    CreateNoteRequest {
        operation_id: CREATE.into(),
        note_id: NOTE.into(),
        first_sheet_id: SHEET.into(),
        title: "Crash test".into(),
        page_format: PageFormat::Sheet16x10,
        notebook: None,
    }
}
fn command() -> Command {
    Command {
        operation_id: ADD.into(),
        dependencies: vec![CREATE.into()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: MARK.into(),
                sheet_id: SHEET.into(),
                samples: encode_samples(SampleChannels {
                    x: vec![-0.0],
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
            }],
        },
    }
}
#[test]
fn crash_probe() {
    let Ok(root) = std::env::var("SYNCR_CRASH_ROOT") else {
        return;
    };
    let lib = open_library(root).unwrap();
    let stage = std::env::var("SYNCR_CRASH_STAGE").unwrap();
    if stage.starts_with("delete_") {
        lib.delete_note(NOTE.into()).unwrap();
    } else if stage.starts_with("restore_") {
        lib.restore_note(NOTE.into()).unwrap();
    } else if stage.starts_with("purge_") {
        lib.purge_note(NOTE.into()).unwrap();
    } else if stage.starts_with("command_") {
        lib.open_note(NOTE.into())
            .unwrap()
            .submit(command())
            .unwrap();
    } else {
        lib.create_note(request()).unwrap();
    }
    panic!("Crash fault was not reached");
}
#[test]
fn process_exit_before_after_commit_and_publication() {
    for stage in [
        "create_before_commit",
        "create_after_commit",
        "after_publication",
        "command_before_commit",
        "command_after_commit",
    ] {
        let root = tempfile::tempdir().unwrap();
        if stage.starts_with("command_") {
            open_library(path(root.path()))
                .unwrap()
                .create_note(request())
                .unwrap();
        }
        let result = ProcessCommand::new(std::env::current_exe().unwrap())
            .args(["--exact", "failure_tests::crash_probe"])
            .env("SYNCR_CRASH_ROOT", root.path())
            .env("SYNCR_CRASH_STAGE", stage)
            .output()
            .unwrap();
        assert_eq!(
            result.status.code(),
            Some(91),
            "{stage}: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        let creation_published = stage == "after_publication" || stage.starts_with("command_");
        let leftovers = |root: &Path| {
            std::fs::read_dir(root)
                .unwrap()
                .filter(|e| {
                    e.as_ref()
                        .unwrap()
                        .file_name()
                        .to_string_lossy()
                        .starts_with(".syncr-create-")
                })
                .count()
        };
        // Publication renames the temp file onto the note, so a crash after it leaves
        // no creation-prefixed alias at all; earlier crashes leave one for cleanup.
        if creation_published {
            assert_eq!(leftovers(root.path()), 0, "{stage}");
        } else {
            assert!(leftovers(root.path()) > 0, "{stage}");
        }
        let lib = open_library(path(root.path())).unwrap();
        assert_eq!(
            lib.list_notes().unwrap().len(),
            usize::from(creation_published),
            "{stage}"
        );
        assert_eq!(leftovers(root.path()), 0, "{stage}");
        if creation_published {
            assert!(matches!(
                lib.lookup_creation(CREATE.into()).unwrap(),
                OperationLookup::Committed { .. }
            ));
            let note = lib.open_note(NOTE.into()).unwrap();
            let committed = stage == "command_after_commit";
            let s = note.snapshot().unwrap();
            assert_eq!(s.local_sequence, if committed { 2 } else { 1 });
            assert_eq!(s.strokes.len(), usize::from(committed));
            assert_eq!(
                matches!(
                    note.lookup_operation(ADD.into()).unwrap(),
                    OperationLookup::Committed { .. }
                ),
                committed
            );
            if committed {
                assert!(note.submit(command()).unwrap().duplicate);
                assert_eq!(
                    note.get_samples(MARK.into()).unwrap(),
                    match &command().body {
                        CommandBody::Marks { mutations } => match &mutations[0] {
                            MarkMutation::AddStroke { samples, .. } => samples.clone(),
                            _ => unreachable!(),
                        },
                        _ => unreachable!(),
                    }
                );
            }
        } else {
            assert_eq!(
                lib.lookup_creation(CREATE.into()).unwrap(),
                OperationLookup::NotRecorded
            );
            lib.create_note(request()).unwrap();
        }
    }
}
/// Delete, restore and purge killed at each fault point leave the note in exactly one place
/// (library or Recently deleted) or, after a recorded purge, finishable by purging again.
#[test]
fn process_exit_during_delete_restore_and_purge() {
    for stage in [
        "delete_before_rename",
        "delete_after_rename",
        "restore_after_rename",
        "purge_after_record",
    ] {
        let root = tempfile::tempdir().unwrap();
        {
            let lib = open_library(path(root.path())).unwrap();
            lib.create_note(request()).unwrap();
            let note = lib.open_note(NOTE.into()).unwrap();
            note.submit(command()).unwrap();
            note.close_note().unwrap();
            if !stage.starts_with("delete_") {
                lib.delete_note(NOTE.into()).unwrap();
            }
        }
        let result = ProcessCommand::new(std::env::current_exe().unwrap())
            .args(["--exact", "failure_tests::crash_probe"])
            .env("SYNCR_CRASH_ROOT", root.path())
            .env("SYNCR_CRASH_STAGE", stage)
            .output()
            .unwrap();
        assert_eq!(
            result.status.code(),
            Some(91),
            "{stage}: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        let lib = open_library(path(root.path())).unwrap();
        let listed = lib.list_notes().unwrap().len();
        let deleted = lib.list_deleted_notes().unwrap().len();
        let expected_listed = match stage {
            "delete_before_rename" | "restore_after_rename" => 1,
            _ => 0,
        };
        assert_eq!(listed, expected_listed, "{stage}");
        if stage == "purge_after_record" {
            // Recorded but the file is still there: still listed, purge finishes it.
            assert_eq!(deleted, 1, "{stage}");
            lib.purge_note(NOTE.into()).unwrap();
            assert!(lib.list_deleted_notes().unwrap().is_empty());
            assert!(matches!(
                lib.create_note(request()),
                Err(CoreError::IdCollision { .. })
            ));
            continue;
        }
        assert_eq!(listed + deleted, 1, "{stage}");
        if listed == 0 {
            lib.restore_note(NOTE.into()).unwrap();
        }
        let note = lib.open_note(NOTE.into()).unwrap();
        let s = note.snapshot().unwrap();
        assert_eq!((s.local_sequence, s.strokes.len()), (2, 1), "{stage}");
        assert!(note.submit(command()).unwrap().duplicate);
    }
}
// Library-private tests live in library.rs so production internals remain private.
pub(crate) fn large_add(note: &Arc<Note>, request: &CreateNoteRequest) -> Command {
    let mut c = command();
    c.operation_id = new_id();
    c.dependencies = vec![request.operation_id.clone()];
    if let CommandBody::Marks { mutations } = &mut c.body
        && let MarkMutation::AddStroke {
            sheet_id,
            samples,
            mark_id,
            ..
        } = &mut mutations[0]
    {
        *sheet_id = request.first_sheet_id.clone();
        *mark_id = new_id();
        *samples = encode_samples(SampleChannels {
            x: vec![0.0; 65536],
            y: vec![0.0; 65536],
            pressure: vec![1.0; 65536],
            elapsed_ms: vec![0; 65536],
            tilt: None,
            orientation: None,
        })
        .unwrap();
    }
    assert_eq!(note.snapshot().unwrap().local_sequence, 1);
    c
}
