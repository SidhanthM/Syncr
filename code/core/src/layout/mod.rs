//! Layout policy shared by the Mac and the phone. Pure functions over explicit inputs: the
//! platforms measure (text lines, card heights) and draw; the rules live here once.
mod margin;
#[cfg(test)]
mod margin_tests;
mod pages;
#[cfg(test)]
mod pages_tests;

pub use margin::*;
pub use pages::*;
