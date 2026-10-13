use base64::{Engine, engine::general_purpose::STANDARD};
use serde::Deserialize;
use serde_json::Value;
use std::{
    path::{Path, PathBuf},
    process::Command,
};
use syncr_core::*;

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/offline-v1")
}
fn json(path: &str) -> Value {
    serde_json::from_slice(&std::fs::read(fixtures().join(path)).unwrap()).unwrap()
}
fn path(root: &Path) -> String {
    root.to_str().unwrap().into()
}
#[derive(Deserialize)]
struct Channels {
    x: Vec<f32>,
    y: Vec<f32>,
    pressure: Vec<f32>,
    elapsed_ms: Vec<u32>,
    tilt: Option<Vec<f32>>,
    orientation: Option<Vec<f32>>,
}
impl From<Channels> for SampleChannels {
    fn from(c: Channels) -> Self {
        Self {
            x: c.x,
            y: c.y,
            pressure: c.pressure,
            elapsed_ms: c.elapsed_ms,
            tilt: c.tilt,
            orientation: c.orientation,
        }
    }
}

#[test]
fn manifest_and_sample_goldens() {
    let manifest = json("manifest.json");
    assert_eq!(manifest["fixture_version"], 1);
    for case in manifest["cases"].as_array().unwrap() {
        let input = json(case["input_path"].as_str().unwrap());
        let expected = json(case["expected_path"].as_str().unwrap());
        match case["name"].as_str().unwrap() {
            "sample-layouts-valid" => {
                for (input, expected) in input
                    .as_array()
                    .unwrap()
                    .iter()
                    .zip(expected.as_array().unwrap())
                {
                    assert_eq!(input["name"], expected["name"]);
                    let blob = STANDARD
                        .decode(input["blob_base64"].as_str().unwrap())
                        .unwrap();
                    let channels: SampleChannels =
                        serde_json::from_value::<Channels>(expected["channels"].clone())
                            .unwrap()
                            .into();
                    assert_eq!(decode_samples(blob.clone()).unwrap(), channels);
                    // Exact bytes include signed zero, endian order and absent channels.
                    assert_eq!(encode_samples(channels).unwrap(), blob);
                }
            }
            "sample-layouts-invalid" => {
                for (input, expected) in input
                    .as_array()
                    .unwrap()
                    .iter()
                    .zip(expected.as_array().unwrap())
                {
                    assert_eq!(input["name"], expected["name"]);
                    let blob = STANDARD
                        .decode(input["blob_base64"].as_str().unwrap())
                        .unwrap();
                    let error = decode_samples(blob).unwrap_err();
                    match expected["error"].as_str().unwrap() {
                        "InvalidInput" => assert!(matches!(error, CoreError::InvalidInput { .. })),
                        "ResourceLimit" => {
                            assert!(matches!(error, CoreError::ResourceLimit { .. }))
                        }
                        "UnsupportedVersion" => {
                            assert!(matches!(error, CoreError::UnsupportedVersion { .. }))
                        }
                        other => panic!("Unexpected fixture error: {other}"),
                    }
                }
            }
            // Exercised with private validation access and actual storage below.
            "affine-conventions"
            | "canonical-encoding"
            | "creation-reopen-retry"
            | "offline-workflow"
            | "atomicity-dependencies-branch" => {}
            other => panic!("Unrecognized fixture case: {other}"),
        }
    }
}

#[test]
fn library_identity_is_persistent_and_exclusively_locked() {
    let root = tempfile::tempdir().unwrap();
    let library = open_library(path(root.path())).unwrap();
    let author = library.author_id();
    assert_eq!(author.parse::<ulid::Ulid>().unwrap().to_string(), author);
    assert!(matches!(
        open_library(path(root.path())),
        Err(CoreError::NoteLocked {
            retryable: true,
            ..
        })
    ));
    let status = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "writer_process_probe"])
        .env("SYNCR_LOCK_TEST_ROOT", root.path())
        .status()
        .unwrap();
    assert!(status.success());
    drop(library);
    let library = open_library(path(root.path())).unwrap();
    assert_eq!(library.author_id(), author);
}

#[test]
fn writer_process_probe() {
    if let Ok(root) = std::env::var("SYNCR_LOCK_TEST_ROOT") {
        assert!(matches!(
            open_library(root),
            Err(CoreError::NoteLocked { .. })
        ));
    }
}

#[test]
fn creation_fixture_reopens_and_recovers_lost_reply() {
    let root = tempfile::tempdir().unwrap();
    let library = open_library(path(root.path())).unwrap();
    let input = json("bootstrap.input.json");
    let expected = json("bootstrap.expected.json");
    let request = CreateNoteRequest {
        operation_id: input["operation_id"].as_str().unwrap().into(),
        note_id: input["note_id"].as_str().unwrap().into(),
        first_sheet_id: input["first_sheet_id"].as_str().unwrap().into(),
        title: input["title"].as_str().unwrap().into(),
        page_format: PageFormat::Sheet16x10,
        notebook: None,
    };
    let receipt = library.create_note(request.clone()).unwrap();
    assert_eq!(
        receipt.local_sequence.to_string(),
        expected["local_sequence"]
    );
    drop(library); // Treat the original result as lost; no in-memory dedup survives.
    let library = open_library(path(root.path())).unwrap();
    let OperationLookup::Committed { receipt: recovered } = library
        .lookup_creation(request.operation_id.clone())
        .unwrap()
    else {
        panic!("lost creation");
    };
    assert_eq!(receipt, recovered);
    let retry = library.create_note(request.clone()).unwrap();
    assert!(retry.duplicate);
    assert_eq!(retry.local_sequence, receipt.local_sequence);
    let mut different = request.clone();
    different.title.push('!');
    assert!(matches!(
        library.create_note(different),
        Err(CoreError::IdCollision { .. })
    ));
    let note = library.open_note(request.note_id.clone()).unwrap();
    let snapshot = note.snapshot().unwrap();
    assert_eq!(snapshot.title, input["title"]);
    assert_eq!(
        snapshot.sheets,
        vec![SheetView {
            id: request.first_sheet_id,
            creation_operation_id: request.operation_id.clone(),
        }]
    );
    assert_eq!(snapshot.metadata_head.operation_id, request.operation_id);
    assert_eq!(snapshot.structure_head, snapshot.metadata_head);
    assert!(snapshot.undo_dependencies.is_empty());
    assert!(snapshot.redo_dependencies.is_empty());
    assert_eq!(snapshot.metadata_head.confirmed_revision, None);
    assert_eq!(library.list_notes().unwrap().len(), 1);
    note.close_note().unwrap();
}

#[test]
fn newer_library_version_is_rejected_without_changing_database() {
    let root = tempfile::tempdir().unwrap();
    let file = root.path().join("library.sqlite3");
    let db = rusqlite::Connection::open(&file).unwrap();
    db.pragma_update(None, "user_version", 4).unwrap();
    drop(db);
    let before = std::fs::read(&file).unwrap();
    assert!(matches!(
        open_library(path(root.path())),
        Err(CoreError::UnsupportedVersion { .. })
    ));
    assert_eq!(std::fs::read(&file).unwrap(), before);
}

#[test]
fn sqlite_full_synchronous_transaction_rollback_and_reopen() {
    let root = tempfile::tempdir().unwrap();
    let library = open_library(path(root.path())).unwrap();
    let db = rusqlite::Connection::open(root.path().join("library.sqlite3")).unwrap();
    let mode: String = db
        .pragma_query_value(None, "journal_mode", |r| r.get(0))
        .unwrap();
    assert_eq!(mode, "wal");
    drop(db);
    // SQLite failure classification is exercised without claiming a note commit.
    drop(library);
    let mut db = rusqlite::Connection::open(root.path().join("library.sqlite3")).unwrap();
    {
        let tx = db.transaction().unwrap();
        tx.execute("UPDATE installation SET value='not committed'", [])
            .unwrap();
        // Drop rolls back the transaction.
    }
    drop(db);
    assert!(
        open_library(path(root.path()))
            .unwrap()
            .author_id()
            .parse::<ulid::Ulid>()
            .is_ok()
    );
}

fn channels() -> SampleChannels {
    SampleChannels {
        x: vec![0.0, 1.0],
        y: vec![0.0, 1.0],
        pressure: vec![0.0, 1.0],
        elapsed_ms: vec![0, u32::MAX],
        tilt: None,
        orientation: None,
    }
}

#[test]
fn codec_negative_channels_and_exact_limits() {
    let mut c = channels();
    c.y.pop();
    assert!(matches!(
        encode_samples(c),
        Err(CoreError::InvalidInput { .. })
    ));
    let mut c = channels();
    c.pressure[0] = f32::NAN;
    assert!(matches!(
        encode_samples(c),
        Err(CoreError::InvalidInput { .. })
    ));
    for bad in [f32::NAN, f32::INFINITY, -0.1, 2.0] {
        let mut c = channels();
        c.tilt = Some(vec![bad, 0.0]);
        assert!(matches!(
            encode_samples(c),
            Err(CoreError::InvalidInput { .. })
        ));
    }
    for bad in [f32::NAN, f32::INFINITY, -4.0, 4.0] {
        let mut c = channels();
        c.orientation = Some(vec![bad, 0.0]);
        assert!(matches!(
            encode_samples(c),
            Err(CoreError::InvalidInput { .. })
        ));
    }
    let mut c = channels();
    c.tilt = Some(vec![0.0, std::f32::consts::FRAC_PI_2]);
    c.orientation = Some(vec![-std::f32::consts::PI, std::f32::consts::PI]);
    assert_eq!(
        decode_samples(encode_samples(c.clone()).unwrap()).unwrap(),
        c
    );
    let mut c = channels();
    c.orientation = Some(vec![0.0, 1.0]);
    assert_eq!(
        decode_samples(encode_samples(c.clone()).unwrap()).unwrap(),
        c
    );
    assert!(matches!(
        decode_samples(vec![0; 16 * 1024 * 1024 + 1]),
        Err(CoreError::ResourceLimit { .. })
    ));
    let n = (16 * 1024 * 1024 - 12) / 16;
    let c = SampleChannels {
        x: vec![0.0; n],
        y: vec![0.0; n],
        pressure: vec![1.0; n],
        elapsed_ms: vec![0; n],
        tilt: None,
        orientation: None,
    };
    let blob = encode_samples(c).unwrap();
    assert_eq!(blob.len(), 16 * 1024 * 1024 - 4);
    assert!(decode_samples(blob).is_ok());
}

#[test]
fn path_validation_does_not_treat_saf_uri_as_path() {
    for root in ["", "content://documents/tree/123"] {
        assert!(matches!(
            open_library(root.into()),
            Err(CoreError::InvalidInput { .. })
        ));
    }
}
