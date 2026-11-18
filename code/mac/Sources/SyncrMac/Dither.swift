import AppKit

/// Every duration of the Mac's motion, in one place; the phone's `Motion.kt` has the same names. Calm: nothing moves at
/// rest, motion only answers the user, every change runs 150 to 280 ms on ease-out (no springs, no bounce), appearing and
/// leaving is the stipple dissolve, line art is drawn on once, and only the waiting band loops while the companion works.
enum Motion {
    // The table: three durations and one easing.
    /// A press, a hover, a menu: the quickest thing that still reads as an answer.
    static let quick: CFTimeInterval = 0.15
    /// Things appearing or leaving in place: the sticky title, a text line that changed.
    static let standard: CFTimeInterval = 0.22
    /// Shape changes (the sidebar) and line art being drawn on: the slowest ordinary motion.
    static let slow: CFTimeInterval = 0.28
    /// Ease-out: starts fast, settles gently. The only easing.
    nonisolated(unsafe) static let easeOut = CAMediaTimingFunction(controlPoints: 0.2, 0, 0, 1)

    // Names for what uses them.
    /// A one-shot hand-drawn reveal: an outline drawing itself on.
    static let drawOn: CFTimeInterval = slow
    /// The stipple dissolve of what the owner's own actions bring in or send away.
    static let dissolve: CFTimeInterval = standard

    /// A companion card, picture or answer dissolving in from its top edge down.
    static let reveal: CFTimeInterval = 0.42
    /// The same dissolve run backwards when something is dismissed, replaced or undone: two thirds of the time.
    static let leave: CFTimeInterval = reveal * 2 / 3
    /// A line of companion text that changed (a step's name): a plain fade, where a stipple would be noise.
    static let textFade: CFTimeInterval = standard
    /// One loop of the waiting band across placeholder lines.
    static let drift: CFTimeInterval = 1.5
    /// The sticky title dissolving in or out.
    static let title: CFTimeInterval = dissolve
    /// The owner's own menus and panels.
    static let menu: CFTimeInterval = quick
    /// Hover actions on a card.
    static let hover: CFTimeInterval = quick
    /// A dissolve does not start on the page while the pen is down or was active this recently (150 ms, in ns).
    static let penQuietNs: UInt64 = 150_000_000
}

/// Ordered-dither stipple: the one motion the companion uses. Something new arrives by dissolving in through a 4 x 4
/// Bayer pattern from its top edge down, and waiting is a denser band of stipple drifting across placeholder lines.
/// Each is a layer mask stepping through a few tiny images (one pixel per stipple cell, scaled up unsmoothed), so the
/// render server plays it and the main thread does nothing while ink or typing is going on. With Reduce motion there
/// is no dissolve and the placeholder stands still.
@MainActor enum Dither {
    private static let bayer: [UInt8] = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
    /// The pattern's threshold at a cell, in 0..<1.
    static func threshold(_ x: Int, _ y: Int) -> Double { (Double(bayer[(y & 3) * 4 + (x & 3)]) + 0.5) / 16 }

    /// Whether the phone's pen is writing now. Set once by the window (`attach(pen:)`); a dissolve that would start
    /// while it is true does not: the content lands finished, so nothing animates on the page under a stroke.
    static var penBusy: () -> Bool = { false }
    static func pen(strokeOpen: Bool, idleNs: UInt64?) -> Bool { strokeOpen || (idleNs.map { $0 < Motion.penQuietNs } ?? false) }
    static func attach(pen engine: LiveInk) { penBusy = { engine.strokeOpen || engine.penActive(within: Motion.penQuietNs) } }
    private static var penDown: Bool { freeze == nil && penBusy() }

    /// `SYNCR_MAC_DITHER_FREEZE=<0...1>` (checks and screenshots): every dissolve stops at that point and stays.
    static let freeze: Double? = ProcessInfo.processInfo.environment["SYNCR_MAC_DITHER_FREEZE"].flatMap(Double.init).map { min(1, max(0, $0)) }

    /// An alpha mask of `columns` x `rows` cells, row 0 at the top; `on` says which cells show.
    static func mask(columns: Int, rows: Int, on: (Int, Int) -> Bool) -> CGImage? {
        var bytes = [UInt8](repeating: 0, count: columns * rows * 4)
        for y in 0..<rows { for x in 0..<columns where on(x, y) { bytes[(y * columns + x) * 4 + 3] = 255 } }
        return bytes.withUnsafeMutableBytes { raw in
            CGContext(data: raw.baseAddress, width: columns, height: rows, bitsPerComponent: 8, bytesPerRow: columns * 4,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?.makeImage()
        }
    }

    /// Stipple cells are about three screen points whatever the view's own scale is (the page is in canvas units).
    static func cell(for view: NSView, points wanted: CGFloat = 3) -> CGFloat {
        let points = abs(view.convert(CGSize(width: 1, height: 1), to: nil).width)
        return min(64, max(1, wanted / max(0.01, points)))
    }

    /// How far the dissolve front has passed a cell: the pattern breaks up an edge that travels down the view.
    static func arrival(_ x: Int, _ y: Int, rows: Int) -> Double { threshold(x, y) * 0.55 + Double(y) / Double(max(1, rows)) * 0.45 }

    /// The masks of a dissolve over a view of this size, from nothing to everything.
    static func revealFrames(size: CGSize, cell: CGFloat, count: Int = 14) -> [CGImage] {
        let columns = max(4, min(512, Int((size.width / cell).rounded(.up)))), rows = max(4, min(512, Int((size.height / cell).rounded(.up))))
        return (0...count).compactMap { k in
            let passed = Double(k) / Double(count)
            return mask(columns: columns, rows: rows) { x, y in k == count || arrival(x, y, rows: rows) < passed }
        }
    }

    static let revealSeconds: CFTimeInterval = Motion.reveal

    /// Dissolves `view` in. Call once its frame is set and it is in its superview. `elapsed`: the dissolve began that
    /// long ago on a view this one replaces (a card rebuilt as its text grows), and carries on from there.
    static func reveal(_ view: NSView, duration: CFTimeInterval = revealSeconds, elapsed: CFTimeInterval = 0) {
        guard freeze != nil || (!AppPreferences.shared.reducedMotion && elapsed < duration) else { return }
        guard !penDown else { return }
        view.wantsLayer = true
        guard let layer = view.layer, layer.bounds.width > 1, layer.bounds.height > 1 else { return }
        let frames = revealFrames(size: layer.bounds.size, cell: cell(for: view))
        guard frames.count > 1 else { return }
        let mask = CALayer()
        mask.frame = layer.bounds
        mask.magnificationFilter = .nearest
        mask.contentsGravity = .resize
        // A flipped view's layer draws its contents top row first; an unflipped one needs the wipe turned over.
        if !view.isFlipped { mask.setAffineTransform(CGAffineTransform(scaleX: 1, y: -1)) }
        if let freeze {
            mask.contents = frames[min(frames.count - 1, Int((freeze * Double(frames.count - 1)).rounded()))]
            layer.mask = mask
            return
        }
        mask.contents = frames[frames.count - 1]
        let steps = CAKeyframeAnimation(keyPath: "contents")
        steps.values = frames
        steps.calculationMode = .discrete
        steps.duration = duration
        if elapsed > 0 { steps.beginTime = layer.convertTime(CACurrentMediaTime(), from: nil) - elapsed }
        CATransaction.begin()
        CATransaction.setCompletionBlock { [weak layer, weak mask] in if let mask, layer?.mask === mask { layer?.mask = nil } }
        mask.add(steps, forKey: "reveal")
        layer.mask = mask
        CATransaction.commit()
    }

    /// Dissolves `view` out the way it came in, backwards and in two thirds of the time; `done` runs when it is gone
    /// (at once with Reduce motion). The view is left as it is: the caller removes it.
    static func dissolveOut(_ view: NSView, duration: CFTimeInterval = Motion.leave, done: @escaping @MainActor () -> Void) {
        view.wantsLayer = true
        guard freeze != nil || !AppPreferences.shared.reducedMotion, !penDown, let layer = view.layer, layer.bounds.width > 1, layer.bounds.height > 1 else { done(); return }
        let frames = revealFrames(size: layer.bounds.size, cell: cell(for: view))
        guard frames.count > 1 else { done(); return }
        let mask = CALayer()
        mask.frame = layer.bounds
        mask.magnificationFilter = .nearest
        mask.contentsGravity = .resize
        if !view.isFlipped { mask.setAffineTransform(CGAffineTransform(scaleX: 1, y: -1)) }
        if let freeze {
            mask.contents = frames[min(frames.count - 1, Int(((1 - freeze) * Double(frames.count - 1)).rounded()))]
            layer.mask = mask
            return
        }
        mask.contents = frames[0]
        let steps = CAKeyframeAnimation(keyPath: "contents")
        steps.values = Array(frames.reversed())
        steps.calculationMode = .discrete
        steps.duration = duration
        CATransaction.begin()
        CATransaction.setCompletionBlock { MainActor.assumeIsolated { done() } }
        mask.add(steps, forKey: "leave")
        layer.mask = mask
        CATransaction.commit()
    }

    /// Fades `view` in: for a line of text that changed, where a stipple would be noise.
    static func fade(_ view: NSView, duration: CFTimeInterval = Motion.textFade) {
        guard !AppPreferences.shared.reducedMotion, !penDown else { return }
        view.wantsLayer = true
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0; fade.toValue = 1; fade.duration = duration
        fade.timingFunction = Motion.easeOut
        view.layer?.add(fade, forKey: "fade")
    }
}

/// Placeholder lines where the companion's words will be, while it has written nothing yet: faint stipple with a
/// denser band drifting from left to right. The last line is short, as a paragraph's is.
@MainActor final class DitherLines: NSView {
    private let fill = CALayer(), stipple = CALayer()
    private let lines: Int, lineHeight: CGFloat, gap: CGFloat, cellPoints: CGFloat
    private var built = CGSize.zero
    override var isFlipped: Bool { true }

    /// `cellPoints`: the stipple's cell on screen; small marks want a finer one than the page does.
    init(width: CGFloat, lines: Int = 3, lineHeight: CGFloat, gap: CGFloat? = nil, cellPoints: CGFloat = 3) {
        self.lines = max(1, lines); self.lineHeight = lineHeight; self.gap = gap ?? lineHeight * 0.9; self.cellPoints = cellPoints
        super.init(frame: CGRect(x: 0, y: 0, width: width, height: CGFloat(max(1, lines)) * lineHeight + CGFloat(max(0, lines - 1)) * (gap ?? lineHeight * 0.9)))
        wantsLayer = true
        stipple.magnificationFilter = .nearest
        stipple.contentsGravity = .resize
        fill.mask = stipple
        layer?.addSublayer(fill)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() { tint() }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); tint() }
    private func tint() { effectiveAppearance.performAsCurrentDrawingAppearance { fill.backgroundColor = Theme.muted.cgColor } }
    // Started as soon as the view is on screen, not only from a layout pass: a card swapped in for another one in
    // the same turn does not always get one.
    override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); built = .zero; refresh() }
    override func layout() { super.layout(); refresh() }
    private func refresh() {
        fill.frame = bounds; stipple.frame = fill.bounds
        guard window != nil, bounds.size != built, bounds.width > 1 else { return }
        built = bounds.size
        tint()
        start()
    }

    /// The band's strength at `at` (0...1 along the line) when its middle is at `middle`.
    static func band(_ at: Double, middle: Double) -> Double { let d = (at - middle) / 0.22; return exp(-d * d) }

    private func frames(count: Int) -> [CGImage] {
        let cell = Dither.cell(for: self, points: cellPoints)
        let columns = max(8, min(512, Int((bounds.width / cell).rounded(.up)))), rows = max(2, min(256, Int((bounds.height / cell).rounded(.up))))
        let pitch = (lineHeight + gap) / cell, thick = lineHeight / cell
        return (0..<count).compactMap { k in
            // The band enters from before the left edge and leaves past the right one, so the loop has no jump.
            let middle = -0.35 + 1.7 * Double(k) / Double(count)
            return Dither.mask(columns: columns, rows: rows) { x, y in
                let line = Int(CGFloat(y) / pitch)
                guard line < lines, CGFloat(y) - CGFloat(line) * pitch < thick else { return false }
                let at = Double(x) / Double(columns)
                if line == lines - 1, lines > 1, at > 0.6 { return false }
                return Dither.threshold(x, y) < 0.2 + 0.6 * Self.band(at, middle: middle)
            }
        }
    }

    private func start() {
        stipple.removeAllAnimations()
        if AppPreferences.shared.reducedMotion || Dither.freeze != nil {
            // Still: the band parked off the edge leaves the even faint stipple.
            stipple.contents = frames(count: 1).first
            return
        }
        let images = frames(count: 28)
        stipple.contents = images.first
        let drift = CAKeyframeAnimation(keyPath: "contents")
        drift.values = images
        drift.calculationMode = .discrete
        drift.duration = Motion.drift
        drift.repeatCount = .infinity
        stipple.add(drift, forKey: "drift")
    }
}
