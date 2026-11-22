//! Reducer side of notes and canvases v1: pages as sized sheets (K2), the canvas background and
//! waypoints (K3), and the read-time presentation of a note written before pages (K4).
use crate::{
    error::{collision, invalid, missing, stale},
    reducer::{Sheet, State, check_base, head, require},
    *,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

/// A value with its own head, for state a command bases on without touching another head.
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Headed<T> {
    pub head: EntityHead,
    pub value: T,
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct CanvasStyle {
    pub pattern: PagePattern,
    pub pattern_colour: Option<Rgba>,
    pub spacing: Option<f32>,
    pub paper: Option<Rgba>,
}
/// A page as one undo step restores it: size, state and place in the order. `after` is the
/// page before it (None = first).
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct PageEdit {
    pub sheet_id: Id,
    pub width: f32,
    pub height: f32,
    pub auto_height: bool,
    pub tombstone: bool,
    pub after: Option<Id>,
}

pub(crate) fn side(value: f32) -> CoreResult<()> {
    if !value.is_finite() || !(PAGE_MIN_SIDE..=PAGE_MAX_SIDE).contains(&value) {
        return Err(invalid("Each page side must be between 10 and 2000 mm"));
    }
    Ok(())
}
pub(crate) fn style_parts(spacing: Option<f32>) -> CoreResult<()> {
    if spacing.is_some_and(|s| !s.is_finite()) {
        return Err(invalid("Pattern spacing must be finite"));
    }
    Ok(())
}
pub(crate) fn waypoints(list: &[Waypoint]) -> CoreResult<()> {
    if list.len() > MAX_WAYPOINTS {
        return Err(invalid("A canvas keeps at most 100 waypoints"));
    }
    for w in list {
        let chars = w.name.chars().count();
        if chars == 0 || chars > MAX_WAYPOINT_NAME_CHARS || w.name.chars().any(char::is_control) {
            return Err(invalid("A waypoint name is 1 to 48 characters"));
        }
        if ![w.cx, w.cy, w.zoom].iter().all(|v| v.is_finite()) || w.zoom <= 0.0 {
            return Err(invalid(
                "A waypoint needs a finite centre and a positive zoom",
            ));
        }
    }
    Ok(())
}
/// A later waypoint whose name matches an earlier one, ignoring case, replaces it in place.
fn merge_waypoints(list: &[Waypoint]) -> Vec<Waypoint> {
    let mut out: Vec<Waypoint> = Vec::with_capacity(list.len());
    for w in list {
        match out
            .iter_mut()
            .find(|o| o.name.to_lowercase() == w.name.to_lowercase())
        {
            Some(existing) => *existing = w.clone(),
            None => out.push(w.clone()),
        }
    }
    out
}

impl Sheet {
    pub(crate) fn page_head(&self) -> EntityHead {
        self.page_head
            .clone()
            .unwrap_or_else(|| head(&self.creation))
    }
}

impl State {
    pub(crate) fn is_canvas(&self) -> bool {
        self.kind == NoteKind::Canvas
    }
    /// The note's pages, tombstoned included. Before the first page command a note has one
    /// virtual page: its first sheet (K4).
    fn page_ids(&self) -> Vec<Id> {
        if self.is_canvas() {
            vec![]
        } else if self.pages.is_empty() {
            self.sheets
                .first()
                .map(|s| s.id.clone())
                .into_iter()
                .collect()
        } else {
            self.pages.clone()
        }
    }
    /// The first page command writes the page list. Page 1 keeps `auto_height`.
    fn ensure_pages(&mut self) {
        if self.pages.is_empty()
            && let Some(first) = self.sheets.first_mut()
        {
            first.page_width = LEGACY_PAGE_WIDTH;
            first.page_height = LEGACY_PAGE_MIN_HEIGHT;
            first.auto_height = true;
            self.pages = vec![first.id.clone()];
        }
    }
    fn require_note(&self) -> CoreResult<()> {
        if self.is_canvas() {
            return Err(invalid("A canvas has no pages"));
        }
        Ok(())
    }
    fn page_edit(&self, id: &str) -> Option<PageEdit> {
        let at = self.pages.iter().position(|p| p == id)?;
        let s = self.sheets.iter().find(|s| s.id == id)?;
        Some(PageEdit {
            sheet_id: id.into(),
            width: s.page_width,
            height: s.page_height,
            auto_height: s.auto_height,
            tombstone: s.tombstone,
            after: at.checked_sub(1).map(|i| self.pages[i].clone()),
        })
    }
    fn live_pages(&self) -> usize {
        self.pages
            .iter()
            .filter(|p| self.sheets.iter().any(|s| s.id == **p && !s.tombstone))
            .count()
    }
    fn page_sheet(&mut self, id: &str) -> CoreResult<&mut Sheet> {
        if !self.pages.iter().any(|p| p == id) {
            return Err(missing());
        }
        self.sheets
            .iter_mut()
            .find(|s| s.id == id)
            .ok_or_else(missing)
    }

    /// Applies one page mutation; returns (before, after) for the undo stack. The inverse of
    /// an add is its tombstone, as for strokes.
    pub(crate) fn apply_page_mutation(
        &mut self,
        m: &MarkMutation,
        op: &Id,
        deps: &HashSet<&str>,
    ) -> CoreResult<(PageEdit, PageEdit)> {
        self.require_note()?;
        self.ensure_pages();
        match m {
            MarkMutation::AddPage {
                sheet_id,
                after,
                width,
                height,
            } => {
                side(*width)?;
                side(*height)?;
                if self.sheets.iter().any(|s| s.id == *sheet_id)
                    || self.strokes.iter().any(|s| s.id == *sheet_id)
                {
                    return Err(collision());
                }
                if self.pages.len() >= MAX_PAGES {
                    return Err(error::limit("A note keeps at most 2000 pages"));
                }
                let at = match after {
                    None => 0,
                    Some(prev) => {
                        let at = self
                            .pages
                            .iter()
                            .position(|p| p == prev)
                            .ok_or_else(missing)?;
                        let creation = &self
                            .sheets
                            .iter()
                            .find(|s| s.id == *prev)
                            .ok_or_else(missing)?
                            .creation;
                        require(deps, creation)?;
                        at + 1
                    }
                };
                let mut sheet = Sheet::new(sheet_id.clone(), op.clone());
                sheet.page_width = *width;
                sheet.page_height = *height;
                self.sheets.push(sheet);
                self.pages.insert(at, sheet_id.clone());
                let new = self.page_edit(sheet_id).ok_or_else(missing)?;
                Ok((
                    PageEdit {
                        tombstone: true,
                        ..new.clone()
                    },
                    new,
                ))
            }
            MarkMutation::ResizePage {
                sheet_id,
                base,
                width,
                height,
            } => {
                side(*width)?;
                side(*height)?;
                self.edit_page(sheet_id, base, op, deps, |s| {
                    s.page_width = *width;
                    s.page_height = *height;
                    s.auto_height = false;
                })
            }
            MarkMutation::MovePage {
                sheet_id,
                base,
                after,
            } => {
                if after.as_ref() == Some(sheet_id) {
                    return Err(invalid("A page cannot be moved after itself"));
                }
                if let Some(prev) = after
                    && !self.pages.contains(prev)
                {
                    return Err(missing());
                }
                let result = self.edit_page(sheet_id, base, op, deps, |_| {})?;
                let mut edit = result.1.clone();
                edit.after = after.clone();
                place(&mut self.pages, &edit);
                Ok((result.0, self.page_edit(sheet_id).ok_or_else(missing)?))
            }
            MarkMutation::DeletePage { sheet_id, base } => {
                let live = self.live_pages();
                let here = self
                    .sheets
                    .iter()
                    .any(|s| s.id == *sheet_id && !s.tombstone);
                if here && live <= 1 {
                    return Err(invalid("A note must keep at least one page."));
                }
                self.edit_page(sheet_id, base, op, deps, |s| s.tombstone = true)
            }
            MarkMutation::RestorePage { sheet_id, base } => {
                self.edit_page(sheet_id, base, op, deps, |s| s.tombstone = false)
            }
            _ => Err(invalid("Not a page mutation")),
        }
    }

    fn edit_page(
        &mut self,
        id: &Id,
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
        edit: impl FnOnce(&mut Sheet),
    ) -> CoreResult<(PageEdit, PageEdit)> {
        let before = self.page_edit(id).ok_or_else(missing)?;
        let sheet = self.page_sheet(id)?;
        check_base(base, &sheet.page_head(), deps)?;
        edit(sheet);
        sheet.page_head = Some(head(op));
        Ok((before, self.page_edit(id).ok_or_else(missing)?))
    }

    /// Undo or redo of a page edit: the page's own fields and its place.
    pub(crate) fn check_page_history(
        &self,
        edits: &[PageEdit],
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let h = self.histories.get(author).ok_or_else(stale)?;
        for e in edits {
            let sheet = self
                .sheets
                .iter()
                .find(|s| s.id == e.sheet_id)
                .ok_or_else(stale)?;
            let current = sheet.page_head();
            if h.expected.get(&e.sheet_id) != Some(&current.operation_id) {
                return Err(stale());
            }
            require(deps, &current.operation_id)?;
        }
        Ok(())
    }
}

/// Moves a page in the order to just after `edit.after` (None = first).
fn place(pages: &mut Vec<Id>, edit: &PageEdit) {
    if let Some(at) = pages.iter().position(|p| *p == edit.sheet_id) {
        pages.remove(at);
    }
    let at = match &edit.after {
        Some(prev) => pages
            .iter()
            .position(|p| p == prev)
            .map_or(pages.len(), |i| i + 1),
        None => 0,
    };
    pages.insert(at, edit.sheet_id.clone());
}

/// Installs a page edit from the undo stack (head `op`), as the reducer does for strokes.
pub(crate) fn install_page_edit(
    sheets: &mut [Sheet],
    pages: &mut Vec<Id>,
    edit: &PageEdit,
    op: &Id,
) -> CoreResult<()> {
    let sheet = sheets
        .iter_mut()
        .find(|s| s.id == edit.sheet_id)
        .ok_or_else(stale)?;
    sheet.page_width = edit.width;
    sheet.page_height = edit.height;
    sheet.auto_height = edit.auto_height;
    sheet.tombstone = edit.tombstone;
    sheet.page_head = Some(head(op));
    place(pages, edit);
    Ok(())
}

impl State {
    pub(crate) fn set_page_style(
        &mut self,
        sheet_id: &Option<Id>,
        style: PageStyle,
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        self.require_note()?;
        style_parts(style.spacing)?;
        match sheet_id {
            None => {
                let initial = head(&self.creation);
                let current = self.page_style.as_ref().map_or(&initial, |h| &h.head);
                check_base(base, current, deps)?;
                self.page_style = Some(Headed {
                    head: head(op),
                    value: style,
                });
            }
            Some(id) => {
                self.ensure_pages();
                let sheet = self.page_sheet(id)?;
                let initial = head(&sheet.creation);
                let current = sheet.style.as_ref().map_or(&initial, |h| &h.head);
                check_base(base, current, deps)?;
                sheet.style = Some(Headed {
                    head: head(op),
                    value: style,
                });
            }
        }
        Ok(())
    }

    pub(crate) fn set_canvas_background(
        &mut self,
        value: CanvasStyle,
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        if !self.is_canvas() {
            return Err(invalid("Only a canvas has a background"));
        }
        style_parts(value.spacing)?;
        let initial = head(&self.creation);
        let current = self.canvas_style.as_ref().map_or(&initial, |h| &h.head);
        check_base(base, current, deps)?;
        self.canvas_style = Some(Headed {
            head: head(op),
            value,
        });
        Ok(())
    }

    pub(crate) fn set_waypoints(
        &mut self,
        list: &[Waypoint],
        base: &Base,
        op: &Id,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        if !self.is_canvas() {
            return Err(invalid("Only a canvas has waypoints"));
        }
        waypoints(list)?;
        let initial = head(&self.creation);
        let current = self.waypoint_list.as_ref().map_or(&initial, |h| &h.head);
        check_base(base, current, deps)?;
        self.waypoint_list = Some(Headed {
            head: head(op),
            value: merge_waypoints(list),
        });
        Ok(())
    }

    // ---- read-time presentation (K4) -----------------------------------------------------

    /// Whether the snapshot needs `content_bottom`: the note's page 1 is the auto-height page.
    pub(crate) fn has_auto_page(&self) -> bool {
        !self.is_canvas()
            && self
                .sheets
                .first()
                .is_some_and(|s| self.pages.is_empty() || (s.auto_height && !s.tombstone))
    }
    /// Sheets drawn inside the auto-height page: its own and every sheet that is not a page
    /// (the pre-canvas sheets, at their existing origins).
    fn auto_sheets(&self) -> HashSet<&str> {
        self.sheets
            .iter()
            .enumerate()
            .filter(|(i, s)| *i == 0 || (!self.pages.is_empty() && !self.pages.contains(&s.id)))
            .map(|(_, s)| s.id.as_str())
            .collect()
    }
    /// The lowest live ink, text or picture bottom on the auto-height page, in page units.
    /// `bounds` gives a stroke's sample bounds `[min_x, min_y, max_x, max_y]`, sheet-local.
    /// Text height is an estimate (see README): the core cannot measure type.
    pub(crate) fn content_bottom(&self, bounds: &mut dyn FnMut(&str) -> Option<[f32; 4]>) -> f32 {
        let sheets = self.auto_sheets();
        let origin = |id: &str| {
            self.sheets
                .iter()
                .find(|s| s.id == id)
                .map_or(0.0, |s| s.origin_y)
        };
        let mut bottom = 0.0f32;
        for s in self
            .strokes
            .iter()
            .filter(|s| !s.tombstone && sheets.contains(s.sheet_id.as_str()))
        {
            let Some([x0, y0, x1, y1]) = bounds(&s.id) else {
                continue;
            };
            let t = &s.transform;
            let width = match s.brush.size {
                BrushSize::Small => 2.0,
                BrushSize::Medium => 4.0,
                BrushSize::Large => 8.0,
            } * if s.brush.family == BrushFamily::Highlighter {
                5.0
            } else {
                1.0
            };
            let radius = width / 2.0 * (t.b.hypot(t.d) as f32);
            for (x, y) in [(x0, y0), (x1, y0), (x0, y1), (x1, y1)] {
                let y = t.b * f64::from(x) + t.d * f64::from(y) + t.ty;
                bottom = bottom.max(y as f32 + radius + origin(&s.sheet_id));
            }
        }
        for b in self
            .text_blocks
            .iter()
            .filter(|b| !b.tombstone && sheets.contains(b.sheet_id.as_str()))
        {
            bottom = bottom.max(text_bottom(b) + origin(&b.sheet_id));
        }
        for i in self.images.iter().filter(|i| !i.tombstone) {
            if let ImagePlacement::Canvas {
                sheet_id,
                y,
                height,
                ..
            } = &i.placement
                && sheets.contains(sheet_id.as_str())
            {
                bottom = bottom.max((y + height) as f32 + origin(sheet_id));
            }
        }
        bottom
    }

    pub(crate) fn page_views(&self, content_bottom: f32) -> Vec<PageView> {
        let virtual_page = self.pages.is_empty();
        self.page_ids()
            .iter()
            .filter_map(|id| self.sheets.iter().find(|s| s.id == *id))
            .map(|s| {
                let auto = virtual_page || s.auto_height;
                let style = s.style.as_ref().map(|h| h.value);
                PageView {
                    sheet_id: s.id.clone(),
                    width: if virtual_page {
                        LEGACY_PAGE_WIDTH
                    } else {
                        s.page_width
                    },
                    height: if auto {
                        LEGACY_PAGE_MIN_HEIGHT.max(content_bottom + LEGACY_PAGE_PADDING)
                    } else {
                        s.page_height
                    },
                    auto_height: auto,
                    tombstone: s.tombstone,
                    head: s.page_head(),
                    style,
                    style_head: s
                        .style
                        .as_ref()
                        .map_or_else(|| head(&s.creation), |h| h.head.clone()),
                    resolved_style: resolve_page_style(
                        self.page_style.as_ref().map(|h| h.value),
                        style,
                    ),
                }
            })
            .collect()
    }
    pub(crate) fn page_style_view(&self) -> Option<PageStyleView> {
        if self.is_canvas() {
            return None;
        }
        let style = self.page_style.as_ref().map(|h| h.value);
        Some(PageStyleView {
            style,
            resolved: resolve_page_style(style, None),
            head: self
                .page_style
                .as_ref()
                .map_or_else(|| head(&self.creation), |h| h.head.clone()),
        })
    }
    pub(crate) fn canvas_background_view(&self) -> Option<CanvasBackgroundView> {
        if !self.is_canvas() {
            return None;
        }
        Some(match &self.canvas_style {
            Some(h) => pages_types::canvas_background(
                Some(h.value.pattern),
                h.value.pattern_colour,
                h.value.spacing,
                h.value.paper,
                h.head.clone(),
            ),
            None => pages_types::canvas_background(None, None, None, None, head(&self.creation)),
        })
    }
    pub(crate) fn waypoints_view(&self) -> Option<WaypointsView> {
        if !self.is_canvas() {
            return None;
        }
        Some(WaypointsView {
            items: self
                .waypoint_list
                .as_ref()
                .map_or_else(Vec::new, |h| h.value.clone()),
            head: self
                .waypoint_list
                .as_ref()
                .map_or_else(|| head(&self.creation), |h| h.head.clone()),
        })
    }
}

/// Text bottom estimate: `font` units by size, line height 1.4 font, 0.55 font per character.
fn text_bottom(b: &TextBlockView) -> f32 {
    let font = match b.style.size {
        BrushSize::Small => 36.0,
        BrushSize::Medium => 48.0,
        BrushSize::Large => 72.0,
    };
    let per_line = ((b.width / (font * 0.55)).floor() as usize).max(1);
    let lines: usize = b
        .text
        .split('\n')
        .map(|l| l.chars().count().div_ceil(per_line).max(1))
        .sum();
    let height = lines as f32 * font * 1.4;
    let t = &b.transform;
    let y = t.b * f64::from(b.origin_x) + t.d * f64::from(b.origin_y + height) + t.ty;
    y as f32
}
