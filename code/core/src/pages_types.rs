//! Notes and canvases v1 (K1-K3): the two document kinds, pages as sized sheets, page and
//! canvas styles, waypoints and the named page sizes. Note units throughout.
use crate::*;

/// What a document is, fixed at creation. A Note is paged; a Canvas is one unbounded sheet.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
pub enum NoteKind {
    #[default]
    Note,
    Canvas,
}
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
pub enum PagePattern {
    #[default]
    None,
    Lines,
    Dots,
    Grid,
}
#[derive(
    Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record,
)]
pub struct Rgba {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}
/// A style as stored. Missing parts fall back, per the resolution order page, note, default.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageStyle {
    pub template: PagePattern,
    /// None = the theme paper.
    pub paper: Option<Rgba>,
    /// None = rgba(150,150,150,128).
    pub pattern_colour: Option<Rgba>,
    /// None = 82.6 units. Resolved values are clamped to 20.6..258.
    pub spacing: Option<f32>,
}
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ResolvedPageStyle {
    pub pattern: PagePattern,
    /// None = the theme paper.
    pub paper: Option<Rgba>,
    pub pattern_colour: Rgba,
    pub spacing: f32,
}
/// The note-wide page style with the head a `SetPageStyle` without `sheet_id` bases on.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageStyleView {
    /// None until a style was set.
    pub style: Option<PageStyle>,
    pub resolved: ResolvedPageStyle,
    pub head: EntityHead,
}
/// One page of a Note. A page is a sheet; strokes, text and pictures stay sheet-local.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageView {
    pub sheet_id: Id,
    pub width: f32,
    /// For an `auto_height` page this is `max(3000, content_bottom + 1000)`, computed by the
    /// core in the snapshot.
    pub height: f32,
    pub auto_height: bool,
    pub tombstone: bool,
    /// The head `ResizePage`, `MovePage`, `DeletePage` and `RestorePage` base on.
    pub head: EntityHead,
    /// This page's own style override, if any; the head to change it is `style_head`.
    pub style: Option<PageStyle>,
    pub style_head: EntityHead,
    /// Page, then note, then default.
    pub resolved_style: ResolvedPageStyle,
}
/// The background of a Canvas, resolved, with the head `SetCanvasBackground` bases on.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CanvasBackgroundView {
    pub pattern: PagePattern,
    pub pattern_colour: Rgba,
    pub spacing: f32,
    /// None = the theme paper.
    pub paper: Option<Rgba>,
    pub head: EntityHead,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Waypoint {
    /// At most 48 characters; unique case-insensitively.
    pub name: String,
    pub cx: f32,
    pub cy: f32,
    pub zoom: f32,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct WaypointsView {
    pub items: Vec<Waypoint>,
    pub head: EntityHead,
}
/// A named paper size in note units, portrait (short side first).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageSize {
    pub name: String,
    pub mm_width: f32,
    pub mm_height: f32,
    pub width: f32,
    pub height: f32,
}

/// Units per millimetre: A4's 210 mm is the 1600-unit phone column.
pub const UNITS_PER_MM: f32 = 1600.0 / 210.0;
pub const PAGE_MIN_SIDE: f32 = 76.0;
pub const PAGE_MAX_SIDE: f32 = 15238.0;
pub const LEGACY_PAGE_WIDTH: f32 = 4800.0;
pub const LEGACY_PAGE_MIN_HEIGHT: f32 = 3000.0;
pub const LEGACY_PAGE_PADDING: f32 = 1000.0;
pub const DEFAULT_PAGE_GAP: f32 = 20.6;
pub const DEFAULT_PAGE_SPACING: f32 = 82.6;
pub const MIN_PATTERN_SPACING: f32 = 20.6;
pub const MAX_PATTERN_SPACING: f32 = 258.0;
pub const DEFAULT_PAGE_PATTERN_COLOUR: Rgba = Rgba {
    r: 150,
    g: 150,
    b: 150,
    a: 128,
};
pub const DEFAULT_CANVAS_PATTERN_COLOUR: Rgba = Rgba {
    r: 150,
    g: 150,
    b: 150,
    a: 64,
};
pub const MAX_WAYPOINTS: usize = 100;
pub const MAX_WAYPOINT_NAME_CHARS: usize = 48;
pub const MAX_PAGES: usize = 2000;

const SIZES: [(&str, f32, f32); 6] = [
    ("A4", 210.0, 297.0),
    ("A5", 148.0, 210.0),
    ("Letter", 215.9, 279.4),
    ("Legal", 215.9, 355.6),
    ("Slide 16:9", 190.5, 338.7),
    ("Slide 4:3", 190.5, 254.0),
];

/// The named sizes, portrait.
#[uniffi::export]
pub fn page_sizes() -> Vec<PageSize> {
    SIZES
        .iter()
        .map(|&(name, w, h)| PageSize {
            name: name.into(),
            mm_width: w,
            mm_height: h,
            width: w * UNITS_PER_MM,
            height: h * UNITS_PER_MM,
        })
        .collect()
}

/// The named size a page of `width` x `height` units is, within 0.5 mm, either way round.
#[uniffi::export]
pub fn match_size(width: f32, height: f32) -> Option<PageSize> {
    let (w, h) = (width / UNITS_PER_MM, height / UNITS_PER_MM);
    let close = |a: f32, b: f32| (a - b).abs() <= 0.5;
    page_sizes().into_iter().find(|s| {
        (close(w, s.mm_width) && close(h, s.mm_height))
            || (close(w, s.mm_height) && close(h, s.mm_width))
    })
}

#[uniffi::export]
pub fn default_page_gap() -> f32 {
    DEFAULT_PAGE_GAP
}

fn clamp_spacing(spacing: Option<f32>) -> f32 {
    spacing
        .filter(|s| s.is_finite())
        .unwrap_or(DEFAULT_PAGE_SPACING)
        .clamp(MIN_PATTERN_SPACING, MAX_PATTERN_SPACING)
}

/// Page, then note, then default. A page's own style replaces the note's as a whole; parts it
/// leaves out take the defaults.
#[uniffi::export]
pub fn resolve_page_style(note: Option<PageStyle>, page: Option<PageStyle>) -> ResolvedPageStyle {
    let style = page.or(note);
    ResolvedPageStyle {
        pattern: style.map_or(PagePattern::None, |s| s.template),
        paper: style.and_then(|s| s.paper),
        pattern_colour: style
            .and_then(|s| s.pattern_colour)
            .unwrap_or(DEFAULT_PAGE_PATTERN_COLOUR),
        spacing: clamp_spacing(style.and_then(|s| s.spacing)),
    }
}

/// A canvas background with the defaults applied: Grid, rgba(150,150,150,64), 82.6.
pub(crate) fn canvas_background(
    pattern: Option<PagePattern>,
    colour: Option<Rgba>,
    spacing: Option<f32>,
    paper: Option<Rgba>,
    head: EntityHead,
) -> CanvasBackgroundView {
    CanvasBackgroundView {
        pattern: pattern.unwrap_or(PagePattern::Grid),
        pattern_colour: colour.unwrap_or(DEFAULT_CANVAS_PATTERN_COLOUR),
        spacing: clamp_spacing(spacing),
        paper,
        head,
    }
}
