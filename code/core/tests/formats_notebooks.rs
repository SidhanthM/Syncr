//! The canvas size, flat notebooks, display timestamps and the v1 -> v3 upgrade.
mod common;
use common::{base, blob, brush, path, request};
use rusqlite::Connection;
use std::{path::Path, sync::Arc};
use syncr_core::*;

fn set_notebook(note: &Note, notebook: Option<&str>) -> Command {
    let head = note.snapshot().unwrap().metadata_head.operation_id;
    Command {
        operation_id: new_id(),
        dependencies: vec![head.clone()],
        body: CommandBody::SetNotebook {
            notebook: notebook.map(Into::into),
            base: base(&head),
        },
    }
}
fn add_stroke(note: &Note, sheet: &SheetView) -> Command {
    let c = Command {
        operation_id: new_id(),
        dependencies: vec![sheet.creation_operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: new_id(),
                sheet_id: sheet.id.clone(),
                samples: blob(),
                brush: brush(),
            }],
        },
    };
    note.submit(c.clone()).unwrap();
    c
}
fn summary(lib: &SyncrLibrary, id: &str) -> NoteSummary {
    lib.list_notes()
        .unwrap()
        .into_iter()
        .find(|s| s.id == id)
        .unwrap()
}
fn invalid<T: std::fmt::Debug>(result: CoreResult<T>) {
    assert!(
        matches!(result, Err(CoreError::InvalidInput { .. })),
        "{result:?}"
    );
}
fn ulid_ms(id: &str) -> i64 {
    id.parse::<ulid::Ulid>().unwrap().timestamp_ms() as i64
}

#[test]
fn a_note_is_one_canvas_with_one_sheet_at_the_origin() {
    assert_eq!(
        canvas_size(),
        CanvasSize {
            width: 4800.0,
            view_height: 3000.0,
            phone_column: 1600.0
        }
    );
    let root = tempfile::tempdir().unwrap();
    let r = request(None);
    let far = encode_samples(SampleChannels {
        x: vec![4790.0, 1601.0],
        y: vec![12000.0, 0.5],
        pressure: vec![0.5, 0.5],
        elapsed_ms: vec![0, 8],
        tilt: None,
        orientation: None,
    })
    .unwrap();
    let mark = new_id();
    {
        let lib = open_library(path(root.path())).unwrap();
        lib.create_note(r.clone()).unwrap();
        let note = lib.open_note(r.note_id.clone()).unwrap();
        let s = note.snapshot().unwrap();
        assert_eq!(s.sheets.len(), 1);
        assert_eq!((s.sheets[0].origin_x, s.sheets[0].origin_y), (0.0, 0.0));
        // Anywhere on the canvas, in canvas coordinates, on the first sheet. No clamping.
        note.submit(Command {
            operation_id: new_id(),
            dependencies: vec![r.operation_id.clone()],
            body: CommandBody::Marks {
                mutations: vec![MarkMutation::AddStroke {
                    mark_id: mark.clone(),
                    sheet_id: r.first_sheet_id.clone(),
                    samples: far.clone(),
                    brush: brush(),
                }],
            },
        })
        .unwrap();
        let head = s.structure_head.operation_id;
        invalid(note.submit(Command {
            operation_id: new_id(),
            dependencies: vec![head.clone()],
            body: CommandBody::AddSheet {
                sheet_id: new_id(),
                base: base(&head),
            },
        }));
        note.close_note().unwrap();
    }
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(r.note_id).unwrap();
    let s = note.snapshot().unwrap();
    assert_eq!(
        (s.sheets.len(), s.strokes.len(), s.local_sequence),
        (1, 1, 2)
    );
    assert_eq!(note.get_samples(mark).unwrap(), far);
    // No backup for a note that was created as a canvas.
    assert!(!root.path().join(".backups").exists());
}

#[test]
fn creation_identity_includes_notebook_but_no_format() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let r = request(Some("Physics"));
    let first = lib.create_note(r.clone()).unwrap();
    let retry = lib.create_note(r.clone()).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, first.local_sequence);
    for changed in [
        CreateNoteRequest {
            title: "Other".into(),
            ..r.clone()
        },
        CreateNoteRequest {
            notebook: None,
            ..r.clone()
        },
        CreateNoteRequest {
            notebook: Some("Journal".into()),
            ..r.clone()
        },
    ] {
        assert!(matches!(
            lib.create_note(changed),
            Err(CoreError::IdCollision { .. })
        ));
    }
    let s = summary(&lib, &r.note_id);
    assert_eq!(s.notebook.as_deref(), Some("Physics"));
    // Invalid notebook names never create a file.
    for bad in ["Inbox", "a/b", " Physics", ""] {
        invalid(lib.create_note(request(Some(bad))));
    }
    assert_eq!(lib.list_notes().unwrap().len(), 1);
}

#[test]
fn set_notebook_uses_metadata_base_rules_and_stays_out_of_ink_history() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let r = request(None);
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    let ink = add_stroke(&note, &note.snapshot().unwrap().sheets[0]);
    let before = note.snapshot().unwrap();
    assert_eq!(
        before.undo_target.as_deref(),
        Some(ink.operation_id.as_str())
    );

    // Stale base (metadata head is still the creation op, not the ink op).
    let stale = Command {
        operation_id: new_id(),
        dependencies: vec![ink.operation_id.clone()],
        body: CommandBody::SetNotebook {
            notebook: Some("Physics".into()),
            base: base(&ink.operation_id),
        },
    };
    assert!(matches!(
        note.submit(stale),
        Err(CoreError::StaleBase { .. })
    ));
    let mut missing = set_notebook(&note, Some("Physics"));
    missing.dependencies.clear();
    assert!(matches!(
        note.submit(missing),
        Err(CoreError::MissingDependency { .. })
    ));
    for bad in ["Inbox", "INBOX", "a\\b", "x\u{0}", &"n".repeat(101)] {
        invalid(note.submit(set_notebook(&note, Some(bad))));
    }
    assert_eq!(note.snapshot().unwrap(), before);

    let c = set_notebook(&note, Some("Physics"));
    let receipt = note.submit(c.clone()).unwrap();
    let retry = note.submit(c.clone()).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, receipt.local_sequence);
    let mut different = c.clone();
    different.body = CommandBody::SetNotebook {
        notebook: Some("Journal".into()),
        base: base(&r.operation_id),
    };
    assert!(matches!(
        note.submit(different),
        Err(CoreError::IdCollision { .. })
    ));
    let after = note.snapshot().unwrap();
    assert_eq!(after.notebook.as_deref(), Some("Physics"));
    assert_eq!(after.metadata_head.operation_id, c.operation_id);
    assert_eq!(after.undo_target, before.undo_target);
    assert_eq!(after.undo_dependencies, before.undo_dependencies);
    assert_eq!(after.strokes, before.strokes);
    // Back to Inbox.
    note.submit(set_notebook(&note, None)).unwrap();
    assert_eq!(note.snapshot().unwrap().notebook, None);
    note.submit(set_notebook(&note, Some("Physics"))).unwrap();
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(
        summary(&lib, &r.note_id).notebook.as_deref(),
        Some("Physics")
    );
}

#[test]
fn notebook_registry_union_delete_and_rename() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let physics: Vec<_> = (0..3).map(|_| request(Some("Physics"))).collect();
    let journal = request(Some("journal"));
    let inbox = request(None);
    for r in physics.iter().chain([&journal, &inbox]) {
        lib.create_note(r.clone()).unwrap();
    }
    lib.create_notebook("Recipes".into()).unwrap();
    lib.create_notebook("Recipes".into()).unwrap(); // idempotent
    lib.create_notebook("Physics".into()).unwrap(); // registered and used
    invalid(lib.create_notebook("inbox".into()));
    invalid(lib.create_notebook("a/b".into()));
    let names = |lib: &SyncrLibrary| -> Vec<(String, u64)> {
        lib.list_notebooks()
            .unwrap()
            .into_iter()
            .map(|n| (n.name, n.note_count))
            .collect()
    };
    assert_eq!(
        names(&lib),
        vec![
            ("journal".into(), 1),
            ("Physics".into(), 3),
            ("Recipes".into(), 0)
        ]
    );
    // Delete only removes empty registry entries.
    invalid(lib.delete_notebook("Physics".into()));
    invalid(lib.delete_notebook("journal".into()));
    lib.delete_notebook("Recipes".into()).unwrap();
    lib.delete_notebook("Recipes".into()).unwrap();
    assert_eq!(names(&lib).len(), 2);

    // One member is open live elsewhere: rename must use its shared writer.
    let live = lib.open_note(physics[0].note_id.clone()).unwrap();
    let live_before = live.snapshot().unwrap();
    lib.rename_notebook("Physics".into(), "Mechanics".into())
        .unwrap();
    let live_after = live.snapshot().unwrap();
    assert_eq!(live_after.notebook.as_deref(), Some("Mechanics"));
    assert_eq!(live_after.local_sequence, live_before.local_sequence + 1);
    assert_ne!(
        live_after.metadata_head.operation_id,
        live_before.metadata_head.operation_id
    );
    assert_eq!(
        names(&lib),
        vec![("journal".into(), 1), ("Mechanics".into(), 3)]
    );
    // Registered name moved: deleting old has nothing to remove, new is registered.
    lib.rename_notebook("Mechanics".into(), "Physics".into())
        .unwrap();
    // Simulate an interrupted rename: one note still in the old notebook.
    live.submit(set_notebook(&live, Some("Mechanics"))).unwrap();
    assert_eq!(
        names(&lib),
        vec![
            ("journal".into(), 1),
            ("Mechanics".into(), 1),
            ("Physics".into(), 2)
        ]
    );
    let sequences: Vec<_> = lib
        .list_notes()
        .unwrap()
        .into_iter()
        .map(|s| s.local_sequence)
        .collect();
    lib.rename_notebook("Mechanics".into(), "Physics".into())
        .unwrap();
    assert_eq!(
        live.snapshot().unwrap().notebook.as_deref(),
        Some("Physics")
    );
    // Retry of a completed rename is a no-op: no new operations anywhere.
    lib.rename_notebook("Mechanics".into(), "Physics".into())
        .unwrap();
    let after: Vec<_> = lib
        .list_notes()
        .unwrap()
        .into_iter()
        .map(|s| s.local_sequence)
        .collect();
    assert_eq!(after.iter().sum::<u64>(), sequences.iter().sum::<u64>() + 1);
    // Rename into an existing name merges.
    lib.rename_notebook("journal".into(), "Physics".into())
        .unwrap();
    assert_eq!(names(&lib), vec![("Physics".into(), 4)]);
    assert_eq!(summary(&lib, &inbox.note_id).notebook, None);
    invalid(lib.rename_notebook("Physics".into(), "Inbox".into()));
    live.close_note().unwrap();
    drop(live);
    drop(lib);
    // Registry persists; membership is read from note metadata.
    let lib = open_library(path(root.path())).unwrap();
    lib.create_notebook("Empty".into()).unwrap();
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(
        names(&lib),
        vec![("Empty".into(), 0), ("Physics".into(), 4)]
    );
}

#[test]
fn timestamps_are_wall_clock_display_only_and_ignore_duplicates() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let now = || {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64
    };
    let t0 = now();
    let r = request(None);
    lib.create_note(r.clone()).unwrap();
    let t1 = now();
    let s = summary(&lib, &r.note_id);
    assert!((t0..=t1).contains(&s.created_at_ms));
    assert_eq!(s.modified_at_ms, s.created_at_ms);
    std::thread::sleep(std::time::Duration::from_millis(20));
    assert!(lib.create_note(r.clone()).unwrap().duplicate);
    assert_eq!(summary(&lib, &r.note_id), s);

    let note = lib.open_note(r.note_id.clone()).unwrap();
    let t2 = now();
    let c = add_stroke(&note, &note.snapshot().unwrap().sheets[0]);
    let t3 = now();
    let edited = summary(&lib, &r.note_id);
    assert_eq!(edited.created_at_ms, s.created_at_ms);
    assert!((t2..=t3).contains(&edited.modified_at_ms));
    std::thread::sleep(std::time::Duration::from_millis(20));
    assert!(note.submit(c).unwrap().duplicate);
    assert_eq!(summary(&lib, &r.note_id), edited);
    note.close_note().unwrap();
    drop(note);
    drop(lib);
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(summary(&lib, &r.note_id), edited);
}

const LEGACY_NOTE: &str = "01M759CD8RBPHQNP94FKX26ZHT";
const LEGACY_CREATION: &str = "01M759CD8RQRA0W9ZTDSMMXPQ3";
const LEGACY_SHEET: &str = "01M759CD8SF4N6SEX2X6821WFC";
const LEGACY_LAST_OP: &str = "01M759G4MPZQG6ASW3FT8QHVAH";

/// Copies the v1 note and v1 library written by the pre-format core (sequence 6).
fn legacy_root() -> tempfile::TempDir {
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/legacy-v1");
    let root = tempfile::tempdir().unwrap();
    std::fs::copy(
        fixtures.join("note-v1.syncr"),
        root.path().join(format!("{LEGACY_NOTE}.syncr")),
    )
    .unwrap();
    std::fs::copy(
        fixtures.join("library-v1.sqlite3"),
        root.path().join("library.sqlite3"),
    )
    .unwrap();
    root
}
fn version(file: &Path) -> u32 {
    Connection::open(file)
        .unwrap()
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .unwrap()
}

#[test]
fn legacy_v1_note_reads_as_inbox_upgrades_to_canvas_with_backup_and_accepts_new_operations() {
    let root = legacy_root();
    let note_file = root.path().join(format!("{LEGACY_NOTE}.syncr"));
    assert_eq!(version(&note_file), 1);
    assert_eq!(version(&root.path().join("library.sqlite3")), 1);
    let lib: Arc<SyncrLibrary> = open_library(path(root.path())).unwrap();
    assert_eq!(version(&root.path().join("library.sqlite3")), 3);

    // Listing reads v1 read-only: no migration, fallback ULID display times.
    let s = summary(&lib, LEGACY_NOTE);
    assert_eq!(s.notebook, None);
    assert_eq!(s.local_sequence, 6);
    assert_eq!(s.created_at_ms, ulid_ms(LEGACY_NOTE));
    assert_eq!(s.modified_at_ms, ulid_ms(LEGACY_LAST_OP));
    assert_eq!(version(&note_file), 1);
    assert!(lib.list_notebooks().unwrap().is_empty());

    // Canonical retry of the original tag-0 creation still verifies.
    let original = CreateNoteRequest {
        operation_id: LEGACY_CREATION.into(),
        note_id: LEGACY_NOTE.into(),
        first_sheet_id: LEGACY_SHEET.into(),
        title: "Example".into(),
        notebook: None,
    };
    let retry = lib.create_note(original.clone()).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, 1);
    for changed in [
        CreateNoteRequest {
            title: "Other".into(),
            ..original.clone()
        },
        CreateNoteRequest {
            notebook: Some("Physics".into()),
            ..original.clone()
        },
    ] {
        assert!(matches!(
            lib.create_note(changed),
            Err(CoreError::IdCollision { .. })
        ));
    }

    // Opening for write backs the v1 file up, then upgrades v1 -> v3 in place in one
    // transaction, preserving all operations.
    let note = lib.open_note(LEGACY_NOTE.into()).unwrap();
    assert_eq!(version(&note_file), 3);
    let backups: Vec<_> = std::fs::read_dir(root.path().join(".backups"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    assert_eq!(backups.len(), 1);
    let name = backups[0]
        .file_name()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    assert!(name.starts_with(&format!("{LEGACY_NOTE}.v1.")) && name.ends_with(".syncr"));
    assert_eq!(version(&backups[0]), 1);
    let snap = note.snapshot().unwrap();
    assert_eq!(snap.sheets.len(), 1);
    assert_eq!(
        (snap.sheets[0].origin_x, snap.sheets[0].origin_y),
        (0.0, 0.0)
    );
    assert_eq!(snap.notebook, None);
    assert_eq!(snap.local_sequence, 6);
    assert_eq!(snap.sheets[0].creation_operation_id, LEGACY_CREATION);
    let ink = add_stroke(&note, &snap.sheets[0]);
    assert!(note.submit(ink.clone()).unwrap().duplicate);
    note.submit(set_notebook(&note, Some("Physics"))).unwrap();
    let after = note.snapshot().unwrap();
    assert_eq!(after.local_sequence, 8);
    assert_eq!(
        after.undo_target.as_deref(),
        Some(ink.operation_id.as_str())
    );
    let s = summary(&lib, LEGACY_NOTE);
    assert_eq!(s.created_at_ms, ulid_ms(LEGACY_NOTE));
    assert!(s.modified_at_ms > ulid_ms(LEGACY_LAST_OP));
    assert_eq!(s.notebook.as_deref(), Some("Physics"));
    note.close_note().unwrap();
    drop(note);
    drop(lib);

    let db = Connection::open(&note_file).unwrap();
    let (old_null, new_set): (u32, u32) = db
        .query_row(
            "SELECT SUM(committed_at_ms IS NULL), SUM(committed_at_ms IS NOT NULL) FROM operations",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!((old_null, new_set), (6, 2));
    drop(db);
    let lib = open_library(path(root.path())).unwrap();
    let note = lib.open_note(LEGACY_NOTE.into()).unwrap();
    assert_eq!(note.snapshot().unwrap(), {
        let mut a = after;
        a.cursor = note.snapshot().unwrap().cursor;
        a
    });
    assert!(lib.create_note(original).unwrap().duplicate);
}
