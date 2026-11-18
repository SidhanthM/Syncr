import AppKit

/// Reads the cubic path data of `EmptyArtData` (M and C only) into a path on the sheet, y turned upward for AppKit.
func emptyArtPath(_ d: String) -> CGPath {
    let path = CGMutablePath()
    let tokens = d.split(separator: " ")
    var numbers: [CGFloat] = []
    func flush(_ command: Character) {
        if command == "M", numbers.count >= 2 { path.move(to: CGPoint(x: numbers[0], y: EmptyArtData.size.height - numbers[1])) }
        if command == "C", numbers.count >= 6 {
            path.addCurve(to: CGPoint(x: numbers[4], y: EmptyArtData.size.height - numbers[5]),
                          control1: CGPoint(x: numbers[0], y: EmptyArtData.size.height - numbers[1]),
                          control2: CGPoint(x: numbers[2], y: EmptyArtData.size.height - numbers[3]))
        }
        numbers = []
    }
    var command: Character = "M"
    for token in tokens {
        if token == "M" || token == "C" { flush(command); command = token.first! } else if let n = Double(token) { numbers.append(CGFloat(n)) }
    }
    flush(command)
    return path
}

/// A rough length of a stroke: the control polygon's, which is close enough to order the strokes of a drawing.
func emptyArtLength(_ path: CGPath) -> CGFloat {
    var length: CGFloat = 0, last = CGPoint.zero
    path.applyWithBlock { element in
        let e = element.pointee
        let count = e.type == .addCurveToPoint ? 3 : (e.type == .moveToPoint || e.type == .addLineToPoint ? 1 : 0)
        for i in 0..<count {
            let p = e.points[i]
            if !(e.type == .moveToPoint) { length += hypot(p.x - last.x, p.y - last.y) }
            last = p
        }
    }
    return length
}

/// What portion of the whole draw-on each stroke covers, in order: `(start, share)` pairs of 0...1.
func emptyArtSchedule(_ lengths: [CGFloat]) -> [(start: Double, share: Double)] {
    let total = Double(lengths.reduce(0, +))
    var at = 0.0
    return lengths.map { len in
        let share = total > 0 ? Double(len) / total : 0
        defer { at += share }
        return (at, share)
    }
}

/// A small hand-drawn picture for an empty place (`EmptyArtData`): drawn on once with `play()`, then still. Ink lines
/// with a faint second pass. With Reduce motion it is simply there. It takes no clicks.
@MainActor final class EmptyArtView: NSView {
    private let strokes: [ArtStroke]
    private var layers: [CAShapeLayer] = []
    private let schedule: [(start: Double, share: Double)]

    init(_ strokes: [ArtStroke]) {
        self.strokes = strokes
        let paths = strokes.map { emptyArtPath($0.d) }
        schedule = emptyArtSchedule(paths.map(emptyArtLength))
        super.init(frame: CGRect(origin: .zero, size: EmptyArtData.size))
        wantsLayer = true
        for (stroke, path) in zip(strokes, paths) {
            let layer = CAShapeLayer()
            layer.path = path
            layer.fillColor = nil
            layer.lineWidth = stroke.width
            layer.lineCap = .round; layer.lineJoin = .round
            self.layer?.addSublayer(layer)
            layers.append(layer)
        }
        tint()
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var intrinsicContentSize: NSSize { EmptyArtData.size }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); tint() }
    override func layout() { super.layout(); layers.forEach { $0.frame = bounds } }
    private func tint() {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            for (layer, stroke) in zip(layers, strokes) {
                layer.strokeColor = stroke.faint ? Theme.muted.withAlphaComponent(0.55).cgColor : Theme.ink.cgColor
            }
        }
    }

    /// Draws the picture on once, stroke after stroke, over `Motion.drawOn`. Reduce motion shows it finished.
    func play() {
        layers.forEach { $0.removeAllAnimations() }
        guard !AppPreferences.shared.reducedMotion else { return }
        let now = layers.first.map { $0.convertTime(CACurrentMediaTime(), from: nil) } ?? 0
        for (layer, slot) in zip(layers, schedule) {
            let draw = CABasicAnimation(keyPath: "strokeEnd")
            draw.fromValue = 0; draw.toValue = 1
            draw.beginTime = now + slot.start * Motion.drawOn
            draw.duration = max(0.01, slot.share * Motion.drawOn)
            draw.fillMode = .backwards
            draw.timingFunction = Motion.easeOut
            layer.add(draw, forKey: "draw")
        }
    }
}
