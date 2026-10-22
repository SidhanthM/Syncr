//! Canvas amendment acceptance (canvas-amendment.md section 6, items 1-6) against the real
//! v2 notes in fixtures/canvas-v2/, written by the pre-canvas core. Fixtures are copied
//! into a temporary root; they are never opened in place.
use crate::{
    reducer::State,
    store::{self, BACKUP_DIR, NOTE_VERSION},
    *,
};
use rusqlite::{Connection, OpenFlags};
use serde_json::Value;
use std::{
    path::{Path, PathBuf},
    sync::Arc,
};

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/canvas-v2")
}
fn expected() -> Value {
    serde_json::from_slice(&std::fs::read(fixtures().join("expected.json")).unwrap()).unwrap()
}
fn notes() -> Vec<Value> {
    expected()["notes"].as_array().unwrap().clone()
}
fn copy_root() -> tempfile::TempDir {
    let root = tempfile::tempdir().unwrap();
    for note in notes() {
        let file = note["file"].as_str().unwrap();
        std::fs::copy(fixtures().join(file), root.path().join(file)).unwrap();
    }
    std::fs::copy(
        fixtures().join("library.sqlite3"),
        root.path().join("library.sqlite3"),
    )
    .unwrap();
    root
}
fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
}
fn read_only(file: &Path) -> Connection {
    Connection::open_with_flags(file, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap()
}
fn version(file: &Path) -> u32 {
    read_only(file)
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .unwrap()
}
fn document(file: &Path) -> String {
    read_only(file)
        .query_row("SELECT state FROM document", [], |r| r.get(0))
        .unwrap()
}
fn state(file: &Path) -> State {
    store::load(&read_only(file)).unwrap()
}
/// Every immutable row: operations (all columns), samples and mark effects.
type Log = (
    Vec<Vec<rusqlite::types::Value>>,
    Vec<(String, Vec<u8>)>,
    Vec<(String, String)>,
);
fn log(file: &Path) -> Log {
    let db = read_only(file);
    let mut ops = db
        .prepare("SELECT * FROM operations ORDER BY local_sequence")
        .unwrap();
    let columns = ops.column_count();
    let operations = ops
        .query_map([], |r| {
            (0..columns)
                .map(|i| r.get::<_, rusqlite::types::Value>(i))
                .collect::<Result<Vec<_>, _>>()
        })
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    let samples = db
        .prepare("SELECT mark_id, bytes FROM samples ORDER BY mark_id")
        .unwrap()
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    let effects = db
        .prepare("SELECT operation_id, effects FROM mark_effects ORDER BY operation_id")
        .unwrap()
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    (operations, samples, effects)
}
fn backups(root: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(root.join(BACKUP_DIR)) else {
        return vec![];
    };
    let mut files: Vec<_> = entries.map(|e| e.unwrap().path()).collect();
    files.sort();
    files
}
fn without_cursor(mut s: NoteSnapshot) -> NoteSnapshot {
    s.cursor.stream_id = String::new();
    s.cursor.sequence = 0;
    s
}
fn pen() -> Brush {
    Brush {
        family: BrushFamily::Pen,
        size: BrushSize::Medium,
        colour: InkColour::Ink,
    }
}
fn stroke_at(x: f32, y: f32) -> Vec<u8> {
    encode_samples(SampleChannels {
        x: vec![x, x + 5.0],
        y: vec![y, y + 5.0],
        pressure: vec![0.5, 0.5],
        elapsed_ms: vec![0, 8],
        tilt: None,
        orientation: None,
    })
    .unwrap()
}
fn add_on(note: &Arc<Note>, sheet: &SheetView, x: f32, y: f32) -> (Id, Command) {
    let mark = new_id();
    let command = Command {
        operation_id: new_id(),
        dependencies: vec![sheet.creation_operation_id.clone()],
        body: CommandBody::Marks {
            mutations: vec![MarkMutation::AddStroke {
                mark_id: mark.clone(),
                sheet_id: sheet.id.clone(),
                samples: stroke_at(x, y),
                brush: pen(),
            }],
        },
    };
    note.submit(command.clone()).unwrap();
    (mark, command)
}
fn canvas_points(origin: (f32, f32), t: &Affine, samples: &[u8]) -> Vec<(f64, f64)> {
    let c = decode_samples(samples.to_vec()).unwrap();
    c.x.iter()
        .zip(&c.y)
        .map(|(&x, &y)| {
            let (x, y) = (f64::from(x), f64::from(y));
            (
                f64::from(origin.0) + t.a * x + t.c * y + t.tx,
                f64::from(origin.1) + t.b * x + t.d * y + t.ty,
            )
        })
        .collect()
}

/// Item 1: each format upgrades; every stroke lands at its old page position minus the
/// gaps; nothing immutable changes; reopening is stable. A backup of the v2 file exists.
#[test]
fn v2_notes_of_both_formats_upgrade_to_one_canvas() {
    for fixture in notes() {
        let root = copy_root();
        let file = root.path().join(fixture["file"].as_str().unwrap());
        let note_id = fixture["creation_request"]["note_id"].as_str().unwrap();
        let height = fixture["page_height"].as_f64().unwrap() as f32;
        let original = std::fs::read(&file).unwrap();
        assert_eq!(version(&file), 2);
        let old_state = state(&file);
        let old_document = document(&file);
        let old_log = log(&file);
        let lib = open_library(path(root.path())).unwrap();
        // Listing reads the v2 note without touching it.
        let summary = lib.list_notes().unwrap();
        assert!(summary.iter().any(|s| s.id == note_id));
        assert_eq!(std::fs::read(&file).unwrap(), original);
        assert!(backups(root.path()).is_empty());

        let note = lib.open_note(note_id.into()).unwrap();
        assert_eq!(version(&file), NOTE_VERSION);
        // Backup: one published copy of the unmodified v2 note.
        let saved = backups(root.path());
        assert_eq!(saved.len(), 1);
        let name = saved[0].file_name().unwrap().to_str().unwrap().to_owned();
        let prefix = format!("{note_id}.v2.");
        assert!(
            name.starts_with(&prefix) && name.ends_with(".syncr"),
            "{name}"
        );
        assert!(
            name[prefix.len()..name.len() - 6]
                .chars()
                .all(|c| c.is_ascii_digit())
        );
        assert_eq!(version(&saved[0]), 2);
        assert_eq!(document(&saved[0]), old_document);
        assert_eq!(log(&saved[0]), old_log);

        let snapshot = note.snapshot().unwrap();
        let sheets: Vec<_> = snapshot
            .sheets
            .iter()
            .map(|s| {
                serde_json::json!({
                    "id": s.id, "creation_operation_id": s.creation_operation_id,
                    "origin_x": s.origin_x, "origin_y": s.origin_y,
                })
            })
            .collect();
        assert_eq!(Value::from(sheets), fixture["sheets_in_order"]);
        assert_eq!(snapshot.sheets.len(), 3);
        assert_eq!(
            snapshot.local_sequence.to_string(),
            fixture["local_sequence"]
        );
        for stroke in &snapshot.strokes {
            // Old position: page index in the v2 display order, pages stacked without gap.
            let index = old_state
                .sheets
                .iter()
                .position(|s| s.id == stroke.sheet_id)
                .unwrap();
            let sheet = snapshot
                .sheets
                .iter()
                .find(|s| s.id == stroke.sheet_id)
                .unwrap();
            let samples = note.get_samples(stroke.id.clone()).unwrap();
            let old = canvas_points((0.0, index as f32 * height), &stroke.transform, &samples);
            let new = canvas_points(
                (sheet.origin_x, sheet.origin_y),
                &stroke.transform,
                &samples,
            );
            assert_eq!(new, old);
            let reviewed = fixture["strokes"]
                .as_array()
                .unwrap()
                .iter()
                .find(|s| s["id"] == stroke.id.as_str())
                .unwrap();
            assert_eq!(
                serde_json::json!([sheet.origin_x, sheet.origin_y]),
                reviewed["canvas_origin"]
            );
            assert_eq!(stroke.tombstone, reviewed["tombstone"].as_bool().unwrap());
        }
        // Only the sheet origins changed; the old format is no longer stored.
        let mut placed = old_state.clone();
        placed.place_sheets(placed.legacy_page_format);
        assert_eq!(
            store::json(&state(&file)).unwrap(),
            store::json(&placed).unwrap()
        );
        assert!(!document(&file).contains("page_format"));
        assert_eq!(log(&file), old_log);

        // A legacy sheet still demands its own creator; new strokes name the first sheet in
        // canvas coordinates, anywhere on the canvas.
        let lower = &snapshot.sheets[2];
        let wrong = Command {
            operation_id: new_id(),
            dependencies: vec![snapshot.sheets[0].creation_operation_id.clone()],
            body: CommandBody::Marks {
                mutations: vec![MarkMutation::AddStroke {
                    mark_id: new_id(),
                    sheet_id: lower.id.clone(),
                    samples: stroke_at(1.0, 1.0),
                    brush: pen(),
                }],
            },
        };
        assert!(matches!(
            note.submit(wrong),
            Err(CoreError::MissingDependency { .. })
        ));
        let first = snapshot.sheets[0].clone();
        assert_eq!((first.origin_x, first.origin_y), (0.0, 0.0));
        let (far, far_command) = add_on(&note, &first, 4500.0, 3.0 * height + 777.0);
        let after = note.snapshot().unwrap();
        note.close_note().unwrap();
        drop(note);
        drop(lib);

        let lib = open_library(path(root.path())).unwrap();
        let note = lib.open_note(note_id.into()).unwrap();
        assert_eq!(
            without_cursor(note.snapshot().unwrap()),
            without_cursor(after)
        );
        assert_eq!(
            note.get_samples(far).unwrap(),
            stroke_at(4500.0, 3.0 * height + 777.0)
        );
        assert!(note.submit(far_command).unwrap().duplicate);
        // Already v3: reopening takes no further backup.
        assert_eq!(backups(root.path()).len(), 1);
        note.close_note().unwrap();
    }
}

/// Item 3: replaying the immutable log from empty yields the upgraded file's origins (and
/// its whole projection), both from the v2 file and from the upgraded v3 file.
#[test]
fn replaying_the_log_from_empty_gives_the_same_origins() {
    for fixture in notes() {
        let root = copy_root();
        let file = root.path().join(fixture["file"].as_str().unwrap());
        let note_id = fixture["creation_request"]["note_id"].as_str().unwrap();
        let from_v2 = store::replay(&read_only(&file)).unwrap();
        let lib = open_library(path(root.path())).unwrap();
        lib.open_note(note_id.into()).unwrap().close_note().unwrap();
        let upgraded = state(&file);
        let from_v3 = store::replay(&read_only(&file)).unwrap();
        let origins = |s: &State| {
            s.sheets
                .iter()
                .map(|s| (s.id.clone(), s.creation.clone(), s.origin_x, s.origin_y))
                .collect::<Vec<_>>()
        };
        assert_eq!(origins(&from_v2), origins(&upgraded));
        assert_eq!(origins(&from_v3), origins(&upgraded));
        assert_eq!(
            store::json(&from_v3).unwrap(),
            store::json(&upgraded).unwrap()
        );
        assert_eq!(
            store::json(&from_v2).unwrap(),
            store::json(&upgraded).unwrap()
        );
        let reorders = log(&file)
            .0
            .iter()
            .filter(|row| {
                matches!(&row[6], rusqlite::types::Value::Text(t) if t.contains("ReorderSheets"))
            })
            .count();
        assert_eq!(reorders, 1, "the fixture log contains a reorder");
    }
}

/// Item 2: a process killed during the upgrade (after the backup, or inside the
/// transaction before commit) leaves the v2 note intact and openable, with the backup.
#[test]
fn crash_during_upgrade_leaves_the_v2_note_and_a_backup() {
    let fixture = &notes()[0];
    let note_id = fixture["creation_request"]["note_id"].as_str().unwrap();
    for stage in ["upgrade_after_backup", "upgrade_before_commit"] {
        let root = copy_root();
        let file = root.path().join(fixture["file"].as_str().unwrap());
        let (old_document, old_log) = (document(&file), log(&file));
        crate::failure_tests::run_crash_probe(root.path(), stage, Some(note_id));
        assert_eq!(version(&file), 2, "{stage}");
        assert_eq!(document(&file), old_document, "{stage}");
        assert_eq!(log(&file), old_log, "{stage}");
        let saved = backups(root.path());
        assert_eq!(saved.len(), 1, "{stage}");
        assert_eq!(version(&saved[0]), 2);
        assert_eq!(document(&saved[0]), old_document);
        assert_eq!(log(&saved[0]), old_log);
        // The v2 note is still listed and opens (upgrading now, with a second backup).
        let lib = open_library(path(root.path())).unwrap();
        assert!(lib.list_notes().unwrap().iter().any(|s| s.id == note_id));
        let note = lib.open_note(note_id.into()).unwrap();
        assert_eq!(note.snapshot().unwrap().sheets[2].origin_y, 6400.0);
        assert_eq!(version(&file), NOTE_VERSION);
        assert_eq!(backups(root.path()).len(), 2);
        assert_eq!(log(&file), old_log);
        note.close_note().unwrap();
    }
}

/// Item 2, in-process: an error inside the upgrade transaction rolls it back; a backup
/// that cannot be written stops the upgrade before any change. Interrupted backup temp
/// files are removed on library open.
#[test]
fn failed_upgrade_rolls_back_and_failed_backup_changes_nothing() {
    let fixture = &notes()[1];
    let note_id = fixture["creation_request"]["note_id"].as_str().unwrap();
    let root = copy_root();
    let file = root.path().join(fixture["file"].as_str().unwrap());
    let (original, old_log) = (document(&file), log(&file));
    let lib = open_library(path(root.path())).unwrap();
    crate::fault::FAIL_AT.with(|f| f.set(Some("upgrade_before_commit")));
    let failed = lib.open_note(note_id.into());
    crate::fault::FAIL_AT.with(|f| f.set(None));
    assert!(matches!(failed, Err(CoreError::Internal { .. })));
    assert_eq!((version(&file), document(&file)), (2, original.clone()));
    assert_eq!(log(&file), old_log);
    assert_eq!(backups(root.path()).len(), 1);
    let note = lib.open_note(note_id.into()).unwrap();
    assert_eq!(note.snapshot().unwrap().sheets[1].origin_y, 1000.0);
    note.close_note().unwrap();
    drop((note, lib));

    // Backup location unusable: nothing is upgraded.
    let root = copy_root();
    let file = root.path().join(fixture["file"].as_str().unwrap());
    std::fs::write(root.path().join(BACKUP_DIR), b"not a directory").unwrap();
    let lib = open_library(path(root.path())).unwrap();
    assert!(lib.open_note(note_id.into()).is_err());
    assert_eq!((version(&file), document(&file)), (2, original));
    drop(lib);

    // A temp copy left by a crash mid-backup is not a backup and is cleaned up.
    let root = copy_root();
    std::fs::create_dir(root.path().join(BACKUP_DIR)).unwrap();
    let stray =
        root.path()
            .join(BACKUP_DIR)
            .join(format!("{}{}", store::BACKUP_TEMP_PREFIX, new_id()));
    std::fs::write(&stray, b"partial").unwrap();
    let _lib = open_library(path(root.path())).unwrap();
    assert!(!stray.exists());
}

/// Item 4: retries of pre-canvas creations (tag 2, both formats; tag 0 is covered by the
/// legacy-v1 fixture test) still match as duplicates, before and after the upgrade; a new
/// creation stores tag 3 with no format.
#[test]
fn pre_canvas_creation_retries_match_and_new_creations_have_no_format() {
    let root = copy_root();
    let lib = open_library(path(root.path())).unwrap();
    assert_eq!(lib.author_id(), expected()["author_id"].as_str().unwrap());
    for fixture in notes() {
        let r = &fixture["creation_request"];
        let request = CreateNoteRequest {
            operation_id: r["operation_id"].as_str().unwrap().into(),
            note_id: r["note_id"].as_str().unwrap().into(),
            first_sheet_id: r["first_sheet_id"].as_str().unwrap().into(),
            title: r["title"].as_str().unwrap().into(),
            notebook: r["notebook"].as_str().map(Into::into),
        };
        let receipt = lib.create_note(request.clone()).unwrap();
        assert!(receipt.duplicate);
        assert_eq!(receipt.local_sequence, 1);
        let other_notebook = CreateNoteRequest {
            notebook: match &request.notebook {
                Some(_) => None,
                None => Some("Elsewhere".into()),
            },
            ..request.clone()
        };
        assert!(matches!(
            lib.create_note(other_notebook.clone()),
            Err(CoreError::IdCollision { .. })
        ));
        lib.open_note(request.note_id.clone())
            .unwrap()
            .close_note()
            .unwrap();
        assert!(lib.create_note(request).unwrap().duplicate);
        assert!(matches!(
            lib.create_note(other_notebook),
            Err(CoreError::IdCollision { .. })
        ));
    }
    let fresh = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Canvas".into(),
        notebook: Some("Fixtures".into()),
    };
    lib.create_note(fresh.clone()).unwrap();
    let file = root.path().join(format!("{}.syncr", fresh.note_id));
    let (canonical, payload): (Vec<u8>, String) = read_only(&file)
        .query_row(
            "SELECT canonical, payload FROM operations WHERE id=?1",
            [&fresh.operation_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!(&canonical[..7], b"SYNO\x01\x00\x03");
    assert_eq!(
        canonical,
        crate::canonical::creation(&fresh, &lib.author_id()).unwrap()
    );
    assert!(!payload.contains("page_format"));
    assert_eq!(version(&file), NOTE_VERSION);
    assert!(lib.create_note(fresh.clone()).unwrap().duplicate);
    let s = lib
        .open_note(fresh.note_id.clone())
        .unwrap()
        .snapshot()
        .unwrap();
    assert_eq!(s.sheets.len(), 1);
    assert_eq!((s.sheets[0].origin_x, s.sheets[0].origin_y), (0.0, 0.0));
}

/// Item 5: AddSheet and ReorderSheets are InvalidInput on a canvas note (upgraded or new)
/// and change nothing. The already committed reorder still answers its identical retry.
#[test]
fn add_sheet_and_reorder_are_rejected_and_change_nothing() {
    let root = copy_root();
    let lib = open_library(path(root.path())).unwrap();
    let fixture = &notes()[0];
    let upgraded = fixture["creation_request"]["note_id"].as_str().unwrap();
    let fresh = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "New".into(),
        notebook: None,
    };
    lib.create_note(fresh.clone()).unwrap();
    for note_id in [upgraded.to_string(), fresh.note_id.clone()] {
        let note = lib.open_note(note_id.clone()).unwrap();
        let before = note.snapshot().unwrap();
        let file = root.path().join(format!("{note_id}.syncr"));
        let log_before = log(&file);
        let head = before.structure_head.operation_id.clone();
        let mut order: Vec<Id> = before.sheets.iter().map(|s| s.id.clone()).collect();
        order.reverse();
        for body in [
            CommandBody::AddSheet {
                sheet_id: new_id(),
                base: Base::Operation {
                    operation_id: head.clone(),
                },
            },
            CommandBody::ReorderSheets {
                sheet_ids: order.clone(),
                base: Base::Operation {
                    operation_id: head.clone(),
                },
            },
        ] {
            let command = Command {
                operation_id: new_id(),
                dependencies: vec![head.clone()],
                body,
            };
            assert!(matches!(
                note.submit(command.clone()),
                Err(CoreError::InvalidInput { .. })
            ));
            assert_eq!(note.snapshot().unwrap(), before);
            assert_eq!(
                note.lookup_operation(command.operation_id).unwrap(),
                OperationLookup::NotRecorded
            );
        }
        assert_eq!(log(&file), log_before);
        note.close_note().unwrap();
    }
    // The committed pre-canvas reorder: an identical retry is still a duplicate.
    let file = root.path().join(fixture["file"].as_str().unwrap());
    let reorder = fixture["reorder_operation_id"].as_str().unwrap();
    let payload: String = read_only(&file)
        .query_row(
            "SELECT payload FROM operations WHERE id=?1",
            [reorder],
            |r| r.get(0),
        )
        .unwrap();
    let note = lib.open_note(upgraded.into()).unwrap();
    let before = note.snapshot().unwrap();
    let retry = note
        .submit(store::parse::<Command>(&payload).unwrap())
        .unwrap();
    assert!(retry.duplicate);
    assert_eq!(note.snapshot().unwrap(), before);
    note.close_note().unwrap();
}

/// Item 6: a core that does not know a note's schema (as a pre-v3 build does not know v3)
/// refuses it with UnsupportedVersion and leaves every byte unchanged.
#[test]
fn a_core_without_the_note_version_refuses_it_unchanged() {
    let root = copy_root();
    let fixture = &notes()[0];
    let note_id = fixture["creation_request"]["note_id"].as_str().unwrap();
    let file = root.path().join(fixture["file"].as_str().unwrap());
    {
        let lib = open_library(path(root.path())).unwrap();
        lib.open_note(note_id.into()).unwrap().close_note().unwrap();
    }
    // Model one version too new for this build (v4 text is now supported).
    Connection::open(&file)
        .unwrap()
        .pragma_update(None, "user_version", store::TEXT_NOTE_VERSION + 1)
        .unwrap();
    let bytes = std::fs::read(&file).unwrap();
    assert!(matches!(
        open_library(path(root.path())),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert!(matches!(
        store::open(&file, true),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert_eq!(std::fs::read(&file).unwrap(), bytes);
    assert_eq!(backups(root.path()).len(), 1);
}
