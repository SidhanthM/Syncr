import AppKit

@MainActor protocol SidebarDelegate: AnyObject {
    func sidebarOpen(note id: String, newTab: Bool)
    func sidebarSelectNotebook(_ name: String?)
    func sidebarFilterChanged(_ text: String)
    func sidebarExpanded(_ key: String, _ expanded: Bool)
    func sidebarNewNote()
    func sidebarNewNotebook()
    func sidebarRename(note id: String, to title: String)
    func sidebarRename(notebook name: String, to new: String)
    func sidebarMove(note id: String, to notebook: String?)
    func sidebarDelete(note id: String)
    func sidebarDelete(notebook name: String)
    func sidebarRestore(note id: String)
    func sidebarPurge(note id: String)
    func sidebarNotebookNames() -> [String]
}

private final class Item: NSObject {
    let section: SidebarSection
    let note: SidebarNote?
    init(section: SidebarSection, note: SidebarNote?) { self.section = section; self.note = note }
}

private final class ActionItem: NSMenuItem {
    private let handler: () -> Void
    init(_ title: String, symbol: String, enabled: Bool = true, _ handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        isEnabled = enabled
        image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(
            NSImage.SymbolConfiguration(pointSize: 14, weight: .regular))
    }
    required init(coder: NSCoder) { fatalError("not used") }
    @objc private func fire() { handler() }
}

/// Selection is a quiet raised fill with ink text, never the system accent.
private final class RowView: NSTableRowView {
    override var isEmphasized: Bool { get { false } set {} }
    override var isSelected: Bool { didSet { tint() } }
    override func drawSelection(in dirtyRect: NSRect) {
        Theme.raise.setFill()
        NSBezierPath(roundedRect: bounds.insetBy(dx: 8, dy: 1), xRadius: 6, yRadius: 6).fill()
    }
    override func didAddSubview(_ subview: NSView) { super.didAddSubview(subview); tint() }
    private func tint() {
        for case let cell as NSTableCellView in subviews where cell.identifier?.rawValue == "note" {
            cell.textField?.textColor = isSelected ? Theme.ink : (cell.textField?.tag == 1 ? Theme.muted : Theme.soft)
        }
    }
}

private final class Outline: NSOutlineView {
    var menuFor: ((Int) -> NSMenu?)?
    override func menu(for event: NSEvent) -> NSMenu? {
        menuFor?(row(at: convert(event.locationInWindow, from: nil)))
    }
}

/// File tree: title filter, Inbox, notebooks as groups with their notes, tags, Recently deleted. Notes from
/// the phone carry a small "phone" mark, and "phone · live" while the phone is writing in them.
@MainActor final class SidebarView: ThemedView, NSOutlineViewDataSource, NSOutlineViewDelegate, NSTextFieldDelegate {
    weak var delegate: SidebarDelegate?
    private let outline = Outline()
    private let search = NSSearchField()
    private var items: [Item] = []
    private var children: [ObjectIdentifier: [Item]] = [:]
    private var quiet = false // programmatic reloads must not look like clicks
    private var liveNote: String?
    private var newNoteButton: NSButton?
    private var newNotebookButton: NSButton?
    private let emptyArt = EmptyArtView(EmptyArtData.library)
    private let emptyCaption = NSTextField(labelWithString: "")
    private var emptyShown: Bool? // the picture is drawn on once each time the sidebar goes empty

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.well
        rightLine = true
        search.placeholderString = "Filter notes"
        search.sendsSearchStringImmediately = true
        search.target = self
        search.action = #selector(filterChanged)
        search.controlSize = .small
        let newNote = NSButton(image: NSImage(systemSymbolName: "square.and.pencil", accessibilityDescription: "New note")!,
                               target: self, action: #selector(newNote))
        let newNotebook = NSButton(image: NSImage(systemSymbolName: "folder.badge.plus", accessibilityDescription: "New notebook")!,
                                   target: self, action: #selector(newNotebook))
        for button in [newNote, newNotebook] { button.isBordered = false; button.contentTintColor = Theme.muted }
        newNoteButton = newNote; newNotebookButton = newNotebook
        let header = NSStackView(views: [search, newNote, newNotebook])
        header.spacing = 6
        header.edgeInsets = NSEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        header.translatesAutoresizingMaskIntoConstraints = false

        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("tree"))
        outline.addTableColumn(column)
        outline.outlineTableColumn = column
        outline.headerView = nil
        outline.style = .plain
        outline.backgroundColor = .clear
        outline.rowHeight = 24
        outline.indentationPerLevel = 0
        outline.intercellSpacing = NSSize(width: 0, height: 2)
        outline.floatsGroupRows = false
        outline.dataSource = self
        outline.delegate = self
        outline.menuFor = { [weak self] row in self?.menu(row: row) }
        let scroll = NSScrollView()
        scroll.documentView = outline
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let wordmark = WordmarkView()
        wordmark.translatesAutoresizingMaskIntoConstraints = false
        addSubview(wordmark); addSubview(header); addSubview(scroll)
        emptyCaption.font = Theme.smallFont; emptyCaption.textColor = Theme.muted; emptyCaption.alignment = .center
        for view in [emptyArt, emptyCaption] { view.translatesAutoresizingMaskIntoConstraints = false; view.isHidden = true; addSubview(view) }
        NSLayoutConstraint.activate([
            wordmark.topAnchor.constraint(equalTo: topAnchor, constant: 12), wordmark.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            header.topAnchor.constraint(equalTo: wordmark.bottomAnchor, constant: 10), header.heightAnchor.constraint(equalToConstant: 24),
            header.leadingAnchor.constraint(equalTo: leadingAnchor), header.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            emptyArt.centerXAnchor.constraint(equalTo: centerXAnchor), emptyArt.centerYAnchor.constraint(equalTo: scroll.centerYAnchor, constant: 40),
            emptyArt.widthAnchor.constraint(equalToConstant: EmptyArtData.size.width), emptyArt.heightAnchor.constraint(equalToConstant: EmptyArtData.size.height),
            emptyCaption.centerXAnchor.constraint(equalTo: centerXAnchor), emptyCaption.topAnchor.constraint(equalTo: emptyArt.bottomAnchor, constant: 10)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func focusFilter() { window?.makeFirstResponder(search) }

    func updateHotkeys(_ store: HotkeyStore) {
        newNoteButton?.toolTip = store.hint(.newNote)
        newNotebookButton?.toolTip = store.hint(.newNotebook)
    }

    /// Rebuild from the workspace's sections and show `active` selected.
    func update(_ sections: [SidebarSection], active: String?, live: String? = nil) {
        liveNote = live
        quiet = true
        defer { quiet = false }
        children = [:]
        items = sections.map { section in
            let parent = Item(section: section, note: nil)
            children[ObjectIdentifier(parent)] = section.notes.map { Item(section: section, note: $0) }
            return parent
        }
        outline.reloadData()
        for item in items where item.section.expanded { outline.expandItem(item) }
        selectActive(active)
        showEmpty(items.allSatisfy { $0.section.kind == .trash || $0.section.notes.isEmpty })
    }

    /// An empty library or a filter that matches nothing gets a small drawing and a line, in place of a bare list.
    private func showEmpty(_ empty: Bool) {
        let filtering = !search.stringValue.trimmingCharacters(in: .whitespaces).isEmpty
        emptyCaption.stringValue = filtering ? "No notes match" : "No notes yet"
        emptyArt.isHidden = !empty; emptyCaption.isHidden = !empty
        if emptyShown != empty { emptyShown = empty; if empty { emptyArt.play() } }
    }

    private func selectActive(_ id: String?) {
        guard let id else { outline.deselectAll(nil); return }
        for item in items where item.section.kind != .trash {
            if let child = children[ObjectIdentifier(item)]?.first(where: { $0.note?.id == id }) {
                let row = outline.row(forItem: child)
                if row >= 0 { outline.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false) }
                return
            }
        }
        outline.deselectAll(nil)
    }

    // MARK: Data source

    func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        guard let item = item as? Item else { return items.count }
        return children[ObjectIdentifier(item)]?.count ?? 0
    }
    func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        guard let item = item as? Item else { return items[index] }
        return children[ObjectIdentifier(item)]![index]
    }
    func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool { (item as? Item)?.note == nil }

    func outlineView(_ outlineView: NSOutlineView, rowViewForItem item: Any) -> NSTableRowView? { RowView() }

    func outlineView(_ outlineView: NSOutlineView, heightOfRowByItem item: Any) -> CGFloat {
        (item as? Item)?.note == nil ? 30 : 24
    }

    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        guard let item = item as? Item else { return nil }
        let cell = NSTableCellView()
        let field = NSTextField(labelWithString: "")
        field.lineBreakMode = .byTruncatingTail
        field.delegate = self
        field.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(field)
        cell.textField = field
        guard let note = item.note else {
            // Group label: small caps style, aligned with the notes' text.
            field.attributedStringValue = Theme.groupLabel(item.section.title)
            field.identifier = NSUserInterfaceItemIdentifier("")
            NSLayoutConstraint.activate([
                field.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 18),
                field.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
                field.bottomAnchor.constraint(equalTo: cell.bottomAnchor, constant: -3)
            ])
            return cell
        }
        cell.identifier = NSUserInterfaceItemIdentifier("note")
        field.stringValue = note.title
        field.font = Theme.font
        let quiet = item.section.kind == .trash
        field.tag = quiet ? 1 : 0
        field.textColor = quiet ? Theme.muted : Theme.soft
        field.identifier = NSUserInterfaceItemIdentifier(note.id)
        let mark = NSTextField(labelWithString: note.readOnly ? (note.id == liveNote ? "phone · live" : "phone") : (note.detail ?? (note.id == liveNote ? "live" : "")))
        mark.font = .systemFont(ofSize: 10)
        mark.textColor = note.id == liveNote ? Theme.connected : Theme.muted
        mark.setContentCompressionResistancePriority(.required, for: .horizontal)
        mark.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(mark)
        NSLayoutConstraint.activate([
            field.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 18),
            field.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
            mark.leadingAnchor.constraint(greaterThanOrEqualTo: field.trailingAnchor, constant: 6),
            mark.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -16),
            mark.firstBaselineAnchor.constraint(equalTo: field.firstBaselineAnchor)
        ])
        return cell
    }

    // MARK: Selection and expansion

    func outlineViewSelectionDidChange(_ notification: Notification) {
        guard !quiet, outline.selectedRow >= 0, let item = outline.item(atRow: outline.selectedRow) as? Item else { return }
        if let note = item.note {
            if item.section.kind != .trash { delegate?.sidebarOpen(note: note.id, newTab: NSEvent.modifierFlags.contains(.command)) }
            return
        }
        switch item.section.kind {
        case .inbox: delegate?.sidebarSelectNotebook(nil)
        case .notebook(let name): delegate?.sidebarSelectNotebook(name)
        case .tags, .trash: break
        }
        // A folder row toggles on click, like the Obsidian file tree.
        if outline.isItemExpanded(item) { outline.collapseItem(item) } else { outline.expandItem(item) }
    }
    func outlineViewItemDidExpand(_ notification: Notification) { expansion(notification, true) }
    func outlineViewItemDidCollapse(_ notification: Notification) { expansion(notification, false) }
    private func expansion(_ notification: Notification, _ expanded: Bool) {
        guard !quiet, let item = notification.userInfo?["NSObject"] as? Item else { return }
        delegate?.sidebarExpanded(item.section.key, expanded)
    }

    @objc private func filterChanged() { delegate?.sidebarFilterChanged(search.stringValue) }
    @objc private func newNote() { delegate?.sidebarNewNote() }
    @objc private func newNotebook() { delegate?.sidebarNewNotebook() }

    // MARK: Inline rename

    func beginRename(note id: String) {
        for parent in items {
            guard let child = children[ObjectIdentifier(parent)]?.first(where: { $0.note?.id == id }) else { continue }
            if !outline.isExpandedOrExpand(parent) { return }
            edit(child)
            return
        }
    }
    func beginRename(notebook name: String) {
        guard let item = items.first(where: { $0.section.kind == .notebook(name) }) else { return }
        edit(item)
    }
    private func edit(_ item: Item) {
        let row = outline.row(forItem: item)
        guard row >= 0, let cell = outline.view(atColumn: 0, row: row, makeIfNecessary: true) as? NSTableCellView,
              let field = cell.textField else { return }
        outline.scrollRowToVisible(row)
        field.isEditable = true
        window?.makeFirstResponder(field)
        field.selectText(nil)
    }

    func controlTextDidEndEditing(_ obj: Notification) {
        guard let field = obj.object as? NSTextField, field.isEditable else { return }
        field.isEditable = false
        let cancelled = (obj.userInfo?["NSTextMovement"] as? Int) == NSTextMovement.cancel.rawValue
        let row = outline.row(for: field)
        guard !cancelled, row >= 0, let item = outline.item(atRow: row) as? Item else { delegateRefresh(); return }
        if let note = item.note { delegate?.sidebarRename(note: note.id, to: field.stringValue) }
        else if case .notebook(let name) = item.section.kind { delegate?.sidebarRename(notebook: name, to: field.stringValue) }
        else { delegateRefresh() }
    }
    private func delegateRefresh() { delegate?.sidebarFilterChanged(search.stringValue) }

    // MARK: Context menu

    private func menu(row: Int) -> NSMenu? {
        guard let d = delegate else { return nil }
        let menu = NSMenu()
        guard row >= 0, let item = outline.item(atRow: row) as? Item else {
            menu.addItem(ActionItem("New Notebook", symbol: "folder.badge.plus") { d.sidebarNewNotebook() })
            return menu
        }
        if let note = item.note {
            if item.section.kind == .tags { return nil }
            if item.section.kind == .trash {
                menu.addItem(ActionItem("Restore", symbol: "arrow.uturn.backward") { d.sidebarRestore(note: note.id) })
                menu.addItem(ActionItem("Delete Permanently", symbol: "trash.slash") { d.sidebarPurge(note: note.id) })
                return menu
            }
            menu.addItem(ActionItem("Open", symbol: "doc.text") { d.sidebarOpen(note: note.id, newTab: false) })
            menu.addItem(ActionItem("Open in New Tab", symbol: "plus.square.on.square") { d.sidebarOpen(note: note.id, newTab: true) })
            menu.addItem(.separator())
            menu.addItem(ActionItem("Rename", symbol: "pencil", enabled: !note.readOnly) { [weak self] in self?.beginRename(note: note.id) })
            let move = NSMenuItem(title: "Move to", action: nil, keyEquivalent: "")
            move.image = NSImage(systemSymbolName: "folder", accessibilityDescription: nil)
            let submenu = NSMenu()
            submenu.addItem(ActionItem("Inbox", symbol: "tray", enabled: !note.readOnly && item.section.kind != .inbox) { d.sidebarMove(note: note.id, to: nil) })
            for name in d.sidebarNotebookNames() {
                submenu.addItem(ActionItem(name, symbol: "folder", enabled: !note.readOnly && item.section.kind != .notebook(name)) { d.sidebarMove(note: note.id, to: name) })
            }
            move.submenu = submenu
            move.isEnabled = !note.readOnly
            menu.addItem(move)
            menu.addItem(.separator())
            menu.addItem(ActionItem("Delete", symbol: "trash") { d.sidebarDelete(note: note.id) })
            return menu
        }
        switch item.section.kind {
        case .inbox:
            menu.addItem(ActionItem("New Note Here", symbol: "square.and.pencil") { d.sidebarSelectNotebook(nil); d.sidebarNewNote() })
        case .notebook(let name):
            menu.addItem(ActionItem("New Note Here", symbol: "square.and.pencil") { d.sidebarSelectNotebook(name); d.sidebarNewNote() })
            menu.addItem(ActionItem("Rename Notebook", symbol: "pencil") { [weak self] in self?.beginRename(notebook: name) })
            menu.addItem(ActionItem("Delete Notebook", symbol: "trash") { d.sidebarDelete(notebook: name) })
        case .tags, .trash: return nil
        }
        menu.addItem(.separator())
        menu.addItem(ActionItem("New Notebook", symbol: "folder.badge.plus") { d.sidebarNewNotebook() })
        return menu
    }
}

private extension NSOutlineView {
    func isExpandedOrExpand(_ item: Any) -> Bool {
        if !isItemExpanded(item) { expandItem(item) }
        return isItemExpanded(item)
    }
}
