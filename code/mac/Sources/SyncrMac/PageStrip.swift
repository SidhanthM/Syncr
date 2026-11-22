import AppKit
import SyncrCore

/// The thumbnail sidebar of a Note (Cmd-Option-1): 224 wide, 150 wide thumbnails at the page's aspect ratio, 10 apart, in
/// page order. Click goes to a page, Cmd and Shift click select several, right-click is the page menu, and dragging
/// reorders with a drop line and auto-scroll within 56 of an edge.
@MainActor final class PageStrip: ThemedView {
    struct Item: Equatable {
        var sheetId: String
        var width: CGFloat, height: CGFloat
        /// Anything that changes what the thumbnail shows (ink count, page size, ruling): the picture is redrawn when it changes.
        var signature: Int
    }
    static let width: CGFloat = 224, thumbWidth: CGFloat = 150, gap: CGFloat = 10, padding: CGFloat = 8, barHeight: CGFloat = 52

    var onSelect: ((String) -> Void)?
    var onMenu: ((PageStripAction, [String]) -> Void)?
    var onMove: (([String], String?) -> Void)?
    /// Draws a page's picture at this pixel size; nil when it cannot be drawn.
    var picture: ((String, CGSize) -> CGImage?)?

    private let scroll = NSScrollView()
    private let content = StripContent()
    private let bar = ThemedView()
    private let count = NSTextField(labelWithString: "")
    private(set) var items: [Item] = []
    private(set) var current: String?
    private(set) var selection: [String] = []
    private var anchor: String?
    private var cells: [String: ThumbCell] = [:]
    private var drop: Int?
    private let dropLine = ThemedView()
    private var dragging = false
    private var autoscroll: Timer?
    private var barHeightConstraint: NSLayoutConstraint!

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.well; rightLine = true
        widthAnchor.constraint(equalToConstant: Self.width).isActive = true
        scroll.drawsBackground = false; scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true
        scroll.documentView = content
        content.strip = self
        dropLine.fill = Theme.ink; dropLine.radius = 1; dropLine.isHidden = true
        content.addSubview(dropLine)
        bar.fill = Theme.raise; bar.topLine = true
        let clear = NSButton(image: NSImage(systemSymbolName: "xmark", accessibilityDescription: "Clear selection")!, target: self, action: #selector(clearSelection))
        let all = NSButton(title: "Select all", target: self, action: #selector(selectEvery)); all.bezelStyle = .recessed
        let delete = NSButton(title: "Delete", target: self, action: #selector(deleteSelected)); delete.bezelStyle = .recessed
        clear.isBordered = false; count.font = Theme.font; count.textColor = Theme.ink
        let row = NSStackView(views: [clear, count, NSView(), all, delete]); row.spacing = 8; row.edgeInsets = NSEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        row.translatesAutoresizingMaskIntoConstraints = false; bar.addSubview(row)
        NSLayoutConstraint.activate([row.leadingAnchor.constraint(equalTo: bar.leadingAnchor), row.trailingAnchor.constraint(equalTo: bar.trailingAnchor),
                                     row.topAnchor.constraint(equalTo: bar.topAnchor), row.bottomAnchor.constraint(equalTo: bar.bottomAnchor)])
        for v in [scroll, bar] as [NSView] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        barHeightConstraint = bar.heightAnchor.constraint(equalToConstant: 0)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: topAnchor), scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: bar.topAnchor),
            bar.leadingAnchor.constraint(equalTo: leadingAnchor), bar.trailingAnchor.constraint(equalTo: trailingAnchor), bar.bottomAnchor.constraint(equalTo: bottomAnchor),
            barHeightConstraint
        ])
        bar.isHidden = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    // MARK: Content

    private func frame(at index: Int) -> NSRect {
        var y = Self.padding
        for i in 0..<index { y += cellHeight(items[i]) + Self.gap }
        return NSRect(x: (Self.width - Self.thumbWidth) / 2, y: y, width: Self.thumbWidth, height: thumbHeight(items[index]))
    }
    private func thumbHeight(_ item: Item) -> CGFloat { max(40, min(400, (Self.thumbWidth * item.height / max(1, item.width)).rounded())) }
    /// Thumbnail and the number under it.
    private func cellHeight(_ item: Item) -> CGFloat { thumbHeight(item) + 18 }

    func reload(items new: [Item], current: String?) {
        let changed = new != items
        items = new
        selection = selection.filter { id in new.contains { $0.sheetId == id } }
        if selection.isEmpty, let current { selection = [current] }
        self.current = current
        guard changed else { updateSelection(); return }
        var seen = Set<String>()
        var y = Self.padding
        for (i, item) in new.enumerated() {
            seen.insert(item.sheetId)
            let cell = cells[item.sheetId] ?? { let c = ThumbCell(); c.strip = self; content.addSubview(c); cells[item.sheetId] = c; return c }()
            cell.configure(item: item, number: i + 1, picture: cell.item?.signature == item.signature ? cell.image : nil, height: thumbHeight(item))
            cell.frame = NSRect(x: (Self.width - Self.thumbWidth) / 2, y: y, width: Self.thumbWidth, height: cellHeight(item))
            y += cellHeight(item) + Self.gap
        }
        for (id, cell) in cells where !seen.contains(id) { cell.removeFromSuperview(); cells[id] = nil }
        content.frame = NSRect(x: 0, y: 0, width: Self.width, height: y + Self.padding)
        updateSelection()
        needsDisplay = true
        drawPictures()
    }

    /// Draws the thumbnails of the cells in view that have none (or a stale one), a few per turn so the strip never stalls.
    func drawPictures() {
        let visible = scroll.contentView.bounds.insetBy(dx: 0, dy: -300)
        var drawn = 0
        for cell in cells.values where cell.image == nil && cell.frame.intersects(visible) {
            guard let item = cell.item else { continue }
            let scale = window?.backingScaleFactor ?? 2
            let size = CGSize(width: Self.thumbWidth * scale, height: cell.thumbHeight * scale)
            cell.image = picture?(item.sheetId, size)
            cell.needsDisplay = true
            drawn += 1
            if drawn >= 4 { DispatchQueue.main.async { [weak self] in MainActor.assumeIsolated { self?.drawPictures() } }; break }
        }
    }
    func invalidatePictures() { cells.values.forEach { $0.image = nil }; drawPictures() }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        NotificationCenter.default.addObserver(forName: NSView.boundsDidChangeNotification, object: scroll.contentView, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.drawPictures() }
        }
        scroll.contentView.postsBoundsChangedNotifications = true
    }

    /// Scrolls so the page is in view (the strip opens scrolled to the current page).
    func reveal(_ id: String) {
        guard let i = items.firstIndex(where: { $0.sheetId == id }), items.count > 0 else { return }
        let r = frame(at: i).insetBy(dx: 0, dy: -Self.padding)
        content.scrollToVisible(r)
        drawPictures()
    }

    // MARK: Selection

    func select(_ id: String, extend: Bool, range: Bool) {
        if range, let anchor, let a = items.firstIndex(where: { $0.sheetId == anchor }), let b = items.firstIndex(where: { $0.sheetId == id }) {
            selection = items[min(a, b)...max(a, b)].map(\.sheetId)
        } else if extend {
            if let at = selection.firstIndex(of: id) { selection.remove(at: at) } else { selection.append(id) }
            anchor = id
        } else { selection = [id]; anchor = id; onSelect?(id) }
        updateSelection()
    }
    private func updateSelection() {
        for (id, cell) in cells { cell.selected = selection.contains(id); cell.isCurrent = id == current }
        let many = selection.count > 1
        bar.isHidden = !many
        barHeightConstraint.constant = many ? Self.barHeight : 0
        count.stringValue = "\(selection.count) selected"
    }
    /// The picked pages in page order.
    var picked: [String] { items.map(\.sheetId).filter(selection.contains) }
    @objc private func clearSelection() { selection = current.map { [$0] } ?? []; updateSelection() }
    @objc private func selectEvery() { selection = items.map(\.sheetId); updateSelection() }
    @objc private func deleteSelected() { onMenu?(.delete, picked) }

    // MARK: Menu

    func menu(for id: String) -> NSMenu {
        if !selection.contains(id) { selection = [id]; updateSelection() }
        let ids = picked
        let menu = NSMenu()
        func item(_ title: String, _ action: PageStripAction, symbol: String) {
            let it = MenuAction(title, symbol: symbol) { [weak self] in self?.onMenu?(action, ids) }
            menu.addItem(it)
        }
        item("Add Page", .add, symbol: "plus.square")
        item("Duplicate", .duplicate, symbol: "plus.square.on.square")
        let rotate = NSMenuItem(title: "Rotate…", action: nil, keyEquivalent: ""); rotate.image = NSImage(systemSymbolName: "rotate.right", accessibilityDescription: nil)
        let sub = NSMenu()
        for (title, quarters) in [("90° Clockwise", 1), ("90° Anticlockwise", 3), ("180°", 2)] {
            sub.addItem(MenuAction(title, symbol: nil) { [weak self] in self?.onMenu?(.rotate(quarters), ids) })
        }
        rotate.submenu = sub; menu.addItem(rotate)
        item("Page Size…", .size, symbol: "aspectratio")
        menu.addItem(.separator())
        item("Delete", .delete, symbol: "trash")
        return menu
    }

    // MARK: Reorder by dragging

    fileprivate func dragBegan(_ id: String) {
        if !selection.contains(id) { selection = [id]; updateSelection() }
        dragging = true
        cells.values.forEach { $0.alphaValue = selection.contains($0.item?.sheetId ?? "") ? 0.45 : 1 }
    }
    fileprivate func dragMoved(to point: NSPoint) {
        guard dragging else { return }
        // The drop slot is the gap before the first cell whose middle is below the pointer.
        var slot = items.count
        for i in items.indices where point.y < frame(at: i).midY { slot = i; break }
        drop = slot
        let y = slot == items.count ? (items.isEmpty ? Self.padding : frame(at: items.count - 1).maxY + 18 + Self.gap / 2) : frame(at: slot).minY - Self.gap / 2
        dropLine.frame = NSRect(x: (Self.width - Self.thumbWidth) / 2, y: y - 1, width: Self.thumbWidth, height: 2)
        dropLine.isHidden = false
        // Auto-scroll within 56 of the strip's top or bottom, up to 22 a step.
        let view = scroll.contentView.bounds
        let fromTop = point.y - view.minY, fromBottom = view.maxY - point.y
        autoscroll?.invalidate(); autoscroll = nil
        let step: CGFloat = fromTop < 56 ? -22 * (1 - max(0, fromTop) / 56) : fromBottom < 56 ? 22 * (1 - max(0, fromBottom) / 56) : 0
        if step != 0 {
            let timer = Timer(timeInterval: 0.016, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let v = self.scroll.contentView
                    var o = v.bounds.origin; o.y = min(max(0, o.y + step), max(0, self.content.frame.height - v.bounds.height))
                    v.scroll(to: o); self.scroll.reflectScrolledClipView(v)
                    self.dragMoved(to: NSPoint(x: point.x, y: point.y + (o.y - v.bounds.origin.y) + step))
                }
            }
            RunLoop.main.add(timer, forMode: .common); autoscroll = timer
        }
    }
    fileprivate func dragEnded(cancelled: Bool) {
        autoscroll?.invalidate(); autoscroll = nil
        dropLine.isHidden = true
        cells.values.forEach { $0.alphaValue = 1 }
        defer { dragging = false; drop = nil }
        guard dragging, !cancelled, let slot = drop else { return }
        let moving = picked, order = items.map(\.sheetId)
        let rest = order.filter { !moving.contains($0) }
        // The slot counts every page; take the picked pages in front of it out of the count.
        let at = slot - items[..<slot].filter { moving.contains($0.sheetId) }.count
        guard Array(rest[..<at]) + moving + Array(rest[at...]) != order else { return }
        onMove?(moving, at == 0 ? nil : rest[at - 1])
    }
}

enum PageStripAction: Equatable { case add, duplicate, rotate(Int), size, delete }

private final class StripContent: NSView {
    weak var strip: PageStrip?
    override var isFlipped: Bool { true }
}

/// One page in the strip: the picture, its border and its number.
private final class ThumbCell: NSView {
    weak var strip: PageStrip?
    private(set) var item: PageStrip.Item?
    var image: CGImage? { didSet { needsDisplay = true } }
    var selected = false { didSet { needsDisplay = true } }
    var isCurrent = false { didSet { needsDisplay = true } }
    private var number = 1
    private(set) var thumbHeight: CGFloat = 100
    private var downAt: NSPoint?
    private var dragStarted = false
    override var isFlipped: Bool { true }
    func configure(item: PageStrip.Item, number: Int, picture: CGImage?, height: CGFloat) {
        if self.item?.signature != item.signature { image = picture }
        self.item = item; self.number = number; thumbHeight = height; needsDisplay = true
    }
    override func draw(_ dirtyRect: NSRect) {
        let r = NSRect(x: 0, y: 0, width: bounds.width, height: thumbHeight)
        Theme.page.setFill(); r.fill()
        if let image, let ctx = NSGraphicsContext.current?.cgContext {
            ctx.saveGState(); ctx.translateBy(x: 0, y: thumbHeight); ctx.scaleBy(x: 1, y: -1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: r.width, height: r.height)); ctx.restoreGState()
        }
        let ring = NSBezierPath(rect: r.insetBy(dx: selected ? 1 : 0.5, dy: selected ? 1 : 0.5))
        (selected ? Theme.ink : isCurrent ? Theme.soft : Theme.hairlineStrong).setStroke()
        ring.lineWidth = selected ? 2 : 1; ring.stroke()
        let s = NSAttributedString(string: "\(number)", attributes: [.font: NSFont.systemFont(ofSize: 11), .foregroundColor: isCurrent ? Theme.ink : Theme.muted])
        s.draw(at: NSPoint(x: (bounds.width - s.size().width) / 2, y: thumbHeight + 2))
    }
    override func mouseDown(with event: NSEvent) {
        downAt = superview?.convert(event.locationInWindow, from: nil); dragStarted = false
        guard let id = item?.sheetId else { return }
        let flags = event.modifierFlags
        strip?.select(id, extend: flags.contains(.command), range: flags.contains(.shift))
    }
    override func mouseDragged(with event: NSEvent) {
        guard let id = item?.sheetId, let down = downAt, let p = superview?.convert(event.locationInWindow, from: nil) else { return }
        if !dragStarted {
            guard hypot(p.x - down.x, p.y - down.y) > 4 else { return }
            dragStarted = true; strip?.dragBegan(id)
        }
        strip?.dragMoved(to: p)
    }
    override func mouseUp(with event: NSEvent) { if dragStarted { strip?.dragEnded(cancelled: false) }; dragStarted = false }
    override func menu(for event: NSEvent) -> NSMenu? { item.flatMap { strip?.menu(for: $0.sheetId) } }
}

private final class MenuAction: NSMenuItem {
    private let handler: () -> Void
    init(_ title: String, symbol: String?, _ handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        image = symbol.flatMap { NSImage(systemSymbolName: $0, accessibilityDescription: nil) }
    }
    required init(coder: NSCoder) { fatalError("not used") }
    @objc private func fire() { handler() }
}
