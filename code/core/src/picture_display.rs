//! Portable outlines, fully resolved references and bounded expansion.
use crate::{
    picture::{Node, Result, numbers, transform},
    picture_path::{Outline, Point},
    *,
};
use std::{collections::BTreeMap, f64::consts::TAU};
#[derive(Clone, Copy)]
struct Matrix([f64; 6]);
impl Matrix {
    const ID: Self = Self([1.0, 0.0, 0.0, 1.0, 0.0, 0.0]);
    fn mul(self, r: Self) -> Self {
        let [a, b, c, d, e, f] = self.0;
        let [g, h, i, j, k, l] = r.0;
        Self([
            a * g + c * h,
            b * g + d * h,
            a * i + c * j,
            b * i + d * j,
            a * k + c * l + e,
            b * k + d * l + f,
        ])
    }
    fn translate(x: f64, y: f64) -> Self {
        Self([1.0, 0.0, 0.0, 1.0, x, y])
    }
    fn scale(x: f64, y: f64) -> Self {
        Self([x, 0.0, 0.0, y, 0.0, 0.0])
    }
    fn rotate(angle: f64) -> Self {
        let (s, c) = angle.to_radians().sin_cos();
        Self([c, s, -s, c, 0.0, 0.0])
    }
    fn point(self, p: Point) -> Point {
        let [a, b, c, d, x, y] = self.0;
        [a * p[0] + c * p[1] + x, b * p[0] + d * p[1] + y]
    }
    fn size(self) -> f64 {
        let [a, b, c, d, _, _] = self.0;
        (a * d - b * c).abs().sqrt()
    }
    fn valid(self) -> Result<Self> {
        if self.0.iter().any(|v| !v.is_finite() || v.abs() > 1e9) {
            Err(PictureError::Limit)
        } else {
            Ok(self)
        }
    }
}
fn local(node: &Node) -> Matrix {
    let mut m = Matrix::ID;
    if let Some(ts) = transform(node.attr("transform")) {
        for (name, ns) in ts {
            let t = match name.as_str() {
                "translate" => Matrix::translate(ns[0], *ns.get(1).unwrap_or(&0.0)),
                "scale" => Matrix::scale(ns[0], *ns.get(1).unwrap_or(&ns[0])),
                "rotate" if ns.len() == 3 => Matrix::translate(ns[1], ns[2])
                    .mul(Matrix::rotate(ns[0]))
                    .mul(Matrix::translate(-ns[1], -ns[2])),
                _ => Matrix::rotate(ns[0]),
            };
            m = m.mul(t);
        }
    }
    m
}
#[derive(Clone)]
struct Style {
    stroke: String,
    fill: String,
    width: f64,
    font: String,
    size: f64,
    anchor: String,
    baseline: String,
    start: String,
    end: String,
}
impl Default for Style {
    fn default() -> Self {
        Self {
            stroke: "none".into(),
            fill: "ink".into(),
            width: 1.0,
            font: "font-voice".into(),
            size: 14.0,
            anchor: "start".into(),
            baseline: "alphabetic".into(),
            start: String::new(),
            end: String::new(),
        }
    }
}
fn token(s: &str) -> String {
    s.strip_prefix("var(--")
        .and_then(|s| s.strip_suffix(')'))
        .unwrap_or(s)
        .into()
}
impl Style {
    fn inherit(&self, node: &Node) -> Self {
        let mut s = self.clone();
        for (key, value) in &node.attrs {
            match key.as_str() {
                "stroke" => s.stroke = token(value),
                "fill" => s.fill = token(value),
                "stroke-width" => s.width = node.number(key, 1.0),
                "font-family" => s.font = token(value),
                "font-size" => s.size = node.number(key, 14.0),
                "text-anchor" => s.anchor = value.clone(),
                "dominant-baseline" => s.baseline = value.clone(),
                "marker-start" => s.start = value[5..value.len() - 1].into(),
                "marker-end" => s.end = value[5..value.len() - 1].into(),
                _ => {}
            }
        }
        s
    }
}
fn quant(v: f64) -> f32 {
    let v = (v * 1024.0).round() / 1024.0;
    if v == 0.0 { 0.0 } else { v as f32 }
}
fn bounds(ps: &[Point], pad: f64) -> PictureBounds {
    let mut lo = [f64::INFINITY; 2];
    let mut hi = [f64::NEG_INFINITY; 2];
    for p in ps {
        for i in 0..2 {
            lo[i] = lo[i].min(p[i]);
            hi[i] = hi[i].max(p[i]);
        }
    }
    let lower = |v: f64| {
        let f = ((v * 1024.0).floor() / 1024.0) as f32;
        if f64::from(f) > v { f.next_down() } else { f }
    };
    let upper = |v: f64| {
        let f = ((v * 1024.0).ceil() / 1024.0) as f32;
        if f64::from(f) < v { f.next_up() } else { f }
    };
    PictureBounds {
        min_x: lower(lo[0] - pad),
        min_y: lower(lo[1] - pad),
        max_x: upper(hi[0] + pad),
        max_y: upper(hi[1] + pad),
    }
}
fn ellipse(cx: f64, cy: f64, rx: f64, ry: f64) -> Result<Vec<Point>> {
    if rx <= 0.0 || ry <= 0.0 {
        return Ok(vec![]);
    }
    let step = 2.0 * (1.0 - 0.25 / rx.max(ry)).clamp(-1.0, 1.0).acos();
    let n = (TAU / step.max(0.00001)).ceil().max(12.0) as usize;
    if n > 32768 {
        return Err(PictureError::Limit);
    }
    Ok((0..n)
        .map(|i| {
            let a = TAU * i as f64 / n as f64;
            [cx + rx * a.cos(), cy + ry * a.sin()]
        })
        .collect())
}
fn outlines(n: &Node) -> Result<Vec<Outline>> {
    let v = |s| n.number(s, 0.0);
    let out = match n.name.as_str() {
        "path" => return crate::picture_path::flatten(n.attr("d")),
        "line" => (vec![[v("x1"), v("y1")], [v("x2"), v("y2")]], false),
        "polyline" | "polygon" => (
            numbers(n.attr("points"))
                .unwrap_or_default()
                .chunks_exact(2)
                .map(|p| [p[0], p[1]])
                .collect(),
            n.name == "polygon",
        ),
        "circle" => (ellipse(v("cx"), v("cy"), v("r"), v("r"))?, true),
        "ellipse" => (ellipse(v("cx"), v("cy"), v("rx"), v("ry"))?, true),
        "rect" => {
            let (x, y, w, h) = (v("x"), v("y"), v("width"), v("height"));
            if w <= 0.0 || h <= 0.0 {
                return Ok(vec![]);
            }
            let rx = n.number("rx", v("ry")).min(w / 2.0);
            let ry = n.number("ry", rx).min(h / 2.0);
            if rx > 0.0 && ry > 0.0 {
                let d = format!(
                    "M {} {} H {} A {rx} {ry} 0 0 1 {} {} V {} A {rx} {ry} 0 0 1 {} {} H {} A {rx} {ry} 0 0 1 {} {} V {} A {rx} {ry} 0 0 1 {} {} Z",
                    x + rx,
                    y,
                    x + w - rx,
                    x + w,
                    y + ry,
                    y + h - ry,
                    x + w - rx,
                    y + h,
                    x + rx,
                    x,
                    y + h - ry,
                    y + ry,
                    x + rx,
                    y
                );
                return crate::picture_path::flatten(&d);
            }
            (vec![[x, y], [x + w, y], [x + w, y + h], [x, y + h]], true)
        }
        _ => return Ok(vec![]),
    };
    Ok(vec![out])
}
struct Builder<'a> {
    ids: BTreeMap<String, &'a Node>,
    visits: usize,
    points: usize,
    items: usize,
    refs: Vec<String>,
    list: PictureDisplayList,
}
impl<'a> Builder<'a> {
    fn append(&mut self, item: PictureItem, step: Option<usize>) -> Result<()> {
        self.items += 1;
        if self.items > 4000 {
            return Err(PictureError::Limit);
        }
        if let Some(i) = step {
            self.list.steps[i].items.push(item);
        } else {
            self.list.always.push(item);
        }
        Ok(())
    }
    fn polyline(
        &mut self,
        ps: &[Point],
        closed: bool,
        m: Matrix,
        s: &Style,
        step: Option<usize>,
    ) -> Result<()> {
        if ps.len() < 2 || (s.stroke == "none" && s.fill == "none") {
            return Ok(());
        }
        self.points += ps.len();
        if self.points > 100000 {
            return Err(PictureError::Limit);
        }
        let ps = ps.iter().map(|p| m.point(*p)).collect::<Vec<_>>();
        if ps.iter().flatten().any(|v| !v.is_finite() || v.abs() > 1e9) {
            return Err(PictureError::Limit);
        }
        let width = s.width * m.size();
        self.append(
            PictureItem::Polyline {
                points: ps
                    .iter()
                    .map(|p| PicturePoint {
                        x: quant(p[0]),
                        y: quant(p[1]),
                    })
                    .collect(),
                closed,
                stroke: s.stroke.clone(),
                stroke_width: quant(width),
                fill: s.fill.clone(),
                bounds: bounds(&ps, if s.stroke == "none" { 0.0 } else { width / 2.0 }),
            },
            step,
        )
    }
    fn marker(
        &mut self,
        id: &str,
        endpoint: (Point, f64, bool),
        m: Matrix,
        s: &Style,
        step: Option<usize>,
        depth: usize,
    ) -> Result<()> {
        let (at, angle, reverse) = endpoint;
        let Some(&node) = self.ids.get(id) else {
            return Ok(());
        };
        if node.name != "marker" {
            return Ok(());
        }
        self.enter(id)?;
        let mut rotate = if node.attr("orient").starts_with("auto") {
            angle
        } else {
            node.number("orient", 0.0)
        };
        if reverse && node.attr("orient") == "auto-start-reverse" {
            rotate += 180.0;
        }
        let scale = if node.attr("markerUnits") == "userSpaceOnUse" {
            1.0
        } else {
            s.width
        };
        let mut t = m
            .mul(Matrix::translate(at[0], at[1]))
            .mul(Matrix::rotate(rotate))
            .mul(Matrix::scale(scale, scale));
        // An absent or degenerate marker viewBox means marker units map 1:1 (no panic on
        // the empty attribute, which parses as zero numbers).
        if let Some(vb) =
            numbers(node.attr("viewBox")).filter(|v| v.len() == 4 && v[2] > 0.0 && v[3] > 0.0)
        {
            let sx = node.number("markerWidth", 3.0) / vb[2];
            let sy = node.number("markerHeight", 3.0) / vb[3];
            // SVG's default preserveAspectRatio = xMidYMid meet.
            let sc = sx.min(sy);
            t = t
                .mul(Matrix::translate(
                    (node.number("markerWidth", 3.0) - vb[2] * sc) / 2.0,
                    (node.number("markerHeight", 3.0) - vb[3] * sc) / 2.0,
                ))
                .mul(Matrix::scale(sc, sc))
                .mul(Matrix::translate(-vb[0], -vb[1]));
        }
        t = t
            .mul(Matrix::translate(
                -node.number("refX", 0.0),
                -node.number("refY", 0.0),
            ))
            .mul(local(node))
            .valid()?;
        let mut style = s.inherit(node);
        style.start.clear();
        style.end.clear();
        for child in &node.children {
            self.walk(child, t, &style, step, depth + 1, true)?;
        }
        self.refs.pop();
        Ok(())
    }
    fn enter(&mut self, id: &str) -> Result<()> {
        if self.refs.len() >= 16 || self.refs.iter().any(|v| v == id) {
            return Err(PictureError::References);
        }
        self.refs.push(id.into());
        Ok(())
    }
    fn text(
        &mut self,
        node: &'a Node,
        m: Matrix,
        s: &Style,
        step: Option<usize>,
        cursor: &mut Point,
        depth: usize,
    ) -> Result<()> {
        if depth > 32 {
            return Err(PictureError::Limit);
        }
        let s = s.inherit(node);
        let m = m.mul(local(node)).valid()?;
        if node.attrs.contains_key("x") {
            cursor[0] = node.number("x", 0.0);
        }
        if node.attrs.contains_key("y") {
            cursor[1] = node.number("y", 0.0);
        }
        cursor[0] += node.number("dx", 0.0);
        cursor[1] += node.number("dy", 0.0);
        for c in &node.children {
            if c.name == "tspan" {
                self.text(c, m, &s, step, cursor, depth + 1)?;
            } else if c.name == "#text" {
                let text = c.text.split_whitespace().collect::<Vec<_>>().join(" ");
                if text.is_empty() {
                    continue;
                }
                let width = text.chars().count() as f64 * s.size; // Conservative, not a font metric.
                let dx = match s.anchor.as_str() {
                    "middle" => -width / 2.0,
                    "end" => -width,
                    _ => 0.0,
                };
                let dy = match s.baseline.as_str() {
                    "middle" | "central" => -s.size * 0.6,
                    "hanging" | "text-before-edge" => 0.0,
                    "text-after-edge" => -s.size * 1.2,
                    _ => -s.size,
                };
                let [x, y] = m.point(*cursor);
                let ps = [
                    [cursor[0] + dx, cursor[1] + dy],
                    [cursor[0] + dx + width, cursor[1] + dy],
                    [cursor[0] + dx, cursor[1] + dy + s.size * 1.4],
                    [cursor[0] + dx + width, cursor[1] + dy + s.size * 1.4],
                ]
                .map(|p| m.point(p));
                let [a, b, c, d, _, _] = m.0;
                self.append(
                    PictureItem::Text {
                        text,
                        x: quant(x),
                        y: quant(y),
                        anchor: s.anchor.clone(),
                        baseline: s.baseline.clone(),
                        font: s.font.clone(),
                        size: quant(s.size),
                        fill: s.fill.clone(),
                        transform: Affine {
                            a: f64::from(quant(a)),
                            b: f64::from(quant(b)),
                            c: f64::from(quant(c)),
                            d: f64::from(quant(d)),
                            tx: 0.0,
                            ty: 0.0,
                        },
                        bounds: bounds(&ps, 0.0),
                    },
                    step,
                )?;
                cursor[0] += width * 0.6;
            }
        }
        Ok(())
    }
    fn walk(
        &mut self,
        node: &'a Node,
        m: Matrix,
        s: &Style,
        mut step: Option<usize>,
        depth: usize,
        reference: bool,
    ) -> Result<()> {
        self.visits += 1;
        if self.visits > 12000 || depth > 64 {
            return Err(PictureError::Limit);
        }
        if matches!(
            node.name.as_str(),
            "defs" | "marker" | "title" | "#text" | "tspan"
        ) {
            return Ok(());
        }
        if node.name == "text" {
            return self.text(node, m, s, step, &mut [0.0, 0.0], 0);
        }
        let m = m.mul(local(node)).valid()?;
        let style = s.inherit(node);
        if !reference && let Ok(n) = node.attr("data-step").parse::<u32>() {
            step = Some(n as usize - 1);
        }
        if node.name == "use" {
            let id = node.attr("href").strip_prefix('#').unwrap_or("");
            if let Some(&target) = self.ids.get(id) {
                if matches!(target.name.as_str(), "svg" | "defs" | "marker" | "title") {
                    return Ok(());
                }
                self.enter(id)?;
                self.walk(
                    target,
                    m.mul(Matrix::translate(
                        node.number("x", 0.0),
                        node.number("y", 0.0),
                    )),
                    &style,
                    step,
                    depth + 1,
                    true,
                )?;
                self.refs.pop();
            }
            return Ok(());
        }
        for (ps, closed) in outlines(node)? {
            self.polyline(&ps, closed, m, &style, step)?;
            if ps.len() >= 2 {
                let first = ps[0];
                let next = ps[1];
                let last = *ps.last().unwrap();
                let prev = ps[ps.len() - 2];
                if !style.start.is_empty() {
                    self.marker(
                        &style.start,
                        (
                            first,
                            (next[1] - first[1]).atan2(next[0] - first[0]).to_degrees(),
                            true,
                        ),
                        m,
                        &style,
                        step,
                        depth,
                    )?;
                }
                if !style.end.is_empty() {
                    self.marker(
                        &style.end,
                        (
                            last,
                            (last[1] - prev[1]).atan2(last[0] - prev[0]).to_degrees(),
                            false,
                        ),
                        m,
                        &style,
                        step,
                        depth,
                    )?;
                }
            }
        }
        for child in &node.children {
            self.walk(child, m, &style, step, depth + 1, reference)?;
        }
        Ok(())
    }
}
pub(crate) fn build(root: &Node) -> Result<PictureDisplayList> {
    fn collect<'a>(
        n: &'a Node,
        ids: &mut BTreeMap<String, &'a Node>,
        steps: &mut Vec<PictureStep>,
    ) {
        if !n.attr("id").is_empty() {
            ids.insert(n.attr("id").into(), n);
        }
        if let Ok(number) = n.attr("data-step").parse() {
            let refs = |key| {
                n.attr(key)
                    .split(',')
                    .filter_map(|v| v.parse().ok())
                    .collect()
            };
            steps.push(PictureStep {
                number,
                caption: n.attr("data-caption").into(),
                hide: refs("data-hide"),
                dim: refs("data-dim"),
                items: vec![],
            });
        }
        for c in &n.children {
            collect(c, ids, steps);
        }
    }
    let mut ids = BTreeMap::new();
    let mut steps = vec![];
    collect(root, &mut ids, &mut steps);
    steps.sort_by_key(|s| s.number);
    let canvas = numbers(root.attr("viewBox"))
        .filter(|v| v.len() == 4)
        .ok_or(PictureError::Canvas)?;
    let mut builder = Builder {
        ids,
        visits: 0,
        points: 0,
        items: 0,
        refs: vec![],
        list: PictureDisplayList {
            width: 320.0,
            height: canvas[3] as f32,
            always: vec![],
            steps,
        },
    };
    builder.walk(root, Matrix::ID, &Style::default(), None, 0, false)?;
    Ok(builder.list)
}
