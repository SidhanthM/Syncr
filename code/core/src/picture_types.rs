//! Portable, browser-free picture boundary. Coordinates are in SVG canvas units.
#[derive(Clone, Debug, PartialEq, thiserror::Error, uniffi::Error)]
pub enum PictureError {
    #[error("Picture exceeds a resource limit")]
    Limit,
    #[error("Malformed picture document")]
    Malformed,
    #[error("Invalid picture canvas")]
    Canvas,
    #[error("Picture requires consecutive steps numbered 1 through 12")]
    Steps,
    #[error("Picture requires at least two drawn elements")]
    Empty,
    #[error("Picture contains cyclic or excessive references")]
    References,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PicturePoint {
    pub x: f32,
    pub y: f32,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PictureBounds {
    pub min_x: f32,
    pub min_y: f32,
    pub max_x: f32,
    pub max_y: f32,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum PictureItem {
    Polyline {
        points: Vec<PicturePoint>,
        closed: bool,
        stroke: String,
        stroke_width: f32,
        fill: String,
        bounds: PictureBounds,
    },
    Text {
        text: String,
        x: f32,
        y: f32,
        anchor: String,
        baseline: String,
        font: String,
        size: f32,
        fill: String,
        /// Linear transform of glyphs around (x,y); tx/ty are zero.
        transform: crate::Affine,
        bounds: PictureBounds,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PictureStep {
    pub number: u32,
    pub caption: String,
    pub hide: Vec<u32>,
    pub dim: Vec<u32>,
    pub items: Vec<PictureItem>,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PictureDisplayList {
    pub width: f32,
    pub height: f32,
    pub always: Vec<PictureItem>,
    pub steps: Vec<PictureStep>,
}
