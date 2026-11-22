//! Notes and canvases v1: kinds, pages, styles, waypoints, read-time legacy presentation, sync.
use crate::{library::sync::sha256_hex, *};
use std::{path::Path, sync::Arc};

fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
}
fn base(op: &str) -> Base {
    Base::Operation {
        operation_id: op.into(),
    }
}
fn cmd(body: CommandBody, deps: &[&str]) -> Command {
    Command {
        operation_id: new_id(),
        dependencies: deps.iter().map(|s| (*s).into()).collect(),
        body,
    }
}
fn marks(m: Vec<MarkMutation>, deps: &[&str]) -> Command {
    cmd(CommandBody::Marks { mutations: m }, deps)
}
fn blob(y: f32) -> Vec<u8> {
    encode_samples(SampleChannels {
        x: vec![10.0, 20.0],
        y: vec![y, y + 1.0],
        pressure: vec![0.5, 0.6],
        elapsed_ms: vec![0, 4],
        tilt: None,
        orientation: None,
    })
    .unwrap()
}
fn brush() -> Brush {
    Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Ink)
}

struct Device {
    root: tempfile::TempDir,
    lib: Arc<SyncrLibrary>,
    note: Arc<Note>,
    request: CreateNoteRequest,
}
fn create(kind: NoteKind) -> Device {
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    let request = CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Pages".into(),
        notebook: None,
        kind,
    };
    lib.create_note(request.clone()).unwrap();
    let note = lib.open_note(request.note_id.clone()).unwrap();
    Device {
        root,
        lib,
        note,
        request,
    }
}
fn peer_of(d: &Device) -> Device {
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("c.syncr");
    d.note.export_copy(path(&copy)).unwrap();
    let root = tempfile::tempdir().unwrap();
    let lib = open_library(path(root.path())).unwrap();
    lib.install_copy(path(&copy)).unwrap();
    let note = lib.open_note(d.request.note_id.clone()).unwrap();
    Device {
        root,
        lib,
        note,
        request: d.request.clone(),
    }
}
fn version(d: &Device) -> u32 {
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("v.syncr");
    d.note.export_copy(path(&copy)).unwrap();
    rusqlite::Connection::open(copy)
        .unwrap()
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .unwrap()
}
fn live(snap: &NoteSnapshot) -> Vec<(String, f32, f32)> {
    snap.pages
        .iter()
        .filter(|p| !p.tombstone)
        .map(|p| (p.sheet_id.clone(), p.width, p.height))
        .collect()
}
fn add_page(d: &Device, after: Option<&str>, w: f32, h: f32) -> (String, Command) {
    let id = new_id();
    let creation = d.request.operation_id.clone();
    let mut deps = vec![creation];
    if let Some(a) = after {
        let snap = d.note.snapshot().unwrap();
        deps.push(
            snap.sheets
                .iter()
                .find(|s| s.id == a)
                .unwrap()
                .creation_operation_id
                .clone(),
        );
    }
    let c = marks(
        vec![MarkMutation::AddPage {
            sheet_id: id.clone(),
            after: after.map(Into::into),
            width: w,
            height: h,
        }],
        &deps.iter().map(String::as_str).collect::<Vec<_>>(),
    );
    d.note.submit(c.clone()).unwrap();
    (id, c)
}
fn page_head(d: &Device, id: &str) -> Id {
    d.note
        .snapshot()
        .unwrap()
        .pages
        .into_iter()
        .find(|p| p.sheet_id == id)
        .unwrap()
        .head
        .operation_id
}
fn page_cmd(d: &Device, id: &str, make: impl FnOnce(Base) -> MarkMutation) -> Command {
    let h = page_head(d, id);
    marks(vec![make(base(&h))], &[&h])
}
fn undo(d: &Device, redo: bool) {
    let s = d.note.snapshot().unwrap();
    let (target, deps) = if redo {
        (s.redo_target.unwrap(), s.redo_dependencies)
    } else {
        (s.undo_target.unwrap(), s.undo_dependencies)
    };
    let body = if redo {
        CommandBody::Redo {
            target_operation_id: target,
        }
    } else {
        CommandBody::Undo {
            target_operation_id: target,
        }
    };
    d.note
        .submit(Command {
            operation_id: new_id(),
            dependencies: deps,
            body,
        })
        .unwrap();
}
fn a4() -> (f32, f32) {
    (1600.0, 2262.9)
}
fn merge(from: &Device, to: &Device) -> MergeReport {
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("copy.syncr");
    from.note.export_copy(path(&copy)).unwrap();
    to.note.merge_copy(path(&copy)).unwrap()
}
fn norm(d: &Device) -> NoteSnapshot {
    let mut s = d.note.snapshot().unwrap();
    s.cursor = EventCursor {
        stream_id: String::new(),
        sequence: 0,
    };
    s.local_sequence = 0;
    s.undo_target = None;
    s.redo_target = None;
    s.undo_dependencies.clear();
    s.redo_dependencies.clear();
    s
}

// ---- K4: a note written before pages -----------------------------------------------------

#[test]
fn a_v8_note_reads_as_one_auto_height_page_and_nothing_is_rewritten() {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/pages-v1/legacy-v8.syncr");
    let root = tempfile::tempdir().unwrap();
    let file = root.path().join("01M76000000000000000000002.syncr");
    std::fs::copy(&fixture, &file).unwrap();
    let before = std::fs::read(&file).unwrap();
    let digest = sha256_hex(&before);
    let conn =
        rusqlite::Connection::open_with_flags(&file, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
            .unwrap();
    assert_eq!(
        conn.pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap(),
        8
    );
    drop(conn);
    {
        let lib = open_library(path(root.path())).unwrap();
        let summary = lib.list_notes().unwrap();
        assert_eq!(summary[0].kind, NoteKind::Note);
        let note = lib.open_note("01M76000000000000000000002".into()).unwrap();
        let snap = note.snapshot().unwrap();
        assert_eq!(snap.kind, NoteKind::Note);
        assert_eq!(snap.pages.len(), 1);
        let page = &snap.pages[0];
        assert_eq!(page.sheet_id, snap.sheets[0].id);
        assert_eq!(
            (page.width, page.auto_height, page.tombstone),
            (4800.0, true, false)
        );
        // Lowest ink: a medium stroke's last sample at y = 3601 plus half the 4-unit width.
        assert_eq!(page.height, 3600.0 + 2.0 + 1000.0);
        assert!(snap.canvas_background.is_none() && snap.waypoints.is_none());
        assert!(snap.page_style.as_ref().unwrap().style.is_none());
        note.close_note().unwrap();
    }
    assert_eq!(
        sha256_hex(&std::fs::read(&file).unwrap()),
        digest,
        "bytes on disk changed"
    );
    assert!(!root.path().join(".backups").exists());
}

#[test]
fn an_empty_legacy_note_is_3000_tall_and_a_low_stroke_grows_it() {
    let d = create(NoteKind::Note);
    let p = &d.note.snapshot().unwrap().pages[0];
    assert_eq!((p.width, p.height, p.auto_height), (4800.0, 3000.0, true));
    let id = new_id();
    d.note
        .submit(marks(
            vec![MarkMutation::AddStroke {
                mark_id: id,
                sheet_id: d.request.first_sheet_id.clone(),
                samples: blob(5000.0),
                brush: brush(),
            }],
            &[&d.request.operation_id],
        ))
        .unwrap();
    assert_eq!(
        d.note.snapshot().unwrap().pages[0].height,
        5001.0 + 2.0 + 1000.0
    );
    // The first page command writes the list; page 1 keeps its auto height and width.
    assert_eq!(version(&d), 3);
    let (second, _) = add_page(&d, Some(&d.request.first_sheet_id), a4().0, a4().1);
    assert_eq!(version(&d), 9);
    let snap = d.note.snapshot().unwrap();
    assert_eq!(snap.pages.len(), 2);
    assert!(snap.pages[0].auto_height && snap.pages[0].width == 4800.0);
    assert_eq!(snap.pages[0].height, 6003.0);
    assert_eq!(snap.pages[1].sheet_id, second);
    assert!(!snap.pages[1].auto_height);
}

// ---- K1: kinds ---------------------------------------------------------------------------

#[test]
fn a_canvas_is_created_at_v9_and_keeps_its_kind_across_retry_and_reopen() {
    let d = create(NoteKind::Canvas);
    assert_eq!(version(&d), 9);
    let snap = d.note.snapshot().unwrap();
    assert_eq!(snap.kind, NoteKind::Canvas);
    assert!(snap.pages.is_empty() && snap.page_style.is_none());
    assert_eq!(snap.sheets.len(), 1);
    // A retry of the same request is a duplicate; the same IDs as a Note are a collision.
    assert!(d.lib.create_note(d.request.clone()).unwrap().duplicate);
    let as_note = CreateNoteRequest {
        kind: NoteKind::Note,
        ..d.request.clone()
    };
    assert!(matches!(
        d.lib.create_note(as_note),
        Err(CoreError::IdCollision { .. })
    ));
    assert_eq!(d.lib.list_notes().unwrap()[0].kind, NoteKind::Canvas);
    d.note.close_note().unwrap();
    let again = d.lib.open_note(d.request.note_id.clone()).unwrap();
    assert_eq!(again.snapshot().unwrap().kind, NoteKind::Canvas);
    // A Note request retried against a Note stays a duplicate (tag 3).
    let n = create(NoteKind::Note);
    assert!(n.lib.create_note(n.request.clone()).unwrap().duplicate);
    assert!(matches!(
        n.lib.create_note(CreateNoteRequest {
            kind: NoteKind::Canvas,
            ..n.request.clone()
        }),
        Err(CoreError::IdCollision { .. })
    ));
}

#[test]
fn canvas_and_note_commands_are_kept_apart() {
    let c = create(NoteKind::Canvas);
    let add = marks(
        vec![MarkMutation::AddPage {
            sheet_id: new_id(),
            after: None,
            width: 1600.0,
            height: 2000.0,
        }],
        &[&c.request.operation_id],
    );
    assert!(matches!(
        c.note.submit(add),
        Err(CoreError::InvalidInput { .. })
    ));
    let style = cmd(
        CommandBody::SetPageStyle {
            sheet_id: None,
            template: PagePattern::Dots,
            paper: None,
            pattern_colour: None,
            spacing: None,
            base: base(&c.request.operation_id),
        },
        &[&c.request.operation_id],
    );
    assert!(matches!(
        c.note.submit(style),
        Err(CoreError::InvalidInput { .. })
    ));
    let n = create(NoteKind::Note);
    let bg = cmd(
        CommandBody::SetCanvasBackground {
            pattern: PagePattern::Grid,
            pattern_colour: None,
            spacing: None,
            paper: None,
            base: base(&n.request.operation_id),
        },
        &[&n.request.operation_id],
    );
    assert!(matches!(
        n.note.submit(bg),
        Err(CoreError::InvalidInput { .. })
    ));
    // Sheets still cannot be added by the old command.
    let sheet = cmd(
        CommandBody::AddSheet {
            sheet_id: new_id(),
            base: base(&n.request.operation_id),
        },
        &[&n.request.operation_id],
    );
    assert!(matches!(
        n.note.submit(sheet),
        Err(CoreError::InvalidInput { .. })
    ));
}

// ---- K2: pages ---------------------------------------------------------------------------

#[test]
fn pages_add_resize_move_delete_restore_and_undo() {
    let d = create(NoteKind::Note);
    let first = d.request.first_sheet_id.clone();
    let (p2, _) = add_page(&d, Some(&first), a4().0, a4().1);
    let (p3, _) = add_page(&d, Some(&p2), 1127.6, 1600.0);
    let (p0, _) = add_page(&d, None, 800.0, 800.0);
    let order = |d: &Device| -> Vec<String> {
        d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .map(|p| p.sheet_id.clone())
            .collect()
    };
    assert_eq!(
        order(&d),
        [p0.clone(), first.clone(), p2.clone(), p3.clone()]
    );
    // Resize clears the legacy page's auto height and applies the size limits.
    let too_small = page_cmd(&d, &first, |b| MarkMutation::ResizePage {
        sheet_id: first.clone(),
        base: b,
        width: 75.0,
        height: 800.0,
    });
    assert!(matches!(
        d.note.submit(too_small),
        Err(CoreError::InvalidInput { .. })
    ));
    let r = page_cmd(&d, &first, |b| MarkMutation::ResizePage {
        sheet_id: first.clone(),
        base: b,
        width: 1600.0,
        height: 2262.9,
    });
    d.note.submit(r).unwrap();
    let snap = d.note.snapshot().unwrap();
    let f = snap.pages.iter().find(|p| p.sheet_id == first).unwrap();
    assert_eq!((f.width, f.height, f.auto_height), (1600.0, 2262.9, false));
    undo(&d, false);
    assert!(
        d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .find(|p| p.sheet_id == first)
            .unwrap()
            .auto_height
    );
    undo(&d, true);
    assert!(
        !d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .find(|p| p.sheet_id == first)
            .unwrap()
            .auto_height
    );
    // Move p3 first; undo puts it back, redo moves it again.
    let m = page_cmd(&d, &p3, |b| MarkMutation::MovePage {
        sheet_id: p3.clone(),
        base: b,
        after: None,
    });
    d.note.submit(m).unwrap();
    assert_eq!(
        order(&d),
        [p3.clone(), p0.clone(), first.clone(), p2.clone()]
    );
    undo(&d, false);
    assert_eq!(
        order(&d),
        [p0.clone(), first.clone(), p2.clone(), p3.clone()]
    );
    undo(&d, true);
    assert_eq!(order(&d)[0], p3);
    // Delete keeps the page, tombstoned, in place; the live list skips it; restore and undo work.
    let del = page_cmd(&d, &p2, |b| MarkMutation::DeletePage {
        sheet_id: p2.clone(),
        base: b,
    });
    d.note.submit(del).unwrap();
    let snap = d.note.snapshot().unwrap();
    assert_eq!(live(&snap).len(), 3);
    assert!(
        snap.pages
            .iter()
            .find(|p| p.sheet_id == p2)
            .unwrap()
            .tombstone
    );
    let res = page_cmd(&d, &p2, |b| MarkMutation::RestorePage {
        sheet_id: p2.clone(),
        base: b,
    });
    d.note.submit(res).unwrap();
    assert_eq!(live(&d.note.snapshot().unwrap()).len(), 4);
    undo(&d, false);
    assert_eq!(live(&d.note.snapshot().unwrap()).len(), 3);
    undo(&d, false);
    assert_eq!(live(&d.note.snapshot().unwrap()).len(), 4);
    // Undoing an add tombstones the page.
    let (p4, _) = add_page(&d, Some(&p2), 500.0, 500.0);
    undo(&d, false);
    assert!(
        d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .find(|p| p.sheet_id == p4)
            .unwrap()
            .tombstone
    );
    undo(&d, true);
    assert!(
        !d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .find(|p| p.sheet_id == p4)
            .unwrap()
            .tombstone
    );
}

#[test]
fn the_last_live_page_cannot_be_deleted_and_a_page_action_commits_with_its_content() {
    let d = create(NoteKind::Note);
    let first = d.request.first_sheet_id.clone();
    let del = page_cmd(&d, &first, |b| MarkMutation::DeletePage {
        sheet_id: first.clone(),
        base: b,
    });
    // Before any page command the base is the creation head.
    let Err(CoreError::InvalidInput { detail, .. }) = d.note.submit(del) else {
        panic!("last page deleted")
    };
    assert_eq!(detail, "A note must keep at least one page.");
    // A rotation: resize plus a stroke added on the page, one undo step.
    let (p2, _) = add_page(&d, Some(&first), 1600.0, 2262.9);
    let stroke = new_id();
    let creation = d.note.snapshot().unwrap().pages[1]
        .head
        .operation_id
        .clone();
    let c = marks(
        vec![
            MarkMutation::ResizePage {
                sheet_id: p2.clone(),
                base: base(&creation),
                width: 2262.9,
                height: 1600.0,
            },
            MarkMutation::AddStroke {
                mark_id: stroke.clone(),
                sheet_id: p2.clone(),
                samples: blob(10.0),
                brush: brush(),
            },
        ],
        &[&creation],
    );
    d.note.submit(c).unwrap();
    undo(&d, false);
    let snap = d.note.snapshot().unwrap();
    assert!(
        snap.strokes
            .iter()
            .find(|s| s.id == stroke)
            .unwrap()
            .tombstone
    );
    assert_eq!(snap.pages[1].width, 1600.0);
    // Deleting a page when only one other is live works; the last live page still refuses.
    let del2 = page_cmd(&d, &p2, |b| MarkMutation::DeletePage {
        sheet_id: p2.clone(),
        base: b,
    });
    d.note.submit(del2).unwrap();
    let del1 = page_cmd(&d, &first, |b| MarkMutation::DeletePage {
        sheet_id: first.clone(),
        base: b,
    });
    assert!(matches!(
        d.note.submit(del1),
        Err(CoreError::InvalidInput { .. })
    ));
}

#[test]
fn page_commands_check_bases_and_ids() {
    let d = create(NoteKind::Note);
    let first = d.request.first_sheet_id.clone();
    let (p2, _) = add_page(&d, Some(&first), 1000.0, 1000.0);
    // A stale base.
    let stale = marks(
        vec![MarkMutation::ResizePage {
            sheet_id: p2.clone(),
            base: base(&d.request.operation_id),
            width: 900.0,
            height: 900.0,
        }],
        &[&d.request.operation_id],
    );
    assert!(matches!(
        d.note.submit(stale),
        Err(CoreError::StaleBase { .. })
    ));
    // Unknown sheet, reused sheet id, a missing `after`, and moving after itself.
    let unknown = new_id();
    let c = marks(
        vec![MarkMutation::ResizePage {
            sheet_id: unknown.clone(),
            base: Base::Absent,
            width: 900.0,
            height: 900.0,
        }],
        &[],
    );
    assert!(d.note.submit(c).is_err());
    let again = marks(
        vec![MarkMutation::AddPage {
            sheet_id: p2.clone(),
            after: None,
            width: 900.0,
            height: 900.0,
        }],
        &[],
    );
    assert!(matches!(
        d.note.submit(again),
        Err(CoreError::IdCollision { .. })
    ));
    let lost = marks(
        vec![MarkMutation::AddPage {
            sheet_id: new_id(),
            after: Some(unknown),
            width: 900.0,
            height: 900.0,
        }],
        &[],
    );
    assert!(d.note.submit(lost).is_err());
    let own = page_cmd(&d, &p2, |b| MarkMutation::MovePage {
        sheet_id: p2.clone(),
        base: b,
        after: Some(p2.clone()),
    });
    assert!(matches!(
        d.note.submit(own),
        Err(CoreError::InvalidInput { .. })
    ));
    // An add after a page needs that page's creation operation among the dependencies.
    let no_dep = marks(
        vec![MarkMutation::AddPage {
            sheet_id: new_id(),
            after: Some(p2.clone()),
            width: 900.0,
            height: 900.0,
        }],
        &[],
    );
    assert!(matches!(
        d.note.submit(no_dep),
        Err(CoreError::MissingDependency { .. })
    ));
    // Limits: 76 and 15238 units are in; 15239 is out.
    add_page(&d, None, 76.0, 15238.0);
    let big = marks(
        vec![MarkMutation::AddPage {
            sheet_id: new_id(),
            after: None,
            width: 15239.0,
            height: 100.0,
        }],
        &[],
    );
    assert!(matches!(
        d.note.submit(big),
        Err(CoreError::InvalidInput { .. })
    ));
}

#[test]
fn page_styles_resolve_page_then_note_then_default() {
    let d = create(NoteKind::Note);
    let first = d.request.first_sheet_id.clone();
    let snap = d.note.snapshot().unwrap();
    let view = snap.page_style.unwrap();
    assert_eq!(
        view.resolved,
        ResolvedPageStyle {
            pattern: PagePattern::None,
            paper: None,
            pattern_colour: Rgba {
                r: 150,
                g: 150,
                b: 150,
                a: 128
            },
            spacing: 82.6
        }
    );
    let note_style = cmd(
        CommandBody::SetPageStyle {
            sheet_id: None,
            template: PagePattern::Lines,
            paper: Some(Rgba {
                r: 255,
                g: 250,
                b: 240,
                a: 255,
            }),
            pattern_colour: None,
            spacing: Some(500.0),
            base: base(&view.head.operation_id),
        },
        &[&view.head.operation_id],
    );
    let note_op = note_style.operation_id.clone();
    d.note.submit(note_style).unwrap();
    let snap = d.note.snapshot().unwrap();
    assert_eq!(snap.pages[0].resolved_style.pattern, PagePattern::Lines);
    assert_eq!(snap.pages[0].resolved_style.spacing, 258.0, "clamped");
    assert_eq!(snap.page_style.as_ref().unwrap().head.operation_id, note_op);
    // A page override replaces the note's style as a whole; its head is its own.
    let p = &snap.pages[0];
    let over = cmd(
        CommandBody::SetPageStyle {
            sheet_id: Some(first.clone()),
            template: PagePattern::Grid,
            paper: None,
            pattern_colour: Some(Rgba {
                r: 1,
                g: 2,
                b: 3,
                a: 4,
            }),
            spacing: Some(10.0),
            base: base(&p.style_head.operation_id),
        },
        &[&p.style_head.operation_id],
    );
    d.note.submit(over).unwrap();
    let snap = d.note.snapshot().unwrap();
    let r = snap.pages[0].resolved_style;
    assert_eq!(
        (r.pattern, r.paper, r.spacing, r.pattern_colour.a),
        (PagePattern::Grid, None, 20.6, 4)
    );
    assert_eq!(
        snap.pages[0].head.operation_id, d.request.operation_id,
        "style does not touch the page head"
    );
    // The same base twice is stale.
    let stale = cmd(
        CommandBody::SetPageStyle {
            sheet_id: None,
            template: PagePattern::Dots,
            paper: None,
            pattern_colour: None,
            spacing: None,
            base: base(&view.head.operation_id),
        },
        &[&view.head.operation_id],
    );
    assert!(matches!(
        d.note.submit(stale),
        Err(CoreError::StaleBase { .. })
    ));
    assert_eq!(version(&d), 9);
}

// ---- K3: canvas --------------------------------------------------------------------------

#[test]
fn canvas_background_and_waypoints_default_set_replace_and_clamp() {
    let d = create(NoteKind::Canvas);
    let snap = d.note.snapshot().unwrap();
    let bg = snap.canvas_background.unwrap();
    assert_eq!(
        (bg.pattern, bg.pattern_colour, bg.spacing, bg.paper),
        (
            PagePattern::Grid,
            Rgba {
                r: 150,
                g: 150,
                b: 150,
                a: 64
            },
            82.6,
            None
        )
    );
    assert!(snap.waypoints.as_ref().unwrap().items.is_empty());
    let set = cmd(
        CommandBody::SetCanvasBackground {
            pattern: PagePattern::Dots,
            pattern_colour: None,
            spacing: Some(5.0),
            paper: Some(Rgba {
                r: 10,
                g: 20,
                b: 30,
                a: 255,
            }),
            base: base(&bg.head.operation_id),
        },
        &[&bg.head.operation_id],
    );
    d.note.submit(set).unwrap();
    let bg = d.note.snapshot().unwrap().canvas_background.unwrap();
    assert_eq!(
        (bg.pattern, bg.spacing, bg.paper.unwrap().g),
        (PagePattern::Dots, 20.6, 20)
    );
    let w = |name: &str, cx: f32| Waypoint {
        name: name.into(),
        cx,
        cy: 2.0,
        zoom: 1.5,
    };
    let head = snap.waypoints.unwrap().head.operation_id;
    let save = cmd(
        CommandBody::SetWaypoints {
            waypoints: vec![w("Home", 1.0), w("Desk", 3.0), w("HOME", 9.0)],
            base: base(&head),
        },
        &[&head],
    );
    d.note.submit(save).unwrap();
    let items = d.note.snapshot().unwrap().waypoints.unwrap().items;
    assert_eq!(
        items,
        vec![w("HOME", 9.0), w("Desk", 3.0)],
        "a same-name waypoint replaces, ignoring case"
    );
    // Bad names and values are refused; unicode counts characters.
    let head = d
        .note
        .snapshot()
        .unwrap()
        .waypoints
        .unwrap()
        .head
        .operation_id;
    for bad in [
        w("", 0.0),
        w(&"x".repeat(49), 0.0),
        Waypoint {
            zoom: 0.0,
            ..w("z", 0.0)
        },
        Waypoint {
            cx: f32::NAN,
            ..w("n", 0.0)
        },
    ] {
        let c = cmd(
            CommandBody::SetWaypoints {
                waypoints: vec![bad],
                base: base(&head),
            },
            &[&head],
        );
        assert!(matches!(
            d.note.submit(c),
            Err(CoreError::InvalidInput { .. })
        ));
    }
    let ok = cmd(
        CommandBody::SetWaypoints {
            waypoints: vec![w(&"é".repeat(48), 0.0)],
            base: base(&head),
        },
        &[&head],
    );
    d.note.submit(ok).unwrap();
}

#[test]
fn a_canvas_takes_ink_anywhere_without_a_range_check() {
    let d = create(NoteKind::Canvas);
    let ch = SampleChannels {
        x: vec![-90000.0, 250000.0],
        y: vec![-5.0e6, 7.0e6],
        pressure: vec![0.5, 0.5],
        elapsed_ms: vec![0, 1],
        tilt: None,
        orientation: None,
    };
    let c = marks(
        vec![MarkMutation::AddStroke {
            mark_id: new_id(),
            sheet_id: d.request.first_sheet_id.clone(),
            samples: encode_samples(ch).unwrap(),
            brush: brush(),
        }],
        &[&d.request.operation_id],
    );
    d.note.submit(c).unwrap();
    let pic = ImageMutation::Add {
        image_id: new_id(),
        bytes: include_bytes!("../fixtures/image-v2/pixel.png").to_vec(),
        media_type: ImageMediaType::Png,
        pixel_width: 1,
        pixel_height: 1,
        placement: ImagePlacement::Canvas {
            sheet_id: d.request.first_sheet_id.clone(),
            x: -123456.0,
            y: 987654.0,
            width: 10.0,
            height: 10.0,
        },
    };
    d.note
        .submit(cmd(
            CommandBody::ImageMarks {
                mutations: vec![pic],
            },
            &[&d.request.operation_id],
        ))
        .unwrap();
}

// ---- sizes ---------------------------------------------------------------------------------

#[test]
fn named_sizes_and_matching() {
    let sizes = page_sizes();
    assert_eq!(sizes.len(), 6);
    let near = |a: f32, b: f32| (a - b).abs() < 0.06;
    let expect = [
        ("A4", 1600.0, 2262.9),
        ("A5", 1127.6, 1600.0),
        ("Letter", 1645.0, 2128.8),
        ("Legal", 1645.0, 2709.3),
        ("Slide 16:9", 1451.4, 2580.6),
        ("Slide 4:3", 1451.4, 1935.2),
    ];
    for (s, (name, w, h)) in sizes.iter().zip(expect) {
        assert!(
            s.name == name && near(s.width, w) && near(s.height, h),
            "{s:?}"
        );
    }
    assert_eq!(match_size(1600.0, 2262.9).unwrap().name, "A4");
    assert_eq!(
        match_size(2262.9, 1600.0).unwrap().name,
        "A4",
        "landscape matches too"
    );
    // 0.5 mm is 3.8 units: inside matches, outside does not.
    assert_eq!(match_size(1600.0 + 3.7, 2262.9).unwrap().name, "A4");
    assert!(match_size(1600.0 + 4.0, 2262.9).is_none());
    assert!(match_size(1000.0, 1000.0).is_none());
    assert_eq!(default_page_gap(), 20.6);
}

// ---- K6: sync ----------------------------------------------------------------------------

#[test]
fn every_new_command_travels_by_copy_and_by_delta_and_the_peers_converge() {
    let a = create(NoteKind::Note);
    let b = peer_of(&a);
    let first = a.request.first_sheet_id.clone();
    let (p2, _) = add_page(&a, Some(&first), 1600.0, 2262.9);
    let (p3, _) = add_page(&a, Some(&p2), 1127.6, 1600.0);
    let r = page_cmd(&a, &p3, |bs| MarkMutation::ResizePage {
        sheet_id: p3.clone(),
        base: bs,
        width: 900.0,
        height: 900.0,
    });
    a.note.submit(r).unwrap();
    let m = page_cmd(&a, &p3, |bs| MarkMutation::MovePage {
        sheet_id: p3.clone(),
        base: bs,
        after: None,
    });
    a.note.submit(m).unwrap();
    let del = page_cmd(&a, &p2, |bs| MarkMutation::DeletePage {
        sheet_id: p2.clone(),
        base: bs,
    });
    a.note.submit(del).unwrap();
    let res = page_cmd(&a, &p2, |bs| MarkMutation::RestorePage {
        sheet_id: p2.clone(),
        base: bs,
    });
    a.note.submit(res).unwrap();
    let head = a
        .note
        .snapshot()
        .unwrap()
        .page_style
        .unwrap()
        .head
        .operation_id;
    a.note
        .submit(cmd(
            CommandBody::SetPageStyle {
                sheet_id: None,
                template: PagePattern::Grid,
                paper: None,
                pattern_colour: None,
                spacing: Some(100.0),
                base: base(&head),
            },
            &[&head],
        ))
        .unwrap();
    // First by delta, then both ways by copy.
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("d.syncr-delta");
    let receipt = a
        .note
        .export_delta(b.note.sync_heads().unwrap(), path(&file))
        .unwrap();
    assert!(receipt.anchored);
    let merged = b.note.merge_delta(path(&file)).unwrap();
    assert!(
        merged.complete && merged.report.rejected.is_empty(),
        "{merged:?}"
    );
    assert_eq!(merged.report.applied, receipt.operations);
    assert_eq!(a.note.sync_digest().unwrap(), b.note.sync_digest().unwrap());
    assert_eq!(norm(&a), norm(&b));
    assert_eq!(norm(&b).pages.len(), 3);
    assert_eq!(version(&b), 9);
    assert!(merge(&b, &a).rejected.is_empty());

    // A canvas: background and waypoints cross too.
    let c = create(NoteKind::Canvas);
    let d = peer_of(&c);
    let snap = c.note.snapshot().unwrap();
    let h = snap.canvas_background.unwrap().head.operation_id;
    c.note
        .submit(cmd(
            CommandBody::SetCanvasBackground {
                pattern: PagePattern::Lines,
                pattern_colour: None,
                spacing: None,
                paper: None,
                base: base(&h),
            },
            &[&h],
        ))
        .unwrap();
    let h = snap.waypoints.unwrap().head.operation_id;
    c.note
        .submit(cmd(
            CommandBody::SetWaypoints {
                waypoints: vec![Waypoint {
                    name: "A".into(),
                    cx: 1.0,
                    cy: 2.0,
                    zoom: 3.0,
                }],
                base: base(&h),
            },
            &[&h],
        ))
        .unwrap();
    assert!(merge(&c, &d).rejected.is_empty());
    assert_eq!(norm(&c), norm(&d));
    assert_eq!(norm(&d).kind, NoteKind::Canvas);
}

#[test]
fn a_concurrent_move_of_one_page_is_rejected_on_the_merger_and_the_mac_wins() {
    let mac = create(NoteKind::Note);
    let first = mac.request.first_sheet_id.clone();
    let (p2, _) = add_page(&mac, Some(&first), 1000.0, 1000.0);
    let (p3, _) = add_page(&mac, Some(&p2), 1000.0, 1000.0);
    let phone = peer_of(&mac);
    let order = |d: &Device| -> Vec<String> {
        d.note
            .snapshot()
            .unwrap()
            .pages
            .iter()
            .map(|p| p.sheet_id.clone())
            .collect()
    };
    let mac_move = page_cmd(&mac, &p3, |bs| MarkMutation::MovePage {
        sheet_id: p3.clone(),
        base: bs,
        after: None,
    });
    mac.note.submit(mac_move).unwrap();
    let phone_move = page_cmd(&phone, &p3, |bs| MarkMutation::MovePage {
        sheet_id: p3.clone(),
        base: bs,
        after: Some(first.clone()),
    });
    phone.note.submit(phone_move.clone()).unwrap();
    let report = merge(&phone, &mac);
    assert_eq!((report.applied, report.rejected.len()), (0, 1));
    assert_eq!(report.rejected[0].operation_id, phone_move.operation_id);
    assert_eq!(report.rejected[0].reason, "stale base");
    assert_eq!(order(&mac), [p3.clone(), first.clone(), p2.clone()]);
    // The phone takes the Mac's copy and keeps what still applies.
    let tmp = tempfile::tempdir().unwrap();
    let copy = tmp.path().join("mac.syncr");
    mac.note.export_copy(path(&copy)).unwrap();
    phone.note.close_note().unwrap();
    let rebased = phone
        .lib
        .rebase_onto_copy(phone.request.note_id.clone(), path(&copy))
        .unwrap();
    assert_eq!(rebased.rejected.len(), 1);
    let reopened = phone.lib.open_note(phone.request.note_id.clone()).unwrap();
    let got: Vec<String> = reopened
        .snapshot()
        .unwrap()
        .pages
        .iter()
        .map(|p| p.sheet_id.clone())
        .collect();
    assert_eq!(got, [p3, first, p2]);
}
