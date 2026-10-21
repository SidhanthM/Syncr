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
    init(_ title: String, enabled: Bool = true, _ handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        isEnabled = enabled
    }
    required init(coder: NSCoder) { fatalError("not used") }
    @objc private func fire() { handler() }
}

private final class Outline: NSOutlineView {
    var menuFor: ((Int) -> NSMenu?)?
    override func menu(for event: NSEvent) -> NSMenu? {
        menuFor?(row(at: convert(event.locationInWindow, from: nil)))
    }
}

/// Left column: title filter, notebooks as folders with their notes, Inbox, Recently deleted.
@MainActor final class SidebarView: ThemedView, NSOutlineViewDataSource, NSOutlineViewDelegate, NSTextFieldDelegate {
    weak var delegate: SidebarDelegate?
    private let outline = Outline()
    private let search = NSSearchField()
    private var items: [Item] = []
    private var children: [ObjectIdentifier: [Item]] = [:]
    private var quiet = false // programmatic reloads must not look like clicks

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.desk
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
        newNote.toolTip = "New note (⌘N)"
        newNotebook.toolTip = "New notebook (⇧⌘N)"
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
        outline.rowHeight = 26
        outline.indentationPerLevel = 14
        outline.floatsGroupRows = false
        outline.dataSource = self
        outline.delegate = self
        outline.menuFor = { [weak self] row in self?.menu(row: row) }
        let scroll = NSScrollView()
        scroll.documentView = outline
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(header); addSubview(scroll)
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: topAnchor, constant: 10), header.heightAnchor.constraint(equalToConstant: 24),
            header.leadingAnchor.constraint(equalTo: leadingAnchor), header.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func focusFilter() { window?.makeFirstResponder(search) }

    /// Rebuild from the workspace's sections and show `active` selected.
    func update(_ sections: [SidebarSection], active: String?) {
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

    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        guard let item = item as? Item else { return nil }
        let cell = NSTableCellView()
        let symbol: String, text: String
        if let note = item.note {
            symbol = item.section.kind == .trash ? "trash" : (note.readOnly ? "lock" : "doc.text")
            text = note.title
        } else {
            switch item.section.kind {
            case .inbox: symbol = "tray"
            case .notebook: symbol = "folder"
            case .tags: symbol = "number"
            case .trash: symbol = "trash"
            }
            text = item.section.title
        }
        let image = NSImageView(image: NSImage(systemSymbolName: symbol, accessibilityDescription: nil)!)
        image.contentTintColor = Theme.muted
        let field = NSTextField(labelWithString: text)
        field.font = item.note == nil ? .systemFont(ofSize: 13, weight: .medium) : Theme.font
        field.textColor = item.note?.readOnly == true ? Theme.muted : Theme.ink
        field.lineBreakMode = .byTruncatingTail
        field.delegate = self
        field.identifier = NSUserInterfaceItemIdentifier(item.note?.id ?? "")
        image.translatesAutoresizingMaskIntoConstraints = false
        field.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(image); cell.addSubview(field)
        cell.textField = field
        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2), image.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
            image.widthAnchor.constraint(equalToConstant: 16),
            field.leadingAnchor.constraint(equalTo: image.trailingAnchor, constant: 6),
            field.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -6), field.centerYAnchor.constraint(equalTo: cell.centerYAnchor)
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
            menu.addItem(ActionItem("New Notebook") { d.sidebarNewNotebook() })
            return menu
        }
        if let note = item.note {
            if item.section.kind == .tags { return nil }
            if item.section.kind == .trash {
                menu.addItem(ActionItem("Restore") { d.sidebarRestore(note: note.id) })
                menu.addItem(ActionItem("Delete Permanently") { d.sidebarPurge(note: note.id) })
                return menu
            }
            menu.addItem(ActionItem("Open") { d.sidebarOpen(note: note.id, newTab: false) })
            menu.addItem(ActionItem("Open in New Tab") { d.sidebarOpen(note: note.id, newTab: true) })
            menu.addItem(.separator())
            menu.addItem(ActionItem("Rename", enabled: !note.readOnly) { [weak self] in self?.beginRename(note: note.id) })
            let move = NSMenuItem(title: "Move to", action: nil, keyEquivalent: "")
            let submenu = NSMenu()
            submenu.addItem(ActionItem("Inbox", enabled: !note.readOnly && item.section.kind != .inbox) { d.sidebarMove(note: note.id, to: nil) })
            for name in d.sidebarNotebookNames() {
                submenu.addItem(ActionItem(name, enabled: !note.readOnly && item.section.kind != .notebook(name)) { d.sidebarMove(note: note.id, to: name) })
            }
            move.submenu = submenu
            move.isEnabled = !note.readOnly
            menu.addItem(move)
            menu.addItem(.separator())
            menu.addItem(ActionItem("Delete") { d.sidebarDelete(note: note.id) })
            return menu
        }
        switch item.section.kind {
        case .inbox:
            menu.addItem(ActionItem("New Note Here") { d.sidebarSelectNotebook(nil); d.sidebarNewNote() })
        case .notebook(let name):
            menu.addItem(ActionItem("New Note Here") { d.sidebarSelectNotebook(name); d.sidebarNewNote() })
            menu.addItem(ActionItem("Rename Notebook") { [weak self] in self?.beginRename(notebook: name) })
            menu.addItem(ActionItem("Delete Notebook") { d.sidebarDelete(notebook: name) })
        case .tags, .trash: return nil
        }
        menu.addItem(.separator())
        menu.addItem(ActionItem("New Notebook") { d.sidebarNewNotebook() })
        return menu
    }
}

private extension NSOutlineView {
    func isExpandedOrExpand(_ item: Any) -> Bool {
        if !isItemExpanded(item) { expandItem(item) }
        return isItemExpanded(item)
    }
}
