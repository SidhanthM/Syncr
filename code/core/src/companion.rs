//! Additive companion entities. Text remains owned by the text reducer; anchors follow
//! its single splice path. Entity history is isolated by the submitter's author ID.
use crate::{
    error::{collision, invalid, limit, missing, stale},
    reducer::{Action, State, check_base, head, require},
    *,
};
use std::collections::HashSet;

fn bounded(value: &str, max: usize, empty: bool) -> CoreResult<()> {
    if value.len() > max {
        return Err(limit("Companion text exceeds limit"));
    }
    if value.contains('\0') || (!empty && value.trim().is_empty()) {
        return Err(invalid("Invalid companion text"));
    }
    Ok(())
}
fn finite(values: &[f64]) -> CoreResult<()> {
    if values.iter().any(|v| !v.is_finite()) {
        return Err(invalid("Companion coordinates must be finite"));
    }
    Ok(())
}
fn sources(values: &[CompanionSource]) -> CoreResult<()> {
    if values.len() > 10 {
        return Err(limit("Too many companion sources"));
    }
    for value in values {
        bounded(&value.title, 256, false)?;
        bounded(&value.url, 2048, false)?;
        let host = value
            .url
            .strip_prefix("https://")
            .and_then(|s| s.split('/').next());
        if host.is_none_or(|s| s.is_empty() || s.contains('@'))
            || value.url.chars().any(char::is_whitespace)
        {
            return Err(invalid("Source must be an HTTPS URL"));
        }
    }
    Ok(())
}
pub(crate) fn settings(value: &CompanionSettings) -> CoreResult<()> {
    bounded(&value.tell, 4096, true)?;
    bounded(&value.active_reader, 128, true)
}
pub(crate) fn validate(body: &CompanionBody) -> CoreResult<()> {
    if serde_json::to_vec(body)
        .map_err(|_| invalid("Invalid companion body"))?
        .len()
        > 65536
    {
        return Err(limit("Companion item exceeds 64 KiB"));
    }
    match body {
        CompanionBody::Anchor { target } => match target {
            AnchorTarget::Text {
                block_id,
                start,
                end,
                ..
            } => {
                crate::validation::id(block_id)?;
                if start > end {
                    return Err(invalid("Invalid anchor range"));
                }
            }
            AnchorTarget::Canvas {
                sheet_id,
                x,
                y,
                width,
                height,
            } => {
                crate::validation::id(sheet_id)?;
                finite(&[*x, *y, *width, *height])?;
                if *width <= 0.0 || *height <= 0.0 {
                    return Err(invalid("Invalid anchor rectangle"));
                }
            }
        },
        CompanionBody::MarginNote {
            anchor_id,
            text,
            reader,
            sources: urls,
            ..
        } => {
            crate::validation::id(anchor_id)?;
            bounded(text, 8192, false)?;
            bounded(reader, 128, false)?;
            sources(urls)?;
        }
        CompanionBody::ThreadMessage {
            margin_id,
            text,
            sources: urls,
            ..
        } => {
            crate::validation::id(margin_id)?;
            bounded(text, 8192, false)?;
            sources(urls)?;
        }
        CompanionBody::Picture {
            anchor_id,
            title,
            svg,
        } => {
            crate::validation::id(anchor_id)?;
            bounded(title, 256, false)?;
            let clean = filter_picture(svg.clone()).map_err(|_| invalid("Invalid picture SVG"))?;
            if clean != *svg {
                return Err(invalid("Picture SVG must be filtered before submission"));
            }
        }
        CompanionBody::Drawing {
            anchor_id,
            title,
            width,
            height,
            steps,
        } => {
            crate::validation::id(anchor_id)?;
            bounded(title, 256, false)?;
            finite(&[*width, *height])?;
            if !(100.0..=1600.0).contains(width)
                || !(100.0..=1600.0).contains(height)
                || steps.is_empty()
                || steps.len() > 12
            {
                return Err(invalid("Invalid drawing box or steps"));
            }
            if steps.iter().map(|s| s.primitives.len()).sum::<usize>() > 400 {
                return Err(limit("Drawing exceeds 400 primitives"));
            }
            let point = |x: f64, y: f64| -> CoreResult<()> {
                finite(&[x, y])?;
                if x < 0.0 || y < 0.0 || x > *width || y > *height {
                    return Err(invalid("Drawing point outside box"));
                }
                Ok(())
            };
            for step in steps {
                bounded(&step.caption, 512, true)?;
                for p in &step.primitives {
                    match p {
                        DrawingPrimitive::Line { from, to }
                        | DrawingPrimitive::Arrow { from, to } => {
                            point(from.x, from.y)?;
                            point(to.x, to.y)?;
                        }
                        DrawingPrimitive::Polyline { points }
                        | DrawingPrimitive::ShadedRegion { points } => {
                            let min = if matches!(p, DrawingPrimitive::ShadedRegion { .. }) {
                                3
                            } else {
                                2
                            };
                            if points.len() < min || points.len() > 128 {
                                return Err(limit("Invalid drawing point count"));
                            }
                            for p in points {
                                point(p.x, p.y)?;
                            }
                        }
                        DrawingPrimitive::Rectangle {
                            x,
                            y,
                            width: w,
                            height: h,
                        }
                        | DrawingPrimitive::Ellipse {
                            x,
                            y,
                            width: w,
                            height: h,
                        } => {
                            point(*x, *y)?;
                            finite(&[*w, *h])?;
                            if *w < 0.0 || *h < 0.0 {
                                return Err(invalid("Invalid drawing shape"));
                            }
                            point(x + w, y + h)?;
                        }
                        DrawingPrimitive::Dot { x, y, size } => {
                            point(*x, *y)?;
                            finite(&[*size])?;
                            if !(1.0..=24.0).contains(size) {
                                return Err(invalid("Invalid dot size"));
                            }
                        }
                        DrawingPrimitive::Label { x, y, text, size } => {
                            point(*x, *y)?;
                            bounded(text, 256, false)?;
                            finite(&[*size])?;
                            if !(10.0..=40.0).contains(size) {
                                return Err(invalid("Invalid label size"));
                            }
                        }
                    }
                }
            }
        }
    }
    Ok(())
}
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct AnchorRestore {
    pub id: Id,
    pub target: AnchorTarget,
}

impl State {
    pub(crate) fn anchor_positions(&self, block: &str) -> Vec<AnchorRestore> {
        self.companion_items
            .iter()
            .filter_map(|v| match &v.body {
                CompanionBody::Anchor {
                    target: target @ AnchorTarget::Text { block_id, .. },
                } if block_id == block => Some(AnchorRestore {
                    id: v.id.clone(),
                    target: target.clone(),
                }),
                _ => None,
            })
            .collect()
    }
    pub(crate) fn restore_anchor_positions(&mut self, values: &[AnchorRestore]) {
        fn restore(item: &mut CompanionItemView, values: &[AnchorRestore]) {
            if let CompanionBody::Anchor { target } = &mut item.body
                && let Some(value) = values.iter().find(|v| v.id == item.id)
            {
                *target = value.target.clone();
            }
        }
        for item in &mut self.companion_items {
            restore(item, values);
        }
        for h in self.histories.values_mut() {
            for action in h.undo.iter_mut().chain(h.redo.iter_mut()) {
                for item in action
                    .companion_before
                    .iter_mut()
                    .chain(action.companion_after.iter_mut())
                {
                    restore(item, values);
                }
            }
        }
    }
    pub(crate) fn detach_block_anchors(&mut self, block: &str, deleted: bool) {
        let mut positions = self.anchor_positions(block);
        for position in &mut positions {
            if let AnchorTarget::Text {
                start,
                end,
                detached,
                ..
            } = &mut position.target
            {
                *detached = deleted || start == end;
            }
        }
        self.restore_anchor_positions(&positions);
    }
    pub(crate) fn apply_companion(
        &mut self,
        mutations: &[CompanionMutation],
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let mut before = vec![];
        let mut after = vec![];
        for m in mutations {
            let current = self.companion_items.iter().position(|v| v.id == m.id);
            let old = if let Some(index) = current {
                let value = &self.companion_items[index];
                check_base(&m.base, &value.head, deps)?;
                if std::mem::discriminant(&value.body) != std::mem::discriminant(&m.body) {
                    return Err(invalid("Companion item kind is immutable"));
                }
                value.clone()
            } else {
                if !matches!(m.base, Base::Absent)
                    || self.strokes.iter().any(|v| v.id == m.id)
                    || self.text_blocks.iter().any(|v| v.id == m.id)
                    || self.images.iter().any(|v| v.id == m.id)
                {
                    return Err(collision());
                }
                if self.companion_items.len() >= 10000 {
                    return Err(limit("Too many companion items"));
                }
                CompanionItemView {
                    id: m.id.clone(),
                    author_id: author.into(),
                    head: head(op),
                    tombstone: true,
                    body: m.body.clone(),
                }
            };
            self.check_companion_reference(&m.body, &m.id, op, deps)?;
            let value = CompanionItemView {
                id: m.id.clone(),
                author_id: old.author_id.clone(),
                head: head(op),
                tombstone: m.tombstone,
                body: m.body.clone(),
            };
            if let Some(index) = current {
                self.companion_items[index] = value.clone();
            } else {
                self.companion_items.push(value.clone());
            }
            before.push(old);
            after.push(value);
        }
        let history = self.histories.entry(author.into()).or_default();
        for value in &after {
            history.expected.insert(value.id.clone(), op.clone());
        }
        history.undo.push(Action {
            operation: op.clone(),
            before: vec![],
            after: vec![],
            text_before: vec![],
            text_after: vec![],
            companion_before: before,
            companion_after: after,
            image_before: vec![],
            image_after: vec![],
        });
        history.redo.clear();
        Ok(())
    }
    fn check_companion_reference(
        &self,
        body: &CompanionBody,
        item_id: &str,
        op: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        match body {
            CompanionBody::Anchor { target } => match target {
                AnchorTarget::Text {
                    block_id,
                    start,
                    end,
                    detached,
                } => {
                    let block = self
                        .text_blocks
                        .iter()
                        .find(|b| b.id == *block_id)
                        .ok_or_else(missing)?;
                    require(deps, &block.head.operation_id)?;
                    if *end as usize > block.text.len()
                        || !block.text.is_char_boundary(*start as usize)
                        || !block.text.is_char_boundary(*end as usize)
                        || (!detached && start == end)
                    {
                        return Err(invalid("Anchor range must name existing text"));
                    }
                }
                AnchorTarget::Canvas { sheet_id, .. } => {
                    let sheet = self
                        .sheets
                        .iter()
                        .find(|s| s.id == *sheet_id)
                        .ok_or_else(missing)?;
                    require(deps, &sheet.creation)?;
                }
            },
            CompanionBody::MarginNote { anchor_id, .. }
            | CompanionBody::Drawing { anchor_id, .. }
            | CompanionBody::Picture { anchor_id, .. } => {
                let anchor = self
                    .companion_items
                    .iter()
                    .find(|v| {
                        v.id == *anchor_id
                            && !v.tombstone
                            && matches!(v.body, CompanionBody::Anchor { .. })
                    })
                    .ok_or_else(missing)?;
                if anchor.head.operation_id != op {
                    require(deps, &anchor.head.operation_id)?;
                }
            }
            CompanionBody::ThreadMessage {
                margin_id, order, ..
            } => {
                let margin = self
                    .companion_items
                    .iter()
                    .find(|v| {
                        v.id == *margin_id
                            && !v.tombstone
                            && matches!(v.body, CompanionBody::MarginNote { .. })
                    })
                    .ok_or_else(missing)?;
                if margin.head.operation_id != op {
                    require(deps, &margin.head.operation_id)?;
                }
                if self.companion_items.iter().any(|v| v.id != item_id && !v.tombstone && matches!(&v.body, CompanionBody::ThreadMessage { margin_id: id, order: n, .. } if id == margin_id && n == order)) { return Err(invalid("Thread order must be unique")); }
            }
        }
        Ok(())
    }
    pub(crate) fn check_companion_history(
        &self,
        values: &[CompanionItemView],
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let h = self.histories.get(author).ok_or_else(stale)?;
        for v in values {
            let current = self
                .companion_items
                .iter()
                .find(|c| c.id == v.id)
                .ok_or_else(stale)?;
            if h.expected.get(&v.id) != Some(&current.head.operation_id) {
                return Err(stale());
            }
            require(deps, &current.head.operation_id)?;
        }
        Ok(())
    }
    pub(crate) fn move_anchors(&mut self, block: &str, offset: u32, delete: u32, insert: u32) {
        fn move_item(
            value: &mut CompanionItemView,
            block: &str,
            offset: u32,
            delete: u32,
            insert: u32,
        ) {
            let CompanionBody::Anchor {
                target:
                    AnchorTarget::Text {
                        block_id,
                        start,
                        end,
                        detached,
                    },
            } = &mut value.body
            else {
                return;
            };
            if block_id != block {
                return;
            }
            let cut_end = offset + delete;
            let map = |p: u32, right: bool| {
                if p < offset || (p == offset && !right) {
                    p
                } else if p >= cut_end {
                    p - delete + insert
                } else {
                    offset + if right { insert } else { 0 }
                }
            };
            // At an insertion at the start, the original passage follows the insertion.
            let new_start = map(*start, true);
            let new_end = map(*end, false).max(new_start);
            if delete > 0 && offset <= *start && cut_end >= *end {
                *detached = true;
            }
            *start = new_start;
            *end = new_end;
        }
        for v in &mut self.companion_items {
            move_item(v, block, offset, delete, insert);
        }
        for h in self.histories.values_mut() {
            for a in h.undo.iter_mut().chain(h.redo.iter_mut()) {
                for v in a
                    .companion_before
                    .iter_mut()
                    .chain(a.companion_after.iter_mut())
                {
                    move_item(v, block, offset, delete, insert);
                }
            }
        }
    }
}
