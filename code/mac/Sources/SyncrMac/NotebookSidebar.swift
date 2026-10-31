import AppKit

/// The right pane for the open note: outline, backlinks, then the open and pinned companion threads.
@MainActor final class NotebookSidebar: ThemedView {
    private let stack = FlippedStack()
    private let scroll = NSScrollView()
    let threads = CompanionThreads()
    var onOpen: ((String, Int) -> Void)?
    var onOutline: ((Int) -> Void)?
    private var actions: [() -> Void] = []
    static let inner: CGFloat = 212

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.panel
        leftLine = true
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        stack.edgeInsets = NSEdgeInsets(top: 14, left: 14, bottom: 18, right: 14)
        scroll.frame = bounds
        scroll.autoresizingMask = [.width, .height]
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.documentView = stack
        addSubview(scroll)
        threads.onResize = { [weak self] in self?.needsLayout = true }
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func update(title: String, text: String, index: NotebookIndex) {
        stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() }
        actions = []
        func label(_ text: String, colour: NSColor) {
            let label = NSTextField(labelWithString: text)
            label.font = Theme.font
            label.textColor = colour
            label.lineBreakMode = .byTruncatingTail
            stack.addArrangedSubview(label)
            label.widthAnchor.constraint(lessThanOrEqualToConstant: Self.inner).isActive = true
        }
        func heading(_ text: String) {
            if let last = stack.arrangedSubviews.last { stack.setCustomSpacing(18, after: last) }
            let label = NSTextField(labelWithAttributedString: Theme.groupLabel(text))
            stack.addArrangedSubview(label)
            stack.setCustomSpacing(8, after: label)
        }
        func row(_ text: String, detail: String? = nil, indent: Int = 0, _ action: @escaping () -> Void) {
            let b = NSButton(title: "", target: self, action: #selector(clicked(_:)))
            b.isBordered = false
            b.alignment = .left
            let title = NSMutableAttributedString(string: String(repeating: "    ", count: indent) + text,
                                                  attributes: [.font: Theme.font, .foregroundColor: Theme.soft])
            if let detail { title.append(NSAttributedString(string: " · " + detail, attributes: [.font: Theme.smallFont, .foregroundColor: Theme.muted])) }
            b.attributedTitle = title
            b.lineBreakMode = .byTruncatingTail
            b.tag = actions.count
            b.toolTip = detail.map { text + "\n" + $0 } ?? text
            actions.append(action)
            stack.addArrangedSubview(b)
            b.widthAnchor.constraint(lessThanOrEqualToConstant: Self.inner).isActive = true
        }
        heading("Outline")
        let outline = NotebookIndex.outline(text)
        if outline.isEmpty { label("No headings", colour: Theme.muted) }
        for h in outline { row(h.title, indent: h.level - 1) { [weak self] in self?.onOutline?(h.offset) } }
        heading("Backlinks")
        let links = index.backlinks(to: title)
        if links.isEmpty { label("No notes link here", colour: Theme.muted) }
        for link in links { row(link.title, detail: link.line) { [weak self] in self?.onOpen?(link.id, link.offset) } }
        if let last = stack.arrangedSubviews.last { stack.setCustomSpacing(18, after: last) }
        stack.addArrangedSubview(threads)
        threads.widthAnchor.constraint(equalToConstant: Self.inner).isActive = true
        needsLayout = true
    }

    override func layout() {
        super.layout()
        stack.frame = CGRect(x: 0, y: 0, width: max(1, scroll.contentSize.width), height: stack.fittingSize.height)
    }
    @objc private func clicked(_ sender: NSButton) { actions[sender.tag]() }
}
