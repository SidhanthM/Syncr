//! Stroke mesh (`mac-api.md` §1): turns stored samples plus a [`StrokeStyle`] into triangles for
//! hosts without an ink engine (the Mac draws them with Metal). Pure and stateless.
//!
//! Model, following the phone's AndroidX Ink brush built from the same style: the stroke is the
//! union of the convex hulls of consecutive tip shapes along the modeled path. Steps:
//!
//! 1. Decode and validate exactly like `decode_samples`; drop repeated (x, y, time) samples, which
//!    the phone's renderer also drops.
//! 2. Upsample gaps longer than `1 / upsampling_hz` (and long spatial gaps), then smooth position and
//!    pressure with a centred sliding time window of `smoothing_window_ms`, shrunk at the ends so the
//!    stroke keeps its first and last points.
//! 3. Per point size multiplier = pressure (smoothed, time-damped, eased by the phone's pressure
//!    curve, mapped to `pressure_low..pressure_high`) x speed thinning (time-damped 30 ms like the
//!    phone) x start/end tapers (gated by the distance to the other end, so a tap is a full dot),
//!    clamped to [0, 2] like Ink.
//! 4. Drop points whose removal changes the outline by at most `epsilon`.
//! 5. The tip is a rounded rectangle (`tip_scale_x/y` x size, `corner_rounding`, rotated by
//!    `tip_rotation_degrees`; a circle for pens, a flat chisel for the highlighter), polygonised so
//!    arcs deviate at most `epsilon`. Each consecutive pair of tips emits its convex hull as a
//!    triangle fan; a single point emits one tip (a dot). Round tips therefore give round joins and
//!    caps. Triangles of neighbouring hulls overlap; that is why translucent ink sets
//!    `discard_self_overlap`.
//! 6. Vertices are transformed by the affine and written as f32.
//!
//! Determinism: all arithmetic is IEEE-754 f64 using only +, -, *, /, sqrt, floor/ceil and
//! comparisons, which are correctly rounded on every platform; exp/sin/cos are evaluated by fixed
//! series here instead of the platform libm, and sorting uses a total order. Rust never contracts
//! a*b+c into an FMA implicitly.
//!
//! Interpretations: `epsilon` is measured in the stroke's own (pre-transform) units; the transform
//! may flip winding, so renderers should not cull back faces.
use crate::{
    Affine, Brush, BrushFamily, BrushSize, CoreResult,
    error::{invalid, limit},
    samples,
};

/// Bumped whenever the output for some input changes. A mesh is a disposable cache.
pub const MESH_RENDERER_VERSION: u32 = 1;
pub const MAX_MESH_VERTICES: usize = 4_000_000;

/// How one stroke is drawn; the same description the phone derives in `InkStyles.style()`. Lengths
/// are in note units unless named "sizes" (multiples of `size`).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct StrokeStyle {
    pub size: f32,
    pub epsilon: f32,
    pub pressure_low: f32,
    pub pressure_high: f32,
    pub pressure_damping_seconds: f32,
    pub speed_thinning: f32,
    pub speed_start_sizes_per_second: f32,
    pub speed_end_sizes_per_second: f32,
    pub taper_start: f32,
    pub taper_start_sizes: f32,
    pub taper_end: f32,
    pub taper_end_sizes: f32,
    pub smoothing_window_ms: u32,
    pub upsampling_hz: u32,
    pub tip_scale_x: f32,
    pub tip_scale_y: f32,
    pub corner_rounding: f32,
    pub tip_rotation_degrees: f32,
    pub discard_self_overlap: bool,
}

/// Triangles for one stroke. `vertices` are little-endian f32 (x, y) pairs with the transform
/// applied; `indices` are little-endian u32, three per triangle.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct StrokeMesh {
    pub vertices: Vec<u8>,
    pub indices: Vec<u8>,
    pub min_x: f32,
    pub min_y: f32,
    pub max_x: f32,
    pub max_y: f32,
    pub discard_self_overlap: bool,
    pub renderer_version: u32,
}

// Phone constants (InkBrushes.kt / NoteCanvasView.kt), transcribed in f32 so values match bit for bit.
const EPSILON: f32 = 0.05;
const UPSAMPLING_HZ: u32 = 180;
const SPEED_START_UNITS: f32 = 900.0;
const SPEED_END_UNITS: f32 = 4500.0;
const PRESSURE_CURVE: [f32; 4] = [0.25, 0.5, 0.6, 1.0];
const SPEED_DAMPING_SECONDS: f64 = 0.03;
// Default InkPreferences: FOUNTAIN look, pressure 0.5, smoothing 0.5, taper on.
const DEFAULT_PRESSURE: f32 = 0.5;
const DEFAULT_SMOOTHING: f32 = 0.5;
const FOUNTAIN_PRESSURE: f32 = 0.6;
const FOUNTAIN_SPEED_THINNING: f32 = 0.82;
const FOUNTAIN_TAPER_START: f32 = 0.22;
const FOUNTAIN_TAPER_END: f32 = 0.18;
const FOUNTAIN_TAPER_START_UNITS: f32 = 18.0;
const FOUNTAIN_TAPER_END_UNITS: f32 = 16.0;
const FOUNTAIN_TAPER_PER_SIZE: f32 = 2.0;

const MAX_INSERTED_PER_GAP: usize = 64;
const MAX_ARC_SEGMENTS: usize = 64;
const SIMPLIFY_LOOKAHEAD: usize = 32;

fn brush_width(brush: &Brush) -> f32 {
    let base = match brush.size {
        BrushSize::Small => 2.0,
        BrushSize::Medium => 4.0,
        BrushSize::Large => 8.0,
    };
    base * if brush.family == BrushFamily::Highlighter {
        5.0
    } else {
        1.0
    }
}

/// The phone's default look for a brush: `InkStyles.style(brush, InkPreferences())`.
#[uniffi::export]
pub fn default_stroke_style(brush: Brush) -> StrokeStyle {
    let size = brush_width(&brush);
    let window = (6.0_f32 + DEFAULT_SMOOTHING * 28.0).round() as u32;
    if brush.family == BrushFamily::Highlighter {
        return StrokeStyle {
            size,
            epsilon: EPSILON,
            pressure_low: 1.0,
            pressure_high: 1.0,
            pressure_damping_seconds: 0.0,
            speed_thinning: 1.0,
            speed_start_sizes_per_second: 0.0,
            speed_end_sizes_per_second: 1.0,
            taper_start: 1.0,
            taper_start_sizes: 0.0,
            taper_end: 1.0,
            taper_end_sizes: 0.0,
            smoothing_window_ms: window,
            upsampling_hz: UPSAMPLING_HZ,
            tip_scale_x: 0.4,
            tip_scale_y: 1.0,
            corner_rounding: 0.3,
            tip_rotation_degrees: 0.0,
            discard_self_overlap: true,
        };
    }
    let gain = DEFAULT_PRESSURE * 2.0; // pressureGain(0.5) == 1
    let amount = FOUNTAIN_PRESSURE * gain;
    StrokeStyle {
        size,
        epsilon: EPSILON,
        pressure_low: (1.0 - amount).max(0.12),
        pressure_high: (1.0 + amount).min(2.0),
        pressure_damping_seconds: 0.006 + 0.024 * DEFAULT_SMOOTHING,
        speed_thinning: FOUNTAIN_SPEED_THINNING,
        speed_start_sizes_per_second: SPEED_START_UNITS / size,
        speed_end_sizes_per_second: SPEED_END_UNITS / size,
        taper_start: FOUNTAIN_TAPER_START,
        taper_start_sizes: (FOUNTAIN_TAPER_START_UNITS + FOUNTAIN_TAPER_PER_SIZE * size) / size,
        taper_end: FOUNTAIN_TAPER_END,
        taper_end_sizes: (FOUNTAIN_TAPER_END_UNITS + FOUNTAIN_TAPER_PER_SIZE * size) / size,
        smoothing_window_ms: window,
        upsampling_hz: UPSAMPLING_HZ,
        tip_scale_x: 1.0,
        tip_scale_y: 1.0,
        corner_rounding: 1.0,
        tip_rotation_degrees: 0.0,
        discard_self_overlap: false,
    }
}

#[uniffi::export]
pub fn mesh_renderer_version() -> u32 {
    MESH_RENDERER_VERSION
}

/// Triangulates one stored stroke. Invalid samples, style or transform give `InvalidInput`; a mesh
/// over [`MAX_MESH_VERTICES`] gives `ResourceLimit`.
#[uniffi::export]
pub fn tessellate_stroke(
    samples: Vec<u8>,
    style: StrokeStyle,
    transform: Affine,
) -> CoreResult<StrokeMesh> {
    tessellate(&samples, &style, &transform)
}

fn validate_style(s: &StrokeStyle) -> CoreResult<()> {
    let floats = [
        s.size,
        s.epsilon,
        s.pressure_low,
        s.pressure_high,
        s.pressure_damping_seconds,
        s.speed_thinning,
        s.speed_start_sizes_per_second,
        s.speed_end_sizes_per_second,
        s.taper_start,
        s.taper_start_sizes,
        s.taper_end,
        s.taper_end_sizes,
        s.tip_scale_x,
        s.tip_scale_y,
        s.corner_rounding,
        s.tip_rotation_degrees,
    ];
    if floats.iter().any(|x| !x.is_finite()) {
        return Err(invalid("Stroke style values must be finite"));
    }
    if s.size <= 0.0 || s.epsilon <= 0.0 || s.tip_scale_x <= 0.0 || s.tip_scale_y <= 0.0 {
        return Err(invalid(
            "Stroke size, epsilon and tip scales must be positive",
        ));
    }
    let nonnegative = [
        s.pressure_low,
        s.pressure_high,
        s.pressure_damping_seconds,
        s.speed_thinning,
        s.taper_start,
        s.taper_start_sizes,
        s.taper_end,
        s.taper_end_sizes,
    ];
    if nonnegative.iter().any(|x| *x < 0.0) {
        return Err(invalid(
            "Stroke style multipliers and lengths must not be negative",
        ));
    }
    Ok(())
}

fn tessellate(blob: &[u8], style: &StrokeStyle, transform: &Affine) -> CoreResult<StrokeMesh> {
    let channels = samples::decode(blob)?;
    validate_style(style)?;
    let m = transform;
    if ![m.a, m.b, m.c, m.d, m.tx, m.ty]
        .iter()
        .all(|x| x.is_finite())
    {
        return Err(invalid("Transform must be finite"));
    }
    let raw = dedupe(&channels);
    let points = model(&raw, style)?;
    let tip = Tip::new(style, points.iter().map(|p| p.m).fold(0.0, f64::max));
    let kept = simplify(&points, f64::from(style.epsilon), tip.extent);
    emit(&points, &kept, &tip, style, transform)
}

/// One raw sample: time in ms relative to the first sample, then x, y, pressure.
type Raw = (f64, [f64; 3]);

/// Drops repeated (x, y, time) triplets, keeping the first, exactly as the phone's renderer input.
/// Times are nondecreasing, so a repeat can only occur within a run of equal times.
fn dedupe(c: &crate::SampleChannels) -> Vec<Raw> {
    let t0 = c.elapsed_ms[0];
    let mut out: Vec<Raw> = Vec::with_capacity(c.x.len());
    let mut run_start = 0;
    for i in 0..c.x.len() {
        let t = f64::from(c.elapsed_ms[i] - t0);
        let v = [
            f64::from(c.x[i]),
            f64::from(c.y[i]),
            f64::from(c.pressure[i]),
        ];
        if out.last().is_some_and(|(lt, _)| *lt != t) {
            run_start = out.len();
        }
        if out[run_start..]
            .iter()
            .any(|(_, w)| w[0] == v[0] && w[1] == v[1])
        {
            continue;
        }
        out.push((t, v));
    }
    out
}

/// A modeled path point: centre and size multiplier.
#[derive(Clone, Copy)]
struct Node {
    c: [f64; 2],
    m: f64,
}

fn lerp3(a: [f64; 3], b: [f64; 3], f: f64) -> [f64; 3] {
    [
        a[0] + (b[0] - a[0]) * f,
        a[1] + (b[1] - a[1]) * f,
        a[2] + (b[2] - a[2]) * f,
    ]
}

fn dist(a: [f64; 2], b: [f64; 2]) -> f64 {
    let (dx, dy) = (b[0] - a[0], b[1] - a[1]);
    (dx * dx + dy * dy).sqrt()
}

/// Running integral of the piecewise-linear raw path over time, for window averages.
struct Integral<'a> {
    raw: &'a [Raw],
    acc: Vec<[f64; 3]>,
}

impl<'a> Integral<'a> {
    fn new(raw: &'a [Raw]) -> Self {
        let mut acc = Vec::with_capacity(raw.len());
        let mut sum = [0.0; 3];
        acc.push(sum);
        for w in raw.windows(2) {
            let len = w[1].0 - w[0].0;
            for (k, s) in sum.iter_mut().enumerate() {
                *s += len * (w[0].1[k] + w[1].1[k]) * 0.5;
            }
            acc.push(sum);
        }
        Integral { raw, acc }
    }

    fn at(&self, t: f64) -> [f64; 3] {
        let raw = self.raw;
        let i = raw.partition_point(|(x, _)| *x <= t).saturating_sub(1);
        if i + 1 >= raw.len() {
            return self.acc[raw.len() - 1];
        }
        let (ti, vi) = raw[i];
        let dt = t - ti;
        let vt = lerp3(vi, raw[i + 1].1, dt / (raw[i + 1].0 - ti));
        let mut out = self.acc[i];
        for (k, o) in out.iter_mut().enumerate() {
            *o += dt * (vi[k] + vt[k]) * 0.5;
        }
        out
    }
}

/// Upsamples, smooths and computes the size multiplier for each point of the modeled path.
fn model(raw: &[Raw], s: &StrokeStyle) -> CoreResult<Vec<Node>> {
    let size = f64::from(s.size);
    let eps = f64::from(s.epsilon);
    let period = if s.upsampling_hz > 0 {
        1000.0 / f64::from(s.upsampling_hz)
    } else {
        f64::INFINITY
    };
    let spatial_step = (size * 0.5).max(4.0 * eps);
    // Upsample: (time, linearly interpolated raw value).
    let mut timed: Vec<Raw> = Vec::new();
    for (i, &(t, v)) in raw.iter().enumerate() {
        timed.push((t, v));
        let Some(&(tn, vn)) = raw.get(i + 1) else {
            break;
        };
        let gap = tn - t;
        let by_time = if gap > period {
            (gap / period).ceil() - 1.0
        } else {
            0.0
        };
        let d = dist([v[0], v[1]], [vn[0], vn[1]]);
        let by_space = if d > spatial_step {
            (d / spatial_step).ceil() - 1.0
        } else {
            0.0
        };
        let inserted = by_time.max(by_space).min(MAX_INSERTED_PER_GAP as f64) as usize;
        for j in 1..=inserted {
            let f = j as f64 / (inserted + 1) as f64;
            timed.push((t + gap * f, lerp3(v, vn, f)));
        }
        if timed.len() > MAX_MESH_VERTICES {
            return Err(limit("Stroke mesh exceeds the vertex limit"));
        }
    }
    // Smooth with a centred window, shrunk at both ends so the end points stay put.
    let end = raw[raw.len() - 1].0;
    let half_window = f64::from(s.smoothing_window_ms) * 0.5;
    if half_window > 0.0 && end > 0.0 {
        let integral = Integral::new(raw);
        for p in timed.iter_mut() {
            let half = half_window.min(p.0).min(end - p.0);
            if half > 0.0 {
                let (a, b) = (integral.at(p.0 - half), integral.at(p.0 + half));
                for k in 0..3 {
                    p.1[k] = (b[k] - a[k]) / (2.0 * half);
                }
            }
        }
    }
    let centres: Vec<[f64; 2]> = timed.iter().map(|(_, v)| [v[0], v[1]]).collect();
    let mut traveled = Vec::with_capacity(centres.len());
    let mut total = 0.0;
    for (i, c) in centres.iter().enumerate() {
        if i > 0 {
            total += dist(centres[i - 1], *c);
        }
        traveled.push(total);
    }
    // Speed in sizes per second, backward difference; the first point takes the second's speed.
    let mut speeds = vec![0.0; timed.len()];
    for i in 1..timed.len() {
        let dt = (timed[i].0 - timed[i - 1].0) / 1000.0;
        speeds[i] = if dt > 0.0 {
            (traveled[i] - traveled[i - 1]) / dt / size
        } else {
            speeds[i - 1]
        };
    }
    if speeds.len() > 1 {
        speeds[0] = speeds[1];
    }

    let pressure_sensitive = s.pressure_high > s.pressure_low;
    let (low, high) = (f64::from(s.pressure_low), f64::from(s.pressure_high));
    let thinning = f64::from(s.speed_thinning);
    let (speed_start, speed_end) = (
        f64::from(s.speed_start_sizes_per_second),
        f64::from(s.speed_end_sizes_per_second),
    );
    let mut pressure = Damped::new(f64::from(s.pressure_damping_seconds));
    let mut speed = Damped::new(SPEED_DAMPING_SECONDS);
    let mut nodes = Vec::with_capacity(timed.len());
    for (i, &(t, v)) in timed.iter().enumerate() {
        let mut m = 1.0;
        let p = pressure.next(t, v[2].clamp(0.0, 1.0));
        if pressure_sensitive {
            m *= low + (high - low) * pressure_curve(p);
        }
        let sp = speed.next(t, speeds[i]);
        if thinning < 1.0 {
            m *= 1.0 + (thinning - 1.0) * unit_ramp(sp, speed_start, speed_end);
        }
        let gone = traveled[i] / size;
        let left = (total - traveled[i]) / size;
        m *= taper(
            f64::from(s.taper_start),
            f64::from(s.taper_start_sizes),
            gone,
            left,
        );
        m *= taper(
            f64::from(s.taper_end),
            f64::from(s.taper_end_sizes),
            left,
            gone,
        );
        nodes.push(Node {
            c: centres[i],
            m: m.clamp(0.0, 2.0),
        });
    }
    Ok(nodes)
}

/// Clamped linear 0..1 ramp from `start` to `end` (either direction), like Ink's clamped source.
fn unit_ramp(x: f64, start: f64, end: f64) -> f64 {
    if start == end {
        return if x >= end { 1.0 } else { 0.0 };
    }
    ((x - start) / (end - start)).clamp(0.0, 1.0)
}

/// The phone's taper: `near` (in sizes) ramps the effect from full to none over `sizes`, gated by
/// `far` reaching 0.6 x `sizes` so short strokes taper only a little and taps not at all.
fn taper(at: f64, sizes: f64, near: f64, far: f64) -> f64 {
    if at >= 1.0 || sizes <= 0.0 {
        return 1.0;
    }
    let effect = unit_ramp(near, sizes, 0.0) * unit_ramp(far, 0.0, sizes * 0.6);
    1.0 + (at - 1.0) * effect
}

/// Ink's time damping node: moves toward the target by 1 - exp(-dt / gap) per step.
struct Damped {
    gap: f64,
    last: Option<(f64, f64)>,
}

impl Damped {
    fn new(gap_seconds: f64) -> Self {
        Damped {
            gap: gap_seconds,
            last: None,
        }
    }

    fn next(&mut self, t_ms: f64, target: f64) -> f64 {
        let value = match self.last {
            Some((t0, v)) if self.gap > 0.0 => {
                v + (target - v) * (1.0 - exp_neg((t_ms - t0) / 1000.0 / self.gap))
            }
            _ => target,
        };
        self.last = Some((t_ms, value));
        value
    }
}

/// e^-x for x >= 0 from a fixed series, so results do not depend on the platform libm.
fn exp_neg(x: f64) -> f64 {
    if x <= 0.0 {
        return 1.0;
    }
    if x > 700.0 {
        return 0.0;
    }
    let (mut r, mut squarings) = (x, 0);
    while r > 0.125 {
        r *= 0.5;
        squarings += 1;
    }
    let (mut term, mut sum) = (1.0, 1.0);
    for i in 1..=16 {
        term *= -r / f64::from(i);
        sum += term;
    }
    for _ in 0..squarings {
        sum *= sum;
    }
    sum
}

/// (sin x, cos x) for |x| <= pi from fixed Taylor series.
fn sin_cos(x: f64) -> (f64, f64) {
    let (mut s, mut c) = (0.0, 0.0);
    let mut term = 1.0; // x^n / n!
    for n in 0..48 {
        match n % 4 {
            0 => c += term,
            1 => s += term,
            2 => c -= term,
            _ => s -= term,
        }
        term *= x / f64::from(n + 1);
    }
    (s, c)
}

/// The phone's ease-out pressure response (CSS cubic Bézier), solved by bisection like the phone.
fn pressure_curve(p: f64) -> f64 {
    let [x1, y1, x2, y2] = PRESSURE_CURVE.map(f64::from);
    let bezier = |a: f64, b: f64, t: f64| {
        3.0 * (1.0 - t) * (1.0 - t) * t * a + 3.0 * (1.0 - t) * t * t * b + t * t * t
    };
    let x = p.clamp(0.0, 1.0);
    let (mut lo, mut hi) = (0.0, 1.0);
    for _ in 0..50 {
        let t = (lo + hi) / 2.0;
        if bezier(x1, x2, t) < x {
            lo = t
        } else {
            hi = t
        }
    }
    bezier(y1, y2, (lo + hi) / 2.0)
}

/// The tip outline at size multiplier 1, centred on the origin and rotated.
struct Tip {
    outline: Vec<[f64; 2]>,
    /// Farthest outline point from the centre, for simplification error.
    extent: f64,
}

impl Tip {
    fn new(s: &StrokeStyle, max_multiplier: f64) -> Self {
        let size = f64::from(s.size);
        let (hw, hh) = (
            size * f64::from(s.tip_scale_x) * 0.5,
            size * f64::from(s.tip_scale_y) * 0.5,
        );
        let r = f64::from(s.corner_rounding).clamp(0.0, 1.0) * hw.min(hh);
        let eps = f64::from(s.epsilon);
        // Segments per quarter arc so the chord sagitta at the largest size stays within epsilon.
        let mut n = 1;
        let r_max = r * max_multiplier.max(1e-9);
        while n < MAX_ARC_SEGMENTS
            && r_max * (1.0 - sin_cos(std::f64::consts::PI / (4.0 * n as f64)).1) > eps
        {
            n += 1;
        }
        let arc: Vec<(f64, f64)> = if r > 0.0 {
            (0..=n)
                .map(|k| sin_cos(std::f64::consts::FRAC_PI_2 * k as f64 / n as f64))
                .map(|(sin, cos)| (cos, sin))
                .collect()
        } else {
            vec![(0.0, 0.0)]
        };
        let mut outline = Vec::new();
        // Corners counter-clockwise from (+x, +y); each quarter arc turns the previous one by 90°.
        for (qx, qy, quarter) in [
            (1.0, 1.0, 0),
            (-1.0, 1.0, 1),
            (-1.0, -1.0, 2),
            (1.0, -1.0, 3),
        ] {
            let (cx, cy) = (qx * (hw - r), qy * (hh - r));
            for &(cos, sin) in &arc {
                let (ux, uy) = match quarter {
                    0 => (cos, sin),
                    1 => (-sin, cos),
                    2 => (-cos, -sin),
                    _ => (sin, -cos),
                };
                outline.push([cx + r * ux, cy + r * uy]);
            }
        }
        let mut degrees = f64::from(s.tip_rotation_degrees) % 360.0;
        if degrees > 180.0 {
            degrees -= 360.0;
        } else if degrees < -180.0 {
            degrees += 360.0;
        }
        if degrees != 0.0 {
            let (sin, cos) = sin_cos(degrees * (std::f64::consts::PI / 180.0));
            for p in outline.iter_mut() {
                *p = [p[0] * cos - p[1] * sin, p[0] * sin + p[1] * cos];
            }
        }
        let extent = outline
            .iter()
            .map(|p| (p[0] * p[0] + p[1] * p[1]).sqrt())
            .fold(0.0, f64::max);
        Tip { outline, extent }
    }

    fn place(&self, node: Node, out: &mut Vec<[f64; 2]>) {
        out.extend(
            self.outline
                .iter()
                .map(|p| [node.c[0] + p[0] * node.m, node.c[1] + p[1] * node.m]),
        );
    }
}

/// Indices of the points to keep: dropping the others moves the outline by at most `eps`, because
/// a tip between two kept tips (interpolated centre and size) lies inside their hull.
fn simplify(nodes: &[Node], eps: f64, extent: f64) -> Vec<usize> {
    let error = |a: Node, b: Node, k: Node| {
        let (dx, dy) = (b.c[0] - a.c[0], b.c[1] - a.c[1]);
        let len2 = dx * dx + dy * dy;
        let f = if len2 > 0.0 {
            (((k.c[0] - a.c[0]) * dx + (k.c[1] - a.c[1]) * dy) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let on = [a.c[0] + dx * f, a.c[1] + dy * f];
        dist(on, k.c) + (k.m - (a.m + (b.m - a.m) * f)).abs() * extent
    };
    let mut kept = vec![0];
    let mut a = 0;
    while a + 1 < nodes.len() {
        let mut best = a + 1;
        let mut j = a + 2;
        while j < nodes.len()
            && j - a <= SIMPLIFY_LOOKAHEAD
            && (a + 1..j).all(|k| error(nodes[a], nodes[j], nodes[k]) <= eps)
        {
            best = j;
            j += 1;
        }
        kept.push(best);
        a = best;
    }
    kept
}

fn cross(o: [f64; 2], a: [f64; 2], b: [f64; 2]) -> f64 {
    (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
}

/// Counter-clockwise convex hull (Andrew's monotone chain), without repeated or collinear points.
fn hull(points: &mut [[f64; 2]], out: &mut Vec<[f64; 2]>) {
    out.clear();
    points.sort_by(|p, q| p[0].total_cmp(&q[0]).then(p[1].total_cmp(&q[1])));
    for pass in 0..2 {
        let start = out.len();
        let iter: Box<dyn Iterator<Item = &[f64; 2]>> = if pass == 0 {
            Box::new(points.iter())
        } else {
            Box::new(points.iter().rev())
        };
        for &p in iter {
            while out.len() >= start + 2 && cross(out[out.len() - 2], out[out.len() - 1], p) <= 0.0
            {
                out.pop();
            }
            out.push(p);
        }
        out.pop();
    }
}

fn emit(
    nodes: &[Node],
    kept: &[usize],
    tip: &Tip,
    style: &StrokeStyle,
    m: &Affine,
) -> CoreResult<StrokeMesh> {
    let mut vertices: Vec<u8> = Vec::new();
    let mut indices: Vec<u8> = Vec::new();
    let mut count = 0usize;
    let (mut min_x, mut min_y, mut max_x, mut max_y) = (
        f32::INFINITY,
        f32::INFINITY,
        f32::NEG_INFINITY,
        f32::NEG_INFINITY,
    );
    let mut push_vertex = |p: [f64; 2], vertices: &mut Vec<u8>| -> CoreResult<()> {
        let x = (m.a * p[0] + m.c * p[1] + m.tx) as f32;
        let y = (m.b * p[0] + m.d * p[1] + m.ty) as f32;
        if !x.is_finite() || !y.is_finite() {
            return Err(invalid("Transformed stroke does not fit in f32"));
        }
        vertices.extend_from_slice(&x.to_le_bytes());
        vertices.extend_from_slice(&y.to_le_bytes());
        min_x = min_x.min(x);
        min_y = min_y.min(y);
        max_x = max_x.max(x);
        max_y = max_y.max(y);
        Ok(())
    };
    let pairs: Vec<(usize, usize)> = if kept.len() == 1 {
        vec![(kept[0], kept[0])]
    } else {
        kept.windows(2).map(|w| (w[0], w[1])).collect()
    };
    let (mut shapes, mut outline) = (Vec::new(), Vec::new());
    for (a, b) in pairs {
        shapes.clear();
        tip.place(nodes[a], &mut shapes);
        if b != a {
            tip.place(nodes[b], &mut shapes);
        }
        hull(&mut shapes, &mut outline);
        if outline.len() < 3 {
            continue;
        }
        if count + outline.len() > MAX_MESH_VERTICES {
            return Err(limit("Stroke mesh exceeds the vertex limit"));
        }
        for &p in &outline {
            push_vertex(p, &mut vertices)?;
        }
        let base = count as u32;
        for k in 1..outline.len() as u32 - 1 {
            for index in [base, base + k, base + k + 1] {
                indices.extend_from_slice(&index.to_le_bytes());
            }
        }
        count += outline.len();
    }
    if count == 0 {
        // Zero-size tip everywhere: no triangles, bounds collapse to the first point.
        let mut none = Vec::new();
        push_vertex(nodes[0].c, &mut none)?;
    }
    Ok(StrokeMesh {
        vertices,
        indices,
        min_x,
        min_y,
        max_x,
        max_y,
        discard_self_overlap: style.discard_self_overlap,
        renderer_version: MESH_RENDERER_VERSION,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{CoreError, InkColour, SampleChannels, encode_samples};

    const IDENTITY: Affine = Affine {
        a: 1.0,
        b: 0.0,
        c: 0.0,
        d: 1.0,
        tx: 0.0,
        ty: 0.0,
    };

    fn pen(size: BrushSize) -> StrokeStyle {
        default_stroke_style(Brush {
            family: BrushFamily::Pen,
            size,
            colour: InkColour::Ink,
        })
    }

    fn blob(points: &[(f32, f32, f32, u32)]) -> Vec<u8> {
        encode_samples(SampleChannels {
            x: points.iter().map(|p| p.0).collect(),
            y: points.iter().map(|p| p.1).collect(),
            pressure: points.iter().map(|p| p.2).collect(),
            elapsed_ms: points.iter().map(|p| p.3).collect(),
            tilt: None,
            orientation: None,
        })
        .unwrap()
    }

    /// Horizontal line from x=100 to x=500 at y=200, 100 units/s, 4 ms samples.
    fn line(pressure: f32) -> Vec<u8> {
        let points: Vec<_> = (0..=1000)
            .map(|i| (100.0 + i as f32 * 0.4, 200.0, pressure, i * 4))
            .collect();
        blob(&points)
    }

    fn vertices(mesh: &StrokeMesh) -> Vec<[f32; 2]> {
        mesh.vertices
            .chunks_exact(8)
            .map(|b| {
                [
                    f32::from_le_bytes([b[0], b[1], b[2], b[3]]),
                    f32::from_le_bytes([b[4], b[5], b[6], b[7]]),
                ]
            })
            .collect()
    }

    /// Vertical extent of the vertices whose x is within [x0, x1].
    fn height_between(mesh: &StrokeMesh, x0: f32, x1: f32) -> f32 {
        let ys: Vec<f32> = vertices(mesh)
            .into_iter()
            .filter(|v| v[0] >= x0 && v[0] <= x1)
            .map(|v| v[1])
            .collect();
        assert!(!ys.is_empty(), "no vertices in [{x0}, {x1}]");
        ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
    }

    fn check_well_formed(mesh: &StrokeMesh) {
        assert_eq!(mesh.vertices.len() % 8, 0);
        assert_eq!(mesh.indices.len() % 12, 0);
        assert!(!mesh.indices.is_empty());
        let vs = vertices(mesh);
        let n = vs.len() as u32;
        for b in mesh.indices.chunks_exact(4) {
            assert!(u32::from_le_bytes([b[0], b[1], b[2], b[3]]) < n);
        }
        for v in &vs {
            assert!(v[0].is_finite() && v[1].is_finite());
            assert!(v[0] >= mesh.min_x && v[0] <= mesh.max_x);
            assert!(v[1] >= mesh.min_y && v[1] <= mesh.max_y);
        }
        // Bounds are tight.
        assert!(vs.iter().any(|v| v[0] == mesh.min_x) && vs.iter().any(|v| v[0] == mesh.max_x));
        assert!(vs.iter().any(|v| v[1] == mesh.min_y) && vs.iter().any(|v| v[1] == mesh.max_y));
        assert_eq!(mesh.renderer_version, mesh_renderer_version());
    }

    fn fnv(bytes: &[u8]) -> u64 {
        bytes.iter().fold(0xcbf2_9ce4_8422_2325, |h, b| {
            (h ^ u64::from(*b)).wrapping_mul(0x100_0000_01b3)
        })
    }

    #[test]
    fn default_styles_match_the_phone() {
        let medium = pen(BrushSize::Medium);
        assert_eq!(medium.size, 4.0);
        assert_eq!(medium.epsilon, 0.05);
        assert_eq!(
            (medium.pressure_low, medium.pressure_high),
            (1.0 - 0.6_f32, 1.6)
        );
        assert_eq!(medium.pressure_damping_seconds, 0.006 + 0.024 * 0.5_f32);
        assert_eq!(medium.speed_thinning, 0.82);
        assert_eq!(medium.speed_start_sizes_per_second, 225.0);
        assert_eq!(medium.speed_end_sizes_per_second, 1125.0);
        assert_eq!((medium.taper_start, medium.taper_end), (0.22, 0.18));
        assert_eq!(medium.taper_start_sizes, 26.0 / 4.0);
        assert_eq!(medium.taper_end_sizes, 24.0 / 4.0);
        assert_eq!(
            (medium.smoothing_window_ms, medium.upsampling_hz),
            (20, 180)
        );
        assert_eq!(
            (
                medium.tip_scale_x,
                medium.tip_scale_y,
                medium.corner_rounding
            ),
            (1.0, 1.0, 1.0)
        );
        assert!(!medium.discard_self_overlap);
        assert_eq!(pen(BrushSize::Small).size, 2.0);
        assert_eq!(pen(BrushSize::Large).size, 8.0);
        let highlighter = default_stroke_style(Brush {
            family: BrushFamily::Highlighter,
            size: BrushSize::Large,
            colour: InkColour::Orange,
        });
        assert_eq!(highlighter.size, 40.0);
        assert_eq!(highlighter.pressure_low, highlighter.pressure_high);
        assert_eq!((highlighter.taper_start, highlighter.taper_end), (1.0, 1.0));
        assert_eq!(
            (
                highlighter.tip_scale_x,
                highlighter.tip_scale_y,
                highlighter.corner_rounding
            ),
            (0.4, 1.0, 0.3)
        );
        assert!(highlighter.discard_self_overlap);
    }

    #[test]
    fn deterministic_and_pinned() {
        let samples = blob(&[
            (10.0, 10.0, 0.2, 0),
            (14.0, 12.0, 0.4, 5),
            (20.0, 20.0, 0.6, 9),
            (21.0, 30.0, 0.8, 16),
            (15.0, 34.0, 0.5, 30),
            (15.0, 34.0, 0.5, 30),
        ]);
        let first = tessellate_stroke(samples.clone(), pen(BrushSize::Medium), IDENTITY).unwrap();
        let second = tessellate_stroke(samples, pen(BrushSize::Medium), IDENTITY).unwrap();
        assert_eq!(first, second);
        check_well_formed(&first);
        // Pinned output: a change here must bump MESH_RENDERER_VERSION. Other platforms running
        // these tests check cross-platform byte identity.
        assert_eq!(
            (fnv(&first.vertices), fnv(&first.indices)),
            (738_027_279_201_436_196, 11_389_533_359_824_591_817),
            "pinned mesh hash changed"
        );
    }

    #[test]
    fn tap_is_a_dot_of_the_tip_width() {
        let style = pen(BrushSize::Large);
        let pressure = 0.3_f32;
        let mesh =
            tessellate_stroke(blob(&[(50.0, 60.0, pressure, 7)]), style.clone(), IDENTITY).unwrap();
        check_well_formed(&mesh);
        let expected = 8.0
            * (f64::from(style.pressure_low)
                + f64::from(style.pressure_high - style.pressure_low)
                    * pressure_curve(f64::from(pressure)));
        for extent in [mesh.max_x - mesh.min_x, mesh.max_y - mesh.min_y] {
            assert!(
                (f64::from(extent) - expected).abs() < 0.1,
                "{extent} vs {expected}"
            );
        }
        assert!(((mesh.min_x + mesh.max_x) / 2.0 - 50.0).abs() < 1e-3);
        // Repeated identical samples are tolerated and still a dot.
        let repeated = tessellate_stroke(
            blob(&[
                (50.0, 60.0, pressure, 7),
                (50.0, 60.0, pressure, 7),
                (50.0, 60.0, pressure, 9),
            ]),
            style,
            IDENTITY,
        )
        .unwrap();
        check_well_formed(&repeated);
        assert!((repeated.max_x - repeated.min_x - mesh.max_x + mesh.min_x).abs() < 0.05);
    }

    #[test]
    fn straight_constant_pressure_stroke_has_the_nominal_width() {
        let style = pen(BrushSize::Medium);
        let mesh = tessellate_stroke(line(0.3), style.clone(), IDENTITY).unwrap();
        check_well_formed(&mesh);
        let nominal = 4.0
            * (f64::from(style.pressure_low)
                + f64::from(style.pressure_high - style.pressure_low) * pressure_curve(0.3));
        let width = f64::from(height_between(&mesh, 250.0, 350.0));
        assert!((width - nominal).abs() < 0.1, "{width} vs {nominal}");
        assert!((mesh.min_x - (100.0 - 2.0)).abs() < 2.0 && mesh.max_x < 503.0);
        // Long straight runs collapse to a few hulls.
        assert!(
            mesh.vertices.len() / 8 < 2000,
            "{} vertices",
            mesh.vertices.len() / 8
        );
    }

    #[test]
    fn pressure_widens_a_sensitive_stroke_only() {
        let style = pen(BrushSize::Medium);
        let light = tessellate_stroke(line(0.15), style.clone(), IDENTITY).unwrap();
        let heavy = tessellate_stroke(line(0.9), style, IDENTITY).unwrap();
        let (l, h) = (
            height_between(&light, 250.0, 350.0),
            height_between(&heavy, 250.0, 350.0),
        );
        assert!(h > l * 1.5, "{h} vs {l}");
        let mut flat = pen(BrushSize::Medium);
        flat.pressure_low = 1.0;
        flat.pressure_high = 1.0;
        let a = height_between(
            &tessellate_stroke(line(0.15), flat.clone(), IDENTITY).unwrap(),
            250.0,
            350.0,
        );
        let b = height_between(
            &tessellate_stroke(line(0.9), flat, IDENTITY).unwrap(),
            250.0,
            350.0,
        );
        assert!((a - b).abs() < 1e-4 && (a - 4.0).abs() < 0.1, "{a} {b}");
    }

    #[test]
    fn tapers_narrow_the_ends() {
        let tapered = pen(BrushSize::Medium);
        let mut plain = tapered.clone();
        plain.taper_start = 1.0;
        plain.taper_end = 1.0;
        let t = tessellate_stroke(line(0.3), tapered, IDENTITY).unwrap();
        let p = tessellate_stroke(line(0.3), plain, IDENTITY).unwrap();
        for (x0, x1) in [(100.0, 102.0), (498.0, 500.0)] {
            let (tw, pw) = (height_between(&t, x0, x1), height_between(&p, x0, x1));
            assert!(tw < pw * 0.6, "[{x0}, {x1}] tapered {tw} vs plain {pw}");
        }
        let middle = (
            height_between(&t, 250.0, 350.0),
            height_between(&p, 250.0, 350.0),
        );
        assert!((middle.0 - middle.1).abs() < 0.05, "{middle:?}");
    }

    #[test]
    fn transform_is_applied() {
        let style = pen(BrushSize::Medium);
        let plain = tessellate_stroke(line(0.3), style.clone(), IDENTITY).unwrap();
        let moved = Affine {
            a: 2.0,
            b: 0.0,
            c: 0.0,
            d: 2.0,
            tx: 100.0,
            ty: -50.0,
        };
        let mesh = tessellate_stroke(line(0.3), style.clone(), moved).unwrap();
        check_well_formed(&mesh);
        assert!((mesh.min_x - (plain.min_x * 2.0 + 100.0)).abs() < 1e-3);
        assert!((mesh.max_y - (plain.max_y * 2.0 - 50.0)).abs() < 1e-3);
        for (p, q) in vertices(&plain).iter().zip(vertices(&mesh)) {
            assert!((q[0] - (p[0] * 2.0 + 100.0)).abs() < 1e-3);
            assert!((q[1] - (p[1] * 2.0 - 50.0)).abs() < 1e-3);
        }
        // A quarter turn swaps the axes: x' = -y, y' = x.
        let turn = Affine {
            a: 0.0,
            b: 1.0,
            c: -1.0,
            d: 0.0,
            tx: 0.0,
            ty: 0.0,
        };
        let turned = tessellate_stroke(line(0.3), style, turn).unwrap();
        assert!((turned.min_y - plain.min_x).abs() < 1e-3);
        assert!((turned.min_x + plain.max_y).abs() < 1e-3);
    }

    #[test]
    fn highlighter_is_a_flat_translucent_chisel() {
        let style = default_stroke_style(Brush {
            family: BrushFamily::Highlighter,
            size: BrushSize::Medium,
            colour: InkColour::Green,
        });
        let horizontal = tessellate_stroke(line(0.3), style.clone(), IDENTITY).unwrap();
        check_well_formed(&horizontal);
        assert!(horizontal.discard_self_overlap);
        assert!((height_between(&horizontal, 250.0, 350.0) - 20.0).abs() < 0.1);
        let vertical: Vec<_> = (0..=500)
            .map(|i| (200.0, 100.0 + i as f32 * 0.4, 0.3, i * 4))
            .collect();
        let vertical = tessellate_stroke(blob(&vertical), style, IDENTITY).unwrap();
        assert!((vertical.max_x - vertical.min_x - 8.0).abs() < 0.1);
    }

    #[test]
    fn invalid_input_is_rejected() {
        let style = pen(BrushSize::Medium);
        for bad in [b"nope".to_vec(), Vec::new(), line(0.3)[..40].to_vec()] {
            assert!(matches!(
                tessellate_stroke(bad, style.clone(), IDENTITY),
                Err(CoreError::InvalidInput { .. })
            ));
        }
        let mut nan = style.clone();
        nan.size = f32::NAN;
        let mut zero = style.clone();
        zero.epsilon = 0.0;
        for bad in [nan, zero] {
            assert!(matches!(
                tessellate_stroke(line(0.3), bad, IDENTITY),
                Err(CoreError::InvalidInput { .. })
            ));
        }
        let mut transform = IDENTITY;
        transform.tx = f64::INFINITY;
        assert!(matches!(
            tessellate_stroke(line(0.3), style.clone(), transform.clone()),
            Err(CoreError::InvalidInput { .. })
        ));
        transform.tx = 0.0;
        transform.a = 1e300;
        assert!(matches!(
            tessellate_stroke(line(0.3), style, transform),
            Err(CoreError::InvalidInput { .. })
        ));
    }

    #[test]
    fn vertex_limit_is_enforced() {
        // A sharp zigzag with a constant fine round tip and a tiny tolerance cannot be simplified:
        // every corner keeps a ~256-vertex hull.
        let mut style = pen(BrushSize::Large);
        style.epsilon = 1e-9;
        style.pressure_low = 1.0;
        style.pressure_high = 1.0;
        style.speed_thinning = 1.0;
        style.taper_start = 1.0;
        style.taper_end = 1.0;
        style.smoothing_window_ms = 0;
        let zigzag: Vec<_> = (0..20_000u32)
            .map(|i| (i as f32 * 2.0, if i % 2 == 0 { 0.0 } else { 6.0 }, 0.5, i))
            .collect();
        assert!(matches!(
            tessellate_stroke(blob(&zigzag), style, IDENTITY),
            Err(CoreError::ResourceLimit { .. })
        ));
    }

    #[test]
    fn deterministic_math_helpers() {
        assert!((exp_neg(1.0) - (-1.0_f64).exp()).abs() < 1e-14);
        assert!((exp_neg(37.5) - (-37.5_f64).exp()).abs() < 1e-20);
        for x in [-3.0, -1.0, 0.3, 1.5, 3.1] {
            let (s, c) = sin_cos(x);
            assert!((s - f64::sin(x)).abs() < 1e-14 && (c - f64::cos(x)).abs() < 1e-14);
        }
        assert!(pressure_curve(0.0).abs() < 1e-9 && (pressure_curve(1.0) - 1.0).abs() < 1e-9);
    }
}
