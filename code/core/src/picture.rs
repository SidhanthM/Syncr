//! Small bounded XML subset: no DTD, browser, CSS engine, or external resolver.
use crate::*;
use std::collections::{BTreeMap, BTreeSet};

pub(crate) type Result<T> = std::result::Result<T, PictureError>;
pub(crate) const MAX_NODES: usize = 1200;
pub(crate) const MAX_DEPTH: usize = 32;
pub(crate) const MAX_DRAWN: usize = 400;
#[derive(Clone, Debug)]
pub(crate) struct Node {
    pub name: String,
    pub attrs: BTreeMap<String, String>,
    pub children: Vec<Node>,
    pub text: String,
}
impl Node {
    pub fn attr(&self, key: &str) -> &str {
        self.attrs.get(key).map(String::as_str).unwrap_or("")
    }
    pub fn number(&self, key: &str, default: f64) -> f64 {
        self.attr(key).parse().unwrap_or(default)
    }
}
fn malformed<T>() -> Result<T> {
    Err(PictureError::Malformed)
}
fn decode(s: &str) -> Result<String> {
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i + 1..];
        let end = rest.find(';').ok_or(PictureError::Malformed)?;
        let entity = &rest[..end];
        let c = match entity {
            "amp" => '&',
            "lt" => '<',
            "gt" => '>',
            "quot" => '"',
            "apos" => '\'',
            _ => {
                let n = if let Some(s) = entity.strip_prefix("#x") {
                    u32::from_str_radix(s, 16).ok()
                } else {
                    entity.strip_prefix('#').and_then(|s| s.parse().ok())
                };
                n.and_then(char::from_u32).ok_or(PictureError::Malformed)?
            }
        };
        out.push(c);
        rest = &rest[end + 1..];
    }
    out.push_str(rest);
    if out
        .chars()
        .any(|c| c.is_control() && !matches!(c, '\n' | '\r' | '\t'))
    {
        return malformed();
    }
    Ok(out)
}
fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}
fn name_end(s: &str) -> usize {
    s.bytes()
        .take_while(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_' | b':'))
        .count()
}
fn parse(input: &str) -> Result<Node> {
    if input.len() > 96000 || input.chars().count() > 24000 {
        return Err(PictureError::Limit);
    }
    let mut stack: Vec<Node> = vec![];
    let mut root = None;
    let mut rest = input;
    let mut count = 0;
    while !rest.is_empty() {
        if let Some(tail) = rest.strip_prefix("<!--") {
            let i = tail.find("-->").ok_or(PictureError::Malformed)?;
            rest = &tail[i + 3..];
            continue;
        }
        if let Some(tail) = rest.strip_prefix("<![CDATA[") {
            let i = tail.find("]]>").ok_or(PictureError::Malformed)?;
            rest = &tail[i + 3..];
            continue;
        }
        if rest.starts_with("<!") {
            return malformed();
        }
        if let Some(tail) = rest.strip_prefix("<?") {
            let i = tail.find("?>").ok_or(PictureError::Malformed)?;
            rest = &tail[i + 2..];
            continue;
        }
        if let Some(tail) = rest.strip_prefix("</") {
            let i = tail.find('>').ok_or(PictureError::Malformed)?;
            let node = stack.pop().ok_or(PictureError::Malformed)?;
            if node.name != tail[..i].trim() {
                return malformed();
            }
            if let Some(parent) = stack.last_mut() {
                parent.children.push(node);
            } else if root.replace(node).is_some() {
                return malformed();
            }
            rest = &tail[i + 1..];
            continue;
        }
        if let Some(tail) = rest.strip_prefix('<') {
            count += 1;
            if count > MAX_NODES || stack.len() >= MAX_DEPTH {
                return Err(PictureError::Limit);
            }
            let n = name_end(tail);
            if n == 0 {
                return malformed();
            }
            let mut node = Node {
                name: tail[..n].into(),
                attrs: BTreeMap::new(),
                children: vec![],
                text: String::new(),
            };
            rest = &tail[n..];
            loop {
                rest = rest.trim_start();
                if rest.starts_with('>') || rest.starts_with("/>") {
                    break;
                }
                let n = name_end(rest);
                if n == 0 {
                    return malformed();
                }
                let key = &rest[..n];
                rest = rest[n..].trim_start();
                rest = rest
                    .strip_prefix('=')
                    .ok_or(PictureError::Malformed)?
                    .trim_start();
                let quote = rest
                    .as_bytes()
                    .first()
                    .copied()
                    .ok_or(PictureError::Malformed)?;
                if !matches!(quote, b'\'' | b'"') {
                    return malformed();
                }
                rest = &rest[1..];
                let end = rest
                    .find(char::from(quote))
                    .ok_or(PictureError::Malformed)?;
                if rest[..end].contains('<') || node.attrs.len() >= 64 {
                    return malformed();
                }
                let value = decode(&rest[..end])?;
                if node.attrs.insert(key.into(), value).is_some() {
                    return malformed();
                }
                rest = &rest[end + 1..];
            }
            if let Some(tail) = rest.strip_prefix("/>") {
                rest = tail;
                if let Some(parent) = stack.last_mut() {
                    parent.children.push(node);
                } else if root.replace(node).is_some() {
                    return malformed();
                }
            } else {
                rest = &rest[1..];
                stack.push(node);
            }
        } else {
            let i = rest.find('<').unwrap_or(rest.len());
            let text = decode(&rest[..i])?;
            if let Some(node) = stack.last_mut() {
                if matches!(node.name.as_str(), "text" | "tspan" | "title") {
                    node.children.push(Node {
                        name: "#text".into(),
                        attrs: BTreeMap::new(),
                        children: vec![],
                        text,
                    });
                } else if !text.trim().is_empty() { /* Non-text character data is discarded. */
                }
            } else if !text.trim().is_empty() {
                return malformed();
            }
            rest = &rest[i..];
        }
    }
    if !stack.is_empty() {
        return malformed();
    }
    root.ok_or(PictureError::Malformed)
}
pub(crate) fn num(s: &str) -> Option<f64> {
    let v: f64 = s.parse().ok()?;
    (v.is_finite() && v.abs() <= 1_000_000.0).then_some((v * 10.0).round() / 10.0)
}
pub(crate) fn number(v: f64) -> String {
    if v == 0.0 {
        "0".into()
    } else {
        format!("{v:.1}")
            .trim_end_matches('0')
            .trim_end_matches('.')
            .into()
    }
}
pub(crate) fn numbers(s: &str) -> Option<Vec<f64>> {
    crate::picture_path::tokens(s, false)
        .ok()?
        .into_iter()
        .map(|s| num(&s))
        .collect()
}
fn identifier(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
}
fn colour(s: &str) -> bool {
    s == "none"
        || ["ink", "soft", "muted", "hairline", "wash", "page", "voice"]
            .iter()
            .any(|v| s == format!("var(--{v})"))
}
fn geometry(name: &str, key: &str) -> bool {
    match name {
        "svg" => key == "viewBox",
        "line" => matches!(key, "x1" | "y1" | "x2" | "y2"),
        "rect" => matches!(key, "x" | "y" | "width" | "height" | "rx" | "ry"),
        "circle" => matches!(key, "cx" | "cy" | "r"),
        "ellipse" => matches!(key, "cx" | "cy" | "rx" | "ry"),
        "polyline" | "polygon" => key == "points",
        "path" => key == "d",
        "text" | "tspan" => matches!(key, "x" | "y" | "dx" | "dy"),
        "use" => matches!(key, "x" | "y" | "href" | "xlink:href"),
        "marker" => matches!(
            key,
            "viewBox" | "refX" | "refY" | "markerWidth" | "markerHeight" | "orient" | "markerUnits"
        ),
        _ => false,
    }
}
fn presentation(key: &str) -> bool {
    matches!(
        key,
        "fill"
            | "stroke"
            | "stroke-width"
            | "stroke-linecap"
            | "stroke-linejoin"
            | "font-family"
            | "font-size"
            | "text-anchor"
            | "dominant-baseline"
            | "marker-start"
            | "marker-end"
    )
}
pub(crate) fn transform(s: &str) -> Option<Vec<(String, Vec<f64>)>> {
    let mut out = vec![];
    let mut rest = s.trim();
    while !rest.is_empty() {
        if out.len() >= 16 {
            return None;
        }
        let i = rest.find('(')?;
        let name = rest[..i].trim();
        let end = rest.find(')')?;
        let ns = numbers(&rest[i + 1..end])?;
        if !match name {
            "translate" | "scale" => matches!(ns.len(), 1 | 2),
            "rotate" => matches!(ns.len(), 1 | 3),
            _ => false,
        } {
            return None;
        }
        out.push((name.into(), ns));
        rest = rest[end + 1..].trim_start_matches(|c: char| c.is_whitespace() || c == ',');
    }
    (!out.is_empty()).then_some(out)
}
fn attribute(name: &str, key: &str, s: &str) -> Option<String> {
    let s = s.trim();
    let valid = match key {
        "id" => identifier(s),
        "fill" | "stroke" => colour(s),
        "font-family" => matches!(s, "var(--font-voice)" | "var(--font-sans)"),
        "font-size" => return num(s).filter(|v| (10.0..=20.0).contains(v)).map(number),
        "stroke-width" => {
            return s
                .parse::<f64>()
                .ok()
                .filter(|v| (0.75..=2.5).contains(v))
                .and_then(|_| num(s))
                .map(number);
        }
        "stroke-linecap" | "stroke-linejoin" => s == "round",
        "text-anchor" => matches!(s, "start" | "middle" | "end"),
        "dominant-baseline" => matches!(
            s,
            "auto"
                | "alphabetic"
                | "middle"
                | "central"
                | "hanging"
                | "text-before-edge"
                | "text-after-edge"
        ),
        "transform" => {
            return transform(s).map(|ts| {
                ts.into_iter()
                    .map(|(n, vs)| {
                        format!(
                            "{n}({})",
                            vs.into_iter().map(number).collect::<Vec<_>>().join(" ")
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(" ")
            });
        }
        "data-step" if name == "g" => {
            return s
                .parse::<u32>()
                .ok()
                .filter(|v| (1..=12).contains(v))
                .map(|v| v.to_string());
        }
        "data-caption" if name == "g" => s.chars().count() <= 160,
        "data-hide" | "data-dim" if name == "g" => {
            let ns: Option<BTreeSet<u32>> = s
                .split(',')
                .map(|v| {
                    v.trim()
                        .parse::<u32>()
                        .ok()
                        .filter(|v| (1..=12).contains(v))
                })
                .collect();
            return ns.map(|ns| {
                ns.into_iter()
                    .map(|v| v.to_string())
                    .collect::<Vec<_>>()
                    .join(",")
            });
        }
        "href" | "xlink:href" if name == "use" => s.strip_prefix('#').is_some_and(identifier),
        "marker-start" | "marker-end" => s
            .strip_prefix("url(#")
            .and_then(|v| v.strip_suffix(')'))
            .is_some_and(identifier),
        "d" if name == "path" => return crate::picture_path::canonical(s).ok(),
        "points" if geometry(name, key) => {
            return numbers(s)
                .filter(|ns| ns.len() >= 4 && ns.len() % 2 == 0)
                .map(|ns| ns.into_iter().map(number).collect::<Vec<_>>().join(" "));
        }
        "viewBox" if geometry(name, key) => {
            return numbers(s)
                .filter(|ns| ns.len() == 4 && ns[2] > 0.0 && ns[3] > 0.0)
                .map(|ns| ns.into_iter().map(number).collect::<Vec<_>>().join(" "));
        }
        "orient" if name == "marker" => {
            s == "auto" || s == "auto-start-reverse" || num(s).is_some()
        }
        "markerUnits" if name == "marker" => matches!(s, "strokeWidth" | "userSpaceOnUse"),
        _ if geometry(name, key) => {
            return num(s)
                .filter(|v| {
                    !matches!(
                        key,
                        "width" | "height" | "r" | "rx" | "ry" | "markerWidth" | "markerHeight"
                    ) || *v >= 0.0
                })
                .map(number);
        }
        _ => false,
    };
    valid.then(|| s.into())
}
fn clean_node(mut node: Node, ids: &mut BTreeSet<String>, count: &mut usize) -> Option<Node> {
    if node.name == "#text" {
        return Some(node);
    }
    if !matches!(
        node.name.as_str(),
        "svg"
            | "g"
            | "defs"
            | "use"
            | "path"
            | "line"
            | "polyline"
            | "polygon"
            | "rect"
            | "circle"
            | "ellipse"
            | "text"
            | "tspan"
            | "marker"
            | "title"
    ) {
        return None;
    }
    if let Some(style) = node.attrs.remove("style") {
        for rule in style.split(';') {
            if let Some((key, value)) = rule.split_once(':') {
                let key = key.trim();
                if presentation(key) {
                    node.attrs.insert(key.into(), value.trim().into());
                }
            }
        }
    }
    node.attrs = node
        .attrs
        .into_iter()
        .filter_map(|(k, v)| {
            attribute(&node.name, &k, &v)
                .map(|v| (if k == "xlink:href" { "href".into() } else { k }, v))
        })
        .collect();
    if let Some(id) = node.attrs.get("id")
        && !ids.insert(id.clone())
    {
        node.attrs.remove("id");
    }
    let required: &[&str] = match node.name.as_str() {
        "path" => &["d"],
        "polyline" | "polygon" => &["points"],
        "rect" => &["width", "height"],
        "circle" => &["r"],
        "ellipse" => &["rx", "ry"],
        "use" => &["href"],
        _ => &[],
    };
    if required.iter().any(|k| !node.attrs.contains_key(*k)) {
        return None;
    }
    if drawn(&node.name) {
        *count += 1;
    }
    node.children = node
        .children
        .into_iter()
        .filter_map(|c| clean_node(c, ids, count))
        .collect();
    Some(node)
}
pub(crate) fn drawn(name: &str) -> bool {
    matches!(
        name,
        "use" | "path" | "line" | "polyline" | "polygon" | "rect" | "circle" | "ellipse" | "text"
    )
}
fn references(node: &mut Node, ids: &BTreeSet<String>) {
    node.attrs.retain(|k, v| {
        if k == "href" {
            ids.contains(&v[1..])
        } else if matches!(k.as_str(), "marker-start" | "marker-end") {
            ids.contains(&v[5..v.len() - 1])
        } else {
            true
        }
    });
    node.children.retain(|c| {
        c.name != "use" || ids.contains(c.attr("href").strip_prefix('#').unwrap_or(""))
    });
    for c in &mut node.children {
        references(c, ids);
    }
}
fn steps(node: &mut Node, found: &mut BTreeSet<u32>, parent: bool, definition: bool) -> Result<()> {
    let definition = definition || matches!(node.name.as_str(), "defs" | "marker");
    let n = node.attr("data-step").parse::<u32>().ok();
    if let Some(n) = n {
        if parent || definition || !found.insert(n) {
            return Err(PictureError::Steps);
        }
        for key in ["data-hide", "data-dim"] {
            if node
                .attrs
                .get(key)
                .is_some_and(|v| v.split(',').any(|v| v.parse::<u32>().is_ok_and(|v| v >= n)))
            {
                node.attrs.remove(key);
            }
        }
    } else {
        for key in ["data-caption", "data-hide", "data-dim"] {
            node.attrs.remove(key);
        }
    }
    for c in &mut node.children {
        steps(c, found, parent || n.is_some(), definition)?;
    }
    Ok(())
}
fn serialize(node: &Node, out: &mut String) {
    if node.name == "#text" {
        out.push_str(&escape(&node.text));
        return;
    }
    out.push('<');
    out.push_str(&node.name);
    for (k, v) in &node.attrs {
        out.push(' ');
        out.push_str(k);
        out.push_str("=\"");
        out.push_str(&escape(v));
        out.push('"');
    }
    if node.children.is_empty() {
        out.push_str("/>");
    } else {
        out.push('>');
        for c in &node.children {
            serialize(c, out);
        }
        out.push_str("</");
        out.push_str(&node.name);
        out.push('>');
    }
}
pub(crate) fn document(svg: &str) -> Result<Node> {
    let root = parse(svg)?;
    if root.name != "svg" {
        return Err(PictureError::Canvas);
    }
    let mut ids = BTreeSet::new();
    let mut count = 0;
    let mut root = clean_node(root, &mut ids, &mut count).ok_or(PictureError::Canvas)?;
    let canvas = numbers(root.attr("viewBox")).ok_or(PictureError::Canvas)?;
    if canvas.len() != 4 || canvas[..3] != [0.0, 0.0, 320.0] || !(60.0..=900.0).contains(&canvas[3])
    {
        return Err(PictureError::Canvas);
    }
    // Nested viewports are outside this format; discard, not reinterpret as groups.
    fn prune(node: &mut Node) {
        node.children.retain(|c| c.name != "svg");
        for c in &mut node.children {
            prune(c);
        }
    }
    prune(&mut root);
    ids.clear();
    fn collect_ids(node: &Node, ids: &mut BTreeSet<String>) {
        if !node.attr("id").is_empty() {
            ids.insert(node.attr("id").into());
        }
        for child in &node.children {
            collect_ids(child, ids);
        }
    }
    collect_ids(&root, &mut ids);
    // Propagate missing targets once through the reverse use graph, not by repeated
    // whole-tree cleanup (which would be quadratic for a long dangling chain).
    let mut reverse: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut pending = std::collections::VecDeque::new();
    fn uses(
        node: &Node,
        ids: &BTreeSet<String>,
        reverse: &mut BTreeMap<String, Vec<String>>,
        pending: &mut std::collections::VecDeque<String>,
    ) {
        if node.name == "use" {
            let target = node.attr("href").strip_prefix('#').unwrap_or("");
            if !ids.contains(target) {
                pending.push_back(target.into());
            }
            if !node.attr("id").is_empty() {
                reverse
                    .entry(target.into())
                    .or_default()
                    .push(node.attr("id").into());
            }
        }
        for child in &node.children {
            uses(child, ids, reverse, pending);
        }
    }
    uses(&root, &ids, &mut reverse, &mut pending);
    let mut removed = BTreeSet::new();
    while let Some(target) = pending.pop_front() {
        if !removed.insert(target.clone()) {
            continue;
        }
        ids.remove(&target);
        if let Some(dependents) = reverse.get(&target) {
            pending.extend(dependents.iter().cloned());
        }
    }
    references(&mut root, &ids);
    let mut found = BTreeSet::new();
    steps(&mut root, &mut found, false, false)?;
    if found.is_empty() || found.iter().copied().ne(1..=found.len() as u32) {
        return Err(PictureError::Steps);
    }
    if count > MAX_DRAWN {
        return Err(PictureError::Limit);
    }
    if count < 2 {
        return Err(PictureError::Empty);
    }
    Ok(root)
}
/// Canonical sanitization, with bounded reference expansion checked before admission.
#[uniffi::export]
pub fn filter_picture(svg: String) -> Result<String> {
    let root = document(&svg)?;
    let display = crate::picture_display::build(&root)?;
    if display.always.len() + display.steps.iter().map(|s| s.items.len()).sum::<usize>() < 2 {
        return Err(PictureError::Empty);
    }
    let mut out = String::new();
    serialize(&root, &mut out);
    if out.chars().count() > 24000 {
        return Err(PictureError::Limit);
    }
    Ok(out)
}
/// Accepts untrusted SVG too: the same filtering/admission rules always apply.
#[uniffi::export]
pub fn picture_display_list(svg: String) -> Result<PictureDisplayList> {
    let root = document(&svg)?;
    let out = crate::picture_display::build(&root)?;
    if out.always.len() + out.steps.iter().map(|s| s.items.len()).sum::<usize>() < 2 {
        return Err(PictureError::Empty);
    }
    Ok(out)
}
