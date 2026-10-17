//! In-memory candidate projection; it is installed only by the same transaction as the log.
use crate::{
    error::{collision, dependency, missing, stale},
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
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Action {
    pub operation: Id,
    pub before: Vec<StrokeView>,
    pub after: Vec<StrokeView>,
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
    pub strokes: Vec<StrokeView>,
    pub sequence: u64,
    pub histories: BTreeMap<Id, History>,
}
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
            sheets: vec![Sheet {
                id: r.first_sheet_id.clone(),
                creation: r.operation_id.clone(),
                origin_x: 0.0,
                origin_y: 0.0,
            }],
            strokes: vec![],
            sequence: 1,
            histories: BTreeMap::new(),
        }
    }
    pub fn snapshot(&self, author: &str, cursor: EventCursor) -> NoteSnapshot {
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
        }
    }
    fn action_dependencies(&self, action: Option<&Action>) -> Vec<Id> {
        let Some(action) = action else {
            return vec![];
        };
        // Both inverse directions affect the same <=1024 marks, including tombstones.
        // Read current heads, not the original action heads or History.expected:
        // submission still checks expected heads transactionally in apply().
        let affected: HashSet<&Id> = action.after.iter().map(|s| &s.id).collect();
        self.strokes
            .iter()
            .filter(|s| affected.contains(&s.id))
            .map(|s| s.head.operation_id.clone())
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
        let deps: HashSet<&str> = c.dependencies.iter().map(String::as_str).collect();
        match &c.body {
            CommandBody::Marks { mutations } => {
                let mut before = Vec::with_capacity(mutations.len());
                let mut after = Vec::with_capacity(mutations.len());
                for m in mutations {
                    match m {
                        MarkMutation::AddStroke {
                            mark_id,
                            sheet_id,
                            brush,
                            ..
                        } => {
                            if self.strokes.iter().any(|s| s.id == *mark_id) {
                                return Err(collision());
                            }
                            let sheet = self
                                .sheets
                                .iter()
                                .find(|s| s.id == *sheet_id)
                                .ok_or_else(missing)?;
                            require(&deps, &sheet.creation)?;
                            let stroke = StrokeView {
                                id: mark_id.clone(),
                                sheet_id: sheet_id.clone(),
                                author_id: author.into(),
                                head: head(&c.operation_id),
                                transform: Affine {
                                    a: 1.0,
                                    b: 0.0,
                                    c: 0.0,
                                    d: 1.0,
                                    tx: 0.0,
                                    ty: 0.0,
                                },
                                brush: brush.clone(),
                                tombstone: false,
                            };
                            let mut inverse = stroke.clone();
                            inverse.tombstone = true;
                            before.push(inverse);
                            after.push(stroke.clone());
                            self.strokes.push(stroke);
                        }
                        _ => {
                            let (id, base) = match m {
                                MarkMutation::DeleteStroke { mark_id, base }
                                | MarkMutation::RestoreStroke { mark_id, base }
                                | MarkMutation::TransformStroke { mark_id, base, .. }
                                | MarkMutation::RestyleStroke { mark_id, base, .. } => {
                                    (mark_id, base)
                                }
                                _ => unreachable!(),
                            };
                            let stroke = self
                                .strokes
                                .iter_mut()
                                .find(|s| s.id == *id)
                                .ok_or_else(missing)?;
                            check_base(base, &stroke.head, &deps)?;
                            before.push(stroke.clone());
                            match m {
                                MarkMutation::DeleteStroke { .. } => stroke.tombstone = true,
                                MarkMutation::RestoreStroke { .. } => stroke.tombstone = false,
                                MarkMutation::TransformStroke { transform, .. } => {
                                    stroke.transform = transform.clone()
                                }
                                MarkMutation::RestyleStroke { brush, .. } => {
                                    stroke.brush = brush.clone()
                                }
                                _ => unreachable!(),
                            }
                            stroke.head = head(&c.operation_id);
                            after.push(stroke.clone());
                        }
                    }
                }
                let history = self.histories.entry(author.into()).or_default();
                for s in &after {
                    history
                        .expected
                        .insert(s.id.clone(), c.operation_id.clone());
                }
                history.undo.push(Action {
                    operation: c.operation_id.clone(),
                    before,
                    after,
                });
                history.redo.clear();
                Ok(ChangeKind::MarksChanged)
            }
            CommandBody::AddSheet { sheet_id, base } => {
                check_base(base, &self.structure_head, &deps)?;
                if self.sheets.iter().any(|s| s.id == *sheet_id) {
                    return Err(collision());
                }
                // Origins are placed after replay; a canvas note never reaches this.
                self.sheets.push(Sheet {
                    id: sheet_id.clone(),
                    creation: c.operation_id.clone(),
                    origin_x: 0.0,
                    origin_y: 0.0,
                });
                self.structure_head = head(&c.operation_id);
                Ok(ChangeKind::StructureChanged)
            }
            CommandBody::ReorderSheets { sheet_ids, base } => {
                check_base(base, &self.structure_head, &deps)?;
                if sheet_ids.len() != self.sheets.len()
                    || sheet_ids
                        .iter()
                        .any(|id| !self.sheets.iter().any(|s| s.id == *id))
                {
                    return Err(crate::error::invalid(
                        "Sheet order must be an exact permutation",
                    ));
                }
                let old = self.sheets.clone();
                self.sheets = sheet_ids
                    .iter()
                    .map(|id| {
                        old.iter()
                            .find(|s| s.id == *id)
                            .expect("permutation checked")
                            .clone()
                    })
                    .collect();
                self.structure_head = head(&c.operation_id);
                Ok(ChangeKind::StructureChanged)
            }
            CommandBody::RenameNote { title, base } => {
                check_base(base, &self.metadata_head, &deps)?;
                self.title = title.clone();
                self.metadata_head = head(&c.operation_id);
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::SetNotebook { notebook, base } => {
                check_base(base, &self.metadata_head, &deps)?;
                self.notebook = notebook.clone();
                self.metadata_head = head(&c.operation_id);
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::SetArchived { archived, base } => {
                check_base(base, &self.metadata_head, &deps)?;
                self.archived = *archived;
                self.metadata_head = head(&c.operation_id);
                Ok(ChangeKind::MetadataChanged)
            }
            CommandBody::Undo {
                target_operation_id,
            }
            | CommandBody::Redo {
                target_operation_id,
            } => {
                let redo = matches!(c.body, CommandBody::Redo { .. });
                let history = self.histories.get_mut(author).ok_or_else(stale)?;
                let stack = if redo {
                    &mut history.redo
                } else {
                    &mut history.undo
                };
                let action = stack.last().ok_or_else(stale)?;
                if action.operation != *target_operation_id {
                    return Err(stale());
                }
                let desired = if redo { &action.after } else { &action.before };
                // Check the entire inverse batch before installing any of it.
                for s in desired {
                    let current = self
                        .strokes
                        .iter()
                        .find(|v| v.id == s.id)
                        .ok_or_else(stale)?;
                    if history.expected.get(&s.id) != Some(&current.head.operation_id) {
                        return Err(stale());
                    }
                    require(&deps, &current.head.operation_id)?;
                }
                for s in desired {
                    let current = self
                        .strokes
                        .iter_mut()
                        .find(|v| v.id == s.id)
                        .ok_or_else(stale)?;
                    *current = s.clone();
                    current.head = head(&c.operation_id);
                    history
                        .expected
                        .insert(s.id.clone(), c.operation_id.clone());
                }
                let action = stack.pop().ok_or_else(stale)?;
                if redo {
                    history.undo.push(action);
                } else {
                    history.redo.push(action);
                }
                Ok(ChangeKind::HistoryChanged)
            }
        }
    }
}
pub(crate) fn sheets_fixed() -> CoreError {
    crate::error::invalid("A note is one canvas: sheets can no longer be added or reordered")
}
fn require(deps: &HashSet<&str>, operation: &str) -> CoreResult<()> {
    if !deps.contains(operation) {
        return Err(dependency());
    }
    Ok(())
}
fn check_base(base: &Base, current: &EntityHead, deps: &HashSet<&str>) -> CoreResult<()> {
    match base {
        Base::Operation { operation_id } if *operation_id == current.operation_id => {
            require(deps, operation_id)
        }
        _ => Err(stale()),
    }
}
