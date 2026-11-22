use crate::*;
fn svg(body: &str) -> String {
    format!(
        "<svg viewBox=\"0 0 320 200\"><g data-step=\"1\" data-caption=\"Start\">{body}</g></svg>"
    )
}
fn shapes() -> &'static str {
    "<line x1=\"10\" y1=\"20\" x2=\"100\" y2=\"20\" stroke=\"var(--ink)\"/><text x=\"10\" y=\"40\">Label</text>"
}
#[test]
fn canonical_picture_idempotence_and_determinism() {
    let input = svg(shapes());
    let clean = filter_picture(input).unwrap();
    assert_eq!(filter_picture(clean.clone()).unwrap(), clean);
    assert_eq!(
        picture_display_list(clean.clone()).unwrap(),
        picture_display_list(clean).unwrap()
    );
}
#[test]
fn dangling_references_and_random_hostile_text_are_stable() {
    for extra in [
        "<rect id=\"a\" width=\"bad\"/><use href=\"#a\"/>",
        "<use id=\"a\" href=\"#missing\"/><use id=\"b\" href=\"#a\"/><use href=\"#b\"/>",
        "<svg id=\"a\"><text>Nested</text></svg><use href=\"#a\"/>",
    ] {
        let clean = filter_picture(svg(&format!("{}{extra}", shapes()))).unwrap();
        assert_eq!(filter_picture(clean.clone()).unwrap(), clean);
        assert!(!clean.contains("<use"));
    }
    let alphabet: Vec<char> = "<>/=\"'&;!-+012eNaNxyz é🖊\n".chars().collect();
    let mut seed = 17u64;
    for length in 0..1000 {
        let mut input = String::new();
        for _ in 0..length % 257 {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            input.push(alphabet[(seed >> 32) as usize % alphabet.len()]);
        }
        if let Ok(clean) = filter_picture(svg(&format!("{}{input}", shapes()))) {
            assert_eq!(filter_picture(clean.clone()).unwrap(), clean);
        }
    }
}

#[test]
fn picture_allowlists_remove_unsafe_subtrees_attributes_and_styles() {
    for bad in [
        "<script>alert(1)</script>",
        "<style>text{fill:red}</style>",
        "<foreignObject><text>HTML</text></foreignObject>",
        "<image href=\"data:image/png,x\"/>",
        "<a href=\"https://x\"><text>Link</text></a>",
        "<animate attributeName=\"x\"/>",
        "<!-- comment -->",
        "<![CDATA[<text>CDATA</text>]]>",
    ] {
        let input = svg(&format!("{}{bad}", shapes()));
        let clean = filter_picture(input).unwrap();
        assert!(
            !clean.contains("alert")
                && !clean.contains("HTML")
                && !clean.contains("CDATA")
                && !clean.contains("Link")
        );
    }
    for (key, value) in [
        ("onclick", "bad()"),
        ("filter", "url(#x)"),
        ("fill", "red"),
        ("stroke", "#fff"),
        ("font-family", "Arial"),
        ("font-size", "9"),
        ("font-size", "21"),
        ("stroke-width", "0.7"),
        ("stroke-width", "2.6"),
        ("stroke-linecap", "square"),
        ("stroke-linejoin", "miter"),
        ("transform", "skewX(5)"),
        ("transform", "matrix(1 0 0 1 0 0)"),
        ("marker-end", "url(https://x)"),
        ("marker-start", "url(data:x)"),
        ("text-anchor", "bad"),
        ("dominant-baseline", "bad"),
    ] {
        let input = svg(&format!("<g {key}=\"{value}\">{}</g>", shapes()));
        let clean = filter_picture(input).unwrap();
        assert!(
            !clean.contains(&format!("{key}=\"{value}\"")),
            "{key}={value}"
        );
    }
    let clean = filter_picture(svg(&format!(
        "<g style=\"fill:var(--voice);font-size:15;position:absolute;stroke-linecap:round\">{}</g>",
        shapes()
    )))
    .unwrap();
    assert!(clean.contains("fill=\"var(--voice)\"") && !clean.contains("style="));
    for colour in ["ink", "soft", "muted", "hairline", "wash", "page", "voice"] {
        assert!(
            filter_picture(svg(&format!(
                "<g fill=\"var(--{colour})\">{}</g>",
                shapes()
            )))
            .unwrap()
            .contains(&format!("var(--{colour})"))
        );
    }
}
#[test]
fn picture_hostile_xml_and_resource_budgets() {
    for bad in [
        "",
        "<svg>",
        "<svg><g></svg></g>",
        "<!DOCTYPE svg [<!ENTITY a 'x'>]><svg/>",
        "<svg viewBox=\"0 0 320 200\">&evil;</svg>",
        "<svg a=\"1\" a=\"2\"/>",
        "<svg><text>\0</text></svg>",
    ] {
        assert!(filter_picture(bad.into()).is_err(), "{bad}");
    }
    for bad in [
        svg(&"<circle r=\"2\"/>".repeat(10000)),
        "x".repeat(1_000_000),
        svg(&format!(
            "{}{}{}",
            "<g>".repeat(100),
            shapes(),
            "</g>".repeat(100)
        )),
    ] {
        assert!(filter_picture(bad).is_err());
    }
    for value in ["NaN", "inf", "-inf", "1e100", "1000001"] {
        let clean = filter_picture(svg(&format!(
            "<g transform=\"translate({value})\">{}</g>",
            shapes()
        )))
        .unwrap();
        assert!(!clean.contains(value));
    }
    for value in ["https://x", "data:x", "#missing"] {
        let clean = filter_picture(svg(&format!("{}<use href=\"{value}\"/>", shapes()))).unwrap();
        assert!(!clean.contains("<use"));
    }
    let cyclic =
        svg("<defs><g id=\"a\"><use href=\"#a\"/></g></defs><use href=\"#a\"/><text>X</text>");
    assert_eq!(filter_picture(cyclic), Err(PictureError::References));
    for len in 0..256 {
        let s = "é🖊".repeat(len);
        let _ = filter_picture(svg(&format!("<text>{s}</text><text>{s}</text>")));
    }
}
#[test]
fn picture_canvas_steps_caption_limits_and_numbers() {
    for canvas in [
        "0 0 319 200",
        "1 0 320 200",
        "0 0 320 59.9",
        "0 0 320 900.1",
    ] {
        assert_eq!(
            filter_picture(svg(shapes()).replace("0 0 320 200", canvas)),
            Err(PictureError::Canvas)
        );
    }
    for step in ["0", "2", "13"] {
        assert!(
            filter_picture(
                svg(shapes()).replace("data-step=\"1\"", &format!("data-step=\"{step}\""))
            )
            .is_err()
        );
    }
    assert!(filter_picture(format!("<svg viewBox=\"0 0 320 200\">{}</svg>", shapes())).is_err());
    assert!(
        filter_picture(svg(shapes()).replace("Start", &"é".repeat(160)))
            .unwrap()
            .contains(&"é".repeat(160))
    );
    assert!(
        !filter_picture(svg(shapes()).replace("Start", &"é".repeat(161)))
            .unwrap()
            .contains("data-caption")
    );
    let clean = filter_picture(svg(&format!(
        "<g transform=\"translate(1.234 -0.001) scale(2) rotate(90)\">{}</g>",
        shapes()
    )))
    .unwrap();
    assert!(clean.contains("translate(1.2 0)"));
    assert_eq!(
        filter_picture(svg("<text>Only one</text>")),
        Err(PictureError::Empty)
    );
    assert_eq!(
        filter_picture(svg(&"<circle r=\"1\"/>".repeat(401))),
        Err(PictureError::Limit)
    );
}
#[test]
fn all_path_commands_and_transforms_resolve() {
    let path = "M 10 10 l 5 5 H 30 v 10 C 30 50 50 50 50 40 s 20 -10 20 0 Q 80 60 90 40 t 20 0 A 10 20 30 0 1 140 60 z";
    let clean=filter_picture(svg(&format!("<path d=\"{path}\" fill=\"none\" stroke=\"var(--voice)\"/><text x=\"20\" y=\"100\" text-anchor=\"middle\" dominant-baseline=\"central\" font-family=\"var(--font-sans)\">A<tspan dx=\"5\" dy=\"18\">B</tspan></text>"))).unwrap();
    let display = picture_display_list(clean).unwrap();
    assert_eq!(display.steps[0].items.len(), 3);
    assert!(
        matches!(&display.steps[0].items[0],PictureItem::Polyline{points,closed:true,..} if points.len()>20)
    );
    let d = picture_display_list(svg(&format!(
        "<g transform=\"translate(10 20) scale(2)\">{}</g>",
        shapes()
    )))
    .unwrap();
    assert!(
        matches!(&d.steps[0].items[0],PictureItem::Polyline{points,..} if points[0]==PicturePoint{x:30.0,y:60.0})
    );
}
#[test]
fn golden_picture_display_lists() {
    for name in ["board", "call-tree", "forces"] {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/picture-v2");
        let input = std::fs::read_to_string(root.join(format!("{name}.svg"))).unwrap();
        let clean = filter_picture(input).unwrap();
        assert_eq!(filter_picture(clean.clone()).unwrap(), clean);
        let got = picture_display_list(clean).unwrap();
        let expected: PictureDisplayList = serde_json::from_str(
            &std::fs::read_to_string(root.join(format!("{name}.display.json"))).unwrap(),
        )
        .unwrap();
        assert_eq!(got, expected, "{name}");
    }
}
#[test]
fn picture_storage_admission_history_reopen_and_vector() {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(root.path().to_str().unwrap().into()).unwrap();
    let r = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Pictures".into(),
        notebook: None,
        kind: NoteKind::Note,
    };
    lib.create_note(r.clone()).unwrap();
    let note = lib.open_note(r.note_id.clone()).unwrap();
    let anchor = new_id();
    let id = new_id();
    let body = CompanionBody::Picture {
        anchor_id: anchor.clone(),
        title: "Flow".into(),
        svg: filter_picture(svg(shapes())).unwrap(),
    };
    let command = Command {
        operation_id: new_id(),
        dependencies: vec![r.operation_id.clone()],
        body: CommandBody::CompanionMarks {
            mutations: vec![
                CompanionMutation {
                    id: anchor,
                    base: Base::Absent,
                    tombstone: false,
                    body: CompanionBody::Anchor {
                        target: AnchorTarget::Canvas {
                            sheet_id: r.first_sheet_id,
                            x: 0.0,
                            y: 0.0,
                            width: 320.0,
                            height: 200.0,
                        },
                    },
                },
                CompanionMutation {
                    id: id.clone(),
                    base: Base::Absent,
                    tombstone: false,
                    body: body.clone(),
                },
            ],
        },
    };
    note.submit_agent(command.clone()).unwrap();
    assert!(note.submit_agent(command).unwrap().duplicate);
    for redo in [false, true] {
        let s = note.agent_snapshot().unwrap();
        note.submit_agent(Command {
            operation_id: new_id(),
            dependencies: if redo {
                s.redo_dependencies
            } else {
                s.undo_dependencies
            },
            body: if redo {
                CommandBody::Redo {
                    target_operation_id: s.redo_target.unwrap(),
                }
            } else {
                CommandBody::Undo {
                    target_operation_id: s.undo_target.unwrap(),
                }
            },
        })
        .unwrap();
        assert_eq!(note.snapshot().unwrap().companion_items[1].tombstone, !redo);
    }
    let mut raw = body.clone();
    if let CompanionBody::Picture { svg, .. } = &mut raw {
        svg.push(' ');
    }
    assert!(crate::companion::validate(&raw).is_err());
    note.close_note().unwrap();
    let reopened = lib.open_note(r.note_id.clone()).unwrap();
    assert_eq!(reopened.snapshot().unwrap().companion_items[1].body, body);
    let db = rusqlite::Connection::open(root.path().join(format!("{}.syncr", r.note_id))).unwrap();
    assert_eq!(crate::store::check_version(&db).unwrap(), 6);
    let id = |n| format!("{n:026}");
    let vector: Command =
        serde_json::from_str(include_str!("../fixtures/picture-v2/picture.command.json")).unwrap();
    assert_eq!(
        crate::canonical::command(&vector, &id(1), &id(2))
            .unwrap()
            .iter()
            .map(|v| format!("{v:02x}"))
            .collect::<String>(),
        include_str!("../fixtures/picture-v2/picture.hex").trim()
    );
}
#[test]
fn picture_marker_without_viewbox_does_not_panic() {
    let input = "<svg viewBox=\"0 0 320 200\"><defs><marker id=\"tip\" markerWidth=\"6\" markerHeight=\"6\" refX=\"5\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L6,3 L0,6\" fill=\"none\" stroke=\"var(--ink)\"/></marker></defs><g data-step=\"1\" data-caption=\"Arrow\"><line x1=\"10\" y1=\"20\" x2=\"100\" y2=\"20\" stroke=\"var(--ink)\" marker-end=\"url(#tip)\"/><text x=\"10\" y=\"40\">Label</text></g></svg>";
    let clean = filter_picture(input.into()).unwrap();
    let list = picture_display_list(clean).unwrap();
    assert!(list.steps[0].items.len() >= 3);
    picture_display_list(input.into()).unwrap();
}
