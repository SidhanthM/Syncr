import Foundation
import SyncrCompanion

// Fixture-only XML subset. Production must use the Rust filter/display list, never this parser.
struct ReferencePictureInspector: PictureInspector {
    func filter(svg: String) throws -> String {
        guard svg.count <= 24000, !svg.contains("<!"), !svg.contains("script"), !svg.contains("http"), !svg.contains("style="), !svg.contains("onload"), svg.hasPrefix("<svg"), svg.hasSuffix("</svg>") else { throw CompanionError.invalid("reference filter") }
        let root = try tree(svg)
        guard root.tag == "svg", root.attrs["viewBox"]?.hasPrefix("0 0 320 ") == true else { throw CompanionError.invalid("reference root") }
        let list = try displayList(svg: svg)
        guard !list.steps.isEmpty, list.always.count + list.steps.reduce(0, { $0 + $1.items.count }) >= 2 else { throw CompanionError.invalid("reference drawn count") }
        return svg
    }
    func displayList(svg: String) throws -> PictureDisplayList {
        let root = try tree(svg); let dimensions = numbers(root.attrs["viewBox"] ?? "")
        guard dimensions.count == 4 else { throw CompanionError.invalid("fixture dimensions") }
        var defs: [String: XMLNode] = [:]
        func collect(_ n: XMLNode) { if let id = n.attrs["id"] { defs[id] = n }; n.children.forEach(collect) }; collect(root)
        var list = PictureDisplayList(height: dimensions[3], steps: []); var serial = 0
        func draw(_ node: XMLNode, inherited: [String: String] = [:], dx: Double = 0, dy: Double = 0, depth: Int = 0) -> [PictureDrawItem] {
            guard depth < 16 else { return [] }
            let a = inherited.merging(node.attrs, uniquingKeysWith: { _, b in b })
            func n(_ key: String, _ fallback: Double = 0) -> Double { Double(a[key] ?? "") ?? fallback }
            func p(_ x: Double, _ y: Double) -> PicturePoint { PicturePoint(x + dx, y + dy) }
            if node.tag == "defs" || node.tag == "marker" || node.tag == "title" { return [] }
            if node.tag == "use", let name = a["href"], let def = defs[String(name.dropFirst())] { return draw(def, inherited: a.filter { $0.key != "href" }, dx: dx + n("x"), dy: dy + n("y"), depth: depth + 1) }
            if node.tag == "g" || node.tag == "svg" { return node.children.flatMap { draw($0, inherited: a, dx: dx, dy: dy, depth: depth + 1) } }
            var points: [PicturePoint] = []; var text: String? = nil
            switch node.tag {
            case "text": text = node.text
            case "line": points = [p(n("x1"), n("y1")), p(n("x2"), n("y2"))]
            case "rect": let x = n("x"), y = n("y"), w = n("width"), h = n("height"); points = [p(x,y),p(x+w,y),p(x+w,y+h),p(x,y+h),p(x,y)]
            case "circle", "ellipse":
                let rx = n("r", n("rx")), ry = n("r", n("ry")); points = (0...16).map { i in let t = Double(i) * .pi / 8; return p(n("cx") + rx * cos(t), n("cy") + ry * sin(t)) }
            case "polyline", "polygon": let v = numbers(a["points"] ?? ""); for i in stride(from: 0, to: v.count - 1, by: 2) { points.append(p(v[i], v[i+1])) }; if node.tag == "polygon", let first = points.first { points.append(first) }
            case "path":
                // Fixtures use absolute M/L and a single quadratic Q only.
                let d = a["d"] ?? ""; let v = numbers(d)
                if d.contains("Q"), v.count == 6 { for i in 0...24 { let t = Double(i)/24, u = 1-t; points.append(p(u*u*v[0]+2*u*t*v[2]+t*t*v[4], u*u*v[1]+2*u*t*v[3]+t*t*v[5])) } }
                else { for i in stride(from: 0, to: max(0,v.count-1), by: 2) { points.append(p(v[i],v[i+1])) } }
            default: return []
            }
            serial += 1
            func token(_ key: String, _ fallback: String) -> String { (a[key] ?? fallback).replacingOccurrences(of: "var(--", with: "").replacingOccurrences(of: ")", with: "") }
            return [PictureDrawItem(id: "i\(serial)", points: points, text: text, x: n("x") + dx, y: n("y") + dy, fontSize: n("font-size",12), anchor: a["text-anchor"] ?? "start", stroke: token("stroke", "none"), fill: token("fill", "none"), dashed: a["stroke-dasharray"] != nil, arrow: a["marker-end"] != nil || a["marker-start"] != nil)]
        }
        for node in root.children {
            if let step = node.attrs["data-step"].flatMap(Int.init) {
                func refs(_ name: String) -> [Int] { (node.attrs[name] ?? "").split(separator: ",").compactMap { Int($0) } }
                list.steps.append(PictureStep(number: step, caption: node.attrs["data-caption"] ?? "", items: draw(node, inherited: root.attrs), hide: refs("data-hide"), dim: refs("data-dim")))
            } else { list.always += draw(node, inherited: root.attrs) }
        }
        return list
    }
    private func numbers(_ s: String) -> [Double] {
        let regex = try! NSRegularExpression(pattern: "[-+]?[0-9]*\\.?[0-9]+")
        return regex.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range, in: s).flatMap { Double(s[$0]) } }
    }
    private func tree(_ svg: String) throws -> XMLNode {
        let parser = XMLParser(data: Data(svg.utf8)); let delegate = FixtureXML(); parser.delegate = delegate; parser.shouldResolveExternalEntities = false
        guard parser.parse(), let root = delegate.root else { throw CompanionError.invalid("fixture XML") }; return root
    }
}
private final class XMLNode {
    let tag: String; let attrs: [String: String]; var children: [XMLNode] = []; var text = ""
    init(_ tag: String, _ attrs: [String: String]) { self.tag = tag; self.attrs = attrs }
}
private final class FixtureXML: NSObject, XMLParserDelegate {
    var root: XMLNode?; var stack: [XMLNode] = []
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        let node = XMLNode(name, attributes); if let parent = stack.last { parent.children.append(node) } else { root = node }; stack.append(node)
    }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) { stack.removeLast() }
    func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
}
