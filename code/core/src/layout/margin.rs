//! Margin annotation layout: which lane a note takes, where it sits beside its passage, and
//! the rectangle that is both its drawing frame and its hit target. Canvas units throughout;
//! the left lane has negative x whenever the column sits near the page edge.

/// How wide a view the layout is for. `Desktop` keeps a lane on each side of the column.
/// `Phone` keeps the right lane only: by policy, since the phone's window cannot show the
/// left one, not because of a missing code path.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum MarginPolicy {
    Desktop,
    Phone,
}

#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum MarginItemKind {
    /// A margin note, sorted by its passage and then its id.
    Note,
    /// A picture whose embed line is gone: placed after every note, in the order given.
    Picture,
}

/// What an annotation hangs from. Text offsets are in the same unit as [`MarginLine`]
/// offsets; the core never interprets the unit (the platforms pass UTF-16 offsets).
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum MarginAnchor {
    /// No usable anchor (detached): it takes the default top.
    None,
    Text { offset: u32 },
    /// A fixed canvas y, for anchors drawn on the canvas.
    Canvas { y: f32 },
}

/// One visual line of the anchored text, measured natively: the half-open offset range it
/// covers and its top. Lines are given in reading order.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MarginLine {
    pub start: u32,
    pub end: u32,
    pub top: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MarginItem {
    pub id: String,
    pub kind: MarginItemKind,
    pub anchor: MarginAnchor,
    /// The card's measured height.
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MarginInput {
    pub policy: MarginPolicy,
    /// The text column's edges.
    pub column_left: f32,
    pub column_right: f32,
    /// Every card is this wide.
    pub card_width: f32,
    /// Between the column and a lane.
    pub gap: f32,
    /// A lane starts empty below this y (cards sit at least `spacing` under it).
    pub lane_top: f32,
    /// The least space between two cards in a lane.
    pub spacing: f32,
    /// The top of an annotation with no anchor, or no line to attach to.
    pub default_top: f32,
    pub lines: Vec<MarginLine>,
    pub items: Vec<MarginItem>,
}

#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum MarginLane {
    Left,
    Right,
}

/// Where one annotation goes. `x`, `y`, `width` and `height` are the card's frame and its hit
/// rectangle (a point is a hit when `x <= px <= x + width` and `y <= py <= y + height`).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MarginPlacement {
    pub id: String,
    pub lane: MarginLane,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct MarginLayout {
    /// In placement order: notes by passage then id, then pictures in the order given.
    pub placements: Vec<MarginPlacement>,
    /// The lowest edge of any placed card; 0 when nothing is placed.
    pub bottom: f32,
}

/// The top of the line an offset falls on: the first line holding it (`start <= offset < end`,
/// or one that starts at it, which catches empty lines); an offset past the last line takes the
/// last line; no lines at all gives `default_top`.
fn passage_top(input: &MarginInput, anchor: &MarginAnchor) -> f32 {
    match *anchor {
        MarginAnchor::None => input.default_top,
        MarginAnchor::Canvas { y } => y,
        MarginAnchor::Text { offset } => input
            .lines
            .iter()
            .find(|l| (l.start <= offset && offset < l.end) || l.start == offset)
            .or(input.lines.last())
            .map_or(input.default_top, |l| l.top),
    }
}

/// Places every item in one pass. Each card goes in the lane whose last card ends higher
/// (ties go left) so it stays closest to its passage, at its passage's top or `spacing` under
/// the lane's last card, whichever is lower. Same input, same output on every platform.
#[uniffi::export]
pub fn layout_margins(input: MarginInput) -> MarginLayout {
    let left_x = input.column_left - input.gap - input.card_width;
    let right_x = input.column_right + input.gap;
    let mut order: Vec<(usize, f32)> = input
        .items
        .iter()
        .enumerate()
        .map(|(i, item)| (i, passage_top(&input, &item.anchor)))
        .collect();
    // Notes first, by passage then id; pictures keep the order they came in (the sort is stable).
    order.sort_by(|a, b| {
        let (ia, ib) = (&input.items[a.0], &input.items[b.0]);
        let picture = |k: MarginItemKind| k == MarginItemKind::Picture;
        picture(ia.kind).cmp(&picture(ib.kind)).then_with(|| {
            if picture(ia.kind) {
                std::cmp::Ordering::Equal
            } else {
                a.1.total_cmp(&b.1).then_with(|| ia.id.cmp(&ib.id))
            }
        })
    });
    let mut bottoms = [input.lane_top; 2];
    let mut bottom = 0f32;
    let mut placements = Vec::with_capacity(order.len());
    for (i, top) in order {
        let item = &input.items[i];
        let right = input.policy == MarginPolicy::Phone || bottoms[0] > bottoms[1];
        let lane = usize::from(right);
        let y = top.max(bottoms[lane] + input.spacing);
        bottoms[lane] = y + item.height;
        bottom = bottom.max(bottoms[lane]);
        placements.push(MarginPlacement {
            id: item.id.clone(),
            lane: if right { MarginLane::Right } else { MarginLane::Left },
            x: if right { right_x } else { left_x },
            y,
            width: input.card_width,
            height: item.height,
        });
    }
    MarginLayout { placements, bottom }
}
