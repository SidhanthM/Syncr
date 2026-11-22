//! In-memory candidate projection; it is installed only by the same transaction as the log.
use crate::{
    error::{collision, dependency, invalid, missing, stale},
    pages::{CanvasStyle, Headed, PageEdit, install_page_edit},
    text::{TextEdit, TextEffect},
    *,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, HashSet};

/// Page format of a note written before the canvas amendment (note schema v1/v2). Only
/// read from stored state and creation payloads to place old sheets; never produced.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) enum LegacyPageFormat {
    /// Portrait phone page, 1600 x 3200 note units.
    Phone,
    /// 16:10 sheet, 1600 x 1000 note units (also every note created before formats).
    Sheet16x10,
}
impl LegacyPageFormat {
    pub fn page_height(format: Option<Self>) -> f32 {
        match format.unwrap_or(LegacyPageFormat::Sheet16x10) {
            LegacyPageFormat::Phone => 3200.0,
            LegacyPageFormat::Sheet16x10 => 1000.0,
        }
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Sheet {
    pub id: Id,
    pub creation: Id,
    // Absent in state written before the canvas amendment; set by the v3 upgrade.
    #[serde(default)]
    pub origin_x: f32,
    #[serde(default)]
    pub origin_y: f32,
    // Page state (notes and canvases v1). Only meaningful for a sheet in `State::pages`.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub page_width: f32,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub page_height: f32,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub auto_height: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub tombstone: bool,
    // None until a page command changes it: the creation head.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub page_head: Option<EntityHead>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub style: Option<Headed<PageStyle>>,
}
fn is_zero(value: &f32) -> bool {
    *value == 0.0
}
impl Sheet {
    pub fn new(id: Id, creation: Id) -> Self {
        Self {
            id,
            creation,
            origin_x: 0.0,
            origin_y: 0.0,
            page_width: 0.0,
            page_height: 0.0,
            auto_height: false,
            tombstone: false,
            page_head: None,
            style: None,
        }
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Action {
    pub operation: Id,
    pub before: Vec<StrokeView>,
    pub after: Vec<StrokeView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub text_before: Vec<TextEdit>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub text_after: Vec<TextEdit>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub companion_before: Vec<CompanionItemView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub companion_after: Vec<CompanionItemView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub image_before: Vec<ImageMarkView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub image_after: Vec<ImageMarkView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub page_before: Vec<PageEdit>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub page_after: Vec<PageEdit>,
}
#[derive(Clone, Default, Serialize, Deserialize)]
pub(crate) struct History {
    pub undo: Vec<Action>,
    pub redo: Vec<Action>,
    pub expected: BTreeMap<Id, Id>,
}
#[derive(Serialize, Deserialize)]
pub(crate) struct MarkEffect {
    pub mark_id: Id,
    pub before: Option<StrokeView>,
    pub after: StrokeView,
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct State {
    pub note_id: Id,
    pub title: String,
    // Pre-canvas state (schema v1/v2) only: read to place old sheets, never written
    // again. Absent before page formats existed (Sheet16x10) and in canvas state.
    #[serde(rename = "page_format", default, skip_serializing)]
    pub legacy_page_format: Option<LegacyPageFormat>,
    #[serde(default)]
    pub notebook: Option<String>,
    // Absent in note state written before archiving existed: not archived.
    #[serde(default)]
    pub archived: bool,
    pub creation: Id,
    pub metadata_head: EntityHead,
    pub structure_head: EntityHead,
    pub sheets: Vec<Sheet>,
    // Notes and canvases v1. A note written before has none of these: a Note, no page list
    // (its first sheet is its one page), no styles.
    #[serde(default, skip_serializing_if = "is_note")]
    pub kind: NoteKind,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub pages: Vec<Id>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub page_style: Option<Headed<PageStyle>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub canvas_style: Option<Headed<CanvasStyle>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub waypoint_list: Option<Headed<Vec<Waypoint>>>,
    pub strokes: Vec<StrokeView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub text_blocks: Vec<TextBlockView>,
    // Candidate-only effects; persisted separately in the commit transaction.
    #[serde(skip)]
    pub text_effects: Vec<TextEffect>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub companion_items: Vec<CompanionItemView>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub companion_settings: Option<CompanionSettingsView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<ImageMarkView>,
    pub sequence: u64,
    pub histories: BTreeMap<Id, History>,
}
fn is_note(kind: &NoteKind) -> bool {
    *kind == NoteKind::Note
}
pub(crate) const IDENTITY: Affine = Affine {
    a: 1.0,
    b: 0.0,
    c: 0.0,
    d: 1.0,
    tx: 0.0,
    ty: 0.0,
};

pub(crate) fn head(operation: &str) -> EntityHead {
    EntityHead {
        operation_id: operation.into(),
        confirmed_revision: None,
    }
}
impl State {
    pub fn new(r: &CreateNoteRequest) -> Self {
        Self {
            note_id: r.note_id.clone(),
            title: r.title.clone(),
            legacy_page_format: None,
            notebook: r.notebook.clone(),
            archived: false,
            creation: r.operation_id.clone(),
            metadata_head: head(&r.operation_id),
            structure_head: head(&r.operation_id),
            sheets: vec![Sheet::new(r.first_sheet_id.clone(), r.operation_id.clone())],
            kind: r.kind,
            pages: vec![],
            page_style: None,
            canvas_style: None,
            waypoint_list: None,
            strokes: vec![],
            text_blocks: vec![],
            text_effects: vec![],
            companion_items: vec![],
            companion_settings: None,
            images: vec![],
            sequence: 1,
            histories: BTreeMap::new(),
        }
    }
    /// `content_bottom` is the lowest content on the auto-height page (see
    /// `content_bottom()`); it is only read when the note has such a page.
    pub fn snapshot(&self, author: &str, cursor: EventCursor, content_bottom: f32) -> NoteSnapshot {
        let history = self.histories.get(author);
        let undo = history.and_then(|h| h.undo.last());
        let redo = history.and_then(|h| h.redo.last());
        NoteSnapshot {
            note_id: self.note_id.clone(),
            title: self.title.clone(),
            notebook: self.notebook.clone(),
            metadata_head: self.metadata_head.clone(),
            structure_head: self.structure_head.clone(),
            sheets: self
                .sheets
                .iter()
                .map(|s| SheetView {
                    id: s.id.clone(),
                    creation_operation_id: s.creation.clone(),
                    origin_x: s.origin_x,
                    origin_y: s.origin_y,
                })
                .collect(),
            strokes: self.strokes.clone(),
            local_sequence: self.sequence,
            cursor,
            undo_target: undo.map(|a| a.operation.clone()),
            redo_target: redo.map(|a| a.operation.clone()),
            undo_dependencies: self.action_dependencies(undo),
            redo_dependencies: self.action_dependencies(redo),
            archived: self.archived,
            text_blocks: self.text_blocks.clone(),
            companion_items: self.companion_items.clone(),
            companion_settings: self.companion_settings.clone(),
            images: self.images.clone(),
            kind: self.kind,
            pages: self.page_views(content_bottom),
            page_style: self.page_style_view(),
            canvas_background: self.canvas_background_view(),
            waypoints: self.waypoints_view(),
        }
    }
    fn action_dependencies(&self, action: Option<&Action>) -> Vec<Id> {
        let Some(action) = action else {
            return vec![];
        };
        // Both inverse directions affect the same <=1024 marks, including tombstones.
        // Read current heads, not the original action heads or History.expected:
        // submission still checks expected heads transactionally in apply().
        let affected: HashSet<&Id> = action
            .after
            .iter()
            .map(|s| &s.id)
            .chain(action.text_after.iter().map(|e| &e.mark_id))
            .chain(action.companion_after.iter().map(|e| &e.id))
            .chain(action.image_after.iter().map(|e| &e.id))
            .chain(action.page_after.iter().map(|e| &e.sheet_id))
            .collect();
        self.strokes
            .iter()
            .filter(|s| affected.contains(&s.id))
            .map(|s| s.head.operation_id.clone())
            .chain(
                self.text_blocks
                    .iter()
                    .filter(|b| affected.contains(&b.id))
                    .map(|b| b.head.operation_id.clone()),
            )
            .chain(
                self.companion_items
                    .iter()
                    .filter(|v| affected.contains(&v.id))
                    .map(|v| v.head.operation_id.clone()),
            )
            .chain(
                self.images
                    .iter()
                    .filter(|v| affected.contains(&v.id))
                    .map(|v| v.head.operation_id.clone()),
            )
            .chain(
                self.sheets
                    .iter()
                    .filter(|s| affected.contains(&s.id))
                    .map(|s| s.page_head().operation_id),
            )
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
    pub fn mark_effects_since(&self, previous: &Self) -> Vec<MarkEffect> {
        self.strokes
            .iter()
            .filter_map(|after| {
                let before = previous.strokes.iter().find(|s| s.id == after.id);
                (before != Some(after)).then(|| MarkEffect {
                    mark_id: after.id.clone(),
                    before: before.cloned(),
                    after: after.clone(),
                })
            })
            .collect()
    }
    /// Fixes every sheet's canvas origin from its current order: sheet i at
    /// (0, i x the old page height), no gap. Run once when a note becomes a canvas
    /// (schema v3) and by a replay of the log; the order can never change afterwards.
    pub fn place_sheets(&mut self, format: Option<LegacyPageFormat>) {
        let height = LegacyPageFormat::page_height(format);
        for (index, sheet) in self.sheets.iter_mut().enumerate() {
            sheet.origin_x = 0.0;
            sheet.origin_y = index as f32 * height;
        }
    }
    /// A new command. Sheets can no longer be added or reordered: the order fixes every
    /// sheet's origin on the canvas.
    pub fn apply(&mut self, c: &Command, author: &str) -> CoreResult<ChangeKind> {
        if matches!(
            c.body,
            CommandBody::AddSheet { .. } | CommandBody::ReorderSheets { .. }
        ) {
            return Err(sheets_fixed());
        }
        self.apply_logged(c, author)
    }
    /// Applies a command exactly as it was admitted when committed, including AddSheet and
    /// ReorderSheets from logs written before the canvas amendment. Used only by replay.
    pub fn apply_logged(&mut self, c: &Command, author: &str) -> CoreResult<ChangeKind> {
        self.text_effects.clear();
        let deps: HashSet<&str> = c.dependencies.iter().map(String::as_str).collect();
        let op = &c.operation_id;
        match &c.body {
            CommandBody::ImageMarks { mutations } => {
                self.apply_images(mutations, op, author, &deps)?;
                Ok(ChangeKind::MarksChanged)
            }
            CommandBody::CompanionMarks { mutations } => {
                self.apply_companion(mutations, op, author, &deps)?;
                Ok(ChangeKind::MarksChanged)
            }
            CommandBody::SetCompanionSettings { settings, base } => {
                let initial = head(&self.creation);
                let current = self
                    .companion_settings
                    .as_ref()
                    .map(|v| &v.head)
                    .unwrap_or(&initial);
                check_base(base, current, &deps)?;
                self.companion_settings = Some(CompanionSettingsView {
                    head: head(op),
                    settings: settings.clone(),
                });
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::Marks { mutations } => {
                self.apply_marks(mutations, op, author, &deps)?;
                Ok(ChangeKind::MarksChanged)
            }
            CommandBody::SetPageStyle {
                sheet_id,
                template,
                paper,
                pattern_colour,
                spacing,
                base,
            } => {
                let style = PageStyle {
                    template: *template,
                    paper: *paper,
                    pattern_colour: *pattern_colour,
                    spacing: *spacing,
                };
                self.set_page_style(sheet_id, style, base, op, &deps)?;
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::SetCanvasBackground {
                pattern,
                pattern_colour,
                spacing,
                paper,
                base,
            } => {
                let value = CanvasStyle {
                    pattern: *pattern,
                    pattern_colour: *pattern_colour,
                    spacing: *spacing,
                    paper: *paper,
                };
                self.set_canvas_background(value, base, op, &deps)?;
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::SetWaypoints { waypoints, base } => {
                self.set_waypoints(waypoints, base, op, &deps)?;
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::AddSheet { sheet_id, base } => {
                check_base(base, &self.structure_head, &deps)?;
                if self.sheets.iter().any(|s| s.id == *sheet_id) {
                    return Err(collision());
                }
                // Origins are placed after replay; a canvas note never reaches this.
                self.sheets.push(Sheet::new(sheet_id.clone(), op.clone()));
                self.structure_head = head(op);
                Ok(ChangeKind::StructureChanged)
            }
            CommandBody::ReorderSheets { sheet_ids, base } => {
                check_base(base, &self.structure_head, &deps)?;
                if sheet_ids.len() != self.sheets.len()
                    || sheet_ids
                        .iter()
                        .any(|id| !self.sheets.iter().any(|s| s.id == *id))
                {
                    return Err(invalid("Sheet order must be an exact permutation"));
                }
                let old = std::mem::take(&mut self.sheets);
                self.sheets = sheet_ids
                    .iter()
                    .map(|id| {
                        old.iter()
                            .find(|s| s.id == *id)
                            .expect("permutation checked")
                            .clone()
                    })
                    .collect();
                self.structure_head = head(op);
                Ok(ChangeKind::StructureChanged)
            }
            CommandBody::RenameNote { title, base } => {
                self.set_metadata(base, op, &deps, |s| s.title = title.clone())
            }
            CommandBody::SetNotebook { notebook, base } => {
                self.set_metadata(base, op, &deps, |s| s.notebook = notebook.clone())
            }
            CommandBody::SetArchived { archived, base } => {
                self.set_metadata(base, op, &deps, |s| s.archived = *archived)
            }
            CommandBody::Undo {
                target_operation_id,
            } => self.apply_history(target_operation_id, false, op, author, &deps),
            CommandBody::Redo {
                target_operation_id,
            } => self.apply_history(target_operation_id, true, op, author, &deps),
        }
    }

    /// Applies one gesture as a unit and pushes it onto the author's undo stack. The
    /// caller discards the whole state on error, so a failing mutation leaves nothing.
    fn apply_marks(
        &mut self,
        mutations: &[MarkMutation],
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let mut before = Vec::with_capacity(mutations.len());
        let mut after = Vec::with_capacity(mutations.len());
        let mut text_before = Vec::new();
        let mut text_after = Vec::new();
        let mut page_before = Vec::new();
        let mut page_after = Vec::new();
        for m in mutations {
            let (old, new) = match m {
                MarkMutation::AddStroke {
                    mark_id,
                    sheet_id,
                    brush,
                    ..
                } => self.add_stroke(mark_id, sheet_id, brush, op, author, deps)?,
                MarkMutation::DeleteStroke { mark_id, base } => {
                    self.edit_stroke(mark_id, base, op, deps, |s| s.tombstone = true)?
                }
                MarkMutation::RestoreStroke { mark_id, base } => {
                    self.edit_stroke(mark_id, base, op, deps, |s| s.tombstone = false)?
                }
                MarkMutation::TransformStroke {
                    mark_id,
                    base,
                    transform,
                } => {
                    self.edit_stroke(mark_id, base, op, deps, |s| s.transform = transform.clone())?
                }
                MarkMutation::RestyleStroke {
                    mark_id,
                    base,
                    brush,
                } => self.edit_stroke(mark_id, base, op, deps, |s| s.brush = brush.clone())?,
                MarkMutation::AddPage { .. }
                | MarkMutation::ResizePage { .. }
                | MarkMutation::MovePage { .. }
                | MarkMutation::DeletePage { .. }
                | MarkMutation::RestorePage { .. } => {
                    let (old, new) = self.apply_page_mutation(m, op, deps)?;
                    page_before.push(old);
                    page_after.push(new);
                    continue;
                }
                _ => {
                    let (old, new) = self.apply_text_mutation(m, op, author, deps)?;
                    text_before.push(old);
                    text_after.push(new);
                    continue;
                }
            };
            before.push(old);
            after.push(new);
        }
        self.check_document_text()?;
        let history = self.histories.entry(author.into()).or_default();
        for edit in &text_after {
            history.expected.insert(edit.mark_id.clone(), op.clone());
        }
        for s in &after {
            history.expected.insert(s.id.clone(), op.clone());
        }
        for e in &page_after {
            history.expected.insert(e.sheet_id.clone(), op.clone());
        }
        history.undo.push(Action {
            operation: op.clone(),
            before,
            after,
            text_before,
            text_after,
            companion_before: vec![],
            companion_after: vec![],
            image_before: vec![],
            image_after: vec![],
            page_before,
            page_after,
        });
        history.redo.clear();
        Ok(())
    }

    /// Adds a stroke; returns (inverse, added). The inverse of an add is its tombstone.
    fn add_stroke(
        &mut self,
        mark_id: &Id,
        sheet_id: &Id,
        brush: &Brush,
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<(StrokeView, StrokeView)> {
        if self.strokes.iter().any(|s| s.id == *mark_id)
            || self.text_blocks.iter().any(|b| b.id == *mark_id)
            || self.companion_items.iter().any(|v| v.id == *mark_id)
            || self.images.iter().any(|v| v.id == *mark_id)
        {
            return Err(collision());
        }
        let sheet = self
            .sheets
            .iter()
            .find(|s| s.id == *sheet_id)
            .ok_or_else(missing)?;
        require(deps, &sheet.creation)?;
        let stroke = StrokeView {
            id: mark_id.clone(),
            sheet_id: sheet_id.clone(),
            author_id: author.into(),
            head: head(op),
            transform: IDENTITY,
            brush: brush.clone(),
            tombstone: false,
        };
        let inverse = StrokeView {
            tombstone: true,
            ..stroke.clone()
        };
        self.strokes.push(stroke.clone());
        Ok((inverse, stroke))
    }

    /// Edits an existing stroke whose head matches `base`; returns (before, after).
    fn edit_stroke(
        &mut self,
        mark_id: &Id,
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
        edit: impl FnOnce(&mut StrokeView),
    ) -> CoreResult<(StrokeView, StrokeView)> {
        let stroke = self
            .strokes
            .iter_mut()
            .find(|s| s.id == *mark_id)
            .ok_or_else(missing)?;
        check_base(base, &stroke.head, deps)?;
        let before = stroke.clone();
        edit(stroke);
        stroke.head = head(op);
        Ok((before, stroke.clone()))
    }

    /// Title, notebook and archive share the metadata head.
    fn set_metadata(
        &mut self,
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
        set: impl FnOnce(&mut Self),
    ) -> CoreResult<ChangeKind> {
        check_base(base, &self.metadata_head, deps)?;
        set(self);
        self.metadata_head = head(op);
        Ok(ChangeKind::MetadataChanged)
    }

    /// Undo or redo the author's top action. Every affected stroke must still carry the
    /// head this history last gave it; the whole inverse is checked before any of it is
    /// installed.
    fn apply_history(
        &mut self,
        target: &Id,
        redo: bool,
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<ChangeKind> {
        let action = self
            .histories
            .get(author)
            .and_then(|h| if redo { h.redo.last() } else { h.undo.last() })
            .ok_or_else(stale)?;
        let companion_desired = if redo {
            action.companion_after.clone()
        } else {
            action.companion_before.clone()
        };
        self.check_companion_history(&companion_desired, author, deps)?;
        let image_desired = if redo {
            action.image_after.clone()
        } else {
            action.image_before.clone()
        };
        self.check_image_history(&image_desired, author, deps)?;
        let page_desired = if redo {
            action.page_after.clone()
        } else {
            action.page_before.clone()
        };
        self.check_page_history(&page_desired, author, deps)?;
        let history = self.histories.get_mut(author).ok_or_else(stale)?;
        let stack = if redo {
            &mut history.redo
        } else {
            &mut history.undo
        };
        let action = stack.last().ok_or_else(stale)?;
        if action.operation != *target {
            return Err(stale());
        }
        let desired = if redo { &action.after } else { &action.before };
        for s in desired {
            let current = self
                .strokes
                .iter()
                .find(|v| v.id == s.id)
                .ok_or_else(stale)?;
            if history.expected.get(&s.id) != Some(&current.head.operation_id) {
                return Err(stale());
            }
            require(deps, &current.head.operation_id)?;
        }
        let text_desired = if redo {
            &action.text_after
        } else {
            &action.text_before
        };
        for edit in text_desired {
            let current = self
                .text_blocks
                .iter()
                .find(|b| b.id == edit.mark_id)
                .ok_or_else(stale)?;
            if history.expected.get(&edit.mark_id) != Some(&current.head.operation_id) {
                return Err(stale());
            }
            require(deps, &current.head.operation_id)?;
        }
        let text_desired = text_desired.clone();
        for s in desired {
            let current = self
                .strokes
                .iter_mut()
                .find(|v| v.id == s.id)
                .ok_or_else(stale)?;
            *current = s.clone();
            current.head = head(op);
            history.expected.insert(s.id.clone(), op.clone());
        }
        let action = stack.pop().ok_or_else(stale)?;
        if redo {
            history.undo.push(action);
        } else {
            history.redo.push(action);
        }
        for edit in &text_desired {
            history.expected.insert(edit.mark_id.clone(), op.clone());
        }
        for value in companion_desired {
            let current = self
                .companion_items
                .iter_mut()
                .find(|v| v.id == value.id)
                .ok_or_else(stale)?;
            *current = value;
            current.head = head(op);
            history.expected.insert(current.id.clone(), op.clone());
        }
        for mut value in image_desired {
            let current = self
                .images
                .iter_mut()
                .find(|v| v.id == value.id)
                .ok_or_else(stale)?;
            value.seen_by_companion |= current.seen_by_companion;
            *current = value;
            current.head = head(op);
            history.expected.insert(current.id.clone(), op.clone());
        }
        for edit in &page_desired {
            history.expected.insert(edit.sheet_id.clone(), op.clone());
            install_page_edit(&mut self.sheets, &mut self.pages, edit, op)?;
        }
        for edit in &text_desired {
            self.apply_text_edit(edit, op)?;
        }
        self.check_document_text()?;
        Ok(ChangeKind::HistoryChanged)
    }
}
pub(crate) fn sheets_fixed() -> CoreError {
    invalid("A note is one canvas: sheets can no longer be added or reordered")
}
pub(crate) fn require(deps: &HashSet<&str>, operation: &str) -> CoreResult<()> {
    if !deps.contains(operation) {
        return Err(dependency());
    }
    Ok(())
}
pub(crate) fn check_base(
    base: &Base,
    current: &EntityHead,
    deps: &HashSet<&str>,
) -> CoreResult<()> {
    match base {
        Base::Operation { operation_id } if *operation_id == current.operation_id => {
            require(deps, operation_id)
        }
        _ => Err(stale()),
    }
}
