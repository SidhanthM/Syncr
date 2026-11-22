import Foundation

public protocol PictureInspector: Sendable {
    func filter(svg: String) throws -> String
    func displayList(svg: String) throws -> PictureDisplayList
}
public struct PicturePoint: Codable, Equatable, Sendable {
    public var x: Double; public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
}
public struct PictureBounds: Codable, Equatable, Sendable {
    public var minX: Double; public var minY: Double; public var maxX: Double; public var maxY: Double
    public init(minX: Double, minY: Double, maxX: Double, maxY: Double) { self.minX = minX; self.minY = minY; self.maxX = maxX; self.maxY = maxY }
}
public struct PictureTransform: Codable, Equatable, Sendable {
    public var a: Double; public var b: Double; public var c: Double; public var d: Double
    public init(a: Double = 1, b: Double = 0, c: Double = 0, d: Double = 1) { self.a = a; self.b = b; self.c = c; self.d = d }
}
public struct PictureDrawItem: Codable, Equatable, Sendable {
    public var id: String; public var points: [PicturePoint]; public var text: String?
    public var x: Double; public var y: Double; public var fontSize: Double; public var anchor: String
    public var stroke: String; public var fill: String; public var dashed: Bool; public var arrow: Bool
    public var bounds: PictureBounds?; public var transform: PictureTransform?; public var font: String?; public var baseline: String?; public var strokeWidth: Double?; public var closed: Bool?
    public init(id: String, points: [PicturePoint] = [], text: String? = nil, x: Double = 0, y: Double = 0, fontSize: Double = 12, anchor: String = "start", stroke: String = "ink", fill: String = "none", dashed: Bool = false, arrow: Bool = false, bounds: PictureBounds? = nil, transform: PictureTransform? = nil, font: String? = nil, baseline: String? = nil, strokeWidth: Double? = nil, closed: Bool? = nil) {
        self.id = id; self.points = points; self.text = text; self.x = x; self.y = y; self.fontSize = fontSize; self.anchor = anchor
        self.stroke = stroke; self.fill = fill; self.dashed = dashed; self.arrow = arrow
        self.bounds = bounds; self.transform = transform; self.font = font; self.baseline = baseline; self.strokeWidth = strokeWidth; self.closed = closed
    }
}
public struct PictureStep: Codable, Equatable, Sendable {
    public var number: Int; public var caption: String; public var items: [PictureDrawItem]; public var hide: [Int]; public var dim: [Int]
    public init(number: Int, caption: String, items: [PictureDrawItem], hide: [Int] = [], dim: [Int] = []) { self.number = number; self.caption = caption; self.items = items; self.hide = hide; self.dim = dim }
}
public struct PictureDisplayList: Codable, Equatable, Sendable {
    public var width: Double; public var height: Double; public var always: [PictureDrawItem]; public var steps: [PictureStep]
    public init(width: Double = 320, height: Double, always: [PictureDrawItem] = [], steps: [PictureStep]) { self.width = width; self.height = height; self.always = always; self.steps = steps }
}
public struct PictureFinding: Codable, Equatable, Sendable {
    public var rule: String; public var step: Int; public var items: [String]; public var detail: String; public var minor: Bool
    public init(rule: String, step: Int, items: [String], detail: String, minor: Bool = false) { self.rule = rule; self.step = step; self.items = items; self.detail = detail; self.minor = minor }
}
private struct LabelBox {
    var x: Double; var y: Double; var w: Double; var h: Double
    var right: Double { x + w }; var bottom: Double { y + h }
    func overlap(_ b: LabelBox) -> Double { max(0, min(right, b.right) - max(x, b.x)) * max(0, min(bottom, b.bottom) - max(y, b.y)) }
}
public enum PictureCheck {
    public static func acceptable(_ findings: [PictureFinding]) -> Bool { findings.allSatisfy(\.minor) && findings.count <= 2 }
    public static func inspect(_ picture: PictureDisplayList, commentary: String = "") -> [PictureFinding] {
        guard picture.width.isFinite, picture.height.isFinite, picture.width > 0, picture.height > 0, picture.width <= 1e9, picture.height <= 1e9 else { return [PictureFinding(rule: "bounds", step: 0, items: [], detail: "Invalid canvas geometry.")] }
        for (step, items) in [(0, picture.always)] + picture.steps.map({ ($0.number, $0.items) }) {
            for item in items {
                let values = [item.x, item.y, item.fontSize] + item.points.flatMap { [$0.x,$0.y] } + (item.bounds.map { [$0.minX,$0.minY,$0.maxX,$0.maxY] } ?? []) + (item.transform.map { [$0.a,$0.b,$0.c,$0.d] } ?? [])
                if values.contains(where: { !$0.isFinite || abs($0) > 1e9 }) { return [PictureFinding(rule: "bounds", step: step, items: [item.id], detail: "Nonfinite or excessive display-list geometry.")] }
            }
        }
        var findings: [PictureFinding] = []; var visible: [Int: [PictureDrawItem]] = [0: picture.always]
        func finding(_ rule: String, _ step: Int, _ ids: [String], _ detail: String, minor: Bool = false) {
            guard findings.count < 64 else { return }
            findings.append(PictureFinding(rule: rule, step: step, items: ids, detail: detail, minor: minor))
        }
        func box(_ item: PictureDrawItem) -> LabelBox {
            if let b = item.bounds { return LabelBox(x: b.minX, y: b.minY, w: b.maxX-b.minX, h: b.maxY-b.minY) }
            let w = Double(item.text?.count ?? 0) * item.fontSize * 0.6
            let x = item.x - (item.anchor == "middle" ? w / 2 : item.anchor == "end" ? w : 0)
            let y = item.y - item.fontSize * 0.85 + (["middle", "central"].contains(item.baseline ?? "") ? item.fontSize * 0.35 : ["hanging", "text-before-edge"].contains(item.baseline ?? "") ? item.fontSize * 0.85 : 0)
            if let t = item.transform {
                let corners = [(x,y),(x+w,y),(x+w,y+item.fontSize),(x,y+item.fontSize)].map { px,py in PicturePoint(item.x + t.a*(px-item.x) + t.c*(py-item.y), item.y + t.b*(px-item.x) + t.d*(py-item.y)) }
                let xs=corners.map(\.x), ys=corners.map(\.y)
                return LabelBox(x: xs.min()!, y: ys.min()!, w: xs.max()!-xs.min()!, h: ys.max()!-ys.min()!)
            }
            return LabelBox(x: x, y: y, w: w, h: item.fontSize)
        }
        for step in picture.steps {
            if findings.count >= 64 { break }
            for hidden in step.hide { visible.removeValue(forKey: hidden) }
            visible[step.number] = step.items
            let items = visible.keys.sorted().flatMap { visible[$0] ?? [] }
            if step.items.isEmpty { finding("empty", step.number, [], "Step has no drawn items.") }
            for item in step.items + (step.number == picture.steps.first?.number ? picture.always : []) {
                let b = box(item)
                let pts = item.text == nil ? item.points : [PicturePoint(b.x, b.y), PicturePoint(b.right, b.bottom)]
                if pts.contains(where: { !$0.x.isFinite || !$0.y.isFinite || $0.x < -3 || $0.y < -3 || $0.x > picture.width + 3 || $0.y > picture.height + 3 }) { finding("bounds", step.number, [item.id], "Geometry exceeds canvas by more than 3 units.") }
                if item.text != nil && (item.fontSize < 10 || b.w > picture.width || item.fontSize > 20) { finding("legibility", step.number, [item.id], "Text outside 10...20 units or estimated width exceeds canvas.") }
            }
            let labels = items.filter { $0.text != nil }
            for (i, a) in labels.enumerated() {
                if findings.count >= 64 { break }
                let ba = box(a)
                for b in labels.dropFirst(i + 1) {
                    if findings.count >= 64 { break }
                    let bb = box(b); let area = ba.overlap(bb)
                    if area > 1 { let ratio = area / max(1, min(ba.w * ba.h, bb.w * bb.h)); finding("overlap", step.number, [a.id, b.id], "Label intersection \(Int(min(area,1e12))) square units (\(Int(ratio * 100))%).", minor: ratio <= 0.08) }
                }
                for line in items where line.text == nil && line.stroke != "none" {
                    if findings.count >= 64 { break }
                    // Outline shapes are also polylines; labels inside boxes do not intersect their edges.
                    for (p, q) in zip(line.points, line.points.dropFirst()) {
                        if segment(p, q, crosses: ba) { finding("overlap", step.number, [a.id, line.id], "Label intersects an unrelated stroke."); break }
                    }
                }
            }
            var cells: [String: [String]] = [:]
            for item in items {
                let p = item.text != nil ? PicturePoint(item.x, item.y) : item.points.first ?? PicturePoint(0, 0)
                cells["\(Int(floor(p.x / 48))),\(Int(floor(p.y / 48)))", default: []].append(item.id)
            }
            for ids in cells.values where ids.count > 16 { finding("crowding", step.number, ids, "More than 16 item origins in a 48 by 48 cell.") }
            truth(step.caption, items, step.number, finding)
            if ItemStreamParser.words(step.caption) > 20 || step.caption.count > 160 { finding("caption", step.number, [], "Caption exceeds 20 words or 160 characters.") }
        }
        truth(commentary, picture.always + picture.steps.flatMap(\.items), picture.steps.last?.number ?? 0, finding)
        // Deterministic deduplication; a persistent collision need not be repeated on every step.
        var seen = Set<String>()
        return findings.filter { seen.insert("\($0.rule):\($0.step):\($0.items.joined(separator: ",")):\($0.detail)").inserted }
    }
    private static func truth(_ caption: String, _ items: [PictureDrawItem], _ step: Int, _ add: (String, Int, [String], String, Bool) -> Void) {
        let text = caption.lowercased(); let ids = items.map(\.id)
        if (text.contains("shaded") || text.contains("shading")) && !items.contains(where: { $0.fill != "none" && $0.fill != "page" && $0.text == nil && filledArea($0.points) > 4 }) { add("truth", step, ids, "Names shading with no filled mark.", false) }
        if text.contains("dashed") && !items.contains(where: \.dashed) && !hasDashes(items) { add("truth", step, ids, "Names dashes with no dashed mark.", false) }
        if text.contains("arrow") && !items.contains(where: { $0.arrow || isArrowhead($0) }) { add("truth", step, ids, "Names an arrow with no arrow marker.", false) }
        let named = try! NSRegularExpression(pattern: "\\b(?:point|atom|box|node|label|site)\\s+([A-Z][A-Za-z0-9]*)\\b|\\b([B-HJ-Z])\\b")
        for match in named.matches(in: caption, range: NSRange(caption.startIndex..., in: caption)) {
            let group = match.range(at: 1).location == NSNotFound ? 2 : 1
            if let r = Range(match.range(at: group), in: caption) {
                let name = String(caption[r]); if !items.contains(where: { $0.text?.localizedCaseInsensitiveContains(name) == true }) { add("truth", step, ids, "Names absent label: \(name)", false) }
            }
        }
        let regex = try! NSRegularExpression(pattern: "[‘\"`]([^’\"`]+)[’\"`]")
        for match in regex.matches(in: caption, range: NSRange(caption.startIndex..., in: caption)) {
            if let r = Range(match.range(at: 1), in: caption) {
                let name = String(caption[r]); if !items.contains(where: { $0.text?.localizedCaseInsensitiveContains(name) == true }) { add("truth", step, ids, "Names absent label: \(name)", false) }
            }
        }
    }
    private static func filledArea(_ points: [PicturePoint]) -> Double {
        guard points.count >= 3 else { return 0 }
        var twice=0.0
        for (p,q) in zip(points, Array(points.dropFirst()) + [points[0]]) { twice += p.x*q.y-q.x*p.y }
        return abs(twice)/2
    }
    private static func isArrowhead(_ item: PictureDrawItem) -> Bool {
        guard item.text == nil, item.points.count >= 3, item.points.count <= 4 else { return false }
        let p=item.points[0], q=item.points[1], r=item.points[2]
        let ux=p.x-q.x, uy=p.y-q.y, vx=r.x-q.x, vy=r.y-q.y
        let a=hypot(ux,uy), b=hypot(vx,vy)
        guard a >= 2, b >= 2, a <= 20, b <= 20, min(a,b)/max(a,b) >= 0.5 else { return false }
        let cosine=(ux*vx+uy*vy)/(a*b)
        return cosine > -0.8 && cosine < 0.95
    }
    private static func hasDashes(_ items: [PictureDrawItem]) -> Bool {
        let segments=items.filter { $0.text == nil && $0.points.count == 2 && hypot($0.points[1].x-$0.points[0].x,$0.points[1].y-$0.points[0].y) <= 12 }
        for a in segments {
            let p=a.points[0], q=a.points[1]; let length=hypot(q.x-p.x,q.y-p.y)
            guard length > 1 else { continue }
            let aligned=segments.filter { b in b.points.allSatisfy { abs((q.x-p.x)*($0.y-p.y)-(q.y-p.y)*($0.x-p.x))/length <= 1 } }
            if aligned.count >= 3 { return true }
        }
        return false
    }
    private static func segment(_ p: PicturePoint, _ q: PicturePoint, crosses b: LabelBox) -> Bool {
        // Liang–Barsky clipping against an inset text box; touching its edge alone is not a collision.
        let x = b.x + 1, y = b.y + 1, right = b.right - 1, bottom = b.bottom - 1
        var lo = 0.0, hi = 1.0
        let dx = q.x - p.x, dy = q.y - p.y
        for (a, c) in [(-dx, p.x - x), (dx, right - p.x), (-dy, p.y - y), (dy, bottom - p.y)] {
            if abs(a) < 0.0001 { if c < 0 { return false }; continue }
            let r = c / a
            if a < 0 { lo = max(lo, r) } else { hi = min(hi, r) }
            if lo > hi { return false }
        }
        return true
    }
}
public struct PicturePattern: Codable, Equatable, Sendable {
    public var id: String; public var description: String; public var tags: [String]; public var svg: String
    public init(id: String, description: String, tags: [String], svg: String) { self.id = id; self.description = description; self.tags = tags; self.svg = svg }
}
public enum PatternBook {
    public static var all: [PicturePattern] { bundled }
    /// Decoded once: it is bundled and does not change.
    private static let bundled: [PicturePattern] = {
        guard let url = Bundle.module.url(forResource: "patterns", withExtension: "json", subdirectory: "patterns"), let data = try? Data(contentsOf: url), let patterns = try? JSONDecoder().decode([PicturePattern].self, from: data) else { return [] }
        return patterns
    }()
    public static func loadPinned(at stateFolder: URL) throws -> [PicturePattern] {
        let url=stateFolder.appendingPathComponent("companion-patterns.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let data=try Data(contentsOf: url); guard data.count <= 3 * 1024 * 1024 else { throw CompanionError.invalid("pinned pattern book size") }
        let values=try JSONDecoder().decode([PicturePattern].self, from: data)
        guard values.count <= 100, values.allSatisfy({ $0.svg.count <= 24000 && $0.description.count <= 160 && $0.tags.count <= 20 }) else { throw CompanionError.invalid("pinned pattern book") }; return values
    }
    public static func addPinned(svg: String, description: String, tags: [String], at stateFolder: URL, inspector: PictureInspector) throws {
        guard !description.isEmpty, description.count <= 160, tags.count <= 20, tags.allSatisfy({ $0.count <= 64 }) else { throw CompanionError.invalid("pattern metadata") }
        let clean=try inspector.filter(svg: svg)
        guard PictureCheck.inspect(try inspector.displayList(svg: clean)).isEmpty else { throw CompanionError.invalid("pinned picture has findings") }
        var patterns=try loadPinned(at: stateFolder)
        guard !patterns.contains(where: { $0.svg == clean }) else { return }
        guard patterns.count < 100 else { throw CompanionError.invalid("pinned pattern book full") }
        patterns.append(PicturePattern(id: "pinned-" + UUID().uuidString, description: description, tags: tags, svg: clean))
        try FileManager.default.createDirectory(at: stateFolder, withIntermediateDirectories: true)
        let encoder=JSONEncoder(); encoder.outputFormatting=[.sortedKeys,.prettyPrinted]
        let data = try encoder.encode(patterns)
        guard data.count <= 3 * 1024 * 1024 else { throw CompanionError.invalid("pinned pattern book size") }
        try data.write(to: stateFolder.appendingPathComponent("companion-patterns.json"), options: .atomic)
    }
    public static func closest(to request: String, limit: Int = 3, additional: [PicturePattern] = []) -> [PicturePattern] {
        let words = Set(request.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init))
        func score(_ p: PicturePattern) -> Int {
            let tags = Set(p.tags.flatMap { $0.lowercased().split(separator: " ").map(String.init) })
            let terms = Set((p.description + " " + p.id).lowercased().split(whereSeparator: { !$0.isLetter }).map(String.init))
            return words.intersection(tags).count * 4 + words.intersection(terms).count
        }
        return Array((all + additional).sorted { score($0) == score($1) ? $0.id < $1.id : score($0) > score($1) }.prefix(max(0, limit)))
    }
}
