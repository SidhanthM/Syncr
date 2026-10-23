//! Tests that need library and note internals.
use super::*;
fn setup() -> (
    tempfile::TempDir,
    Arc<SyncrLibrary>,
    CreateNoteRequest,
    Arc<Note>,
) {
    let root = tempfile::tempdir().unwrap();
    let library = open_library(root.path().to_str().unwrap().into()).unwrap();
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Failure fixture".into(),
        notebook: None,
    };
    library.create_note(request.clone()).unwrap();
    let note = library.open_note(request.note_id.clone()).unwrap();
    (root, library, request, note)
}
#[test]
fn actual_sqlite_full_rolls_back_projection_samples_log_history_and_events() {
    let (root, library, request, note) = setup();
    let before = note.snapshot().unwrap();
    let command = crate::failure_tests::large_add(&note, &request);
    {
        let r = note.runtime.lock().unwrap();
        let db = r.db().unwrap();
        store::checkpoint(db).unwrap();
        let count: u32 = db
            .pragma_query_value(None, "page_count", |r| r.get(0))
            .unwrap();
        db.pragma_update(None, "max_page_count", count).unwrap();
    }
    assert!(matches!(
        note.submit(command.clone()),
        Err(CoreError::StorageFull {
            retryable: true,
            ..
        })
    ));
    assert_eq!(note.snapshot().unwrap(), before);
    assert_eq!(
        note.lookup_operation(command.operation_id.clone()).unwrap(),
        OperationLookup::NotRecorded
    );
    let mark = match &command.body {
        CommandBody::Marks { mutations } => match &mutations[0] {
            MarkMutation::AddStroke { mark_id, .. } => mark_id.clone(),
            _ => unreachable!(),
        },
        _ => unreachable!(),
    };
    assert!(matches!(
        note.get_samples(mark),
        Err(CoreError::NotFound { .. })
    ));
    note.close_note().unwrap();
    drop(note);
    drop(library);
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    let note = lib.open_note(request.note_id).unwrap();
    assert_eq!(note.snapshot().unwrap().local_sequence, 1);
    assert_eq!(note.submit(command).unwrap().local_sequence, 2);
}
#[test]
fn stale_inverse_heads_abort_the_entire_batch() {
    for redo in [false, true] {
        let (_root, _lib, request, note) = setup();
        let mut c = crate::failure_tests::large_add(&note, &request);
        if let CommandBody::Marks { mutations } = &mut c.body {
            let mut second = mutations[0].clone();
            if let MarkMutation::AddStroke { mark_id, .. } = &mut second {
                *mark_id = new_id();
            }
            mutations.push(second);
        }
        note.submit(c.clone()).unwrap();
        let expected_head = if redo {
            let before = note.snapshot().unwrap();
            let undo = Command {
                operation_id: new_id(),
                dependencies: before.undo_dependencies,
                body: CommandBody::Undo {
                    target_operation_id: c.operation_id.clone(),
                },
            };
            note.submit(undo.clone()).unwrap();
            undo.operation_id
        } else {
            c.operation_id.clone()
        };
        let foreign = new_id();
        {
            // Simulate a future foreign-author head without weakening the checked inverse.
            let mut r = note.runtime.lock().unwrap();
            r.state.strokes[1].head.operation_id = foreign.clone();
        }
        let before = note.snapshot().unwrap();
        let mut expected = vec![foreign.clone(), expected_head];
        expected.sort();
        let dependencies = if redo {
            &before.redo_dependencies
        } else {
            &before.undo_dependencies
        };
        assert_eq!(dependencies, &expected);
        let undo = Command {
            operation_id: new_id(),
            dependencies: dependencies.clone(),
            body: if redo {
                CommandBody::Redo {
                    target_operation_id: c.operation_id,
                }
            } else {
                CommandBody::Undo {
                    target_operation_id: c.operation_id,
                }
            },
        };
        // Insert a durable dependency so contextual dependency lookup doesn't mask the head check.
        {
            let r = note.runtime.lock().unwrap();
            let receipt = Receipt {
                operation_id: foreign,
                local_sequence: 99,
                cursor: r.cursor(),
                duplicate: false,
            };
            let mut fake_state = r.state.clone();
            fake_state.sequence = 99;
            store::record(
                r.db().unwrap(),
                &fake_state,
                "test",
                b"test",
                &"foreign",
                &receipt,
                0,
            )
            .unwrap();
        }
        assert!(matches!(
            note.submit(undo),
            Err(CoreError::StaleBase { .. })
        ));
        assert_eq!(note.snapshot().unwrap(), before);
    }
}
#[test]
fn close_checkpoint_failure_keeps_handle_open_and_export_failure_is_not_success() {
    let (root, _lib, request, note) = setup();
    let reader = Connection::open(&note.path).unwrap();
    reader
        .execute_batch("BEGIN; SELECT state FROM document;")
        .unwrap();
    let c = Command {
        operation_id: new_id(),
        dependencies: vec![request.operation_id.clone()],
        body: CommandBody::RenameNote {
            title: "Pending checkpoint".into(),
            base: Base::Operation {
                operation_id: request.operation_id,
            },
        },
    };
    note.submit(c).unwrap();
    note.runtime
        .lock()
        .unwrap()
        .db()
        .unwrap()
        .busy_timeout(std::time::Duration::ZERO)
        .unwrap();
    assert!(matches!(
        note.close_note(),
        Err(CoreError::NoteLocked {
            retryable: true,
            ..
        })
    ));
    assert_eq!(note.snapshot().unwrap().local_sequence, 2);
    reader.execute_batch("ROLLBACK").unwrap();
    let blocked = root.path().join("not-a-directory");
    std::fs::write(&blocked, b"sentinel").unwrap();
    assert!(matches!(
        note.export_copy(blocked.join("copy.syncr").to_str().unwrap().into()),
        Err(CoreError::StorageIo { .. })
    ));
    assert_eq!(std::fs::read(&blocked).unwrap(), b"sentinel");
    assert!(!std::fs::read_dir(root.path()).unwrap().any(|e| {
        e.unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".syncr-export-")
    }));
    note.close_note().unwrap();
}
#[test]
fn interrupted_index_update_is_recovered_from_published_metadata() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    lib.catalog.lock().unwrap().db.execute_batch("CREATE TRIGGER fail_index BEFORE INSERT ON notes BEGIN SELECT RAISE(FAIL,'test'); END;").unwrap();
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Lost creation response".into(),
        notebook: None,
    };
    // Deliberately lose the successful reply while the cache is still unwritable.
    lib.create_note(request.clone()).unwrap();
    assert!(matches!(
        lib.lookup_creation(request.operation_id.clone()).unwrap(),
        OperationLookup::Committed { .. }
    ));
    assert!(lib.create_note(request).unwrap().duplicate);
    assert_eq!(lib.list_notes().unwrap().len(), 1);
}
#[test]
fn delete_folds_an_unfolded_wal_so_restore_keeps_every_commit() {
    let (root, library, request, note) = setup();
    let command = crate::failure_tests::large_add(&note, &request);
    note.submit(command.clone()).unwrap();
    let before = note.snapshot().unwrap();
    // Simulate a process that died with the commit only in the WAL: leak the connection.
    {
        let mut r = note.runtime.lock().unwrap();
        let db = r.db.take().unwrap();
        db.pragma_update(None, "wal_autocheckpoint", 0).unwrap();
        std::mem::forget(db);
    }
    let wal = files::sidecar(&note.path, "-wal");
    assert!(std::fs::metadata(&wal).unwrap().len() > 0);
    // The handle is no longer live (closed), so deleting is allowed.
    library.delete_note(request.note_id.clone()).unwrap();
    assert!(!note.path.exists());
    assert!(!std::fs::metadata(&wal).is_ok_and(|m| m.len() > 0));
    library.restore_note(request.note_id.clone()).unwrap();
    let reopened = library.open_note(request.note_id.clone()).unwrap();
    let mut after = reopened.snapshot().unwrap();
    after.cursor = before.cursor.clone();
    assert_eq!(after, before);
    assert!(reopened.submit(command).unwrap().duplicate);
    reopened.close_note().unwrap();
    drop(root);
}
#[test]
fn note_retains_library_lock_and_connections_use_full_wal() {
    let (root, library, request, note) = setup();
    let author = library.author_id();
    for db in [
        &library.catalog.lock().unwrap().db,
        note.runtime.lock().unwrap().db().unwrap(),
    ] {
        assert_eq!(
            db.pragma_query_value::<String, _>(None, "journal_mode", |r| r.get(0))
                .unwrap(),
            "wal"
        );
        assert_eq!(
            db.pragma_query_value::<u32, _>(None, "synchronous", |r| r.get(0))
                .unwrap(),
            2
        );
    }
    drop(library);
    assert!(matches!(
        open_library(root.path().to_str().unwrap().into()),
        Err(CoreError::NoteLocked { .. })
    ));
    note.close_note().unwrap();
    drop(note);
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    assert_eq!(lib.author_id(), author);
    assert_eq!(
        lib.open_note(request.note_id)
            .unwrap()
            .snapshot()
            .unwrap()
            .local_sequence,
        1
    );
}
