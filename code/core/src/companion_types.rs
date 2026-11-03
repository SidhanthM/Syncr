use crate::*;

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum CompanionMode {
    Off,
    WhenAsked,
    ThinksWithYou,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum MarginKind {
    Question,
    Explanation,
    Flag,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum MarginState {
    Open,
    Pinned,
    Dismissed,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum MessageWho {
    You,
    Companion,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionSource {
    pub title: String,
    pub url: String,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum AnchorTarget {
    Text {
        block_id: Id,
        start: u32,
        end: u32,
        detached: bool,
    },
    Canvas {
        sheet_id: Id,
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DrawingPoint {
    pub x: f64,
    pub y: f64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum DrawingPrimitive {
    Line {
        from: DrawingPoint,
        to: DrawingPoint,
    },
    Polyline {
        points: Vec<DrawingPoint>,
    },
    Arrow {
        from: DrawingPoint,
        to: DrawingPoint,
    },
    Rectangle {
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    },
    Ellipse {
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    },
    Dot {
        x: f64,
        y: f64,
        size: f64,
    },
    ShadedRegion {
        points: Vec<DrawingPoint>,
    },
    Label {
        x: f64,
        y: f64,
        text: String,
        size: f64,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionDrawingStep {
    pub caption: String,
    pub primitives: Vec<DrawingPrimitive>,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum CompanionBody {
    Anchor {
        target: AnchorTarget,
    },
    MarginNote {
        anchor_id: Id,
        kind: MarginKind,
        text: String,
        reader: String,
        state: MarginState,
        sources: Vec<CompanionSource>,
    },
    ThreadMessage {
        margin_id: Id,
        order: u32,
        who: MessageWho,
        text: String,
        sources: Vec<CompanionSource>,
    },
    Picture {
        anchor_id: Id,
        title: String,
        svg: String,
    },
    Drawing {
        anchor_id: Id,
        title: String,
        width: f64,
        height: f64,
        steps: Vec<CompanionDrawingStep>,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionItemView {
    pub id: Id,
    pub author_id: Id,
    pub head: EntityHead,
    pub tombstone: bool,
    pub body: CompanionBody,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionSettings {
    pub tell: String,
    pub mode: CompanionMode,
    pub web: bool,
    pub active_reader: String,
    pub colour: InkColour,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionSettingsView {
    pub head: EntityHead,
    pub settings: CompanionSettings,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CompanionMutation {
    pub id: Id,
    pub base: Base,
    pub tombstone: bool,
    pub body: CompanionBody,
}
