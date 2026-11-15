//! Layout policy shared by the Mac and the phone. Pure functions over explicit inputs: the
//! platforms measure (text lines, card heights) and draw; the rules live here once.
mod margin;
#[cfg(test)]
mod margin_tests;

pub use margin::*;
