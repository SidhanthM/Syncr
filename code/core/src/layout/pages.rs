//! Page and canvas-card layout (notes and canvases v1, K5). Note units; pure functions the
//! phone and the Mac share through `code/shared/layout/pages-v1.json` and `canvas-cards-v1.json`.

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum PageMode {
    /// One page per row.
    Single,
    /// Pages 1-2, 3-4, and so on.
    Double,
    /// Page 1 alone, then 2-3, 4-5, and so on.
    Cover,
}

/// One live page's size. The caller leaves tombstoned pages out.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageExtent {
    pub sheet_id: String,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageRect {
    pub sheet_id: String,
    /// Row index, top to bottom.
    pub row: u32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

/// Rows top to bottom, starting at y = 0, `gap` apart. Pages in a row are `gap` apart and
/// vertically centred in it; rows are horizontally centred against the widest row, whose left
/// edge is x = 0.
#[uniffi::export]
pub fn layout_pages(pages: Vec<PageExtent>, mode: PageMode, gap: f32) -> Vec<PageRect> {
    let mut rows: Vec<&[PageExtent]> = vec![];
    let mut rest: &[PageExtent] = &pages;
    if mode == PageMode::Cover && !rest.is_empty() {
        rows.push(&rest[..1]);
        rest = &rest[1..];
    }
    let per_row = if mode == PageMode::Single { 1 } else { 2 };
    rows.extend(rest.chunks(per_row));
    let row_width = |row: &[PageExtent]| {
        row.iter().map(|p| p.width).sum::<f32>() + gap * row.len().saturating_sub(1) as f32
    };
    let widest = rows.iter().map(|r| row_width(r)).fold(0.0, f32::max);
    let mut out = Vec::with_capacity(pages.len());
    let mut y = 0.0;
    for (index, row) in rows.iter().enumerate() {
        let height = row.iter().map(|p| p.height).fold(0.0, f32::max);
        let mut x = (widest - row_width(row)) / 2.0;
        for p in *row {
            out.push(PageRect {
                sheet_id: p.sheet_id.clone(),
                row: index as u32,
                x,
                y: y + (height - p.height) / 2.0,
                width: p.width,
                height: p.height,
            });
            x += p.width + gap;
        }
        y += height + gap;
    }
    out
}

/// The text an annotation card hangs from, as a canvas rectangle.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CardAnchor {
    pub id: String,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CanvasCard {
    pub id: String,
    /// The `CardAnchor::id` it hangs from. An unknown anchor places the card at the origin.
    pub anchor_id: String,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CardPlacement {
    pub id: String,
    pub x: f32,
    pub y: f32,
}

/// Each card goes right of its anchor's box, `gap` away and level with its top, then is pushed
/// down below any earlier card it would overlap (with `gap` between them). Cards are placed in
/// the order given, so the result is deterministic.
#[uniffi::export]
pub fn layout_canvas_cards(
    anchors: Vec<CardAnchor>,
    cards: Vec<CanvasCard>,
    gap: f32,
) -> Vec<CardPlacement> {
    let mut placed: Vec<(f32, f32, f32, f32)> = Vec::with_capacity(cards.len());
    let mut out = Vec::with_capacity(cards.len());
    for card in &cards {
        let (x, mut y) = anchors
            .iter()
            .find(|a| a.id == card.anchor_id)
            .map_or((0.0, 0.0), |a| (a.x + a.width + gap, a.y));
        // Each push moves y strictly down past one placed card, so this ends.
        loop {
            let hit = placed.iter().find(|&&(px, py, pw, ph)| {
                x < px + pw && px < x + card.width && y < py + ph && py < y + card.height
            });
            match hit {
                Some(&(_, py, _, ph)) => y = py + ph + gap,
                None => break,
            }
        }
        placed.push((x, y, card.width, card.height));
        out.push(CardPlacement {
            id: card.id.clone(),
            x,
            y,
        });
    }
    out
}
