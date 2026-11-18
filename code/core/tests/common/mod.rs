//! Helpers shared by the integration tests. Each test crate uses a subset.
#![allow(dead_code)]
use base64::{Engine, engine::general_purpose::STANDARD};
use std::path::Path;
use syncr_core::*;

/// A tempdir path as the `String` the API takes.
pub fn path(p: &Path) -> String {
    p.to_str().unwrap().into()
}
pub fn base(id: &str) -> Base {
    Base::Operation {
        operation_id: id.into(),
    }
}
pub fn brush() -> Brush {
    Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Ink)
}
/// A reviewed two-sample SYNS blob from the offline-v1 fixtures.
pub fn blob() -> Vec<u8> {
    STANDARD
        .decode("U1lOUwEADwACAAAAAACAPwAAAIAAAABAAABAwAAAAAAAAIA/AAAAACoAAAA=")
        .unwrap()
}
/// A creation request with fresh IDs.
pub fn request(notebook: Option<&str>) -> CreateNoteRequest {
    CreateNoteRequest {
        operation_id: new_id(),
        note_id: new_id(),
        first_sheet_id: new_id(),
        title: "Note".into(),
        notebook: notebook.map(Into::into),
    }
}
