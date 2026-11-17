import AppKit
import SyncrCore
import UniformTypeIdentifiers

/// Owns the window and turns commands into `Workspace` calls. No note state lives here.
@MainActor final class MainWindowController: NSObject, SidebarDelegate, NSMenuItemValidation, NSWindowDelegate {
    let model: LibraryModel
    let workspace: Workspace
    let hotkeys = HotkeyStore()
    private lazy var settingsWindow = SettingsWindow(store: hotkeys)
    private var hotkeyMonitor: Any?
    let window: NSWindow
    private let sidebar = SidebarView()
    let notebook: NotebookController
    private let detail: NoteDetailView
    private lazy var companion = CompanionController(model: model, workspace: workspace, notebook: notebook, detail: detail)
    private var displayedDocument: String?
    private var freeRevision = ""
    private var pendingReveal: (String, Int)?
    private let sidebarItem: NSSplitViewItem
    private let palette = PaletteController()
    private var live: LiveInkCoordinator?
    private var link: LinkController?
    private let ribbon = RibbonView()
    private let statusBar = StatusBarView()
    private lazy var leftSidebarToggle = SidebarToggle { [weak self] in self?.run(.toggleSidebar) }
    private var sidebarObservation: NSKeyValueObservation?

    init(model: LibraryModel, workspace: Workspace) {
        self.model = model
        self.workspace = workspace
        notebook = NotebookController(model: model, workspace: workspace)
        detail = NoteDetailView(model: model)
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 820),
                          styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        let sidebarVC = NSViewController(); sidebarVC.view = sidebar
        let detailVC = NSViewController(); detailVC.view = detail
        sidebarItem = NSSplitViewItem(sidebarWithViewController: sidebarVC)
        sidebarItem.minimumThickness = 200
        sidebarItem.maximumThickness = 420
        sidebarItem.canCollapse = true
        sidebarItem.allowsFullHeightLayout = false
        let split = NSSplitViewController()
        split.addSplitViewItem(sidebarItem)
        split.addSplitViewItem(NSSplitViewItem(viewController: detailVC))
        // Ribbon | file tree | note, with one status bar under all of it.
        let root = NSViewController()
        root.view = NSView()
        root.addChild(split)
        for view in [ribbon, split.view, statusBar] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.view.addSubview(view)
        }
        NSLayoutConstraint.activate([
            ribbon.leadingAnchor.constraint(equalTo: root.view.leadingAnchor), ribbon.topAnchor.constraint(equalTo: root.view.topAnchor),
            ribbon.bottomAnchor.constraint(equalTo: statusBar.topAnchor),
            split.view.leadingAnchor.constraint(equalTo: ribbon.trailingAnchor), split.view.trailingAnchor.constraint(equalTo: root.view.trailingAnchor),
            split.view.topAnchor.constraint(equalTo: root.view.topAnchor), split.view.bottomAnchor.constraint(equalTo: statusBar.topAnchor),
            statusBar.leadingAnchor.constraint(equalTo: root.view.leadingAnchor), statusBar.trailingAnchor.constraint(equalTo: root.view.trailingAnchor),
            statusBar.bottomAnchor.constraint(equalTo: root.view.bottomAnchor)
        ])
        super.init()
        window.contentViewController = root
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.backgroundColor = Theme.panel
        window.setContentSize(NSSize(width: 1280, height: 820))
        window.minSize = NSSize(width: 720, height: 480)
        window.center()
        window.delegate = self
        window.setFrameAutosaveName("SyncrMainWindow")
        window.addTitlebarAccessoryViewController(leftSidebarToggle)
        sidebarObservation = sidebarItem.observe(\.isCollapsed, options: [.initial, .new]) { [weak self] _, _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.leftSidebarToggle.show(collapsed: self.sidebarItem.isCollapsed)
            }
        }
        settingsWindow.onCommand = { [weak self] in self?.run($0) }
        settingsWindow.companionContent = { [weak self] in self?.companion.makeSettingsView() }
        settingsWindow.libraryFolder = { [weak self] in self?.model.folder ?? FileManager.default.homeDirectoryForCurrentUser }
        ribbon.onSettings = { [weak self] in self?.run(.settings) }
        hotkeys.onChange = { [weak self] in self?.hotkeysChanged() }
        hotkeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            let handled = MainActor.assumeIsolated {
                guard let self, event.window === self.window, self.window.isKeyWindow,
                      self.window.attachedSheet == nil,
                      let command = self.hotkeys.command(for: event), self.workspace.availableCommands().contains(command) else { return false }
                if !event.isARepeat || [.zoomIn, .zoomOut].contains(command) { self.run(command) }
                return true
            }
            return handled ? nil : event
        }
        hotkeysChanged()
        NotificationCenter.default.addObserver(self, selector: #selector(presentationChanged), name: AppPreferences.didChange, object: AppPreferences.shared)

        if let device = detail.device {
            let live = LiveInkCoordinator(model: model, workspace: workspace, device: device)
            self.live = live
            Dither.attach(pen: live.engine)
            detail.attach(live)
            let link = LinkController(model: model, workspace: workspace, live: live)
            self.link = link
            link.onStatus = { [weak self] in self?.linkChanged() }
            link.onPairing = { [weak self] in self?.detail.showPairing($0) }
        }
        ribbon.onFiles = { [weak self] in self?.run(.toggleSidebar) }
        ribbon.onSearch = { [weak self] in self?.run(.searchNotes) }
        ribbon.onDaily = { [weak self] in self?.run(.dailyNote) }
        ribbon.onPhone = { [weak self] in self?.phoneMenu(at: $0) }
        statusBar.onPhone = { [weak self] in self?.phoneMenu(at: $0) }
        detail.onLiveStatus = { [weak self] in self?.statusBar.show(live: $0) }
        detail.companionStrip.onBudget = { [weak self] text, tip, paused in self?.statusBar.show(budget: text, tooltip: tip, paused: paused) }
        sidebar.delegate = self
        detail.onError = { [weak self] in self?.fail($0) }
        detail.tabBar.onSelect = { [weak self] in self?.workspace.activate($0) }
        detail.tabBar.onClose = { [weak self] in self?.workspace.closeTab($0) }
        workspace.onChange = { [weak self] in self?.refresh() }
        workspace.onActivate = { [weak self] id in
            guard let self else { return }
            self.detail.flushText(); self.notebook.flushAll()
            self.displayedDocument = nil; self.freeRevision = ""
            self.detail.showDocument(nil, title: ""); self.detail.showFree([])
            if let id { self.detail.resetViewport(); self.model.openAsync(id: id) } else { self.model.clearDrawing() }
        }
        model.onChange = { [weak self] in
            guard let self else { return }
            self.syncNotebook()
            self.detail.noteChanged()
            self.link?.stateChanged()
            if let error = self.model.error { self.fail(NSError(domain: "Syncr", code: 1, userInfo: [NSLocalizedDescriptionKey: error])) }
        }
        notebook.onChange = { [weak self] in
            guard let self else { return }
            self.syncNotebook(); self.detail.refreshTextPreview()
            self.sidebar.update(self.workspace.sidebar, active: self.workspace.active, live: self.link?.liveNote)
        }
        notebook.onState = { [weak self] id in
            guard let self, self.workspace.active == id else { return }
            self.showSaveState(self.notebook.status(note: id))
        }
        notebook.onError = { [weak self] in self?.fail($0) }
        detail.documentEditor.onFind = { [weak self] in self?.findInNote() }
        detail.documentEditor.onRename = { [weak self] title in
            guard let self, let id = self.workspace.active else { return }
            self.notebook.rename(id, to: title)
        }
        detail.documentEditor.onColumn = { [weak self] left, right in
            do { try AppPreferences.shared.update { if let left { $0.columnLeft = Double(left) }; if let right { $0.columnRight = Double(right) } } }
            catch { self?.fail(error) }
        }
        detail.documentEditor.resolve = { [weak self] in self?.model.notebookIndex.resolve($0) != nil }
        detail.documentEditor.complete = { [weak self] in self?.model.notebookIndex.completions($0).map(\.title) ?? [] }
        detail.documentEditor.slashCommands = notebook.slashCommands
        detail.documentEditor.onAsk = { [weak self] draw in self?.companion.ask(draw: draw) }
        detail.documentEditor.onLink = { [weak self] title, newTab in self?.attempt { try self?.notebook.openLink(title, newTab: newTab) } }
        detail.onFreeText = { [weak self] x, y in guard let self, let id = self.workspace.active else { return }; self.notebook.addFree(note: id, x: x, y: y) }
        detail.onFreeMove = { [weak self] index, x, y in
            guard let self, let id = self.workspace.active else { return }
            let blocks = self.model.drawing?.textBlocks.filter { $0.role == .free && !$0.tombstone } ?? []
            if index < blocks.count { self.notebook.moveFree(note: id, block: blocks[index], x: x, y: y) }
        }
        companion.onShowSettings = { [weak self] in self?.settingsWindow.show(page: .companion) }
    }

    private func syncNotebook() {
        notebook.refreshIndex()
        guard let id = workspace.active, model.shownNote == id, let drawing = model.drawing else { companion.observe(); return }
        let block = drawing.textBlocks.first { $0.role == .document && !$0.tombstone }
        let debugNoDocument = LiveInkCoordinator.debugRequested && ProcessInfo.processInfo.environment["SYNCR_MAC_DEBUG_NO_DOCUMENT"] == "1"
        let show = block != nil || (model.isWritable(id) && !debugNoDocument)
        let opening = displayedDocument != id
        displayedDocument = id
        detail.showDocument(show ? notebook.document(note: id, block: block) : nil, title: workspace.title(of: id), width: CGFloat(block?.width ?? 1600), opening: opening)
        showSaveState(notebook.status(note: id))
        if opening && model.isWritable(id) && show { window.makeFirstResponder(detail.documentEditor.textView) }
        let free = drawing.textBlocks.filter { $0.role == .free && !$0.tombstone }
        let revision = id + free.map { $0.id + $0.head.operationId }.joined()
        if revision != freeRevision {
            freeRevision = revision
            detail.showFree(free.map { (CGFloat($0.originX) + CGFloat($0.transform.tx), CGFloat($0.originY) + CGFloat($0.transform.ty), CGFloat($0.width), notebook.document(note: id, block: $0)) })
            detail.onFreeEnd = { [weak self] index in
                if index < free.count { self?.notebook.deleteEmptyFree(note: id, block: free[index]) }
            }
        }
        if let reveal = pendingReveal, reveal.0 == id { pendingReveal = nil; detail.revealText(reveal.1) }
        companion.observe()
    }
    private func openMatch(_ id: String, offset: Int) {
        pendingReveal = (id, offset); workspace.open(id)
        if model.shownNote == id, !model.loading { syncNotebook() }
    }

    private func showSaveState(_ text: String) {
        let words = detail.documentEditor.isHidden ? nil : detail.documentEditor.textView.string.split(whereSeparator: { $0.isWhitespace }).count
        statusBar.show(save: text, words: words)
    }

    private func linkChanged() {
        guard let link else {
            statusBar.show(phone: "No phone link", state: .off)
            return
        }
        workspace.hasPairedPhones = link.hasPairedPhones
        let (text, state): (String, StatusBarView.Phone) = switch link.status {
        case .off: ("Phone link off", .off)
        case .starting: ("Phone link starting", .waiting)
        case .waiting: (link.hasPairedPhones ? "Phone not connected" : "No phone paired", .waiting)
        case .pairing(let phone): ("Pairing with \(phone)", .needsYou)
        case .connected(let phone): (link.padOn ? "\(phone) · pen ready" : "\(phone) · connected", .connected)
        case .failed: ("Phone link could not listen", .needsYou)
        }
        statusBar.show(phone: text, state: state)
        let colour: NSColor = switch state { case .connected: Theme.connected; case .needsYou: Theme.needsYou; default: Theme.muted }
        ribbon.showPhone(colour: colour, tip: link.statusText)
        sidebar.update(workspace.sidebar, active: workspace.active, live: link.liveNote)
    }

    /// The phone's controls, from the ribbon or the status bar's pill.
    private func phoneMenu(at view: NSView) {
        let menu = NSMenu()
        for command in [AppCommand.phoneLink, .followPhone, .forgetPhone] {
            let item = NSMenuItem(title: command.title, action: #selector(runMenu(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = command.rawValue
            menu.addItem(item)
        }
        if let link {
            menu.insertItem(.separator(), at: 0)
            let state = NSMenuItem(title: link.statusText, action: nil, keyEquivalent: "")
            state.isEnabled = false
            menu.insertItem(state, at: 0)
        }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: view.isFlipped ? view.bounds.height + 4 : -4), in: view)
    }

    func quit() {
        link?.stop()
        if let hotkeyMonitor { NSEvent.removeMonitor(hotkeyMonitor); self.hotkeyMonitor = nil }
    }

    @objc private func presentationChanged() {
        detail.refreshTextPreview()
        companion.refreshPresentation()
    }

    private func setSidebarCollapsed(_ collapsed: Bool) {
        if AppPreferences.shared.reducedMotion { sidebarItem.isCollapsed = collapsed }
        else { sidebarItem.animator().isCollapsed = collapsed }
    }

    private func hotkeysChanged() {
        // Menus and hints reflect the same effective registry used by keyboard dispatch.
        NSApplication.shared.mainMenu = MainMenu.build(target: self)
        leftSidebarToggle.shortcut = hotkeys.binding(for: .toggleSidebar)?.compact ?? ""
        leftSidebarToggle.show(collapsed: sidebarItem.isCollapsed)
        ribbon.updateHotkeys(hotkeys)
        sidebar.updateHotkeys(hotkeys)
        detail.updateHotkeys(hotkeys)
        settingsWindow.reload()
    }
    func flushBeforeQuit(_ done: @escaping @MainActor (Bool) -> Void) {
        detail.flushText()
        notebook.flushAll { [weak self] ok in
            if !ok { self?.showSaveState("Not saved: edits retained; fix the write error before closing") }
            guard let self else { done(ok); return }
            self.model.drainNotebookWork { done(ok) }
        }
    }
    private var activeEditor: DocumentEditor {
        (window.firstResponder as? MarkdownTextView)?.superview as? DocumentEditor ?? detail.documentEditor
    }
    private var closingReady = false
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if closingReady { return true }
        flushBeforeQuit { [weak self] ok in if ok { self?.closingReady = true; self?.window.performClose(nil) } }
        return false
    }

    func show() {
        refresh()
        link?.start()
        linkChanged()
        startDebugControl()
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        if let id = workspace.active { model.openAsync(id: id) }
        if let error = model.error { fail(NSError(domain: "Syncr", code: 1, userInfo: [NSLocalizedDescriptionKey: error])) }
    }

    private func refresh() {
        notebook.refreshIndex()
        live?.syncTarget()
        link?.stateChanged()
        sidebar.update(workspace.sidebar, active: workspace.active, live: link?.liveNote)
        detail.tabBar.update(workspace.tabs.map { TabInfo(id: $0, title: workspace.title(of: $0), readOnly: workspace.isReadOnly($0)) },
                             active: workspace.active)
        if let id = workspace.active {
            window.title = workspace.title(of: id)
            detail.show(title: workspace.title(of: id), readOnly: workspace.isReadOnly(id))
            statusBar.show(note: workspace.isReadOnly(id) ? "From the phone · read-only until sync" : nil)
        } else {
            window.title = "Syncr"
            detail.show(title: nil, readOnly: false)
            statusBar.show(note: nil)
            statusBar.show(save: "", words: nil)
        }
    }

    private func fail(_ error: Error) { window.presentError(error) }
    private func attempt(_ work: () throws -> Void) { do { try work() } catch { fail(error) } }

    // MARK: Commands

    @objc func runMenu(_ sender: NSMenuItem) {
        if let raw = sender.representedObject as? String, let command = AppCommand(rawValue: raw) {
            if command == .closeTab, let keyWindow = NSApplication.shared.keyWindow, keyWindow !== window {
                keyWindow.performClose(nil)
            } else { run(command) }
        }
    }

    func validateMenuItem(_ item: NSMenuItem) -> Bool {
        guard let raw = item.representedObject as? String, let command = AppCommand(rawValue: raw) else { return true }
        if command == .closeTab, let keyWindow = NSApplication.shared.keyWindow, keyWindow !== window { return keyWindow.styleMask.contains(.closable) }
        if [.undo, .redo].contains(command), let field = NSApplication.shared.keyWindow?.firstResponder as? NSTextView, field.isFieldEditor {
            return command == .undo ? field.undoManager?.canUndo == true : field.undoManager?.canRedo == true
        }
        if let link {
            if command == .phoneLink { item.state = link.enabled ? .on : .off }
            if command == .followPhone { item.state = link.follow ? .on : .off }
        }
        return workspace.availableCommands().contains(command)
    }

    func run(_ command: AppCommand) {
        switch command {
        case .settings: settingsWindow.show()
        case .hotkeys: settingsWindow.show(page: .hotkeys)
        case .commandPalette: openCommandPalette()
        case .showLeftSidebar: setSidebarCollapsed(false)
        case .hideLeftSidebar: setSidebarCollapsed(true)
        case .askSyncr: companion.ask()
        case .drawSelection: companion.ask(draw: true)
        case .askAllReaders: companion.ask(all: true)
        case .companionSettings: companion.showSettings()
        case .lastCompanionRequest: companion.lastRequest()
        case .undoCompanion: companion.undoAgent()
        case .redoCompanion: companion.undoAgent(redo: true)
        case .undo, .redo:
            if let field = NSApplication.shared.keyWindow?.firstResponder as? NSTextView, field.isFieldEditor {
                if command == .redo { field.undoManager?.redo() } else { field.undoManager?.undo() }
            } else if let id = workspace.active {
                detail.flushText(); notebook.document(note: id).history(redo: command == .redo)
            }
        case .browseTags:
            palette.present(over: window, placeholder: "Choose a tag", items: { [weak self] query in
                self?.model.notebookIndex.tags.filter { Matching.score(query, in: $0.0) != nil }.map { PaletteItem(id: $0.0, title: "#" + $0.0, detail: String($0.1) + " notes") } ?? []
            }, choose: { [weak self] item, _ in self?.sidebarOpen(note: "tag:" + item.id, newTab: false) })
        case .outline:
            palette.present(over: window, placeholder: "Go to heading", items: { [weak self] query in
                NotebookIndex.outline(self?.detail.documentEditor.textView.string ?? "").filter { Matching.score(query, in: $0.title) != nil }.map { PaletteItem(id: String($0.offset), title: $0.title, detail: "H" + String($0.level)) }
            }, choose: { [weak self] item, _ in if let offset = Int(item.id) { self?.detail.revealText(offset) } })
        case .bold: activeEditor.shortcut("bold")
        case .italic: activeEditor.shortcut("italic")
        case .insertLink: activeEditor.shortcut("link")
        case .toggleTask: activeEditor.shortcut("task")
        case .findInNote: activeEditor.find()
        case .searchNotes: searchNotes()
        case .dailyNote: attempt { try notebook.daily() }
        case .documentView: detail.documentView()
        case .importMarkdown: importMarkdown()
        case .exportMarkdown: exportMarkdown()
        case .slashMenu: slashMenu()
        case .freeText: if let id = workspace.active { notebook.addFree(note: id, x: 3400, y: 200); detail.fit() }
        case .newNote: newNote()
        case .newNotebook: newNotebook()
        case .quickSwitcher: openSwitcher()
        case .renameNote: if let id = workspace.active { rename(note: id) }
        case .moveNote: pickNotebook()
        case .deleteNote: if let id = workspace.active { attempt { try workspace.delete(id) } }
        case .restoreNote: pickDeleted()
        case .closeTab: if let id = workspace.active { workspace.closeTab(id) }
        case .nextTab: workspace.cycleTab(1)
        case .previousTab: workspace.cycleTab(-1)
        case .toggleSidebar: setSidebarCollapsed(!sidebarItem.isCollapsed)
        case .zoomIn: detail.zoomIn()
        case .zoomOut: detail.zoomOut()
        case .fit: detail.fit()
        case .phoneLink: link?.setEnabled(!(link?.enabled ?? true))
        case .followPhone: link?.setFollow(!(link?.follow ?? true))
        case .forgetPhone: pickPhone()
        case .importExport: importNote()
        case .chooseFolder: chooseFolder()
        case .replayTestStroke: live?.replay(.pen)
        case .replayHighlighter: live?.replay(.highlighter)
        case .replayCancelled: live?.replay(.cancelled)
        case .replayViewRelative: live?.replay(.viewRelative)
        case .simulateDisconnect: live?.disconnect()
        }
    }

    /// Debug only (`SYNCR_MAC_DEBUG_AUTOREPLAY=1` with the replay flag): makes a note and runs three
    /// replays unattended, for launching without a driver for the GUI. Pair with a disposable library.
    func debugAutoReplay() {
        guard let live, live.debug, ProcessInfo.processInfo.environment["SYNCR_MAC_DEBUG_AUTOREPLAY"] == "1" else { return }
        attempt { _ = try workspace.newNote() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self else { return }
            self.detail.documentView()
            if let path = ProcessInfo.processInfo.environment["SYNCR_MAC_DEBUG_DOCUMENT"], let text = try? String(contentsOfFile: path, encoding: .utf8) {
                self.detail.documentEditor.debugSetText(text); self.detail.flushText()
            }
        }
        for (delay, kind) in [(1.0, ReplayKind.pen), (4.0, .highlighter), (7.0, .viewRelative)] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { MainActor.assumeIsolated { live.replay(kind) } }
        }
        // The same pen replay again while the view pans: text and ink must change viewport together.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
            MainActor.assumeIsolated { self?.detail.debugPanStart(seconds: 2.5); live.replay(.pen) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 13.5) { [weak self] in
            MainActor.assumeIsolated {
                if let report = self?.detail.debugPanReport() { FileHandle.standardError.write(Data((report + "\n").utf8)) }
            }
        }
    }

    func openCommandPalette() {
        palette.present(over: window, placeholder: "Type a command", items: { [weak self] query in
            guard let self else { return [] }
            return AppCommand.matching(query, available: self.workspace.availableCommands()).map {
                PaletteItem(id: $0.rawValue, title: $0.title, detail: self.detail(of: $0))
            }
        }, choose: { [weak self] item, _ in
            if let command = AppCommand(rawValue: item.id) { self?.run(command) }
        })
    }

    private func detail(of command: AppCommand) -> String {
        switch command {
        case .phoneLink: link.map { $0.enabled ? "on" : "off" } ?? ""
        case .followPhone: link.map { $0.follow ? "on" : "off" } ?? ""
        default: hotkeys.binding(for: command)?.compact ?? ""
        }
    }

    /// Debug control only: forgets every paired phone, as Forget Phone does for one.
    func debugForgetAllPhones() {
        guard let link else { return }
        for phone in link.store.all { link.forget(phoneId: phone.phoneId) }
        linkChanged()
    }

    private var debugControl: DebugControl?
    private func startDebugControl() {
        guard let live, let link, live.debug, let path = ProcessInfo.processInfo.environment["SYNCR_MAC_DEBUG_CONTROL"], !path.isEmpty else { return }
        debugControl = DebugControl(path: path, model: model, workspace: workspace, live: live, link: link, detail: detail, notebook: notebook, companion: companion,
                                    notebookAction: { [weak self] action, argument in
                                        guard let self else { return }
                                        switch action {
                                        case "command": if let command = AppCommand(rawValue: argument) { self.run(command) }
                                        case "palette": self.palette.debugQuery(argument)
                                        case "accept": self.palette.debugAccept()
                                        case "tag-open": self.sidebarOpen(note: "tag:" + argument, newTab: false)
                                        default: break
                                        }
                                    }, forgetPhones: { [weak self] in self?.debugForgetAllPhones() })
    }

    private func pickPhone() {
        guard let link else { return }
        palette.present(over: window, placeholder: "Forget phone", items: { query in
            link.store.all.filter { Matching.score(query, in: $0.name) != nil }.map { PaletteItem(id: $0.phoneId, title: $0.name, detail: "paired") }
        }, choose: { [weak self] item, _ in
            link.forget(phoneId: item.id)
            self?.linkChanged()
        })
    }

    func openSwitcher() {
        palette.present(over: window, placeholder: "Open note…  (⌘↩ opens in a new tab)", items: { [weak self] query in
            guard let self else { return [] }
            return self.workspace.switcherResults(query).map {
                PaletteItem(id: $0.id, title: self.workspace.displayTitle($0.title), detail: $0.notebook ?? "Inbox")
            }
        }, choose: { [weak self] item, newTab in self?.workspace.open(item.id, newTab: newTab) })
    }

    private func pickNotebook() {
        guard let id = workspace.active else { return }
        palette.present(over: window, placeholder: "Move to notebook", items: { [weak self] query in
            guard let self else { return [] }
            let names: [(String?, String)] = [(nil, "Inbox")] + self.model.notebooks.map { ($0.name, $0.name) }
            return names.filter { Matching.score(query, in: $0.1) != nil }.map { PaletteItem(id: $0.0 ?? "", title: $0.1, detail: "") }
        }, choose: { [weak self] item, _ in
            guard let self else { return }
            self.attempt { try self.workspace.move(id, to: item.id.isEmpty ? nil : item.id) }
        })
    }

    private func pickDeleted() {
        palette.present(over: window, placeholder: "Restore deleted note", items: { [weak self] query in
            guard let self else { return [] }
            return self.model.deleted.compactMap { note in
                Matching.score(query, in: self.workspace.displayTitle(note.title)) == nil ? nil
                    : PaletteItem(id: note.id, title: self.workspace.displayTitle(note.title), detail: note.notebook ?? "Inbox")
            }
        }, choose: { [weak self] item, _ in
            guard let self else { return }
            self.attempt { try self.workspace.restore(item.id) }
        })
    }

    private func newNote() {
        attempt {
            _ = try workspace.newNote()
        }
    }

    private func newNotebook() {
        guard let name = prompt("New notebook", initial: "", ok: "Create") else { return }
        attempt { try workspace.createNotebook(name) }
    }

    private func rename(note id: String) {
        if !sidebarItem.isCollapsed, workspace.filter.isEmpty { sidebar.beginRename(note: id); return }
        guard let title = prompt("Rename note", initial: workspace.title(of: id), ok: "Rename") else { return }
        notebook.rename(id, to: title)
    }

    private func findInNote() {
        let editor = activeEditor
        palette.present(over: window, placeholder: "Find in this note", items: { query in
            guard !query.isEmpty else { return [] }
            return Markdown.lines(editor.textView.string).enumerated().filter { $0.element.text.localizedCaseInsensitiveContains(query) }.map {
                PaletteItem(id: String($0.element.range.location), title: $0.element.text, detail: "Line \($0.offset + 1)")
            }
        }, choose: { [weak self] item, _ in
            guard let self, let offset = Int(item.id) else { return }
            if editor === self.detail.documentEditor { self.detail.revealText(offset) } else { editor.reveal(offset) }
        })
    }
    private func searchNotes() {
        palette.present(over: window, placeholder: "Search every note", items: { [weak self] query in
            self?.model.notebookIndex.search(query).map { PaletteItem(id: $0.id + ":" + String($0.offset), title: $0.title, detail: $0.line) } ?? []
        }, choose: { [weak self] item, _ in
            let pieces = item.id.split(separator: ":")
            if pieces.count == 2 { self?.openMatch(String(pieces[0]), offset: Int(pieces[1]) ?? 0) }
        })
    }
    private func slashMenu() {
        palette.present(over: window, placeholder: "Insert slash command", items: { [weak self] query in
            SlashEngine.match(query.trimmingCharacters(in: CharacterSet(charactersIn: "/")), in: SlashCatalog.all(user: self?.notebook.slashCommands ?? [])).map { PaletteItem(id: $0.name, title: "/" + $0.name, detail: $0.title) }
        }, choose: { [weak self] item, _ in
            guard let self, let cmd = SlashCatalog.all(user: self.notebook.slashCommands).first(where: { $0.name == item.id }) else { return }
            self.detail.documentEditor.insertSlash(cmd)
        })
    }
    private func importMarkdown() {
        let panel = NSOpenPanel(); panel.canChooseDirectories = true; panel.canChooseFiles = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        notebook.importMarkdown(url) { [weak self] result in
            switch result {
            case .failure(let error): self?.fail(error)
            case .success(let mapped):
                if let entry = mapped.entries.first, let note = self?.model.notes.first(where: { $0.title == entry.title && $0.notebook == entry.notebook }) { self?.workspace.open(note.id) }
                let alert = NSAlert(); alert.messageText = "Imported \(mapped.entries.count) Markdown notes"
                alert.informativeText = mapped.skipped.isEmpty ? "No files skipped." : "Skipped:\n" + mapped.skipped.joined(separator: "\n")
                if let window = self?.window { alert.beginSheetModal(for: window) }
            }
        }
    }
    private func exportMarkdown() {
        guard let id = workspace.active else { return }
        let panel = NSSavePanel(); panel.allowedContentTypes = [UTType(filenameExtension: "md")!]
        panel.nameFieldStringValue = workspace.title(of: id) + ".md"
        guard panel.runModal() == .OK, let file = panel.url else { return }
        notebook.exportMarkdown(note: id, to: file) { [weak self] result in if case .failure(let error) = result { self?.fail(error) } }
    }

    private func prompt(_ message: String, initial: String, ok: String) -> String? {
        let alert = NSAlert()
        alert.messageText = message
        alert.addButton(withTitle: ok)
        alert.addButton(withTitle: "Cancel")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        field.stringValue = initial
        alert.accessoryView = field
        alert.window.initialFirstResponder = field
        return alert.runModal() == .alertFirstButtonReturn ? field.stringValue : nil
    }

    // MARK: Import and folder

    private func importNote() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "syncr")!]
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        importAndOpen(url)
    }

    func importAndOpen(_ url: URL) {
        attempt {
            let added = try model.importCopy(from: url)
            workspace.libraryChanged()
            if workspace.tabs.contains(added.id) { model.openAsync(id: added.id); detail.resetViewport() }
            else { workspace.open(added.id) }
        }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Use Folder"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        flushBeforeQuit { [weak self] ok in
            guard let self, ok else { return }
            self.workspace.saveSession()
            self.model.setFolder(url)
            self.notebook.resetForFolder()
            self.detail.documentEditor.slashCommands = self.notebook.slashCommands
            self.link?.libraryFolderChanged()
            self.workspace.restoreSession()
            if self.workspace.active == nil { self.model.clearDrawing() }
            if let error = self.model.error { self.fail(NSError(domain: "Syncr", code: 1, userInfo: [NSLocalizedDescriptionKey: error])) }
        }
    }

    // MARK: SidebarDelegate

    func sidebarOpen(note id: String, newTab: Bool) {
        if id.hasPrefix("tag:") {
            let tag = String(id.dropFirst(4))
            palette.present(over: window, placeholder: "#" + tag, items: { [weak self] query in
                self?.model.notebookIndex.tagged(tag).filter { Matching.score(query, in: $0.title) != nil }.map { PaletteItem(id: $0.id, title: $0.title, detail: "#" + tag) } ?? []
            }, choose: { [weak self] item, newTab in self?.workspace.open(item.id, newTab: newTab) })
        } else { workspace.open(id, newTab: newTab) }
    }
    func sidebarSelectNotebook(_ name: String?) { workspace.selectNotebook(name) }
    func sidebarFilterChanged(_ text: String) { workspace.filter = text; if text == workspace.filter { refresh() } }
    func sidebarExpanded(_ key: String, _ expanded: Bool) { workspace.setExpanded(key, expanded) }
    func sidebarNewNote() { newNote() }
    func sidebarNewNotebook() { newNotebook() }
    func sidebarRename(note id: String, to title: String) { notebook.rename(id, to: title); refresh() }
    func sidebarRename(notebook name: String, to new: String) {
        if new.trimmingCharacters(in: .whitespaces) != name { attempt { try workspace.renameNotebook(name, to: new) } }
        refresh()
    }
    func sidebarMove(note id: String, to notebook: String?) { attempt { try workspace.move(id, to: notebook) } }
    func sidebarDelete(note id: String) { attempt { try workspace.delete(id) } }
    func sidebarDelete(notebook name: String) {
        let alert = NSAlert()
        alert.messageText = "Delete notebook “\(name)”?"
        alert.informativeText = "Its notes move to the Inbox."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Delete Notebook")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        attempt { try workspace.deleteNotebook(name) }
    }
    func sidebarRestore(note id: String) { attempt { try workspace.restore(id) } }
    func sidebarPurge(note id: String) {
        let alert = NSAlert()
        alert.messageText = "Delete this note permanently?"
        alert.informativeText = "This cannot be undone."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Delete Permanently")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        attempt { try workspace.purge(id) }
    }
    func sidebarNotebookNames() -> [String] { model.notebooks.map(\.name) }
}
