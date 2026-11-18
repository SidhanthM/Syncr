//! A stroke's brush on the wire and in canonical operation bytes: one encoding for both.
//!
//! Plain brushes are the original three bytes, so every stored operation keeps its identity.
//! Additive parts, all new tags:
//!
//! - family byte: Pen/Fountain `0`, Highlighter `1`, Pen/Ballpoint `2`, Pen/Fineliner `3`; bit
//!   `0x10` says a feel block follows the colour.
//! - size byte: Small `0`, Medium `1`, Large `2`.
//! - colour byte: named Ink `0`, Red `1`, Green `2`, Blue `3`, Orange `4`.
//! - feel block: pressure u8, smoothing u8, taper u8 (0 or 1).
use crate::{
    Brush, BrushFamily, BrushSize, CoreResult, InkColour, PenFeel, PenLook, error::invalid,
    preview::Reader,
};

const FEEL_FLAG: u8 = 0x10;
pub(crate) const MAX_BRUSH_BYTES: usize = 3 + 3;

impl Brush {
    /// A plain stroke brush: Fountain look, named ink, default feel.
    pub fn plain(family: BrushFamily, size: BrushSize, colour: InkColour) -> Brush {
        Brush {
            family,
            size,
            colour,
            look: None,
            feel: None,
        }
    }
    /// The look this stroke is drawn in (Fountain when absent).
    pub fn pen_look(&self) -> PenLook {
        self.look.unwrap_or(PenLook::Fountain)
    }
    /// True when this brush needs more than the original three bytes.
    pub(crate) fn is_extended(&self) -> bool {
        self.pen_look() != PenLook::Fountain || self.feel.is_some()
    }
}

pub(crate) fn validate(b: &Brush) -> CoreResult<()> {
    if b.family == BrushFamily::Highlighter && b.pen_look() != PenLook::Fountain {
        return Err(invalid("Only a Pen stroke has a pen look"));
    }
    if let Some(feel) = &b.feel
        && (feel.pressure > 100 || feel.smoothing > 100)
    {
        return Err(invalid("Pen feel is outside 0..100"));
    }
    Ok(())
}

pub(crate) fn encode(b: &Brush, out: &mut Vec<u8>) {
    let family = match (&b.family, b.pen_look()) {
        (BrushFamily::Highlighter, _) => 1,
        (BrushFamily::Pen, PenLook::Fountain) => 0,
        (BrushFamily::Pen, PenLook::Ballpoint) => 2,
        (BrushFamily::Pen, PenLook::Fineliner) => 3,
    };
    out.push(family | if b.feel.is_some() { FEEL_FLAG } else { 0 });
    out.push(match b.size {
        BrushSize::Small => 0,
        BrushSize::Medium => 1,
        BrushSize::Large => 2,
    });
    out.push(match b.colour {
        InkColour::Ink => 0,
        InkColour::Red => 1,
        InkColour::Green => 2,
        InkColour::Blue => 3,
        InkColour::Orange => 4,
    });
    if let Some(feel) = &b.feel {
        out.extend_from_slice(&[feel.pressure, feel.smoothing, u8::from(feel.taper)]);
    }
}

pub(crate) fn decode(r: &mut Reader) -> CoreResult<Brush> {
    let first = r.u8()?;
    let feel_present = first & FEEL_FLAG != 0;
    let (family, look) = match first & !FEEL_FLAG {
        0 => (BrushFamily::Pen, None),
        1 => (BrushFamily::Highlighter, None),
        2 => (BrushFamily::Pen, Some(PenLook::Ballpoint)),
        3 => (BrushFamily::Pen, Some(PenLook::Fineliner)),
        _ => return Err(invalid("Unknown brush family")),
    };
    let size = match r.u8()? {
        0 => BrushSize::Small,
        1 => BrushSize::Medium,
        2 => BrushSize::Large,
        _ => return Err(invalid("Unknown brush size")),
    };
    let colour = match r.u8()? {
        0 => InkColour::Ink,
        1 => InkColour::Red,
        2 => InkColour::Green,
        3 => InkColour::Blue,
        4 => InkColour::Orange,
        _ => return Err(invalid("Unknown brush colour")),
    };
    let feel = if feel_present {
        let (pressure, smoothing, taper) = (r.u8()?, r.u8()?, r.u8()?);
        if taper > 1 {
            return Err(invalid("Unknown pen feel"));
        }
        Some(PenFeel {
            pressure,
            smoothing,
            taper: taper == 1,
        })
    } else {
        None
    };
    let brush = Brush {
        family,
        size,
        colour,
        look,
        feel,
    };
    validate(&brush)?;
    Ok(brush)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(b: &Brush) -> Vec<u8> {
        let mut out = Vec::new();
        encode(b, &mut out);
        let back = decode(&mut Reader::new(&out)).unwrap();
        assert_eq!(&back, b);
        out
    }

    #[test]
    fn plain_brushes_keep_their_three_original_bytes() {
        let b = Brush::plain(
            BrushFamily::Highlighter,
            BrushSize::Large,
            InkColour::Orange,
        );
        assert_eq!(roundtrip(&b), vec![1, 2, 4]);
        let b = Brush::plain(BrushFamily::Pen, BrushSize::Small, InkColour::Ink);
        assert_eq!(roundtrip(&b), vec![0, 0, 0]);
    }

    #[test]
    fn fountain_spelled_out_is_the_same_bytes_as_absent() {
        let mut b = Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Blue);
        let plain = roundtrip(&b);
        b.look = Some(PenLook::Fountain);
        let mut out = Vec::new();
        encode(&b, &mut out);
        assert_eq!(out, plain);
    }

    #[test]
    fn looks_and_feel_are_additive_tags() {
        let mut b = Brush::plain(BrushFamily::Pen, BrushSize::Medium, InkColour::Ink);
        b.look = Some(PenLook::Ballpoint);
        assert_eq!(roundtrip(&b), vec![2, 1, 0]);
        b.look = Some(PenLook::Fineliner);
        b.feel = Some(PenFeel {
            pressure: 50,
            smoothing: 50,
            taper: true,
        });
        assert_eq!(roundtrip(&b), vec![0x13, 1, 0, 50, 50, 1]);
        assert_eq!(roundtrip(&b).len(), MAX_BRUSH_BYTES);
    }

    #[test]
    fn hostile_brushes_are_refused() {
        for bytes in [
            vec![4u8, 0, 0],
            vec![0, 3, 0],
            vec![0, 0, 6],
            vec![0x10, 0, 0, 1, 1, 2],
            vec![0x10, 0, 0, 101, 0, 0],
            vec![0x11, 0, 0, 0, 0, 0], // highlighter feel is fine, but...
        ] {
            let result = decode(&mut Reader::new(&bytes));
            if bytes[0] == 0x11 {
                assert!(result.is_ok());
            } else {
                assert!(result.is_err(), "{bytes:?}");
            }
        }
        let mut hl = Brush::plain(BrushFamily::Highlighter, BrushSize::Small, InkColour::Ink);
        hl.look = Some(PenLook::Ballpoint);
        assert!(validate(&hl).is_err());
    }
}
