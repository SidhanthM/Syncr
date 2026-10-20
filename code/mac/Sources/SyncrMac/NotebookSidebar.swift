import AppKit

@MainActor final class NotebookSidebar: NSView {
    private let stack = SidebarStack()
    private let scroll = NSScrollView()
    var onOpen: ((String, Int) -> Void)?
    var onOutline: ((Int) -> Void)?
    private var actions: [() -> Void] = []
    override init(frame: NSRect) {
        super.init(frame: frame)
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 14, bottom: 18, right: 14)
        scroll.frame = bounds; scroll.autoresizingMask = [.width, .height]
        scroll.hasVerticalScroller = true; scroll.drawsBackground = false; scroll.documentView = stack
        addSubview(scroll)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    func update(title: String, text: String, index: NotebookIndex) {
        stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() }
        actions = []
        func heading(_ text: String) { let label = NSTextField(labelWithString: text); label.font = .systemFont(ofSize: 12, weight: .semibold); label.textColor = .secondaryLabelColor; stack.addArrangedSubview(label) }
        func row(_ text: String, _ action: @escaping () -> Void) {
            let b = NSButton(title: text, target: self, action: #selector(clicked(_:)))
            b.isBordered = false; b.alignment = .left; b.font = .systemFont(ofSize: 12)
            b.lineBreakMode = .byTruncatingTail; b.tag = actions.count; actions.append(action)
            stack.addArrangedSubview(b); b.widthAnchor.constraint(lessThanOrEqualTo: stack.widthAnchor, constant: -28).isActive = true
        }
        heading("OUTLINE")
        for h in NotebookIndex.outline(text) { row(String(repeating: "  ", count: h.level - 1) + h.title) { [weak self] in self?.onOutline?(h.offset) } }
        heading("BACKLINKS")
        let links = index.backlinks(to: title)
        if links.isEmpty { heading("No backlinks") }
        for link in links { row(link.title + " · " + link.line) { [weak self] in self?.onOpen?(link.id, link.offset) } }
        needsLayout = true
    }
    override func layout() {
        super.layout()
        stack.frame = CGRect(x: 0, y: 0, width: max(1, scroll.contentSize.width), height: stack.fittingSize.height)
    }
    @objc private func clicked(_ sender: NSButton) { actions[sender.tag]() }
}
private final class SidebarStack: NSStackView { override var isFlipped: Bool { true } }
