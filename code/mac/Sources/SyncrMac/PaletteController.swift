import AppKit

struct PaletteItem {
    let id: String
    let title: String
    let detail: String
}

private final class PalettePanel: NSPanel {
    var onCommandReturn: (() -> Void)?
    var onResign: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if event.keyCode == 36, event.modifierFlags.contains(.command) { onCommandReturn?(); return true }
        return super.performKeyEquivalent(with: event)
    }
    override func resignKey() { super.resignKey(); onResign?() }
}

/// One floating list with a filter field, used for the quick switcher, the command palette and
/// the notebook/trash pickers. `items` is asked again on every keystroke, so ordering stays with
/// the model. Enter chooses; Cmd-Enter chooses with `newTab` true; Escape or clicking away closes.
@MainActor final class PaletteController: NSObject, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
    private let panel: PalettePanel
    private let field = NSTextField()
    private let table = NSTableView()
    private var results: [PaletteItem] = []
    private var source: ((String) -> [PaletteItem])?
    private var choose: ((PaletteItem, Bool) -> Void)?

    override init() {
        panel = PalettePanel(contentRect: NSRect(x: 0, y: 0, width: 560, height: 330),
                             styleMask: [.borderless], backing: .buffered, defer: true)
        super.init()
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .floating
        panel.onCommandReturn = { [weak self] in self?.accept(newTab: true) }
        panel.onResign = { [weak self] in self?.dismiss() }

        let card = ThemedView(frame: panel.contentView!.bounds)
        card.fill = Theme.panel
        card.radius = 10
        card.autoresizingMask = [.width, .height]
        card.wantsLayer = true
        card.layer?.cornerRadius = 10
        card.layer?.borderWidth = 1
        card.layer?.borderColor = Theme.hairlineStrong.cgColor
        panel.contentView!.addSubview(card)

        field.isBordered = false
        field.drawsBackground = false
        field.focusRingType = .none
        field.font = .systemFont(ofSize: 17)
        field.delegate = self
        field.translatesAutoresizingMaskIntoConstraints = false
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("item"))
        table.addTableColumn(column)
        table.headerView = nil
        table.backgroundColor = .clear
        table.rowHeight = 28
        table.dataSource = self
        table.delegate = self
        table.target = self
        table.action = #selector(clicked)
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let rule = ThemedView()
        rule.topLine = true
        rule.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(field); card.addSubview(rule); card.addSubview(scroll)
        NSLayoutConstraint.activate([
            field.topAnchor.constraint(equalTo: card.topAnchor, constant: 12),
            field.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            field.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            rule.topAnchor.constraint(equalTo: field.bottomAnchor, constant: 10), rule.heightAnchor.constraint(equalToConstant: 1),
            rule.leadingAnchor.constraint(equalTo: card.leadingAnchor), rule.trailingAnchor.constraint(equalTo: card.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: rule.bottomAnchor, constant: 4),
            scroll.leadingAnchor.constraint(equalTo: card.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: card.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -6)
        ])
    }

    var isVisible: Bool { panel.isVisible }
    func debugQuery(_ text: String) { field.stringValue = text; reload() }
    func debugAccept() { accept(newTab: false) }

    func present(over window: NSWindow, placeholder: String, items: @escaping (String) -> [PaletteItem],
                 choose: @escaping (PaletteItem, Bool) -> Void) {
        source = items
        self.choose = choose
        field.placeholderString = placeholder
        field.stringValue = ""
        reload()
        let frame = window.frame
        panel.setFrameOrigin(NSPoint(x: frame.midX - panel.frame.width / 2, y: frame.maxY - 90 - panel.frame.height))
        panel.makeKeyAndOrderFront(nil)
        panel.makeFirstResponder(field)
    }

    func dismiss() {
        guard panel.isVisible else { return }
        panel.orderOut(nil)
        source = nil; choose = nil
    }

    private func reload() {
        results = source?(field.stringValue) ?? []
        table.reloadData()
        if !results.isEmpty { table.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false) }
    }

    private func accept(newTab: Bool) {
        guard table.selectedRow >= 0, table.selectedRow < results.count, let choose else { return }
        let item = results[table.selectedRow]
        dismiss()
        choose(item, newTab)
    }

    @objc private func clicked() {
        guard table.clickedRow >= 0 else { return }
        table.selectRowIndexes(IndexSet(integer: table.clickedRow), byExtendingSelection: false)
        accept(newTab: NSEvent.modifierFlags.contains(.command))
    }

    func controlTextDidChange(_ obj: Notification) { reload() }

    func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.moveDown(_:)): step(1)
        case #selector(NSResponder.moveUp(_:)): step(-1)
        case #selector(NSResponder.insertNewline(_:)): accept(newTab: false)
        case #selector(NSResponder.cancelOperation(_:)): dismiss()
        default: return false
        }
        return true
    }

    private func step(_ delta: Int) {
        guard !results.isEmpty else { return }
        let row = min(max(0, table.selectedRow + delta), results.count - 1)
        table.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        table.scrollRowToVisible(row)
    }

    func numberOfRows(in tableView: NSTableView) -> Int { results.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let item = results[row]
        let title = NSTextField(labelWithString: item.title)
        title.font = Theme.font
        title.textColor = Theme.ink
        title.lineBreakMode = .byTruncatingTail
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let detail = NSTextField(labelWithString: item.detail)
        detail.font = Theme.smallFont
        detail.textColor = Theme.muted
        let cell = NSStackView(views: [title, detail])
        cell.distribution = .fill
        cell.edgeInsets = NSEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
        return cell
    }
}
