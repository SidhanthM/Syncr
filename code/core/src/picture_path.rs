//! SVG path grammar and bounded, fixed-tolerance flattening (0.25 local units).
use crate::PictureError;
use crate::picture::{Result, num, number};
use std::f64::consts::{PI, TAU};
pub(crate) type Point = [f64; 2];
pub(crate) type Outline = (Vec<Point>, bool);
const MAX_POINTS: usize = 32768;
const TOLERANCE: f64 = 0.25;

pub(crate) fn tokens(s: &str, commands: bool) -> Result<Vec<String>> {
    let mut out = vec![];
    let mut i = 0;
    let bs = s.as_bytes();
    while i < bs.len() {
        if bs[i].is_ascii_whitespace() || bs[i] == b',' {
            i += 1;
            continue;
        }
        let start = i;
        if commands && b"MmLlHhVvCcSsQqTtAaZz".contains(&bs[i]) {
            i += 1;
        } else {
            if matches!(bs[i], b'+' | b'-') {
                i += 1;
            }
            let mut digits = 0;
            while i < bs.len() && bs[i].is_ascii_digit() {
                i += 1;
                digits += 1;
            }
            if i < bs.len() && bs[i] == b'.' {
                i += 1;
                while i < bs.len() && bs[i].is_ascii_digit() {
                    i += 1;
                    digits += 1;
                }
            }
            if digits == 0 {
                return Err(PictureError::Malformed);
            }
            if i < bs.len() && matches!(bs[i], b'e' | b'E') {
                i += 1;
                if i < bs.len() && matches!(bs[i], b'+' | b'-') {
                    i += 1;
                }
                let exp = i;
                while i < bs.len() && bs[i].is_ascii_digit() {
                    i += 1;
                }
                if exp == i {
                    return Err(PictureError::Malformed);
                }
            }
        }
        out.push(s[start..i].into());
        if out.len() > 12000 {
            return Err(PictureError::Limit);
        }
    }
    Ok(out)
}
fn command(s: &str) -> Option<char> {
    (s.len() == 1 && s.as_bytes()[0].is_ascii_alphabetic()).then(|| char::from(s.as_bytes()[0]))
}
fn arity(c: char) -> usize {
    match c.to_ascii_uppercase() {
        'M' | 'L' | 'T' => 2,
        'H' | 'V' => 1,
        'C' => 6,
        'S' | 'Q' => 4,
        'A' => 7,
        _ => 0,
    }
}
fn instructions(s: &str) -> Result<Vec<(char, Vec<f64>)>> {
    let ts = tokens(s, true)?;
    let mut i = 0;
    let mut current = ' ';
    let mut out = vec![];
    while i < ts.len() {
        if let Some(c) = command(&ts[i]) {
            current = c;
            i += 1;
            if c.eq_ignore_ascii_case(&'Z') {
                if out.is_empty() {
                    return Err(PictureError::Malformed);
                }
                out.push((c, vec![]));
                current = ' ';
                continue;
            }
        }
        let n = arity(current);
        if n == 0 || i + n > ts.len() {
            return Err(PictureError::Malformed);
        }
        let ns = ts[i..i + n]
            .iter()
            .map(|s| num(s).ok_or(PictureError::Malformed))
            .collect::<Result<Vec<_>>>()?;
        if current.eq_ignore_ascii_case(&'A')
            && (ns[0] < 0.0
                || ns[1] < 0.0
                || ![0.0, 1.0].contains(&ns[3])
                || ![0.0, 1.0].contains(&ns[4]))
        {
            return Err(PictureError::Malformed);
        }
        if out.is_empty() && !current.eq_ignore_ascii_case(&'M') {
            return Err(PictureError::Malformed);
        }
        out.push((current, ns));
        i += n;
        if current == 'M' {
            current = 'L';
        } else if current == 'm' {
            current = 'l';
        }
    }
    if out.is_empty() || arity(out.last().unwrap().0) == 0 && out.len() == 1 {
        return Err(PictureError::Malformed);
    }
    Ok(out)
}
pub(crate) fn canonical(s: &str) -> Result<String> {
    Ok(instructions(s)?
        .into_iter()
        .map(|(c, ns)| {
            let nums = ns.into_iter().map(number).collect::<Vec<_>>().join(" ");
            if nums.is_empty() {
                c.to_string()
            } else {
                format!("{c} {nums}")
            }
        })
        .collect::<Vec<_>>()
        .join(" "))
}
fn push(out: &mut Vec<Point>, p: Point) -> Result<()> {
    if out.len() >= MAX_POINTS {
        return Err(PictureError::Limit);
    }
    out.push(p);
    Ok(())
}
fn midpoint(a: Point, b: Point) -> Point {
    [(a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5]
}
fn distance(p: Point, a: Point, b: Point) -> f64 {
    let dx = b[0] - a[0];
    let dy = b[1] - a[1];
    let t = if dx == 0.0 && dy == 0.0 {
        0.0
    } else {
        (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)).clamp(0.0, 1.0)
    };
    (p[0] - a[0] - t * dx).hypot(p[1] - a[1] - t * dy)
}
fn cubic(ps: [Point; 4], depth: usize, out: &mut Vec<Point>) -> Result<()> {
    let [a, b, c, d] = ps;
    if distance(b, a, d).max(distance(c, a, d)) <= TOLERANCE {
        return push(out, d);
    }
    if depth >= 16 {
        return Err(PictureError::Limit);
    }
    let ab = midpoint(a, b);
    let bc = midpoint(b, c);
    let cd = midpoint(c, d);
    let abc = midpoint(ab, bc);
    let bcd = midpoint(bc, cd);
    let center = midpoint(abc, bcd);
    cubic([a, ab, abc, center], depth + 1, out)?;
    cubic([center, bcd, cd, d], depth + 1, out)
}
fn arc(start: Point, ns: &[f64], end: Point, out: &mut Vec<Point>) -> Result<()> {
    let mut rx = ns[0].abs();
    let mut ry = ns[1].abs();
    if start == end {
        return Ok(());
    }
    if rx == 0.0 || ry == 0.0 {
        return push(out, end);
    }
    let phi = ns[2].to_radians();
    let (sn, cs) = phi.sin_cos();
    let dx = (start[0] - end[0]) * 0.5;
    let dy = (start[1] - end[1]) * 0.5;
    let x = cs * dx + sn * dy;
    let y = -sn * dx + cs * dy;
    let lambda = x * x / (rx * rx) + y * y / (ry * ry);
    if lambda > 1.0 {
        let k = lambda.sqrt();
        rx *= k;
        ry *= k;
    }
    let numerator = (rx * rx * ry * ry - rx * rx * y * y - ry * ry * x * x).max(0.0);
    let denom = rx * rx * y * y + ry * ry * x * x;
    let factor = if denom == 0.0 {
        0.0
    } else {
        (numerator / denom).sqrt() * if ns[3] == ns[4] { -1.0 } else { 1.0 }
    };
    let cxp = factor * rx * y / ry;
    let cyp = -factor * ry * x / rx;
    let cx = cs * cxp - sn * cyp + (start[0] + end[0]) * 0.5;
    let cy = sn * cxp + cs * cyp + (start[1] + end[1]) * 0.5;
    let ux = (x - cxp) / rx;
    let uy = (y - cyp) / ry;
    let vx = (-x - cxp) / rx;
    let vy = (-y - cyp) / ry;
    let theta = uy.atan2(ux);
    let mut delta = (ux * vy - uy * vx).atan2(ux * vx + uy * vy);
    if ns[4] == 0.0 && delta > 0.0 {
        delta -= TAU;
    }
    if ns[4] == 1.0 && delta < 0.0 {
        delta += TAU;
    }
    let step = (2.0 * (1.0 - TOLERANCE / rx.max(ry)).clamp(-1.0, 1.0).acos()).min(PI / 4.0);
    let count = (delta.abs() / step.max(0.00001)).ceil().max(1.0) as usize;
    if count > MAX_POINTS {
        return Err(PictureError::Limit);
    }
    for i in 1..=count {
        let angle = theta + delta * i as f64 / count as f64;
        push(
            out,
            if i == count {
                end
            } else {
                [
                    cx + cs * rx * angle.cos() - sn * ry * angle.sin(),
                    cy + sn * rx * angle.cos() + cs * ry * angle.sin(),
                ]
            },
        )?;
    }
    Ok(())
}
pub(crate) fn flatten(s: &str) -> Result<Vec<Outline>> {
    let mut out = vec![];
    let mut points = vec![];
    let mut p = [0.0, 0.0];
    let mut start = p;
    let mut previous = ' ';
    let mut control = p;
    for (c, ns) in instructions(s)? {
        let absolute = c.is_ascii_uppercase();
        let upper = c.to_ascii_uppercase();
        let pair = |i: usize| -> Point {
            [
                ns[i] + if absolute { 0.0 } else { p[0] },
                ns[i + 1] + if absolute { 0.0 } else { p[1] },
            ]
        };
        let mut next_control = p;
        match upper {
            'M' => {
                if !points.is_empty() {
                    out.push((std::mem::take(&mut points), false));
                }
                p = pair(0);
                start = p;
                push(&mut points, p)?;
            }
            'L' | 'T' => {
                let end = pair(0);
                if upper == 'T' {
                    let q = if matches!(previous, 'Q' | 'T') {
                        [2.0 * p[0] - control[0], 2.0 * p[1] - control[1]]
                    } else {
                        p
                    };
                    let b = [
                        p[0] + (q[0] - p[0]) * 2.0 / 3.0,
                        p[1] + (q[1] - p[1]) * 2.0 / 3.0,
                    ];
                    let d = [
                        end[0] + (q[0] - end[0]) * 2.0 / 3.0,
                        end[1] + (q[1] - end[1]) * 2.0 / 3.0,
                    ];
                    cubic([p, b, d, end], 0, &mut points)?;
                    next_control = q;
                } else {
                    push(&mut points, end)?;
                }
                p = end;
            }
            'H' => {
                p[0] = ns[0] + if absolute { 0.0 } else { p[0] };
                push(&mut points, p)?;
            }
            'V' => {
                p[1] = ns[0] + if absolute { 0.0 } else { p[1] };
                push(&mut points, p)?;
            }
            'C' => {
                let b = pair(0);
                let d = pair(2);
                let end = pair(4);
                cubic([p, b, d, end], 0, &mut points)?;
                p = end;
                next_control = d;
            }
            'S' => {
                let b = if matches!(previous, 'C' | 'S') {
                    [2.0 * p[0] - control[0], 2.0 * p[1] - control[1]]
                } else {
                    p
                };
                let d = pair(0);
                let end = pair(2);
                cubic([p, b, d, end], 0, &mut points)?;
                p = end;
                next_control = d;
            }
            'Q' => {
                let q = pair(0);
                let end = pair(2);
                let b = [
                    p[0] + (q[0] - p[0]) * 2.0 / 3.0,
                    p[1] + (q[1] - p[1]) * 2.0 / 3.0,
                ];
                let d = [
                    end[0] + (q[0] - end[0]) * 2.0 / 3.0,
                    end[1] + (q[1] - end[1]) * 2.0 / 3.0,
                ];
                cubic([p, b, d, end], 0, &mut points)?;
                p = end;
                next_control = q;
            }
            'A' => {
                let end = pair(5);
                arc(p, &ns, end, &mut points)?;
                p = end;
            }
            'Z' => {
                if !points.is_empty() {
                    out.push((std::mem::take(&mut points), true));
                }
                p = start;
            }
            _ => return Err(PictureError::Malformed),
        }
        if points.is_empty() && upper == 'Z' {
            push(&mut points, p)?;
        }
        control = next_control;
        previous = upper;
    }
    if points.len() > 1 {
        out.push((points, false));
    }
    if out.iter().map(|(ps, _)| ps.len()).sum::<usize>() > MAX_POINTS {
        return Err(PictureError::Limit);
    }
    Ok(out)
}
