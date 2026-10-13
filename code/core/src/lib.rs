//! Transactional offline Android notes. No sequencer-confirmed state is fabricated.
mod canonical;
#[cfg(test)]
mod dependency_tests;
mod error;
#[cfg(test)]
mod failure_tests;
mod library;
mod mesh;
mod reducer;
mod samples;
mod store;
mod types;
mod validation;

pub use error::{CoreError, CoreResult};
pub use library::{Note, SyncrLibrary, open_library};
pub use mesh::{
    MAX_MESH_VERTICES, MESH_RENDERER_VERSION, StrokeMesh, StrokeStyle, default_stroke_style,
    mesh_renderer_version, tessellate_stroke,
};
pub use samples::{decode_samples, encode_samples};
pub use types::*;

uniffi::setup_scaffolding!("syncr_core");

#[uniffi::export]
pub fn new_id() -> Id {
    ulid::Ulid::generate().to_string()
}

/// Page size in note units for a format, e.g. for library thumbnails.
#[uniffi::export]
pub fn page_size(format: PageFormat) -> PageSize {
    let (width, height) = format.dimensions();
    PageSize { width, height }
}
