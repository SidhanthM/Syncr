//! Container admission and image reducer. Pixel decoding belongs to the apps.
use crate::{
    error::{collision, invalid, limit, missing, stale},
    reducer::{Action, State, check_base, head, require},
    *,
};
use std::collections::HashSet;
pub(crate) const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const MAX_IMAGES: usize = 200;
pub(crate) fn placement(p: &ImagePlacement) -> CoreResult<()> {
    let (w, h) = match p {
        ImagePlacement::Canvas {
            sheet_id,
            x,
            y,
            width,
            height,
        } => {
            crate::validation::id(sheet_id)?;
            if !x.is_finite() || !y.is_finite() {
                return Err(invalid("Image position must be finite"));
            }
            (*width, *height)
        }
        ImagePlacement::Text {
            anchor_id,
            width,
            height,
        } => {
            crate::validation::id(anchor_id)?;
            (*width, *height)
        }
    };
    if !w.is_finite() || !h.is_finite() || w <= 0.0 || h <= 0.0 || w > 100000.0 || h > 100000.0 {
        return Err(invalid("Invalid image display size"));
    }
    Ok(())
}
fn png_size(bs: &[u8]) -> Option<(u32, u32)> {
    if !bs.starts_with(b"\x89PNG\r\n\x1a\n") {
        return None;
    }
    let mut offset = 8;
    let mut size = None;
    let mut data = false;
    while offset + 12 <= bs.len() {
        let len = u32::from_be_bytes(bs[offset..offset + 4].try_into().ok()?) as usize;
        let end = offset.checked_add(12)?.checked_add(len)?;
        if end > bs.len() {
            return None;
        }
        let kind = &bs[offset + 4..offset + 8];
        if offset == 8 && (kind != b"IHDR" || len != 13) {
            return None;
        }
        if kind == b"IHDR" {
            if size.is_some() || len != 13 {
                return None;
            }
            size = Some((
                u32::from_be_bytes(bs[offset + 8..offset + 12].try_into().ok()?),
                u32::from_be_bytes(bs[offset + 12..offset + 16].try_into().ok()?),
            ));
        }
        if kind == b"IDAT" {
            data = true;
        }
        if kind == b"IEND" {
            return if len == 0 && data && end == bs.len() {
                size
            } else {
                None
            };
        }
        offset = end;
    }
    None
}
fn jpeg_size(bs: &[u8]) -> Option<(u32, u32)> {
    if !bs.starts_with(&[0xff, 0xd8]) || !bs.ends_with(&[0xff, 0xd9]) {
        return None;
    }
    let mut i = 2;
    let mut size = None;
    while i < bs.len() {
        if bs[i] != 0xff {
            return None;
        }
        while i < bs.len() && bs[i] == 0xff {
            i += 1;
        }
        let marker = *bs.get(i)?;
        i += 1;
        if matches!(marker, 0xd8 | 0xd9 | 0x00 | 0xd0..=0xd7) {
            return None;
        }
        let length = u16::from_be_bytes([*bs.get(i)?, *bs.get(i + 1)?]) as usize;
        if length < 2 || i.checked_add(length)? > bs.len() {
            return None;
        }
        if marker == 0xda {
            if length < 6 || i + length > bs.len() - 2 {
                return None;
            }
            let components = usize::from(bs[i + 2]);
            return if (1..=4).contains(&components) && length == 6 + 2 * components {
                size
            } else {
                None
            };
        }
        if matches!(marker,0xc0..=0xc3|0xc5..=0xc7|0xc9..=0xcb|0xcd..=0xcf) {
            if length < 8 || size.is_some() {
                return None;
            }
            let components = usize::from(bs[i + 7]);
            if !(1..=4).contains(&components) || length != 8 + 3 * components {
                return None;
            }
            size = Some((
                u32::from(u16::from_be_bytes([bs[i + 5], bs[i + 6]])),
                u32::from(u16::from_be_bytes([bs[i + 3], bs[i + 4]])),
            ));
        }
        i += length;
    }
    None
}
pub(crate) fn container(bs: &[u8], media: &ImageMediaType, w: u32, h: u32) -> CoreResult<()> {
    if bs.len() > MAX_IMAGE_BYTES {
        return Err(limit("Image exceeds 8 MiB"));
    }
    if w == 0 || h == 0 || w > 2400 || h > 2400 {
        return Err(invalid("Image dimensions must be 1 to 2400 pixels"));
    }
    let actual = match media {
        ImageMediaType::Png => png_size(bs),
        ImageMediaType::Jpeg => jpeg_size(bs),
    };
    if actual != Some((w, h)) {
        return Err(invalid(
            "Image container does not match declared media type and dimensions",
        ));
    }
    Ok(())
}
pub(crate) fn validate(mutations: &[ImageMutation], deps: &HashSet<&str>) -> CoreResult<()> {
    if mutations.is_empty() || mutations.len() > MAX_IMAGES {
        return Err(limit("Image batch requires 1 to 200 targets"));
    }
    let mut ids = HashSet::new();
    let mut bytes = 0usize;
    for m in mutations {
        let id = match m {
            ImageMutation::Add {
                image_id,
                bytes: bs,
                media_type,
                pixel_width,
                pixel_height,
                placement: p,
            } => {
                container(bs, media_type, *pixel_width, *pixel_height)?;
                placement(p)?;
                bytes += bs.len();
                if bytes > 16 * 1024 * 1024 {
                    return Err(limit("Image batch exceeds 16 MiB"));
                }
                image_id
            }
            ImageMutation::Move {
                image_id,
                base,
                placement: p,
            } => {
                crate::validation::base(base, deps)?;
                placement(p)?;
                image_id
            }
            ImageMutation::Delete { image_id, base }
            | ImageMutation::Restore { image_id, base }
            | ImageMutation::MarkSeen { image_id, base } => {
                crate::validation::base(base, deps)?;
                image_id
            }
        };
        crate::validation::id(id)?;
        if !ids.insert(id) {
            return Err(invalid("Image targets must be unique"));
        }
    }
    Ok(())
}
impl State {
    fn image_reference(&self, p: &ImagePlacement, deps: &HashSet<&str>) -> CoreResult<()> {
        match p {
            ImagePlacement::Canvas { sheet_id, .. } => {
                let s = self
                    .sheets
                    .iter()
                    .find(|v| v.id == *sheet_id)
                    .ok_or_else(missing)?;
                require(deps, &s.creation)
            }
            ImagePlacement::Text { anchor_id, .. } => {
                let a = self
                    .companion_items
                    .iter()
                    .find(|v| {
                        v.id == *anchor_id
                            && !v.tombstone
                            && matches!(
                                v.body,
                                CompanionBody::Anchor {
                                    target: AnchorTarget::Text { .. }
                                }
                            )
                    })
                    .ok_or_else(missing)?;
                require(deps, &a.head.operation_id)
            }
        }
    }
    pub(crate) fn apply_images(
        &mut self,
        mutations: &[ImageMutation],
        op: &Id,
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let mut before = vec![];
        let mut after = vec![];
        for m in mutations {
            if let ImageMutation::Add {
                image_id,
                media_type,
                pixel_width,
                pixel_height,
                placement,
                ..
            } = m
            {
                if self.images.iter().any(|v| v.id == *image_id)
                    || self.strokes.iter().any(|v| v.id == *image_id)
                    || self.text_blocks.iter().any(|v| v.id == *image_id)
                    || self.companion_items.iter().any(|v| v.id == *image_id)
                {
                    return Err(collision());
                }
                if self.images.len() >= MAX_IMAGES {
                    return Err(limit(
                        "A note may have at most 200 images including deleted images",
                    ));
                }
                self.image_reference(placement, deps)?;
                let v = ImageMarkView {
                    id: image_id.clone(),
                    author_id: author.into(),
                    head: head(op),
                    media_type: media_type.clone(),
                    pixel_width: *pixel_width,
                    pixel_height: *pixel_height,
                    placement: placement.clone(),
                    tombstone: false,
                    seen_by_companion: false,
                };
                before.push(ImageMarkView {
                    tombstone: true,
                    ..v.clone()
                });
                after.push(v.clone());
                self.images.push(v);
            } else {
                let (id, base) = match m {
                    ImageMutation::Delete { image_id, base }
                    | ImageMutation::Restore { image_id, base }
                    | ImageMutation::Move { image_id, base, .. }
                    | ImageMutation::MarkSeen { image_id, base } => (image_id, base),
                    _ => unreachable!(),
                };
                if let ImageMutation::Move { placement, .. } = m {
                    self.image_reference(placement, deps)?;
                }
                let v = self
                    .images
                    .iter_mut()
                    .find(|v| v.id == *id)
                    .ok_or_else(missing)?;
                check_base(base, &v.head, deps)?;
                if matches!(m, ImageMutation::MarkSeen { .. }) {
                    if v.tombstone {
                        return Err(invalid("Deleted images cannot be marked seen"));
                    }
                    v.seen_by_companion = true;
                    continue;
                }
                before.push(v.clone());
                match m {
                    ImageMutation::Delete { .. } => v.tombstone = true,
                    ImageMutation::Restore { .. } => v.tombstone = false,
                    ImageMutation::Move { placement, .. } => v.placement = placement.clone(),
                    _ => unreachable!(),
                }
                v.head = head(op);
                after.push(v.clone());
            }
        }
        if !after.is_empty() {
            let h = self.histories.entry(author.into()).or_default();
            for v in &after {
                h.expected.insert(v.id.clone(), op.clone());
            }
            h.undo.push(Action {
                operation: op.clone(),
                before: vec![],
                after: vec![],
                text_before: vec![],
                text_after: vec![],
                companion_before: vec![],
                companion_after: vec![],
                image_before: before,
                image_after: after,
                page_before: vec![],
                page_after: vec![],
            });
            h.redo.clear();
        }
        Ok(())
    }
    pub(crate) fn check_image_history(
        &self,
        values: &[ImageMarkView],
        author: &str,
        deps: &HashSet<&str>,
    ) -> CoreResult<()> {
        let h = self.histories.get(author).ok_or_else(stale)?;
        for v in values {
            let current = self
                .images
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
}
