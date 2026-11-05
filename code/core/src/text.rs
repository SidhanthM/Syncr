//! Text mutations and compact inverse edits. Every splice (including undo/redo) passes
//! through splice(), the reducer hook for future text-anchor adjustment.
use crate::{
    error::{collision, document_exists, invalid, invalid_text, limit, missing, text_range},
    reducer::{IDENTITY, State, check_base, head, require},
    *,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub(crate) const MAX_TEXT_BYTES: usize = 1_048_576;
pub(crate) const MAX_TEXT_BLOCKS: usize = 10_000;
pub(crate) const MAX_GESTURE_TEXT_BYTES: usize = 16 * 1024 * 1024;

pub(crate) fn content(text: &str) -> CoreResult<()> {
    if text.len() > MAX_TEXT_BYTES {
        return Err(limit("Text exceeds 1 MiB"));
    }
    if text.contains(['\0', '\r']) {
        return Err(invalid_text());
    }
    Ok(())
}
pub(crate) fn width(width: f32) -> CoreResult<()> {
    if !width.is_finite() || width <= 0.0 {
        return Err(invalid("Text width must be finite and greater than zero"));
    }
    Ok(())
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct TextEdit {
    pub mark_id: Id,
    pub change: TextChange,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub anchor_restore: Vec<crate::companion::AnchorRestore>,
}
#[derive(Clone, Serialize, Deserialize)]
pub(crate) enum TextChange {
    Splice {
        offset: u32,
        delete: u32,
        insert: String,
    },
    Width(f32),
    Tombstone(bool),
    Transform(Affine),
    Style(TextStyle),
}
#[derive(Clone, Serialize)]
pub(crate) enum TextEffect {
    Added { after: TextBlockView },
    Edited { before: TextEdit, after: TextEdit },
}

/// Validates before slicing; the inverse carries only the removed bytes. This is the
/// single splice path for commands, replay, undo and redo. Anchor adjustment belongs
/// immediately after replace_range(), using (mark_id, offset, delete, insert.len()).
impl State {
    fn splice(
        &mut self,
        mark_id: &str,
        offset: u32,
        delete: u32,
        insert: &str,
    ) -> CoreResult<TextChange> {
        let block = self
            .text_blocks
            .iter_mut()
            .find(|b| b.id == mark_id)
            .ok_or_else(missing)?;
        let start = offset as usize;
        let end = start.checked_add(delete as usize).ok_or_else(text_range)?;
        if end > block.text.len()
            || !block.text.is_char_boundary(start)
            || !block.text.is_char_boundary(end)
        {
            return Err(text_range());
        }
        content(insert)?;
        if block.text.len() - delete as usize + insert.len() > MAX_TEXT_BYTES {
            return Err(limit("Text exceeds 1 MiB"));
        }
        let inverse = TextChange::Splice {
            offset,
            delete: insert.len() as u32,
            insert: block.text[start..end].into(),
        };
        block.text.replace_range(start..end, insert);
        self.move_anchors(mark_id, offset, delete, insert.len() as u32);
        Ok(inverse)
    }
}

impl State {
    pub(crate) fn check_document_text(&self) -> CoreResult<()> {
        if self
            .text_blocks
            .iter()
            .filter(|b| b.role == TextRole::Document && !b.tombstone)
            .count()
            > 1
        {
            return Err(document_exists());
        }
        Ok(())
    }

    pub(crate) fn apply_text_mutation(
        &mut self,
        mutation: &MarkMutation,
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<(TextEdit, TextEdit)> {
        let (mark_id, base, change) = match mutation {
            MarkMutation::AddText {
                mark_id,
                sheet_id,
                role,
                origin_x,
                origin_y,
                width,
                text,
                style,
            } => {
                if self.strokes.iter().any(|s| s.id == *mark_id)
                    || self.text_blocks.iter().any(|b| b.id == *mark_id)
                    || self.companion_items.iter().any(|v| v.id == *mark_id)
                    || self.images.iter().any(|v| v.id == *mark_id)
                {
                    return Err(collision());
                }
                if self.text_blocks.len() >= MAX_TEXT_BLOCKS {
                    return Err(limit("A note may have at most 10000 text blocks"));
                }
                let sheet = self
                    .sheets
                    .iter()
                    .find(|s| s.id == *sheet_id)
                    .ok_or_else(missing)?;
                require(deps, &sheet.creation)?;
                let block = TextBlockView {
                    id: mark_id.clone(),
                    sheet_id: sheet_id.clone(),
                    author_id: author.into(),
                    head: head(op),
                    role: role.clone(),
                    origin_x: *origin_x,
                    origin_y: *origin_y,
                    transform: IDENTITY,
                    width: *width,
                    text: text.clone(),
                    style: style.clone(),
                    tombstone: false,
                };
                self.text_effects.push(TextEffect::Added {
                    after: block.clone(),
                });
                self.text_blocks.push(block);
                return Ok((
                    TextEdit {
                        mark_id: mark_id.clone(),
                        change: TextChange::Tombstone(true),
                        anchor_restore: vec![],
                    },
                    TextEdit {
                        mark_id: mark_id.clone(),
                        change: TextChange::Tombstone(false),
                        anchor_restore: vec![],
                    },
                ));
            }
            MarkMutation::SpliceText {
                mark_id,
                base,
                offset,
                delete,
                insert,
            } => (
                mark_id,
                base,
                TextChange::Splice {
                    offset: *offset,
                    delete: *delete,
                    insert: insert.clone(),
                },
            ),
            MarkMutation::SetTextWidth {
                mark_id,
                base,
                width,
            } => (mark_id, base, TextChange::Width(*width)),
            MarkMutation::DeleteText { mark_id, base } => {
                (mark_id, base, TextChange::Tombstone(true))
            }
            MarkMutation::RestoreText { mark_id, base } => {
                (mark_id, base, TextChange::Tombstone(false))
            }
            MarkMutation::TransformText {
                mark_id,
                base,
                transform,
            } => (mark_id, base, TextChange::Transform(transform.clone())),
            MarkMutation::RestyleText {
                mark_id,
                base,
                style,
            } => (mark_id, base, TextChange::Style(style.clone())),
            _ => return Err(invalid("Expected a text mutation")),
        };
        let block = self
            .text_blocks
            .iter()
            .find(|b| b.id == *mark_id)
            .ok_or_else(missing)?;
        check_base(base, &block.head, deps)?;
        let after = TextEdit {
            mark_id: mark_id.clone(),
            change,
            anchor_restore: vec![],
        };
        let before = self.apply_text_edit(&after, op)?;
        Ok((before, after))
    }

    /// History heads are checked by the caller before applying any edit in the batch.
    pub(crate) fn apply_text_edit(&mut self, edit: &TextEdit, op: &Id) -> CoreResult<TextEdit> {
        let anchors_before = self.anchor_positions(&edit.mark_id);
        let splice_inverse = if let TextChange::Splice {
            offset,
            delete,
            insert,
        } = &edit.change
        {
            Some(self.splice(&edit.mark_id, *offset, *delete, insert)?)
        } else {
            None
        };
        let block = self
            .text_blocks
            .iter_mut()
            .find(|b| b.id == edit.mark_id)
            .ok_or_else(missing)?;
        let change = match &edit.change {
            TextChange::Splice { .. } => splice_inverse.expect("splice inverse was computed"),
            TextChange::Width(width) => {
                TextChange::Width(std::mem::replace(&mut block.width, *width))
            }
            TextChange::Tombstone(value) => {
                TextChange::Tombstone(std::mem::replace(&mut block.tombstone, *value))
            }
            TextChange::Transform(value) => {
                TextChange::Transform(std::mem::replace(&mut block.transform, value.clone()))
            }
            TextChange::Style(value) => {
                TextChange::Style(std::mem::replace(&mut block.style, value.clone()))
            }
        };
        block.head = head(op);
        let inverse = TextEdit {
            mark_id: edit.mark_id.clone(),
            change,
            anchor_restore: anchors_before,
        };
        if let TextChange::Tombstone(deleted) = edit.change {
            self.detach_block_anchors(&edit.mark_id, deleted);
        }
        if !edit.anchor_restore.is_empty() {
            self.restore_anchor_positions(&edit.anchor_restore);
        }
        self.text_effects.push(TextEffect::Edited {
            before: inverse.clone(),
            after: edit.clone(),
        });
        Ok(inverse)
    }
}
