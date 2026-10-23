use crate::*;
#[test]
fn independent_companion_canonical_vectors() {
    let id = |n| format!("{n:026}");
    let fixtures = [
        (
            include_str!("../fixtures/companion-v1/anchor.hex"),
            CompanionBody::Anchor {
                target: AnchorTarget::Text {
                    block_id: id(5),
                    start: 7,
                    end: 31,
                    detached: false,
                },
            },
        ),
        (
            include_str!("../fixtures/companion-v1/margin.hex"),
            CompanionBody::MarginNote {
                anchor_id: id(5),
                kind: MarginKind::Flag,
                text: "Question?".into(),
                reader: "Editor".into(),
                state: MarginState::Pinned,
                sources: vec![],
            },
        ),
        (
            include_str!("../fixtures/companion-v1/message.hex"),
            CompanionBody::ThreadMessage {
                margin_id: id(5),
                order: 0,
                who: MessageWho::Companion,
                text: "Reply".into(),
                sources: vec![],
            },
        ),
        (
            include_str!("../fixtures/companion-v1/drawing.hex"),
            CompanionBody::Drawing {
                anchor_id: id(5),
                title: "Flow".into(),
                width: 500.0,
                height: 300.0,
                steps: vec![CompanionDrawingStep {
                    caption: "Connect".into(),
                    primitives: vec![
                        DrawingPrimitive::Arrow {
                            from: DrawingPoint { x: 10.0, y: 20.0 },
                            to: DrawingPoint { x: 100.0, y: 20.0 },
                        },
                        DrawingPrimitive::Label {
                            x: 10.0,
                            y: 30.0,
                            text: "Cache".into(),
                            size: 14.0,
                        },
                    ],
                }],
            },
        ),
    ];
    for (hex, body) in fixtures {
        let c = Command {
            operation_id: id(3),
            dependencies: vec![],
            body: CommandBody::CompanionMarks {
                mutations: vec![CompanionMutation {
                    id: id(4),
                    base: Base::Absent,
                    tombstone: false,
                    body,
                }],
            },
        };
        let bytes = crate::canonical::command(&c, &id(1), &id(2)).unwrap();
        let encoded = bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
        assert_eq!(encoded, hex.trim());
    }
    let c = Command {
        operation_id: id(3),
        dependencies: vec![],
        body: CommandBody::SetCompanionSettings {
            base: Base::Absent,
            settings: CompanionSettings {
                tell: "Be brutal".into(),
                mode: CompanionMode::WhenAsked,
                web: false,
                active_reader: "editor".into(),
                colour: InkColour::Blue,
            },
        },
    };
    let bytes = crate::canonical::command(&c, &id(1), &id(2)).unwrap();
    assert_eq!(
        bytes.iter().map(|b| format!("{b:02x}")).collect::<String>(),
        include_str!("../fixtures/companion-v1/settings.hex").trim()
    );
}
#[test]
fn every_primitive_is_bounded_and_drawing_limits_fail_closed() {
    let valid = CompanionBody::Drawing {
        anchor_id: new_id(),
        title: "Bounds".into(),
        width: 500.0,
        height: 300.0,
        steps: vec![CompanionDrawingStep {
            caption: "Shapes".into(),
            primitives: vec![
                DrawingPrimitive::Line {
                    from: DrawingPoint { x: 0.0, y: 0.0 },
                    to: DrawingPoint { x: 500.0, y: 300.0 },
                },
                DrawingPrimitive::Polyline {
                    points: vec![
                        DrawingPoint { x: 0.0, y: 0.0 },
                        DrawingPoint { x: 50.0, y: 30.0 },
                    ],
                },
                DrawingPrimitive::Arrow {
                    from: DrawingPoint { x: 0.0, y: 0.0 },
                    to: DrawingPoint { x: 500.0, y: 300.0 },
                },
                DrawingPrimitive::Rectangle {
                    x: 0.0,
                    y: 0.0,
                    width: 500.0,
                    height: 300.0,
                },
                DrawingPrimitive::Ellipse {
                    x: 0.0,
                    y: 0.0,
                    width: 50.0,
                    height: 30.0,
                },
                DrawingPrimitive::Dot {
                    x: 50.0,
                    y: 30.0,
                    size: 4.0,
                },
                DrawingPrimitive::ShadedRegion {
                    points: vec![
                        DrawingPoint { x: 0.0, y: 0.0 },
                        DrawingPoint { x: 50.0, y: 30.0 },
                        DrawingPoint { x: 30.0, y: 60.0 },
                    ],
                },
                DrawingPrimitive::Label {
                    x: 50.0,
                    y: 30.0,
                    text: "Label".into(),
                    size: 14.0,
                },
            ],
        }],
    };
    crate::companion::validate(&valid).unwrap();
    let mut bad = valid.clone();
    if let CompanionBody::Drawing { steps, .. } = &mut bad {
        steps[0].primitives.push(DrawingPrimitive::Dot {
            x: f64::NAN,
            y: 0.0,
            size: 4.0,
        });
    }
    assert!(crate::companion::validate(&bad).is_err());
    bad = valid.clone();
    if let CompanionBody::Drawing { steps, .. } = &mut bad {
        steps.resize(13, steps[0].clone());
    }
    assert!(crate::companion::validate(&bad).is_err());
    bad = valid.clone();
    if let CompanionBody::Drawing { steps, .. } = &mut bad {
        steps[0].primitives.resize(
            401,
            DrawingPrimitive::Dot {
                x: 0.0,
                y: 0.0,
                size: 4.0,
            },
        );
    }
    assert!(crate::companion::validate(&bad).is_err());
    bad = valid;
    if let CompanionBody::Drawing { steps, .. } = &mut bad {
        steps[0].caption = "x".repeat(65537);
    }
    assert!(crate::companion::validate(&bad).is_err());
}
