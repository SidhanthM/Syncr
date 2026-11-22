import AppKit

@MainActor final class SettingsWindow: NSObject, NSTableViewDataSource, NSTableViewDelegate, NSSearchFieldDelegate, NSWindowDelegate {
    typealias Page = SettingsCategory
    private enum Filter: String, CaseIterable { case all = "All", assigned = "Assigned", customized = "Customized", unassigned = "Unassigned" }
    private let store: HotkeyStore
    private let window: NSWindow
    private let search = NSSearchField()
    private let table = NSTableView()
    private let count = NSTextField(labelWithString: "")
    private let hotkeysPage = ThemedView()
    private let generalPage = ThemedView()
    private var filter: Filter = .all
    private var rows: [AppCommand] = []
    private var chips: [FilterChip] = []
    private var recorder: HotkeyRecorder?
    private var nav: [SettingsNavButton] = []
    private let globalSearch = NSSearchField()
    private let contentStack = NSStackView()
    private let contentScroll = NSScrollView()
    private let preferences: AppPreferences
    private var selectedPage: Page = .general
    var onCommand: ((AppCommand) -> Void)?
    var libraryFolder: (() -> URL)?
    var companionContent: (() -> CompanionSettingsView?)?
    private var companionPage: CompanionSettingsView?

    init(store: HotkeyStore, preferences: AppPreferences = .shared) {
        self.store = store; self.preferences = preferences
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1060, height: 760), styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        super.init()
        window.title = "Syncr Settings"
        window.titlebarAppearsTransparent = true
        window.backgroundColor = Theme.panel
        window.minSize = NSSize(width: 940, height: 620)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.setFrameAutosaveName("SyncrSettingsWindow")
        let root = ThemedView(); root.fill = Theme.panel
        let sidebar = ThemedView(); sidebar.fill = Theme.well; sidebar.rightLine = true
        let brand = label("syncr", size: 25)
        brand.font = Theme.serif(27, weight: .medium)
        globalSearch.placeholderString = "Search settings…"
        globalSearch.font = Theme.font; globalSearch.focusRingType = .none
        globalSearch.controlSize = .large; globalSearch.delegate = self
        globalSearch.sendsSearchStringImmediately = true
        globalSearch.target = self; globalSearch.action = #selector(globalSearchChanged)
        globalSearch.setAccessibilityLabel("Search all settings")
        let navTitle = label("PREFERENCES", size: 10, colour: Theme.muted)
        nav = Page.allCases.map { category in
            let button = SettingsNavButton(category)
            button.onClick = { [weak self] in self?.select(category) }
            return button
        }
        let navStack = NSStackView(views: [brand, globalSearch, navTitle] + nav)
        navStack.orientation = .vertical; navStack.alignment = .leading; navStack.spacing = 6
        navStack.setCustomSpacing(22, after: brand)
        navStack.setCustomSpacing(25, after: globalSearch); navStack.setCustomSpacing(10, after: navTitle)
        navStack.translatesAutoresizingMaskIntoConstraints = false; sidebar.addSubview(navStack)
        let footer = SettingsUI.label("Preferences stay on this Mac.", size: 10.5, colour: Theme.muted)
        footer.translatesAutoresizingMaskIntoConstraints = false; sidebar.addSubview(footer)
        for view in [sidebar, hotkeysPage, generalPage] {
            view.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(view)
        }
        NSLayoutConstraint.activate([
            sidebar.leadingAnchor.constraint(equalTo: root.leadingAnchor), sidebar.topAnchor.constraint(equalTo: root.topAnchor),
            sidebar.bottomAnchor.constraint(equalTo: root.bottomAnchor), sidebar.widthAnchor.constraint(equalToConstant: 224),
            navStack.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 16), navStack.topAnchor.constraint(equalTo: sidebar.topAnchor, constant: 24),
            navStack.trailingAnchor.constraint(equalTo: sidebar.trailingAnchor, constant: -16),
            globalSearch.widthAnchor.constraint(equalTo: navStack.widthAnchor), globalSearch.heightAnchor.constraint(equalToConstant: 32),
            footer.leadingAnchor.constraint(equalTo: navStack.leadingAnchor), footer.trailingAnchor.constraint(equalTo: navStack.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: sidebar.bottomAnchor, constant: -22)
        ])
        for button in nav { button.widthAnchor.constraint(equalTo: navStack.widthAnchor).isActive = true }
        for page in [hotkeysPage, generalPage] {
            page.fill = Theme.panel
            NSLayoutConstraint.activate([
                page.leadingAnchor.constraint(equalTo: sidebar.trailingAnchor), page.trailingAnchor.constraint(equalTo: root.trailingAnchor),
                page.topAnchor.constraint(equalTo: root.topAnchor), page.bottomAnchor.constraint(equalTo: root.bottomAnchor)
            ])
        }
        buildHotkeys(); buildContentScroll()
        window.contentView = root
        select(.general)
    }
    private func label(_ text: String, size: CGFloat, colour: NSColor = Theme.ink) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .systemFont(ofSize: size, weight: size >= 20 ? .semibold : .regular)
        field.textColor = colour
        return field
    }
    private func buildContentScroll() {
        contentScroll.drawsBackground = false; contentScroll.hasVerticalScroller = true; contentScroll.autohidesScrollers = true
        contentScroll.translatesAutoresizingMaskIntoConstraints = false; generalPage.addSubview(contentScroll)
        let document = ThemedView(); document.translatesAutoresizingMaskIntoConstraints = false
        contentStack.orientation = .vertical; contentStack.alignment = .leading; contentStack.spacing = 22
        contentStack.translatesAutoresizingMaskIntoConstraints = false; document.addSubview(contentStack)
        contentScroll.documentView = document
        NSLayoutConstraint.activate([
            contentScroll.leadingAnchor.constraint(equalTo: generalPage.leadingAnchor), contentScroll.trailingAnchor.constraint(equalTo: generalPage.trailingAnchor),
            contentScroll.topAnchor.constraint(equalTo: generalPage.topAnchor), contentScroll.bottomAnchor.constraint(equalTo: generalPage.bottomAnchor),
            document.widthAnchor.constraint(equalTo: contentScroll.contentView.widthAnchor),
            contentStack.leadingAnchor.constraint(equalTo: document.leadingAnchor, constant: 32), contentStack.trailingAnchor.constraint(equalTo: document.trailingAnchor, constant: -32),
            contentStack.topAnchor.constraint(equalTo: document.topAnchor, constant: 30), contentStack.bottomAnchor.constraint(equalTo: document.bottomAnchor, constant: -32)
        ])
    }
    private func addContent(_ view: NSView) {
        contentStack.addArrangedSubview(view)
        if !contentStack.constraints.contains(where: { $0.firstItem as? NSView === view && $0.firstAttribute == .width && $0.secondItem as? NSView === contentStack }) {
            view.widthAnchor.constraint(equalTo: contentStack.widthAnchor).isActive = true
        }
    }
    private var query: String { globalSearch.stringValue.trimmingCharacters(in: .whitespacesAndNewlines) }
    private func renderContent() {
        for view in contentStack.arrangedSubviews { contentStack.removeArrangedSubview(view); view.removeFromSuperview() }
        let searching = !query.isEmpty
        let matches = searching ? SettingsCatalog.results(query) : SettingsCatalog.items.filter { $0.category == selectedPage }
        let commands = searching ? HotkeyStore.commands.filter { SettingsCatalog.matches(query, in: "Hotkeys keyboard shortcuts \($0.title) \($0.category) \(store.binding(for: $0)?.display ?? "")") } : []
        let heading = searching ? "Search results" : selectedPage.title
        addContent(SettingsUI.label(heading, size: 28, weight: .semibold))
        if searching {
            let resultCount = matches.count + commands.count
            addContent(SettingsUI.label("\(resultCount) \(resultCount == 1 ? "result" : "results") for “\(query)”", size: 13, colour: Theme.muted))
        }
        if let warning = preferences.loadWarning { addContent(SettingsUI.label(warning, size: 12, colour: Theme.needsYou)) }
        if !searching, selectedPage == .library, let folder = libraryFolder?() {
            addContent(SettingsUI.label(folder.path, size: 12, colour: Theme.soft))
        }
        for category in Page.allCases {
            let items = matches.filter { $0.category == category }
            guard !items.isEmpty else { continue }
            if category == .companion {
                if companionPage == nil { companionPage = companionContent?() }
                if let companionPage {
                    companionPage.present(sections: Set(items.map(\.id)))
                    addContent(companionPage)
                }
                continue
            }
            let group = NSStackView(); group.orientation = .vertical; group.alignment = .leading; group.spacing = 10
            let caption = SettingsUI.label(searching ? category.title.uppercased() : sectionTitle(category), size: 10, colour: Theme.muted, weight: .medium)
            group.addArrangedSubview(caption)
            let rows = items.enumerated().map { index, item in
                SettingsUI.row(title: item.title, detail: item.detail, control: settingControl(item), separator: index < items.count - 1)
            }
            let card = SettingsCard(rows: rows); group.addArrangedSubview(card)
            card.widthAnchor.constraint(equalTo: group.widthAnchor).isActive = true
            addContent(group)
        }
        if !commands.isEmpty {
            let caption = SettingsUI.label("HOTKEYS · COMMANDS", size: 10, colour: Theme.muted, weight: .medium); addContent(caption)
            let rows = commands.sorted { $0.title < $1.title }.enumerated().map { index, command in
                let button = ActionButton(link: store.binding(for: command)?.display ?? "Set shortcut", size: 11.5) { [weak self] in
                    self?.select(.hotkeys); self?.filter = .all; self?.search.stringValue = command.title; self?.reload()
                }
                button.setAccessibilityLabel("Edit shortcut for \(command.title)")
                return SettingsUI.row(title: command.title, detail: command.category, control: button, separator: index < commands.count - 1)
            }
            addContent(SettingsCard(rows: rows))
        }
        if searching, matches.isEmpty && commands.isEmpty {
            addContent(SettingsCard(rows: [SettingsUI.row(title: "No settings found", detail: "Try “dark”, “font”, “shortcuts” or “models”. Search looks at setting names and descriptions, not your notes or credentials.", control: NSView(), separator: false)]))
        }
        let footer = searching ? "Search never includes your notes, API keys or account secrets."
            : selectedPage == .companion ? "Companion settings apply to this library. API keys stay in macOS Keychain."
            : "Changes save automatically · Just for this Mac"
        addContent(SettingsUI.label(footer, size: 10.5, colour: Theme.muted))
        contentStack.needsLayout = true
    }
    private func sectionTitle(_ category: Page) -> String {
        switch category {
        case .general: "ON STARTUP"
        case .appearance: "THEME"
        case .editor: "TEXT & SCROLLING"
        case .hotkeys: "KEYBOARD"
        case .companion: "PROVIDERS & USAGE"
        case .library: "FILES & STORAGE"
        }
    }
    private func settingControl(_ item: SettingItem) -> NSView {
        let values = preferences.values
        switch item.id {
        case .restoreSession: return toggle(item, value: values.restoreSession) { $0.restoreSession = $1 }
        case .reduceMotion: return toggle(item, value: values.reduceMotion) { $0.reduceMotion = $1 }
        case .spellCheck: return toggle(item, value: values.spellCheck) { $0.spellCheck = $1 }
        case .naturalScroll: return toggle(item, value: values.naturalScroll) { $0.naturalScroll = $1 }
        case .appearance:
            return SettingsPopup(titles: AppPreferenceValues.Appearance.allCases.map(\.title), selected: AppPreferenceValues.Appearance.allCases.firstIndex(of: values.appearance)!, label: item.title) { [weak self] index in
                self?.changePreference { $0.appearance = AppPreferenceValues.Appearance.allCases[index] }
            }
        case .textSize:
            return SettingsPopup(titles: AppPreferenceValues.TextSize.allCases.map(\.title), selected: AppPreferenceValues.TextSize.allCases.firstIndex(of: values.textSize)!, label: item.title) { [weak self] index in
                self?.changePreference { $0.textSize = AppPreferenceValues.TextSize.allCases[index] }
            }
        case .lineSpacing:
            return SettingsPopup(titles: AppPreferenceValues.LineSpacing.allCases.map(\.title), selected: AppPreferenceValues.LineSpacing.allCases.firstIndex(of: values.lineSpacing)!, label: item.title) { [weak self] index in
                self?.changePreference { $0.lineSpacing = AppPreferenceValues.LineSpacing.allCases[index] }
            }
        default:
            let title: String = switch item.id {
            case .libraryFolder: "Choose…"
            case .revealLibrary: "Show in Finder ↗"
            case .importNote, .importMarkdown: "Import…"
            case .hotkeys: "Customize →"
            default: "Open"
            }
            let button = ActionButton(link: title, size: 12) { [weak self] in self?.activate(item.id) }
            button.setAccessibilityLabel("\(title) \(item.title)")
            return button
        }
    }
    private func toggle(_ item: SettingItem, value: Bool, change: @escaping (inout AppPreferenceValues, Bool) -> Void) -> NSView {
        SettingsSwitch(value: value, label: item.title) { [weak self] value in self?.changePreference { change(&$0, value) } }
    }
    private func changePreference(_ change: (inout AppPreferenceValues) -> Void) {
        do { try preferences.update(change); preferences.applyAppearance() }
        catch { renderContent(); window.presentError(error) }
    }
    private func activate(_ id: SettingItem.ID) {
        switch id {
        case .pairPhone: onCommand?(.pairPhone)
        case .hotkeys: select(.hotkeys)
        case .provider, .models, .readers, .limits, .voice: select(.companion)
        case .libraryFolder: onCommand?(.chooseFolder)
        case .revealLibrary: if let folder = libraryFolder?() { NSWorkspace.shared.open(folder) }
        case .importNote: onCommand?(.importExport)
        case .importMarkdown: onCommand?(.importMarkdown)
        default: break
        }
    }
    @objc private func globalSearchChanged() {
        hotkeysPage.isHidden = !query.isEmpty || selectedPage != .hotkeys
        generalPage.isHidden = query.isEmpty && selectedPage == .hotkeys
        for button in nav { button.selected = query.isEmpty && button.category == selectedPage }
        if !generalPage.isHidden { renderContent(); contentScroll.contentView.scroll(to: .zero); contentScroll.reflectScrolledClipView(contentScroll.contentView) }
    }
    private func buildHotkeys() {
        let title = label("Hotkeys", size: 26)
        let resetAll = ActionButton(link: "Restore defaults", size: 12) { [weak self] in self?.resetAll() }
        let header = NSStackView(views: [title, NSView(), resetAll]); header.spacing = 12
        search.placeholderString = "Search commands…"
        search.font = .systemFont(ofSize: 13)
        search.focusRingType = .none
        search.controlSize = .large
        search.delegate = self
        search.sendsSearchStringImmediately = true
        search.target = self; search.action = #selector(searchChanged)
        search.setAccessibilityLabel("Search hotkeys")
        chips = Filter.allCases.map { value in
            let chip = FilterChip(title: value.rawValue)
            chip.onClick = { [weak self] in self?.filter = value; self?.reload() }
            return chip
        }
        let filters = NSStackView(views: chips); filters.spacing = 8
        count.font = Theme.smallFont; count.textColor = Theme.muted
        let toolbar = NSStackView(views: [header, search, filters, count])
        toolbar.orientation = .vertical; toolbar.alignment = .leading; toolbar.spacing = 12
        toolbar.setCustomSpacing(20, after: header)
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        hotkeysPage.addSubview(toolbar)
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("command"))
        table.addTableColumn(column); table.headerView = nil
        table.style = .plain; table.backgroundColor = .clear
        table.rowHeight = 68; table.intercellSpacing = .zero
        table.selectionHighlightStyle = .none
        table.dataSource = self; table.delegate = self
        let scroll = NSScrollView(); scroll.drawsBackground = false
        scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true
        scroll.documentView = table
        scroll.translatesAutoresizingMaskIntoConstraints = false
        hotkeysPage.addSubview(scroll)
        let footer = NSTextField(wrappingLabelWithString: "Click a binding to record. × clears it; ↶ restores its default. Changes save immediately. Some macOS shortcuts cannot be overridden.")
        footer.font = Theme.smallFont; footer.textColor = Theme.muted
        footer.translatesAutoresizingMaskIntoConstraints = false; hotkeysPage.addSubview(footer)
        NSLayoutConstraint.activate([
            toolbar.topAnchor.constraint(equalTo: hotkeysPage.topAnchor, constant: 26),
            toolbar.leadingAnchor.constraint(equalTo: hotkeysPage.leadingAnchor, constant: 28), toolbar.trailingAnchor.constraint(equalTo: hotkeysPage.trailingAnchor, constant: -28),
            header.widthAnchor.constraint(equalTo: toolbar.widthAnchor), search.widthAnchor.constraint(equalTo: toolbar.widthAnchor), search.heightAnchor.constraint(equalToConstant: 34),
            scroll.leadingAnchor.constraint(equalTo: toolbar.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: toolbar.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: toolbar.bottomAnchor, constant: 8), scroll.bottomAnchor.constraint(equalTo: footer.topAnchor, constant: -12),
            footer.leadingAnchor.constraint(equalTo: toolbar.leadingAnchor), footer.trailingAnchor.constraint(equalTo: toolbar.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: hotkeysPage.bottomAnchor, constant: -18)
        ])
        reload()
    }
    func show(page: Page = .general) {
        select(page)
        if !window.isVisible { window.center() }
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }
    private func select(_ page: Page) {
        selectedPage = page; globalSearch.stringValue = ""
        globalSearchChanged()
        if page == .hotkeys { reload(); window.makeFirstResponder(search) }
    }
    func reload() {
        let query = search.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        rows = HotkeyStore.commands.filter { command in
            let binding = store.binding(for: command)
            let matches = query.isEmpty || "\(command.title) \(command.category) \(binding?.display ?? "")".localizedCaseInsensitiveContains(query)
            let visible: Bool = switch filter {
            case .all: true
            case .assigned: binding != nil
            case .customized: store.isCustomized(command)
            case .unassigned: binding == nil
            }
            return matches && visible
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        for (index, chip) in chips.enumerated() { chip.selected = Filter.allCases[index] == filter }
        count.stringValue = store.loadWarning ?? (rows.isEmpty ? "No matching commands" : "\(rows.count) \(rows.count == 1 ? "command" : "commands")")
        count.textColor = store.loadWarning == nil ? Theme.muted : Theme.needsYou
        table.reloadData()
    }
    func numberOfRows(in tableView: NSTableView) -> Int { rows.count }
    func tableView(_ tableView: NSTableView, shouldSelectRow row: Int) -> Bool { false }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let command = rows[row]
        let view = ThemedView(); view.bottomLine = true
        let title = label(command.title, size: 12.5)
        title.lineBreakMode = .byTruncatingTail
        title.toolTip = command.title
        let category = label(command.category + (store.isCustomized(command) ? " · Customized" : ""), size: 10.5, colour: Theme.muted)
        let names = NSStackView(views: [title, category]); names.orientation = .vertical; names.alignment = .leading; names.spacing = 5
        let binding = BindingButton(binding: store.binding(for: command))
        binding.setContentHuggingPriority(.required, for: .horizontal)
        binding.setContentCompressionResistancePriority(.required, for: .horizontal)
        binding.onClick = { [weak self] in self?.record(command) }
        let clear = ChromeIconButton(symbol: "xmark", label: "Clear \(command.title) binding")
        clear.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 10, weight: .regular)
        clear.isEnabled = store.binding(for: command) != nil
        clear.onClick = { [weak self] in self?.change { try self?.store.set(nil, for: command) } }
        let reset = ChromeIconButton(symbol: "arrow.counterclockwise", label: "Restore default for \(command.title)")
        reset.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 12, weight: .regular)
        reset.isEnabled = store.isCustomized(command)
        reset.onClick = { [weak self] in self?.change { try self?.store.reset(command) } }
        for child in [names, binding, clear, reset] as [NSView] { child.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(child) }
        NSLayoutConstraint.activate([
            names.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 2), names.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            names.trailingAnchor.constraint(lessThanOrEqualTo: binding.leadingAnchor, constant: -12),
            binding.centerYAnchor.constraint(equalTo: view.centerYAnchor), binding.heightAnchor.constraint(equalToConstant: 36),
            clear.leadingAnchor.constraint(equalTo: binding.trailingAnchor, constant: 8), clear.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            clear.widthAnchor.constraint(equalToConstant: 26), clear.heightAnchor.constraint(equalToConstant: 28),
            reset.leadingAnchor.constraint(equalTo: clear.trailingAnchor, constant: 4), reset.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            reset.widthAnchor.constraint(equalToConstant: 26), reset.heightAnchor.constraint(equalToConstant: 28), reset.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -2)
        ])
        return view
    }
    private func record(_ command: AppCommand) {
        guard recorder == nil else { return }
        let recorder = HotkeyRecorder(command: command, store: store)
        self.recorder = recorder
        recorder.onFinish = { [weak self] in self?.recorder = nil; self?.reload() }
        recorder.show(over: window)
    }
    private func change(_ action: () throws -> Void) {
        do { try action(); reload() }
        catch { window.presentError(error) }
    }
    private func resetAll() {
        let alert = NSAlert(); alert.messageText = "Restore all default hotkeys?"
        alert.informativeText = "Your custom bindings and cleared shortcuts will be replaced with Syncr’s defaults."
        alert.addButton(withTitle: "Restore defaults"); alert.addButton(withTitle: "Cancel")
        alert.beginSheetModal(for: window) { [weak self] response in
            if response == .alertFirstButtonReturn { self?.change { try self?.store.resetAll() } }
        }
    }
    @objc private func searchChanged() { reload() }
    func controlTextDidChange(_ obj: Notification) {
        if obj.object as? NSSearchField === globalSearch { globalSearchChanged() } else { reload() }
    }
    func windowWillClose(_ notification: Notification) {
        recorder?.finish()
        companionPage?.clearSensitiveInput()
    }
}

private final class FilterChip: NSButton {
    var selected = false { didSet { needsDisplay = true; setAccessibilityValue(selected ? "Selected" : "Not selected") } }
    var onClick: (() -> Void)?
    init(title: String) {
        super.init(frame: .zero)
        self.title = title; isBordered = false; target = self; action = #selector(clicked)
        setAccessibilityLabel(title)
        heightAnchor.constraint(equalToConstant: 28).isActive = true
        widthAnchor.constraint(equalToConstant: (title as NSString).size(withAttributes: [.font: Theme.font]).width + 24).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func draw(_ dirtyRect: NSRect) {
        let path = NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 13, yRadius: 13)
        if selected { Theme.raise.setFill(); path.fill() }
        (selected ? Theme.hairlineStrong : Theme.hairline).setStroke(); path.stroke()
        let attrs: [NSAttributedString.Key: Any] = [.font: Theme.font, .foregroundColor: selected ? Theme.ink : Theme.muted]
        let size = (title as NSString).size(withAttributes: attrs)
        (title as NSString).draw(at: NSPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2), withAttributes: attrs)
    }
    @objc private func clicked() { onClick?() }
}
