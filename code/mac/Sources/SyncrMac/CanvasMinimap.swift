import AppKit

/// The minimap's maths (xnotes `CanvasMinimap`): 168 x 120 points, 14 from the bottom-right corner, 8 of padding. It maps the
/// union of the content and the viewport, so the whole canvas and where you are in it are always on show.
struct CanvasMinimapMap: Equatable {
    static let size = CGSize(width: 168, height: 120)
    static let margin: CGFloat = 14
    static let padding: CGFloat = 8

    /// Note-unit rect the map covers, and points per note unit.
    let extent: CGRect
    let scale: CGFloat
    /// Where the extent's origin sits inside the map (it is centred in the padded box).
    let offset: CGPoint

    init(content: CGRect?, viewport: CGRect) {
        var e = viewport
        if let content, !content.isNull { e = e.union(content) }
        e.size.width = max(1, e.width); e.size.height = max(1, e.height)
        extent = e
        let boxW = Self.size.width - 2 * Self.padding, boxH = Self.size.height - 2 * Self.padding
        scale = min(boxW / e.width, boxH / e.height)
        offset = CGPoint(x: Self.padding + (boxW - e.width * scale) / 2, y: Self.padding + (boxH - e.height * scale) / 2)
    }

    /// A note-unit rect as a rect in the map (origin top-left).
    func rect(_ r: CGRect) -> CGRect {
        CGRect(x: offset.x + (r.minX - extent.minX) * scale, y: offset.y + (r.minY - extent.minY) * scale, width: r.width * scale, height: r.height * scale)
    }

    /// The note unit under a point of the map: a tap centres the view there.
    func unit(at p: CGPoint) -> CGPoint {
        CGPoint(x: extent.minX + (p.x - offset.x) / scale, y: extent.minY + (p.y - offset.y) / scale)
    }

    /// Items drawn as rectangles never vanish: at least 1.5 points a side.
    func itemRect(_ r: CGRect) -> CGRect {
        let m = rect(r)
        return CGRect(x: m.minX, y: m.minY, width: max(1.5, m.width), height: max(1.5, m.height))
    }
}

/// The minimap, a card in the canvas's bottom-right corner. Draws item boxes and the viewport outline in the accent
/// colour; a tap or drag centres the view. Items come in as rects so a 5,000-stroke canvas costs one array.
@MainActor final class CanvasMinimapView: NSView {
    var items: [CGRect] = [] { didSet { needsDisplay = true } }
    var viewport: CGRect = .zero { didSet { if viewport != oldValue { needsDisplay = true } } }
    var content: CGRect? { didSet { needsDisplay = true } }
    /// A tap or drag: centre the view on this note unit.
    var onCentre: ((CGPoint) -> Void)?
    override var isFlipped: Bool { true }

    override init(frame: NSRect) {
        super.init(frame: NSRect(origin: .zero, size: CanvasMinimapMap.size))
        wantsLayer = true
        layer?.cornerRadius = 8
        layer?.borderWidth = 1
        setAccessibilityLabel("Minimap")
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var intrinsicContentSize: NSSize { CanvasMinimapMap.size }
    override func updateLayer() {
        layer?.backgroundColor = Theme.panel.withAlphaComponent(0.92).cgColor
        layer?.borderColor = Theme.muted.withAlphaComponent(0.35).cgColor
    }
    override var wantsUpdateLayer: Bool { true }

    private var map: CanvasMinimapMap { CanvasMinimapMap(content: content, viewport: viewport) }

    override func draw(_ dirty: NSRect) {
        let map = map
        Theme.muted.withAlphaComponent(0.55).setFill()
        for item in items { NSBezierPath.fill(map.itemRect(item)) }
        let outline = NSBezierPath(rect: map.rect(viewport))
        outline.lineWidth = 1.5
        NSColor.controlAccentColor.setStroke()
        outline.stroke()
    }

    override func mouseDown(with event: NSEvent) { centre(event) }
    override func mouseDragged(with event: NSEvent) { centre(event) }
    private func centre(_ event: NSEvent) {
        onCentre?(map.unit(at: convert(event.locationInWindow, from: nil)))
    }
    override func hitTest(_ point: NSPoint) -> NSView? { isHidden ? nil : super.hitTest(point) }
}
