# Text v1 canonical vectors

`canonical.json` pins complete SYNO command bytes for all seven new text mutations (tags 5–11), including UTF-8, exact signed-zero float bits, styles and normalized duplicate dependencies. `src/text_tests.rs` checks these bytes and lengths against Rust.

`generate.py` is an independent standard-library Python encoder of the documented layout. Regenerate deliberately with `python3 fixtures/text-v1/generate.py` from `code/core/`; do not update expected bytes simply to match a changed Rust encoder. Existing fixture directories are untouched.
