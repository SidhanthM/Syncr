//! Syncr core: transactional offline notes behind a UniFFI boundary. Nothing here
//! fabricates sequencer-confirmed state. See README.md for the module map.
mod canonical;
#[cfg(test)]
mod canvas_tests;
mod commit;
mod companion;
#[cfg(test)]
mod companion_golden_tests;
#[cfg(test)]
mod companion_tests;
mod companion_types;
#[cfg(test)]
mod dependency_tests;
mod error;
#[cfg(test)]
mod failure_tests;
mod fault;
mod files;
mod image;
#[cfg(test)]
mod image_tests;
mod image_types;
mod layout;
mod library;
mod mesh;
mod picture;
mod picture_display;
mod picture_path;
#[cfg(test)]
mod picture_tests;
mod picture_types;
mod preview;
#[cfg(test)]
mod preview_tests;
mod reducer;
mod samples;
mod store;
#[cfg(test)]
mod sync_tests;
mod sync_types;
mod text;
#[cfg(test)]
mod text_tests;
mod types;
mod validation;

pub use commit::{StrokeCommit, StrokeReassembler, encode_stroke_commit};
pub use companion_types::*;
pub use error::{CoreError, CoreResult};
pub use image_types::*;
pub use layout::*;
pub use library::{Note, SyncrLibrary, open_library};
pub use mesh::{
    MAX_MESH_VERTICES, MESH_RENDERER_VERSION, StrokeMesh, StrokeStyle, default_stroke_style,
    mesh_renderer_version, tessellate_stroke,
};
pub use picture::{filter_picture, picture_display_list};
pub use picture_types::*;
pub use preview::{
    CoordinateSpace, FrameKind, PreviewBody, PreviewDropReason, PreviewEvent, PreviewFrame,
    PreviewLimits, PreviewReceiver, PreviewSendQueue, decode_preview_frame, encode_preview_frame,
    frame_kind, preview_limits,
};
pub use samples::{decode_samples, encode_samples};
pub use sync_types::*;
pub use types::*;

uniffi::setup_scaffolding!("syncr_core");

#[uniffi::export]
pub fn new_id() -> Id {
    ulid::Ulid::generate().to_string()
}

/// The canvas every note uses: 4800 units wide, a 4800 x 3000 Mac window, a 1600-unit
/// phone column.
#[uniffi::export]
pub fn canvas_size() -> CanvasSize {
    CanvasSize {
        width: CANVAS_WIDTH,
        view_height: CANVAS_VIEW_HEIGHT,
        phone_column: CANVAS_PHONE_COLUMN,
    }
}
