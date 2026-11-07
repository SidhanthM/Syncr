import AppKit

/// Immutable, browser-free rendering input. The adapter is the only place that sees core types.
struct MacPicture: Sendable {
    struct Point: Sendable { var x: Double; var y: Double }
    struct Rect: Sendable {
        var minX: Double; var minY: Double; var maxX: Double; var maxY: Double
        var cg: CGRect { CGRect(x: minX, y: minY, width: max(0, maxX-minX), height: max(0, maxY-minY)) }
    }
    struct Transform: Sendable {
        var a: Double = 1; var b: Double = 0; var c: Double = 0; var d: Double = 1
        var cg: CGAffineTransform { CGAffineTransform(a: a,b: b,c: c,d: d,tx: 0,ty: 0) }
    }
    enum Item: Sendable {
        case path(points: [Point], closed: Bool, stroke: String, width: Double, fill: String, bounds: Rect? = nil)
        case text(text: String, x: Double, y: Double, anchor: String, font: String, size: Double, fill: String, transform: Transform = Transform(), baseline: String = "alphabetic", bounds: Rect? = nil)
    }
    struct Step: Sendable { var caption: String; var hide: [Int] = []; var dim: [Int] = []; var items: [Item] }
    var width: Double = 320
    var height: Double
    var always: [Item] = []
    var steps: [Step]

    static let fixture = MacPicture(height: 190, always: [
        .text(text: "A decision, then an undo", x: 18, y: 24, anchor: "start", font: "font-voice", size: 16, fill: "ink")
    ], steps: [
        Step(caption: "Place the first queen.", items: [
            .path(points: [.init(x: 30, y: 50), .init(x: 290, y: 50), .init(x: 290, y: 145), .init(x: 30, y: 145)], closed: true, stroke: "voice", width: 1.1, fill: "none"),
            .text(text: "Q", x: 65, y: 100, anchor: "middle", font: "font-voice", size: 18, fill: "ink")
        ]),
        Step(caption: "Try a second queen.", items: [.text(text: "Q", x: 170, y: 100, anchor: "middle", font: "font-voice", size: 18, fill: "ink")]),
        Step(caption: "Undo the second queen.", hide: [2], items: [.text(text: "Backtrack", x: 170, y: 165, anchor: "middle", font: "font-sans", size: 12, fill: "muted")])
    ])
}

/// Deterministic generator for the hand-drawn look: the same picture wobbles the same way on every run.
struct SeededGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> Double {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        z ^= z >> 31
        return Double(z >> 11) / Double(1 << 53)
    }
    mutating func signed() -> Double { next() * 2 - 1 }
    static func seed(_ salt: Int, _ points: [MacPicture.Point], _ closed: Bool) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        func mix(_ v: UInt64) { h = (h ^ v) &* 0x100000001b3 }
        mix(UInt64(truncatingIfNeeded: salt)); mix(UInt64(points.count)); mix(closed ? 1 : 0)
        for p in points.prefix(3) + points.suffix(1) { mix(UInt64(truncatingIfNeeded: Int64((p.x * 64).rounded()))); mix(UInt64(truncatingIfNeeded: Int64((p.y * 64).rounded()))) }
        return h
    }
}

/// One path's pen strokes: the main pass, and for straight lines and box edges a faint second pass.
struct PenStrokes {
    var main: [[CGPoint]]
    var faint: [[CGPoint]] = []
    var length: Double { PenStrokes.length(main) }
    static func length(_ strokes: [[CGPoint]]) -> Double {
        strokes.reduce(0) { total, stroke in total + zip(stroke, stroke.dropFirst()).reduce(0) { $0 + hypot($1.1.x-$1.0.x, $1.1.y-$1.0.y) } }
    }
}

@MainActor enum PictureRenderer {
    /// The "Hand-drawn lines" setting. The companion controller sets it from app state; the trial sets it per sheet.
    static var handDrawn = true

    static func colour(_ token: String) -> NSColor {
        switch token.replacingOccurrences(of: "var(--", with: "").replacingOccurrences(of: ")", with: "") {
        case "soft": Theme.soft
        case "muted": Theme.muted
        case "voice": Theme.voice
        case "hairline": Theme.hairline
        case "wash": Theme.wash
        case "page": Theme.page
        case "none": .clear
        default: Theme.ink
        }
    }
    private static func font(_ token: String, _ size: Double) -> NSFont { PictureFonts.font(token, size) }
    typealias TextLayout = PictureTextLayout
    static func layout(_ text: String, token: String, size: Double, anchor: String, baseline: String) -> PictureTextLayout {
        PictureFonts.measure(text, token: token, size: size, anchor: anchor, baseline: baseline)
    }

    /// Fit rectangle: the core's conservative item bounds unioned with measured text and stroke extents.
    static func bounds(_ picture: MacPicture) -> CGRect {
        var bounds = CGRect.null
        for item in picture.always + picture.steps.flatMap(\.items) {
            switch item {
            case .path(let points, _, _, let width, _, let core):
                for p in points { bounds = bounds.union(CGRect(x: p.x, y: p.y, width: 0, height: 0).insetBy(dx: -width, dy: -width)) }
                if let core { bounds = bounds.union(core.cg) }
            case .text(let text, let x, let y, let anchor, let token, let size, _, let transform, let baseline, let core):
                let l = layout(text, token: token, size: size, anchor: anchor, baseline: baseline)
                bounds = bounds.union(l.box.applying(transform.cg).offsetBy(dx: x, dy: y))
                if let core { bounds = bounds.union(core.cg) }
            }
        }
        // 4 units of slack beyond the hand-drawn overshoot, and the canvas never shrinks below its own width.
        let fit = bounds.isNull ? CGRect(x: 0, y: 0, width: picture.width, height: picture.height) : bounds.insetBy(dx: -8, dy: -8)
        return fit
    }

    /// Picture units to the drawing rectangle: `point * scale + (tx, ty)` after subtracting the bounds' origin.
    static func fit(_ picture: MacPicture, in rect: CGRect) -> (scale: CGFloat, tx: CGFloat, ty: CGFloat, bounds: CGRect) {
        let bounds = bounds(picture), scale = min(rect.width / max(1, bounds.width), rect.height / max(1, bounds.height))
        return (scale, rect.minX + (rect.width-bounds.width*scale)/2, rect.minY, bounds)
    }

    /// A label that can be drawn as selectable text: only plain rotations with a uniform scale. Anything else stays drawn.
    nonisolated static func selectable(_ t: MacPicture.Transform) -> Bool {
        let sx = hypot(t.a, t.b), sy = hypot(t.c, t.d)
        return sx > 0.001 && abs(sx - sy) < 0.001 && abs(t.a*t.c + t.b*t.d) < 0.001 && t.a*t.d - t.b*t.c > 0
    }

    /// Labels showing at `step`, as `draw` would paint them, for the card to lay real text over the picture.
    struct Label {
        var key: String; var text: String; var token: String; var size: Double; var fill: String
        var x: Double; var y: Double; var anchor: String; var baseline: String; var transform: MacPicture.Transform; var alpha: Double
    }
    static func labels(_ picture: MacPicture, through step: Int, progress: Double = 1) -> [Label] {
        var out: [Label] = []
        func add(_ items: [MacPicture.Item], group: String, alpha: Double) {
            for (index, item) in items.enumerated() {
                guard case .text(let t, let x, let y, let a, let f, let s, let fill, let tr, let b, _) = item, selectable(tr) else { continue }
                out.append(Label(key: "\(group)-\(index)", text: t, token: f, size: s, fill: fill, x: x, y: y, anchor: a, baseline: b, transform: tr, alpha: alpha))
            }
        }
        var hidden = Set<Int>(), dimmed = Set<Int>()
        let count = min(max(0, step), picture.steps.count)
        for current in picture.steps.prefix(count) { hidden.formUnion(current.hide); dimmed.formUnion(current.dim) }
        add(picture.always, group: "a", alpha: 1)
        for (index, current) in picture.steps.prefix(count).enumerated() where !hidden.contains(index+1) {
            let fraction = index == count-1 ? max(0, min(1, progress)) : 1
            let fade = max(0, min(1, (fraction-0.8)/0.2))
            add(current.items, group: "s\(index)", alpha: (dimmed.contains(index+1) ? 0.22 : 1) * fade)
        }
        return out
    }

    /// `overlay`: label glyphs of selectable labels are left out (a card lays real text over them); their halos stay.
    static func draw(_ picture: MacPicture, in rect: CGRect, through step: Int, progress: Double = 1, handDrawn: Bool? = nil, overlay: Bool = false) {
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        let hand = handDrawn ?? Self.handDrawn
        let (scale, tx, ty, bounds) = fit(picture, in: rect)
        context.saveGState()
        context.translateBy(x: tx, y: ty)
        context.scaleBy(x: scale, y: scale); context.translateBy(x: -bounds.minX, y: -bounds.minY)
        var hidden = Set<Int>(), dimmed = Set<Int>()
        let count = min(max(0, step), picture.steps.count)
        for current in picture.steps.prefix(count) { hidden.formUnion(current.hide); dimmed.formUnion(current.dim) }
        drawGroup(picture.always, salt: 0, context: context, fraction: 1, labels: 1, hand: hand, overlay: overlay)
        for (index, current) in picture.steps.prefix(count).enumerated() where !hidden.contains(index+1) {
            context.saveGState(); context.setAlpha(dimmed.contains(index+1) ? 0.22 : 1)
            context.beginTransparencyLayer(auxiliaryInfo: nil)
            let fraction = index == count-1 ? max(0, min(1, progress)) : 1
            drawGroup(current.items, salt: (index+1)*10_000, context: context, fraction: min(1, fraction/0.8), labels: max(0, (fraction-0.8)/0.2), hand: hand, overlay: overlay)
            context.endTransparencyLayer(); context.restoreGState()
        }
        context.restoreGState()
    }

    /// One step: every stroke in order along its length, then every label's page outline, then every label's fill,
    /// so one label's outline never covers another label's glyphs.
    private static func drawGroup(_ items: [MacPicture.Item], salt: Int, context: CGContext, fraction: Double, labels: Double, hand: Bool, overlay: Bool) {
        var pens: [Int: PenStrokes] = [:]
        var total = 0.0
        for (index, item) in items.enumerated() {
            if case .path(let points, let closed, _, let width, _, _) = item {
                let pen = strokes(points, closed: closed, width: width, salt: salt + index, hand: hand)
                pens[index] = pen; total += max(0.001, pen.length)
            }
        }
        var remaining = total * fraction
        for (index, item) in items.enumerated() {
            guard case .path(let points, let closed, let stroke, let width, let fill, _) = item, let pen = pens[index] else { continue }
            // A finished step draws every item whole: the running subtraction can leave the last item a hair short,
            // which would drop its fill (a filled circle with no stroke would vanish).
            let length = max(0.001, pen.length), part = fraction >= 1 ? 1 : min(1, max(0, remaining / length + 1e-6))
            remaining -= length
            guard part > 0 else { continue }
            if part >= 1, fill != "none" {
                let full = CGMutablePath()
                if let first = points.first {
                    full.move(to: CGPoint(x: first.x, y: first.y)); for p in points.dropFirst() { full.addLine(to: CGPoint(x: p.x, y: p.y)) }
                    if closed { full.closeSubpath() }
                    context.setFillColor(colour(fill).cgColor); context.addPath(full); context.fillPath()
                }
            }
            guard stroke != "none" else { continue }
            context.setLineWidth(width); context.setLineCap(.round); context.setLineJoin(.round)
            if !pen.faint.isEmpty {
                context.setStrokeColor(colour(stroke).withAlphaComponent(0.3).cgColor)
                context.addPath(prefixPath(pen.faint, fraction: part)); context.strokePath()
            }
            context.setStrokeColor(colour(stroke).cgColor)
            context.addPath(prefixPath(pen.main, fraction: part)); context.strokePath()
        }
        guard labels > 0 else { return }
        let texts = items.compactMap { item -> (String, Double, Double, String, String, Double, String, MacPicture.Transform, String)? in
            if case .text(let t, let x, let y, let a, let f, let s, let fill, let tr, let b, _) = item { return (t, x, y, a, f, s, fill, tr, b) }
            return nil
        }
        context.saveGState(); context.setAlpha(labels)
        for halo in [true, false] {
            for (text, x, y, anchor, token, size, fill, transform, baseline) in texts {
                if !halo, overlay, selectable(transform) { continue }
                let l = layout(text, token: token, size: size, anchor: anchor, baseline: baseline)
                context.saveGState()
                context.translateBy(x: x, y: y); context.concatenate(transform.cg)
                // A page-coloured glyph outline rather than a rectangular patch, in both themes.
                let origin = CGPoint(x: -l.offset, y: l.top)
                if halo { (text as NSString).draw(at: origin, withAttributes: [.font: l.font, .strokeColor: Theme.page, .strokeWidth: 16]) }
                else { (text as NSString).draw(at: origin, withAttributes: [.font: l.font, .foregroundColor: colour(fill)]) }
                context.restoreGState()
            }
        }
        context.restoreGState()
    }

    private static func prefixPath(_ strokes: [[CGPoint]], fraction: Double) -> CGPath {
        let path = CGMutablePath()
        var remaining = PenStrokes.length(strokes) * fraction
        for stroke in strokes {
            guard let first = stroke.first, remaining > 0 else { break }
            path.move(to: first)
            for (a, b) in zip(stroke, stroke.dropFirst()) {
                let distance = hypot(b.x-a.x, b.y-a.y), part = min(1, remaining / max(0.001, distance))
                path.addLine(to: CGPoint(x: a.x+(b.x-a.x)*part, y: a.y+(b.y-a.y)*part))
                remaining -= distance; if remaining <= 0 { break }
            }
        }
        return path
    }

    // MARK: Pen strokes

    static func strokes(_ points: [MacPicture.Point], closed: Bool, width: Double, salt: Int, hand: Bool) -> PenStrokes {
        let plain = points.map { CGPoint(x: $0.x, y: $0.y) }
        guard hand, plain.count >= 2 else {
            return PenStrokes(main: [closed && plain.count > 1 ? plain + [plain[0]] : plain])
        }
        var rng = SeededGenerator(seed: SeededGenerator.seed(salt, points, closed))
        var pts = plain
        // The adapter closes polygons by repeating the first point; the core's own lists do not.
        if closed, let first = pts.first, pts.last != first { pts.append(first) }
        if closed, isBox(pts) {
            var main: [[CGPoint]] = [], faint: [[CGPoint]] = []
            for (a, b) in zip(pts, pts.dropFirst()) {
                let edge = straight(a, b, rng: &rng)
                main.append(edge.main); if let f = edge.faint { faint.append(f) }
            }
            return PenStrokes(main: main, faint: faint)
        }
        if !closed, pts.count == 2 {
            let line = straight(pts[0], pts[1], rng: &rng)
            return PenStrokes(main: [line.main], faint: line.faint.map { [$0] } ?? [])
        }
        return PenStrokes(main: [wobble(pts, closed: closed, amplitude: 0.45, rng: &rng)])
    }
    private static func isBox(_ p: [CGPoint]) -> Bool {
        guard p.count == 5, p[0] == p[4] else { return false }
        return (0..<4).allSatisfy { i in abs(p[i].x-p[i+1].x) < 0.01 || abs(p[i].y-p[i+1].y) < 0.01 }
    }
    /// A single straight run: tiny overshoot at both ends, wobble along its length, and a faint second pass
    /// offset sideways by under 0.6 units. Short strokes (ticks, arrowheads) stay exact.
    private static func straight(_ a: CGPoint, _ b: CGPoint, rng: inout SeededGenerator) -> (main: [CGPoint], faint: [CGPoint]?) {
        let length = hypot(b.x-a.x, b.y-a.y)
        guard length >= 20 else { return ([a, b], nil) }
        let ux = (b.x-a.x)/length, uy = (b.y-a.y)/length
        func pass(_ overshoot: Double, amplitude: Double, sideways: Double) -> [CGPoint] {
            let o0 = overshoot * (0.6 + rng.next()), o1 = overshoot * (0.6 + rng.next())
            let start = CGPoint(x: a.x-ux*o0-uy*sideways, y: a.y-uy*o0+ux*sideways), end = CGPoint(x: b.x+ux*o1-uy*sideways, y: b.y+uy*o1+ux*sideways)
            return wobble([start, end], closed: false, amplitude: amplitude, rng: &rng)
        }
        let main = pass(0.9, amplitude: 0.5, sideways: 0)
        let faint = pass(0.6, amplitude: 0.3, sideways: 0.25 + rng.next() * 0.3)
        return (main, faint)
    }
    /// Perpendicular wobble by arclength (about 60 to 100 units a wave), corners kept exact, smoothed through the points.
    private static func wobble(_ input: [CGPoint], closed: Bool, amplitude: Double, rng: inout SeededGenerator) -> [CGPoint] {
        // Resample long segments so the wobble has points to move.
        var pts: [CGPoint] = []
        for (a, b) in zip(input, input.dropFirst()) {
            let length = hypot(b.x-a.x, b.y-a.y), pieces = max(1, Int((length/22).rounded(.up)))
            if pts.isEmpty { pts.append(a) }
            for i in 1...pieces { let t = Double(i)/Double(pieces); pts.append(CGPoint(x: a.x+(b.x-a.x)*t, y: a.y+(b.y-a.y)*t)) }
        }
        guard pts.count >= 2 else { return input }
        var arc = [0.0]
        for (a, b) in zip(pts, pts.dropFirst()) { arc.append(arc.last! + hypot(b.x-a.x, b.y-a.y)) }
        let total = arc.last!
        guard total >= 14 else { return pts }
        let wave = 60 + rng.next() * 40, phase = rng.next() * 2 * .pi, amp = amplitude * (0.7 + rng.next() * 0.6)
        var corner = [Bool](repeating: false, count: pts.count)
        for i in 1..<(pts.count-1) {
            let ax = pts[i].x-pts[i-1].x, ay = pts[i].y-pts[i-1].y, bx = pts[i+1].x-pts[i].x, by = pts[i+1].y-pts[i].y
            let la = hypot(ax, ay), lb = hypot(bx, by)
            if la > 0.001, lb > 0.001 { corner[i] = (ax*bx+ay*by)/(la*lb) < 0.82 } // about 35 degrees
        }
        var moved = pts
        for i in 0..<pts.count where !corner[i] {
            let prev = pts[max(0, i-1)], next = pts[min(pts.count-1, i+1)]
            let tx = next.x-prev.x, ty = next.y-prev.y, tl = max(0.001, hypot(tx, ty))
            let envelope = min(1, arc[i]/6, (total-arc[i])/6)
            let offset = amp * sin(2 * .pi * arc[i] / wave + phase) * max(0, envelope)
            moved[i] = CGPoint(x: pts[i].x - ty/tl*offset, y: pts[i].y + tx/tl*offset)
        }
        // Quadratic midpoint smoothing through non-corner points; corners stay sharp.
        var out = [moved[0]]
        func add(_ p: CGPoint) { if let last = out.last, hypot(last.x-p.x, last.y-p.y) < 1e-6 { return }; out.append(p) }
        for i in 1..<(moved.count-1) {
            if corner[i] { add(moved[i]); continue }
            let m1 = CGPoint(x: (moved[i-1].x+moved[i].x)/2, y: (moved[i-1].y+moved[i].y)/2)
            let m2 = CGPoint(x: (moved[i].x+moved[i+1].x)/2, y: (moved[i].y+moved[i+1].y)/2)
            add(m1)
            for t in [1.0/3, 2.0/3, 1.0] {
                let u = 1-t
                add(CGPoint(x: u*u*m1.x+2*u*t*moved[i].x+t*t*m2.x, y: u*u*m1.y+2*u*t*moved[i].y+t*t*m2.y))
            }
        }
        add(moved[moved.count-1])
        return out
    }

    static func image(_ picture: MacPicture, through step: Int, width: CGFloat = 640, dark: Bool = true, handDrawn: Bool? = nil) -> NSBitmapImageRep? {
        let fit = bounds(picture), height = max(60, ceil(width*fit.height/max(1,fit.width)))
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(width), pixelsHigh: Int(height), bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let graphics = NSGraphicsContext(bitmapImageRep: bitmap) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        graphics.cgContext.translateBy(x: 0,y: height); graphics.cgContext.scaleBy(x: 1,y: -1)
        NSGraphicsContext.current = NSGraphicsContext(cgContext: graphics.cgContext, flipped: true)
        NSAppearance(named: dark ? .darkAqua : .aqua)?.performAsCurrentDrawingAppearance {
            Theme.page.setFill(); CGRect(x: 0,y: 0,width: width,height: height).fill()
            draw(picture, in: CGRect(x: 0,y: 0,width: width,height: height), through: step, handDrawn: handDrawn)
        }
        NSGraphicsContext.restoreGraphicsState(); return bitmap
    }
}

/// A label's placement from the font's own metrics. `shift` is the distance from the item's y to the alphabetic
/// baseline for each SVG baseline keyword; the glyph box runs from `top` to `bottom` around it.
struct PictureTextLayout {
    var font: NSFont; var width: CGFloat; var offset: CGFloat; var shift: CGFloat
    var top: CGFloat { shift - font.ascender }
    var bottom: CGFloat { shift - font.descender }
    var box: CGRect { CGRect(x: -offset, y: top, width: width, height: bottom - top) }
}

/// Font lookup and measuring without the main actor, so the engine's picture check (off the main thread) can use
/// real glyph widths instead of the core's conservative character-count bounds.
enum PictureFonts {
    nonisolated static func font(_ token: String, _ size: Double) -> NSFont {
        let base = NSFont.systemFont(ofSize: size)
        if token.contains("sans") { return base }
        return base.fontDescriptor.withDesign(.serif).flatMap { NSFont(descriptor: $0, size: size) } ?? base
    }
    nonisolated static func measure(_ text: String, token: String, size: Double, anchor: String, baseline: String) -> PictureTextLayout {
        let f = font(token, size), width = (text as NSString).size(withAttributes: [.font: f]).width
        let offset = anchor == "middle" ? width / 2 : anchor == "end" ? width : 0
        let shift: CGFloat
        switch baseline {
        case "middle": shift = f.xHeight / 2
        case "central": shift = (f.ascender + f.descender) / 2
        case "hanging": shift = f.capHeight
        case "text-before-edge": shift = f.ascender
        case "text-after-edge": shift = f.descender
        default: shift = 0 // alphabetic and auto
        }
        return PictureTextLayout(font: f, width: width, offset: offset, shift: shift)
    }
}
