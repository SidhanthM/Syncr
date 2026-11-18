//! OKLCH custom inks: the one conversion every device uses (`code/shared/colour/oklch-v1.json`
//! holds the shared vectors, checked by cargo, an Android unit test and a Mac check).
//!
//! OKLCH -> OKLab -> linear sRGB -> sRGB (Bjorn Ottosson's matrices). A colour outside the sRGB
//! gamut keeps its lightness and hue and loses chroma: the largest chroma that still fits is found
//! by bisection. Channels are never clipped to fit, which would shift the hue.
//!
//! Dark mode: a custom ink draws exactly as chosen in both themes, except a near-black or
//! near-white low-chroma ink, which would vanish on the other theme's paper and so draws in the
//! theme's own ink instead (see [`uses_theme_ink`]).
use crate::Oklch;

pub const MAX_CHROMA: f32 = 0.37;
/// Below this chroma, a very dark or very light ink is a grey that follows the theme.
const GREY_CHROMA: f32 = 0.04;
const DARK_LIMIT: f32 = 0.3;
const LIGHT_LIMIT: f32 = 0.92;
const BISECTIONS: u32 = 28;
/// Channel slack when testing gamut membership; absorbs f64 rounding at white and black.
const GAMUT_SLACK: f64 = 1e-6;

/// The sRGB colour an ink resolves to, and whether it should instead draw in the theme's ink.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct ResolvedInk {
    /// 0xAARRGGBB with alpha 0xff: the gamut-mapped colour as chosen.
    pub argb: u32,
    /// True for near-black / near-white low-chroma inks: draw `NotePalette.ink(dark)` instead.
    pub theme_ink: bool,
}

fn sanitise(ink: Oklch) -> (f64, f64, f64) {
    let fix = |v: f32, lo: f64, hi: f64| {
        if v.is_finite() {
            f64::from(v).clamp(lo, hi)
        } else {
            lo
        }
    };
    (
        fix(ink.l, 0.0, 1.0),
        fix(ink.c, 0.0, f64::from(MAX_CHROMA)),
        if ink.h.is_finite() {
            f64::from(ink.h).rem_euclid(360.0)
        } else {
            0.0
        },
    )
}

fn linear_srgb(l: f64, c: f64, h: f64) -> [f64; 3] {
    let (sin, cos) = h.to_radians().sin_cos();
    let (a, b) = (c * cos, c * sin);
    let l_ = l + 0.396_337_777_4 * a + 0.215_803_757_3 * b;
    let m_ = l - 0.105_561_345_8 * a - 0.063_854_172_8 * b;
    let s_ = l - 0.089_484_177_5 * a - 1.291_485_548_0 * b;
    let (l3, m3, s3) = (l_ * l_ * l_, m_ * m_ * m_, s_ * s_ * s_);
    [
        4.076_741_662_1 * l3 - 3.307_711_591_3 * m3 + 0.230_969_929_2 * s3,
        -1.268_438_004_6 * l3 + 2.609_757_401_1 * m3 - 0.341_319_396_5 * s3,
        -0.004_196_086_3 * l3 - 0.703_418_614_7 * m3 + 1.707_614_701_0 * s3,
    ]
}

fn in_gamut(rgb: [f64; 3]) -> bool {
    rgb.iter()
        .all(|&v| (-GAMUT_SLACK..=1.0 + GAMUT_SLACK).contains(&v))
}

fn encode_channel(v: f64) -> u32 {
    let v = v.clamp(0.0, 1.0); // rounding noise only: the colour is already inside the gamut
    let g = if v <= 0.003_130_8 {
        12.92 * v
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    };
    (g * 255.0).round().clamp(0.0, 255.0) as u32
}

fn mapped_chroma(l: f64, c: f64, h: f64) -> f64 {
    if l <= 0.0 || l >= 1.0 {
        return 0.0; // black and white have no chroma inside sRGB
    }
    if in_gamut(linear_srgb(l, c, h)) {
        return c;
    }
    let (mut lo, mut hi) = (0.0, c);
    for _ in 0..BISECTIONS {
        let mid = (lo + hi) / 2.0;
        if in_gamut(linear_srgb(l, mid, h)) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    lo
}

/// The same ink with chroma reduced, at fixed lightness and hue, until it fits sRGB. Out-of-range
/// input is clamped first (lightness 0..1, chroma 0..0.37, hue wrapped to 0..360).
#[uniffi::export]
pub fn oklch_gamut_map(ink: Oklch) -> Oklch {
    let (l, c, h) = sanitise(ink);
    Oklch {
        l: l as f32,
        c: mapped_chroma(l, c, h) as f32,
        h: h as f32,
    }
}

/// The largest chroma sRGB can show at this lightness and hue (at most 0.37). The picker draws
/// its gamut edge from this.
#[uniffi::export]
pub fn oklch_max_chroma(l: f32, h: f32) -> f32 {
    let (l, _, h) = sanitise(Oklch { l, c: 0.0, h });
    mapped_chroma(l, f64::from(MAX_CHROMA), h) as f32
}

/// The gamut-mapped sRGB colour of an ink as 0xFFRRGGBB.
#[uniffi::export]
pub fn oklch_to_srgb(ink: Oklch) -> u32 {
    let (l, c, h) = sanitise(ink);
    let [r, g, b] = linear_srgb(l, mapped_chroma(l, c, h), h);
    0xff00_0000 | encode_channel(r) << 16 | encode_channel(g) << 8 | encode_channel(b)
}

/// True for a near-black (L < 0.3) or near-white (L > 0.92) ink of low chroma (C < 0.04), the
/// inks that map to the theme's own ink so writing stays readable. Same rule in both themes.
#[uniffi::export]
pub fn oklch_uses_theme_ink(ink: Oklch) -> bool {
    let (l, c, _) = sanitise(ink);
    c < f64::from(GREY_CHROMA) && (l < f64::from(DARK_LIMIT) || l > f64::from(LIGHT_LIMIT))
}

/// Colour and theme rule in one call.
#[uniffi::export]
pub fn resolve_custom_ink(ink: Oklch) -> ResolvedInk {
    ResolvedInk {
        argb: oklch_to_srgb(ink),
        theme_ink: oklch_uses_theme_ink(ink),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: &str = include_str!("../../shared/colour/oklch-v1.json");

    fn ink(l: f64, c: f64, h: f64) -> Oklch {
        Oklch {
            l: l as f32,
            c: c as f32,
            h: h as f32,
        }
    }

    fn channels(argb: u32) -> [i32; 3] {
        [
            (argb >> 16 & 255) as i32,
            (argb >> 8 & 255) as i32,
            (argb & 255) as i32,
        ]
    }

    #[test]
    fn shared_vectors_hold() {
        let doc: serde_json::Value = serde_json::from_str(VECTORS).unwrap();
        let convert = doc["convert"].as_array().unwrap();
        assert!(convert.len() >= 20);
        for v in convert {
            let i = ink(
                v["l"].as_f64().unwrap(),
                v["c"].as_f64().unwrap(),
                v["h"].as_f64().unwrap(),
            );
            let want = u32::from_str_radix(v["srgb"].as_str().unwrap(), 16).unwrap();
            let got = oklch_to_srgb(i);
            // The reference is an independent implementation; one step per channel is rounding.
            for (g, w) in channels(got).iter().zip(channels(want)) {
                assert!((g - w).abs() <= 1, "{v}: got {got:08x}");
            }
            let mapped = oklch_gamut_map(i);
            assert!(
                (f64::from(mapped.c) - v["mapped_c"].as_f64().unwrap()).abs() < 2e-4,
                "{v}"
            );
            assert_eq!(mapped.l, i.l);
        }
        for v in doc["theme"].as_array().unwrap() {
            let i = ink(
                v["l"].as_f64().unwrap(),
                v["c"].as_f64().unwrap(),
                v["h"].as_f64().unwrap(),
            );
            assert_eq!(
                oklch_uses_theme_ink(i),
                v["theme_ink"].as_bool().unwrap(),
                "{v}"
            );
        }
    }

    #[test]
    fn gamut_mapping_keeps_lightness_and_hue_and_never_clips() {
        // A saturated cyan at mid lightness is far outside sRGB.
        let cyan = ink(0.7, 0.3, 200.0);
        let mapped = oklch_gamut_map(cyan);
        assert!(mapped.c < 0.3 && mapped.c > 0.05);
        assert_eq!((mapped.l, mapped.h), (cyan.l, cyan.h));
        // The mapped colour sits on the boundary: a hair more chroma is out of gamut.
        let more = linear_srgb(0.7, f64::from(mapped.c) + 0.002, 200.0);
        assert!(!in_gamut(more));
        // Colours already inside are untouched.
        let calm = ink(0.7, 0.05, 40.0);
        assert_eq!(oklch_gamut_map(calm).c, calm.c);
        // Hue of the mapped result matches the request (clipping would move it).
        let [r, g, b] = linear_srgb(0.7, f64::from(mapped.c), 200.0);
        assert!([r, g, b].iter().all(|v| *v > -1e-5 && *v < 1.0 + 1e-5));
    }

    #[test]
    fn extremes_and_hostile_input_are_safe() {
        assert_eq!(oklch_to_srgb(ink(0.0, 0.3, 90.0)), 0xff00_0000);
        assert_eq!(oklch_to_srgb(ink(1.0, 0.3, 90.0)), 0xffff_ffff);
        let nan = Oklch {
            l: f32::NAN,
            c: f32::INFINITY,
            h: f32::NAN,
        };
        assert_eq!(oklch_to_srgb(nan), 0xff00_0000);
        let wrapped = oklch_gamut_map(ink(0.5, 0.1, 725.0));
        assert!((wrapped.h - 5.0).abs() < 1e-3);
        assert!(oklch_max_chroma(0.5, 30.0) > 0.1);
        assert_eq!(oklch_max_chroma(0.0, 30.0), 0.0);
    }

    #[test]
    fn near_black_and_near_white_greys_follow_the_theme() {
        assert!(oklch_uses_theme_ink(ink(0.2, 0.01, 0.0)));
        assert!(oklch_uses_theme_ink(ink(0.95, 0.0, 0.0)));
        assert!(!oklch_uses_theme_ink(ink(0.2, 0.05, 0.0))); // dark but coloured
        assert!(!oklch_uses_theme_ink(ink(0.6, 0.0, 0.0))); // mid grey draws as chosen
        assert!(!oklch_uses_theme_ink(ink(0.3, 0.0, 0.0))); // boundary: not below 0.3
        assert!(!oklch_uses_theme_ink(ink(0.92, 0.0, 0.0))); // boundary: not above 0.92
        let resolved = resolve_custom_ink(ink(0.15, 0.0, 0.0));
        assert!(resolved.theme_ink);
        assert_eq!(resolved.argb >> 24, 0xff);
    }
}
