//! Image bytes live outside operation JSON and the projection.
use crate::*;
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum ImageMediaType {
    Png,
    Jpeg,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum ImagePlacement {
    Canvas {
        sheet_id: Id,
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    },
    /// Under the text passage named by an existing companion text anchor.
    Text {
        anchor_id: Id,
        width: f64,
        height: f64,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ImageMarkView {
    pub id: Id,
    pub author_id: Id,
    pub head: EntityHead,
    pub media_type: ImageMediaType,
    pub pixel_width: u32,
    pub pixel_height: u32,
    pub placement: ImagePlacement,
    pub tombstone: bool,
    pub seen_by_companion: bool,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum ImageMutation {
    Add {
        image_id: Id,
        /// Omitted from log JSON; carried in canonical bytes and the images table.
        #[serde(skip)]
        bytes: Vec<u8>,
        media_type: ImageMediaType,
        pixel_width: u32,
        pixel_height: u32,
        placement: ImagePlacement,
    },
    Delete {
        image_id: Id,
        base: Base,
    },
    Restore {
        image_id: Id,
        base: Base,
    },
    Move {
        image_id: Id,
        base: Base,
        placement: ImagePlacement,
    },
    /// Agent-only; monotone, not undoable, and does not change the placement head.
    MarkSeen {
        image_id: Id,
        base: Base,
    },
}
