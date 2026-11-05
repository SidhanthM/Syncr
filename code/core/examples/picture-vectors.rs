//! Explicit golden-display regeneration; review diffs after changing the renderer.
fn main() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/picture-v2");
    for name in ["board", "call-tree", "forces"] {
        let svg = std::fs::read_to_string(root.join(format!("{name}.svg"))).unwrap();
        let clean = syncr_core::filter_picture(svg).unwrap();
        let display = syncr_core::picture_display_list(clean).unwrap();
        std::fs::write(
            root.join(format!("{name}.display.json")),
            serde_json::to_string_pretty(&display).unwrap() + "\n",
        )
        .unwrap();
    }
}
