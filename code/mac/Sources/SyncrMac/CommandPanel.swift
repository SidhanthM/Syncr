import AppKit

/// One row of the command panel: a slash command, or a note title for `[[`.
struct PanelRow {
    var icon: String
    var title: String
    /// The name as typed (`/h1`), shown at the right in muted monospace.
    var trailing: String
    var group: String
    var run: () -> Void
}

/// The slash menu's panel (editor-v2 §3.2). It lives in the unscaled layer above the canvas, so its text is the same
/// size at every page zoom. Up to eight rows show, the rest scroll, and the chosen row is always in view.
@MainActor final class CommandPanel: NSView {
    static let width: CGFloat = 320, rowHeight: CGFloat = 30, headerHeight: CGFloat = 24, visibleRows = 8
    /// The 120 ms fade of the app's own surfaces (never the stipple: that is the companion's).
    static let fadeSeconds: CFTimeInterval = Motion.menu
    private enum Item { case header(String), row(Int) }
    private(set) var rows: [PanelRow] = []
    private(set) var selected = 0
    private var items: [Item] = []
    private var scroll: CGFloat = 0
    private var showGroups = false
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    var onChange: (() -> Void)?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.masksToBounds = false
        layer?.shadowOpacity = 0.18; layer?.shadowRadius = 14; layer?.shadowOffset = CGSize(width: 0, height: -4)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseMoved, .activeInKeyWindow, .inVisibleRect], owner: self))
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func set(rows: [PanelRow], showGroups: Bool) {
        self.rows = rows; self.showGroups = showGroups
        selected = min(selected, max(0, rows.count - 1))
        rebuild(); scrollSelectedIntoView(); needsDisplay = true
    }
    func resetSelection() { selected = 0; scroll = 0; needsDisplay = true }
    private func rebuild() {
        items = []
        var last = ""
        for (i, row) in rows.enumerated() {
            if showGroups, row.group != last, !row.group.isEmpty { items.append(.header(row.group)); last = row.group }
            items.append(.row(i))
        }
    }
    private func height(_ item: Item) -> CGFloat { if case .header = item { return Self.headerHeight }; return Self.rowHeight }
    private var contentHeight: CGFloat { items.reduce(0) { $0 + height($1) } }
    /// The panel's own height: everything up to eight rows, headers included.
    var fittedHeight: CGFloat {
        var shown = 0, total: CGFloat = 0
        for item in items {
            if case .row = item { if shown == Self.visibleRows { break }; shown += 1 }
            total += height(item)
        }
        return total + 12
    }
    private func top(ofRow index: Int) -> CGFloat {
        var y: CGFloat = 0
        for item in items { if case .row(let r) = item, r == index { return y }; y += height(item) }
        return y
    }
    private func scrollSelectedIntoView() {
        let visible = bounds.height > 0 ? bounds.height - 12 : fittedHeight - 12
        let y = top(ofRow: selected)
        if y < scroll {
            let index = items.firstIndex { if case .row(let r) = $0 { return r == selected }; return false } ?? 0
            var hasHeader = false
            if index > 0, case .header = items[index - 1] { hasHeader = true }
            scroll = y - (hasHeader ? Self.headerHeight : 0)
        }
        if y + Self.rowHeight > scroll + visible { scroll = y + Self.rowHeight - visible }
        scroll = max(0, min(scroll, max(0, contentHeight - visible)))
    }
    func move(_ by: Int) {
        guard !rows.isEmpty else { return }
        selected = (selected + by + rows.count) % rows.count
        scrollSelectedIntoView(); needsDisplay = true; onChange?()
    }
    func runSelected() { if rows.indices.contains(selected) { rows[selected].run() } }

    private func row(at point: CGPoint) -> Int? {
        var y: CGFloat = 6 - scroll
        for item in items {
            let h = height(item)
            if case .row(let r) = item, point.y >= y, point.y < y + h { return r }
            y += h
        }
        return nil
    }
    override func mouseMoved(with event: NSEvent) {
        if let r = row(at: convert(event.locationInWindow, from: nil)), r != selected { selected = r; needsDisplay = true }
    }
    override func mouseDown(with event: NSEvent) {
        if let r = row(at: convert(event.locationInWindow, from: nil)) { selected = r; needsDisplay = true; runSelected() }
    }
    override func scrollWheel(with event: NSEvent) {
        scroll = max(0, min(scroll - event.scrollingDeltaY, max(0, contentHeight - (bounds.height - 12))))
        needsDisplay = true
    }

    func debugRowTitles() -> [String] { rows.map(\.title) }
    /// Checks: where row `index` is, in this view's own (flipped) coordinates, and how far the list is scrolled.
    func debugRowRect(_ index: Int) -> CGRect { CGRect(x: 0, y: 6 - scroll + top(ofRow: index), width: bounds.width, height: Self.rowHeight) }
    var debugScroll: CGFloat { scroll }
    var debugSelected: Int { selected }
    var debugVisibleHeight: CGFloat { bounds.height - 12 }

    override func draw(_ dirtyRect: NSRect) {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            let card = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: 10, yRadius: 10)
            Theme.panel.setFill(); card.fill()
            Theme.hairlineStrong.setStroke(); card.lineWidth = 1; card.stroke()
            NSGraphicsContext.saveGraphicsState()
            NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 9, yRadius: 9).addClip()
            var y: CGFloat = 6 - scroll
            let name = NSFont.monospacedSystemFont(ofSize: 11.5, weight: .regular)
            for item in items {
                let h = height(item)
                defer { y += h }
                guard y + h > 0, y < bounds.height else { continue }
                switch item {
                case .header(let label):
                    (label.uppercased() as NSString).draw(at: CGPoint(x: 14, y: y + 8), withAttributes: [.font: NSFont.systemFont(ofSize: 10, weight: .medium), .foregroundColor: Theme.muted, .kern: 0.6])
                case .row(let i):
                    let r = rows[i]
                    if i == selected {
                        Theme.hover.setFill()
                        NSBezierPath(roundedRect: CGRect(x: 6, y: y + 1, width: bounds.width - 12, height: h - 2), xRadius: 6, yRadius: 6).fill()
                    }
                    if let image = NSImage(systemSymbolName: r.icon, accessibilityDescription: nil)?.withSymbolConfiguration(.init(pointSize: 13, weight: .regular)) {
                        let tinted = NSImage(size: image.size, flipped: false) { rect in
                            image.draw(in: rect); Theme.soft.set(); rect.fill(using: .sourceAtop); return true
                        }
                        tinted.draw(in: CGRect(x: 16, y: y + (h - tinted.size.height) / 2, width: tinted.size.width, height: tinted.size.height))
                    }
                    let trailing = r.trailing as NSString
                    let tw = trailing.size(withAttributes: [.font: name]).width
                    trailing.draw(at: CGPoint(x: bounds.width - 14 - tw, y: y + (h - 14) / 2), withAttributes: [.font: name, .foregroundColor: Theme.muted])
                    let title = r.title as NSString
                    let para = NSMutableParagraphStyle(); para.lineBreakMode = .byTruncatingTail
                    title.draw(in: CGRect(x: 44, y: y + (h - 16) / 2, width: bounds.width - 44 - tw - 24, height: 16), withAttributes: [.font: NSFont.systemFont(ofSize: 13), .foregroundColor: Theme.ink, .paragraphStyle: para])
                }
            }
            NSGraphicsContext.restoreGraphicsState()
        }
    }

    /// Fades in or out over the 120 ms, or just appears with Reduce motion.
    func fade(in show: Bool, done: (() -> Void)? = nil) {
        guard !AppPreferences.shared.reducedMotion, let layer else { isHidden = !show; done?(); return }
        isHidden = false
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = show ? 0 : 1; a.toValue = show ? 1 : 0; a.duration = Self.fadeSeconds
        layer.opacity = show ? 1 : 0
        CATransaction.begin()
        CATransaction.setCompletionBlock { done?() }
        layer.add(a, forKey: "fade")
        CATransaction.commit()
    }
}
