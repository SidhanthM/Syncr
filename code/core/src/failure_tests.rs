//! Crash tests: a child process exits at a fault point (see `fault.rs`) and the parent
//! checks what a reopened library sees.
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
        notebook: None,
        kind: NoteKind::Note,
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
                brush: Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Ink),
            }],
        },
    }
}
/// Runs [crash_probe] in a child process and asserts that it died at `stage`.
pub(crate) fn run_crash_probe(root: &Path, stage: &str, note: Option<&str>) {
    let mut child = ProcessCommand::new(std::env::current_exe().unwrap());
    child
        .args(["--exact", "failure_tests::crash_probe"])
        .env("SYNCR_CRASH_ROOT", root)
        .env("SYNCR_CRASH_STAGE", stage);
    if let Some(note) = note {
        child.env("SYNCR_CRASH_NOTE", note);
    }
    let result = child.output().unwrap();
    assert_eq!(
        result.status.code(),
        Some(91),
        "{stage}: {}",
        String::from_utf8_lossy(&result.stderr)
    );
}
/// Child-process entry point: does the operation that reaches `SYNCR_CRASH_STAGE`. A no-op
/// in a normal test run.
#[test]
fn crash_probe() {
    let Ok(root) = std::env::var("SYNCR_CRASH_ROOT") else {
        return;
    };
    let lib = open_library(root).unwrap();
    let stage = std::env::var("SYNCR_CRASH_STAGE").unwrap();
    if stage.starts_with("upgrade_") {
        let note = std::env::var("SYNCR_CRASH_NOTE").unwrap();
        lib.open_note(note).unwrap();
    } else if stage.starts_with("delete_") {
        lib.delete_note(NOTE.into()).unwrap();
    } else if stage.starts_with("restore_") {
        lib.restore_note(NOTE.into()).unwrap();
    } else if stage.starts_with("purge_") {
        lib.purge_note(NOTE.into()).unwrap();
    } else if stage == "typing_then_kill" {
        // remaining-v1 L.3: many commits, most without a stored projection, then a kill.
        let note = lib.open_note(NOTE.into()).unwrap();
        for i in 0..TYPED {
            note.submit(typed(i)).unwrap();
        }
        std::process::exit(91);
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
const TYPED: usize = 150;
/// The `i`th of [TYPED] strokes, each its own commit, as a stand-in for typed text commits.
fn typed(i: usize) -> Command {
    let mut c = command();
    c.operation_id = format!("01ARZ3NDEKTSV4RRFFQ7{i:06}");
    if let CommandBody::Marks { mutations } = &mut c.body
        && let MarkMutation::AddStroke { mark_id, .. } = &mut mutations[0]
    {
        *mark_id = format!("01ARZ3NDEKTSV4RRFFQ8{i:06}");
    }
    c
}

/// The projection is stored only every few commits; the log holds each one. A writer killed
/// between stores loses nothing: a reader sees every commit before the note is reopened, the
/// reopened projection equals a replay of the log from empty, and the file's own projection is
/// brought up to date by the open (remaining-v1 L.3).
#[test]
fn a_kill_between_projection_stores_loses_no_commit() {
    let root = tempfile::tempdir().unwrap();
    open_library(path(root.path())).unwrap().create_note(request()).unwrap();
    run_crash_probe(root.path(), "typing_then_kill", None);
    let file = root.path().join(format!("{NOTE}.syncr"));
    {
        let db = store::open(&file, false).unwrap();
        let (state, behind) = store::load_behind(&db).unwrap();
        assert!(behind, "the stored projection lags the log after a kill");
        assert_eq!(state.strokes.len(), TYPED);
        assert_eq!(state.sequence, 1 + TYPED as u64);
        let replayed = store::replay(&db).unwrap();
        assert_eq!(store::json(&state).unwrap(), store::json(&replayed).unwrap(), "caught up equals replayed from empty");
    }
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(lib.list_notes().unwrap()[0].local_sequence, 1 + TYPED as u64);
    let note = lib.open_note(NOTE.into()).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!((s.local_sequence, s.strokes.len()), (1 + TYPED as u64, TYPED));
    assert!(note.submit(typed(7)).unwrap().duplicate);
    {
        let db = store::open(&file, false).unwrap();
        assert!(!store::load_behind(&db).unwrap().1, "opening stored the caught-up projection");
    }
    note.close_note().unwrap();
}

/// Commits store the projection only every `SAVE_EVERY`; closing and exporting store it, so a
/// closed or copied file never needs the log replayed.
#[test]
fn close_and_export_leave_a_current_projection() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    lib.create_note(request()).unwrap();
    let note = lib.open_note(NOTE.into()).unwrap();
    for i in 0..5 {
        note.submit(typed(i)).unwrap();
    }
    let file = root.path().join(format!("{NOTE}.syncr"));
    let stored = |f: &Path| {
        let db = store::open(f, false).unwrap();
        let value: String = db.query_row("SELECT state FROM document", [], |r| r.get(0)).unwrap();
        store::parse::<crate::reducer::State>(&value).unwrap().sequence
    };
    assert!(stored(&file) < 6, "five commits did not each store the projection");
    let copy = root.path().join("copy.syncr");
    note.export_copy(path(&copy)).unwrap();
    assert_eq!(stored(&copy), 6, "an exported copy carries a current projection");
    note.close_note().unwrap();
    assert_eq!(stored(&file), 6, "a closed note's file carries a current projection");
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
        run_crash_probe(root.path(), stage, None);
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
        run_crash_probe(root.path(), stage, None);
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
/// An AddStroke of 64 Ki samples (about 1 MiB), large enough to overflow a capped page count.
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
