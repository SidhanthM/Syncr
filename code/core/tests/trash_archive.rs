//! Archive (note metadata) and Recently deleted (`<root>/.trash/`): delete, restore, purge.
mod common;
use common::{base, blob, brush, path, request};
use std::path::Path;
use syncr_core::*;

fn set_archived(note: &Note, archived: bool) -> Command {
    let head = note.snapshot().unwrap().metadata_head.operation_id;
    Command {
        operation_id: new_id(),
        dependencies: vec![head.clone()],
        body: CommandBody::SetArchived {
            archived,
            base: base(&head),
        },
    }
}
fn add_stroke(note: &Note) -> Command {
    let sheet = note.snapshot().unwrap().sheets[0].clone();
    let c = Command {
        operation_id: new_id(),
        dependencies: vec![sheet.creation_operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: new_id(),
                sheet_id: sheet.id,
                samples: blob(),
                brush: brush(),
            }],
        },
    };
    note.submit(c.clone()).unwrap();
    c
}
fn summary(lib: &SyncrLibrary, id: &str) -> Option<NoteSummary> {
    lib.list_notes().unwrap().into_iter().find(|s| s.id == id)
}
fn deleted_ids(lib: &SyncrLibrary) -> Vec<Id> {
    lib.list_deleted_notes()
        .unwrap()
        .into_iter()
        .map(|d| d.id)
        .collect()
}
fn trash_files(root: &Path) -> Vec<String> {
    let mut names: Vec<_> = std::fs::read_dir(root.join(".trash"))
        .map(|d| {
            d.map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

#[test]
fn archive_uses_metadata_base_rules_stays_out_of_ink_history_and_survives_restart() {
    let root = tempfile::tempdir().unwrap();
    let r = request(Some("Physics"));
    {
        let lib = open_library(path(root.path())).unwrap();
        lib.create_note(r.clone()).unwrap();
        assert!(!summary(&lib, &r.note_id).unwrap().archived);
        let note = lib.open_note(r.note_id.clone()).unwrap();
        assert!(!note.snapshot().unwrap().archived);
        let ink = add_stroke(&note);
        let before = note.snapshot().unwrap();

        // Stale base: the metadata head is the creation, not the ink operation.
        let stale = Command {
            operation_id: new_id(),
            dependencies: vec![ink.operation_id.clone()],
            body: CommandBody::SetArchived {
                archived: true,
                base: base(&ink.operation_id),
            },
        };
        assert!(matches!(
            note.submit(stale),
            Err(CoreError::StaleBase { .. })
        ));
        let mut missing = set_archived(&note, true);
        missing.dependencies.clear();
        assert!(matches!(
            note.submit(missing),
            Err(CoreError::MissingDependency { .. })
        ));
        assert_eq!(note.snapshot().unwrap(), before);

        let c = set_archived(&note, true);
        let receipt = note.submit(c.clone()).unwrap();
        let retry = note.submit(c.clone()).unwrap();
        assert!(retry.duplicate);
        assert_eq!(retry.local_sequence, receipt.local_sequence);
        let mut different = c.clone();
        different.body = CommandBody::SetArchived {
            archived: false,
            base: base(&r.operation_id),
        };
        assert!(matches!(
            note.submit(different),
            Err(CoreError::IdCollision { .. })
        ));
        let after = note.snapshot().unwrap();
        assert!(after.archived);
        assert_eq!(after.metadata_head.operation_id, c.operation_id);
        assert_eq!(after.undo_target, before.undo_target);
        assert_eq!(after.undo_dependencies, before.undo_dependencies);
        assert_eq!(after.redo_target, before.redo_target);
        assert_eq!(after.strokes, before.strokes);
        assert_eq!(after.notebook.as_deref(), Some("Physics"));
        // Archive is metadata only: notebook membership and counts are unchanged.
        assert_eq!(
            lib.list_notebooks().unwrap(),
            vec![NotebookSummary {
                name: "Physics".into(),
                note_count: 1
            }]
        );
        let s = summary(&lib, &r.note_id).unwrap();
        assert!(s.archived);
        assert_eq!(s.local_sequence, after.local_sequence);
        note.close_note().unwrap();
    }
    let lib = open_library(path(root.path())).unwrap();
    assert!(summary(&lib, &r.note_id).unwrap().archived);
    let note = lib.open_note(r.note_id.clone()).unwrap();
    assert!(note.snapshot().unwrap().archived);
    note.submit(set_archived(&note, false)).unwrap();
    assert!(!note.snapshot().unwrap().archived);
    assert!(!summary(&lib, &r.note_id).unwrap().archived);
    // Undo still targets the ink stroke, not the archive toggles.
    let s = note.snapshot().unwrap();
    assert!(s.undo_target.is_some());
    note.submit(Command {
        operation_id: new_id(),
        dependencies: s.undo_dependencies.clone(),
        body: CommandBody::Undo {
            target_operation_id: s.undo_target.unwrap(),
        },
    })
    .unwrap();
    assert!(note.snapshot().unwrap().strokes[0].tombstone);
    assert!(!note.snapshot().unwrap().archived);
    note.close_note().unwrap();
}

#[test]
fn legacy_v1_note_reads_as_not_archived_and_can_be_archived() {
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/legacy-v1");
    let root = tempfile::tempdir().unwrap();
    let id = "01M759CD8RBPHQNP94FKX26ZHT";
    std::fs::copy(
        fixtures.join("note-v1.syncr"),
        root.path().join(format!("{id}.syncr")),
    )
    .unwrap();
    std::fs::copy(
        fixtures.join("library-v1.sqlite3"),
        root.path().join("library.sqlite3"),
    )
    .unwrap();
    let lib = open_library(path(root.path())).unwrap();
    assert!(!summary(&lib, id).unwrap().archived);
    let note = lib.open_note(id.into()).unwrap();
    assert!(!note.snapshot().unwrap().archived);
    note.submit(set_archived(&note, true)).unwrap();
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    let s = summary(&lib, id).unwrap();
    assert!(s.archived);
    assert_eq!(s.local_sequence, 7);
    // Legacy notes go through Recently deleted like any other.
    lib.delete_note(id.into()).unwrap();
    assert_eq!(deleted_ids(&lib), vec![id.to_string()]);
    lib.restore_note(id.into()).unwrap();
    assert!(summary(&lib, id).unwrap().archived);
}

#[test]
fn delete_hides_the_note_lists_it_as_deleted_and_restore_gives_back_the_identical_note() {
    let root = tempfile::tempdir().unwrap();
    let r = request(Some("Physics"));
    let other = request(None);
    let lib = open_library(path(root.path())).unwrap();
    lib.create_note(r.clone()).unwrap();
    lib.create_note(other.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    let ink = add_stroke(&note);
    note.submit(set_archived(&note, true)).unwrap();
    let before = note.snapshot().unwrap();
    let before_summary = summary(&lib, &r.note_id).unwrap();

    // A live handle refuses deletion; nothing moves.
    assert!(matches!(
        lib.delete_note(r.note_id.clone()),
        Err(CoreError::NoteLocked {
            retryable: true,
            ..
        })
    ));
    assert!(summary(&lib, &r.note_id).is_some());
    assert!(deleted_ids(&lib).is_empty());
    note.close_note().unwrap();

    let t0 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    lib.delete_note(r.note_id.clone()).unwrap();
    lib.delete_note(r.note_id.clone()).unwrap(); // idempotent
    assert!(summary(&lib, &r.note_id).is_none());
    assert!(summary(&lib, &other.note_id).is_some());
    assert!(lib.list_notebooks().unwrap().is_empty()); // deleted notes don't count
    assert!(matches!(
        lib.open_note(r.note_id.clone()),
        Err(CoreError::NotFound { .. })
    ));
    assert!(!root.path().join(format!("{}.syncr", r.note_id)).exists());
    let deleted = lib.list_deleted_notes().unwrap();
    assert_eq!(deleted.len(), 1);
    let d = &deleted[0];
    assert_eq!(d.id, r.note_id);
    assert_eq!(d.title, "Note");
    assert_eq!(d.notebook.as_deref(), Some("Physics"));
    assert_eq!(d.created_at_ms, before_summary.created_at_ms);
    assert!(d.deleted_at_ms >= t0);
    assert_eq!(trash_files(root.path()).len(), 1);

    // Survives a restart; startup cleanup leaves Recently deleted alone.
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(lib.list_deleted_notes().unwrap(), deleted);
    assert_eq!(lib.list_notes().unwrap().len(), 1);

    lib.restore_note(r.note_id.clone()).unwrap();
    lib.restore_note(r.note_id.clone()).unwrap(); // idempotent
    assert!(deleted_ids(&lib).is_empty());
    assert_eq!(summary(&lib, &r.note_id).unwrap(), before_summary);
    let note = lib.open_note(r.note_id.clone()).unwrap();
    let mut after = note.snapshot().unwrap();
    after.cursor = before.cursor.clone();
    assert_eq!(after, before);
    assert!(note.submit(ink).unwrap().duplicate);
    add_stroke(&note);
    note.close_note().unwrap();

    // Unknown IDs are NotFound for every trash operation.
    let unknown = new_id();
    for result in [
        lib.delete_note(unknown.clone()),
        lib.restore_note(unknown.clone()),
        lib.purge_note(unknown),
    ] {
        assert!(matches!(result, Err(CoreError::NotFound { .. })));
    }
    // Purge only applies to Recently deleted.
    assert!(matches!(
        lib.purge_note(r.note_id.clone()),
        Err(CoreError::InvalidInput { .. })
    ));
}

#[test]
fn restore_never_overwrites_or_duplicates_a_library_note() {
    let root = tempfile::tempdir().unwrap();
    let r = request(None);
    let lib = open_library(path(root.path())).unwrap();
    lib.create_note(r.clone()).unwrap();
    lib.delete_note(r.note_id.clone()).unwrap();
    // A copy of the same note reappears in the library under another name (e.g. an export).
    let trashed = root
        .path()
        .join(".trash")
        .join(&trash_files(root.path())[0]);
    let copy = root.path().join("copy.syncr");
    std::fs::copy(&trashed, &copy).unwrap();
    assert!(matches!(
        lib.restore_note(r.note_id.clone()),
        Err(CoreError::IdCollision { .. })
    ));
    assert!(trashed.exists());
    std::fs::remove_file(&copy).unwrap();
    // A different file occupying the restore name is never replaced.
    let target = root.path().join(format!("{}.syncr", r.note_id));
    std::fs::create_dir(&target).unwrap();
    assert!(matches!(
        lib.restore_note(r.note_id.clone()),
        Err(CoreError::IdCollision { .. })
    ));
    assert!(target.is_dir() && trashed.exists());
    std::fs::remove_dir(&target).unwrap();
    lib.restore_note(r.note_id.clone()).unwrap();
    assert!(summary(&lib, &r.note_id).is_some());
}

#[test]
fn creation_lookup_and_retry_of_a_deleted_or_purged_note_never_resurrect_it() {
    let root = tempfile::tempdir().unwrap();
    let r = request(None);
    let lib = open_library(path(root.path())).unwrap();
    let original = lib.create_note(r.clone()).unwrap();
    lib.delete_note(r.note_id.clone()).unwrap();

    // Deleted: the creation is still recorded; a retry reports it and stays deleted.
    assert_eq!(
        lib.lookup_creation(r.operation_id.clone()).unwrap(),
        OperationLookup::Committed {
            receipt: original.clone()
        }
    );
    let retry = lib.create_note(r.clone()).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, original.local_sequence);
    assert!(lib.list_notes().unwrap().is_empty());
    assert_eq!(deleted_ids(&lib), vec![r.note_id.clone()]);
    for changed in [
        CreateNoteRequest {
            title: "Changed".into(),
            ..r.clone()
        },
        CreateNoteRequest {
            operation_id: new_id(),
            ..r.clone()
        },
    ] {
        assert!(matches!(
            lib.create_note(changed),
            Err(CoreError::IdCollision { .. })
        ));
    }
    assert!(lib.list_notes().unwrap().is_empty());

    // Purged: no record remains, and a stale retry still cannot recreate it.
    lib.purge_note(r.note_id.clone()).unwrap();
    lib.purge_note(r.note_id.clone()).unwrap(); // idempotent
    assert_eq!(
        lib.lookup_creation(r.operation_id.clone()).unwrap(),
        OperationLookup::NotRecorded
    );
    for retry in [
        r.clone(),
        CreateNoteRequest {
            note_id: new_id(),
            ..r.clone()
        },
        CreateNoteRequest {
            operation_id: new_id(),
            ..r.clone()
        },
    ] {
        assert!(matches!(
            lib.create_note(retry),
            Err(CoreError::IdCollision { .. })
        ));
    }
    assert!(lib.list_notes().unwrap().is_empty());
    assert!(deleted_ids(&lib).is_empty());
    assert!(matches!(
        lib.restore_note(r.note_id.clone()),
        Err(CoreError::NotFound { .. })
    ));
    drop(lib);
    // The purge record survives a restart.
    let lib = open_library(path(root.path())).unwrap();
    assert!(matches!(
        lib.create_note(r),
        Err(CoreError::IdCollision { .. })
    ));
}

#[test]
fn purge_removes_the_file_and_its_sidecars_and_purge_older_than_respects_the_cutoff() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let notes: Vec<_> = (0..3).map(|_| request(None)).collect();
    for r in &notes {
        lib.create_note(r.clone()).unwrap();
        // Opening for write leaves the file in WAL mode, like a real edited note.
        let note = lib.open_note(r.note_id.clone()).unwrap();
        add_stroke(&note);
        note.close_note().unwrap();
    }
    lib.delete_note(notes[0].note_id.clone()).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(5));
    let cutoff = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    std::thread::sleep(std::time::Duration::from_millis(5));
    lib.delete_note(notes[1].note_id.clone()).unwrap();
    lib.delete_note(notes[2].note_id.clone()).unwrap();
    // Newest deletion first.
    let listed = deleted_ids(&lib);
    assert_eq!(listed.len(), 3);
    assert_eq!(listed[2], notes[0].note_id);

    // Sidecars beside a deleted file (e.g. from listing it read-only) are purged with it.
    let file = |id: &str| {
        trash_files(root.path())
            .into_iter()
            .find(|n| n.starts_with(id) && n.ends_with(".syncr"))
            .map(|n| root.path().join(".trash").join(n))
    };
    let first = file(&notes[0].note_id).unwrap();
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut name = first.as_os_str().to_os_string();
        name.push(suffix);
        std::fs::write(name, b"").unwrap();
    }
    assert_eq!(lib.purge_deleted_older_than(cutoff).unwrap(), 1);
    assert!(file(&notes[0].note_id).is_none());
    assert!(
        trash_files(root.path())
            .iter()
            .all(|n| !n.starts_with(notes[0].note_id.as_str()))
    );
    assert_eq!(lib.purge_deleted_older_than(cutoff).unwrap(), 0);
    assert_eq!(deleted_ids(&lib).len(), 2);

    lib.purge_note(notes[1].note_id.clone()).unwrap();
    assert_eq!(deleted_ids(&lib), vec![notes[2].note_id.clone()]);
    assert_eq!(lib.purge_deleted_older_than(i64::MAX).unwrap(), 1);
    assert!(deleted_ids(&lib).is_empty());
    assert!(lib.list_notes().unwrap().is_empty());
    assert!(
        trash_files(root.path())
            .iter()
            .all(|n| !n.contains(".syncr"))
    );
    // The library root holds no note files or stale sidecars for the deleted notes.
    for r in &notes {
        for suffix in ["", "-wal", "-shm"] {
            assert!(
                !root
                    .path()
                    .join(format!("{}.syncr{suffix}", r.note_id))
                    .exists()
            );
        }
    }
}

#[test]
fn an_unreadable_deleted_file_never_blocks_other_creations_and_still_reserves_its_id() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let r = request(None);
    lib.create_note(r.clone()).unwrap();
    lib.delete_note(r.note_id.clone()).unwrap();
    let corrupt_id = new_id();
    std::fs::write(
        root.path()
            .join(".trash")
            .join(format!("{corrupt_id}.1.syncr")),
        b"not a note",
    )
    .unwrap();
    // Listing is honest about the unreadable file rather than hiding it.
    assert!(lib.list_deleted_notes().is_err());
    // Unrelated creation, lookup and retry still work; the unreadable file's ID stays reserved.
    let other = request(None);
    lib.create_note(other.clone()).unwrap();
    assert!(matches!(
        lib.lookup_creation(r.operation_id.clone()).unwrap(),
        OperationLookup::Committed { .. }
    ));
    assert!(lib.create_note(r.clone()).unwrap().duplicate);
    assert!(matches!(
        lib.create_note(CreateNoteRequest {
            note_id: corrupt_id.clone(),
            ..request(None)
        }),
        Err(CoreError::IdCollision { .. })
    ));
    // Expiry works from file names, so the unreadable file is purged with the rest.
    assert_eq!(lib.purge_deleted_older_than(2).unwrap(), 1);
    assert_eq!(deleted_ids(&lib), vec![r.note_id.clone()]);
    assert_eq!(lib.list_notes().unwrap().len(), 1);
}
