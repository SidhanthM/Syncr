//! Exercise the full 1024-head history boundary without thousands of disk commits.
use crate::{reducer::State, *};

#[test]
fn history_snapshot_has_exact_sorted_1024_current_heads_without_truncation() {
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: String::new(),
        page_format: PageFormat::Sheet16x10,
        notebook: None,
    };
    let author = new_id();
    let mut state = State::new(&request);
    let action = new_id();
    let marks: Vec<_> = (0..1024).map(|_| new_id()).collect();
    let samples = encode_samples(SampleChannels {
        x: vec![0.0],
        y: vec![0.0],
        pressure: vec![1.0],
        elapsed_ms: vec![0],
        tilt: None,
        orientation: None,
    })
    .unwrap();
    // Reducer-only read-model boundary: the public API separately enforces the
    // 64 KiB budget, so a 1024-stroke Add is not an admission-test fixture.
    state
        .apply(
            &Command {
                operation_id: action.clone(),
                dependencies: vec![request.operation_id],
                body: CommandBody::Marks {
                    mutations: marks
                        .iter()
                        .map(|id| MarkMutation::AddStroke {
                            mark_id: id.clone(),
                            sheet_id: request.first_sheet_id.clone(),
                            samples: samples.clone(),
                            brush: Brush {
                                family: BrushFamily::Pen,
                                size: BrushSize::Small,
                                colour: InkColour::Ink,
                            },
                        })
                        .collect(),
                },
            },
            &author,
        )
        .unwrap();
    let mut edits = vec![];
    for mark in &marks {
        let operation = new_id();
        state
            .apply(
                &Command {
                    operation_id: operation.clone(),
                    dependencies: vec![action.clone()],
                    body: CommandBody::Marks {
                        mutations: vec![MarkMutation::DeleteStroke {
                            mark_id: mark.clone(),
                            base: Base::Operation {
                                operation_id: action.clone(),
                            },
                        }],
                    },
                },
                &author,
            )
            .unwrap();
        edits.push(operation);
    }
    let mut expected = vec![];
    for edit in edits.into_iter().rev() {
        let operation = new_id();
        state
            .apply(
                &Command {
                    operation_id: operation.clone(),
                    dependencies: vec![edit.clone()],
                    body: CommandBody::Undo {
                        target_operation_id: edit,
                    },
                },
                &author,
            )
            .unwrap();
        expected.push(operation);
    }
    expected.sort();
    let cursor = EventCursor {
        stream_id: new_id(),
        sequence: 0,
    };
    let s = state.snapshot(&author, cursor.clone());
    assert_eq!(s.undo_target, Some(action.clone()));
    assert_eq!(s.undo_dependencies.len(), 1024);
    assert_eq!(s.undo_dependencies, expected);
    assert_eq!(s.redo_dependencies.len(), 1);
    let undo = new_id();
    state
        .apply(
            &Command {
                operation_id: undo.clone(),
                dependencies: s.undo_dependencies,
                body: CommandBody::Undo {
                    target_operation_id: action.clone(),
                },
            },
            &author,
        )
        .unwrap();
    let s = state.snapshot(&author, cursor.clone());
    assert!(s.strokes.iter().all(|s| s.tombstone));
    assert_eq!(s.undo_target, None);
    assert!(s.undo_dependencies.is_empty());
    assert_eq!(s.redo_target, Some(action));
    assert_eq!(s.redo_dependencies, vec![undo]);
    let other = state.snapshot(&new_id(), cursor);
    assert_eq!(other.undo_target, None);
    assert_eq!(other.redo_target, None);
    assert!(other.undo_dependencies.is_empty() && other.redo_dependencies.is_empty());
}
