import AppKit
import SyncrCore
import SyncrCompanion

struct CompanionPreferences: Codable {
    var provider = "codex"
    var readingModel = ""
    var askingModel = ""
    var drawings = false
    var tokenLimit = 100000
    var requestLimit = 100
}
@MainActor final class CompanionController {
    let model: LibraryModel; let workspace: Workspace; let notebook: NotebookController
    let detail: NoteDetailView
    private(set) var snapshot: NoteSnapshot?
    private(set) var readers = Reader.starters
    private(set) var preferences = CompanionPreferences()
    private var session: CompanionSession
    private var revision = ""; private var noteID: String?; private var requestGeneration = 0
    private var timer: Timer?; private var prefsWindow: CompanionSettingsWindow?
    private var previousPassText = ""
    private var lastActivity = -TimeInterval.infinity; private var pendingChange = false
    private var folder: URL
    private var renderItems: [CompanionItemView] = []; private var renderText = ""
    private var renderColour: InkColour?; private var renderDrawings = false
    private var savedBudget: Data?
    private var shownMeter = BudgetMeter(day: BudgetMeter.dayKey())
    private var busy = false; private var catalog: [ProviderModel] = []
    private(set) var status = "Ready"; private var lastWireWindow: NSWindow?
    var debugProvider: RecordedProvider?
    init(model:LibraryModel,workspace:Workspace,notebook:NotebookController,detail:NoteDetailView) {
        self.model = model
        self.workspace = workspace
        self.notebook = notebook
        self.detail = detail
        folder = model.folder
        let state = Self.libraryState(model)
        preferences = state.preferences
        readers = state.readers
        session = CompanionSession(budget: state.meter)
        detail.companionStrip.ask = { [weak self] in self?.ask() }
        detail.companionStrip.settings = { [weak self] in self?.showSettings() }
        detail.companionStrip.askAll = { [weak self] in self?.ask(all: true) }
        detail.companionStrip.draw = { [weak self] in self?.ask(draw: true) }
        detail.companionStrip.lastRequest = { [weak self] in self?.lastRequest() }
        detail.companionStrip.changed = { [weak self] in self?.stripChanged() }
        detail.onCompanionActivity = { [weak self] ink in self?.activity(ink: ink) }
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.readAlong() } }
    }

    /// Preferences, budget and readers stored under the library's `.mac/` folder.
    private static func libraryState(_ model: LibraryModel) -> (preferences: CompanionPreferences, meter: BudgetMeter, readers: [Reader]) {
        let preferences = model.readState("companion.json").flatMap { try? JSONDecoder().decode(CompanionPreferences.self, from: $0) } ?? CompanionPreferences()
        var meter = model.readState("companion-budget.json").flatMap { try? JSONDecoder().decode(BudgetMeter.self, from: $0) } ?? BudgetMeter(day: BudgetMeter.dayKey())
        meter.tokenLimit = max(1, preferences.tokenLimit)
        meter.requestLimit = max(1, preferences.requestLimit)
        let readers = (try? Reader.load(at: model.folder.appendingPathComponent(".mac/readers.json"))) ?? Reader.starters
        return (preferences, meter, readers)
    }

    private func makeProvider() -> any CompanionProvider {
        if let debugProvider { return debugProvider }
        return preferences.provider == "claude" ? ClaudeProvider() : CodexProvider()
    }

    func observe() {
        if folder != model.folder {
            folder = model.folder
            noteID = nil; snapshot = nil; revision = ""; requestGeneration += 1
            let state = Self.libraryState(model)
            preferences = state.preferences
            readers = state.readers
            session = CompanionSession(budget: state.meter)
            savedBudget = nil; renderColour = nil
            prefsWindow?.close(); prefsWindow = nil; catalog = []; debugProvider = nil
        }
        guard let id = workspace.active, model.shownNote == id, model.isWritable(id) else {
            if noteID != nil { noteID = nil; snapshot = nil; revision = ""; requestGeneration += 1; Task { await session.reset() } }
            detail.companionStrip.isHidden = true
            detail.showCompanion(items: [], colour: .systemBlue, controller: self)
            return
        }
        if noteID != id {
            noteID = id; requestGeneration += 1; snapshot = nil; revision = ""
            previousPassText = detail.documentEditor.textView.string; pendingChange = false
            renderText = ""; renderItems = []; renderColour = nil
            detail.showCompanion(items: [], colour: .systemBlue, controller: self)
            Task { await session.reset() }
        }
        let key = model.folder.path + id + String(model.notes.first { $0.id == id }?.localSequence ?? 0)
        guard key != revision else { return }; revision = key
        model.readSnapshot(id:id) { [weak self] result in
            guard let self, self.noteID == id else { return }
            if case .success(let s) = result, s.localSequence >= (self.snapshot?.localSequence ?? 0) {
                if let old = self.snapshot, old.strokes != s.strokes { self.activity() }
                self.snapshot = s; self.updateUI()
            }
        }
    }
    /// Reflow margin anchors after local typography changes without configuring the
    /// session, issuing a provider request, or submitting a note command.
    func refreshPresentation() {
        guard let s = snapshot, noteID == workspace.active else { return }
        let settings = CompanionStore.settings(s)
        detail.showCompanion(items: s.companionItems,
                             colour: CompanionStrip.colour(settings.colour), controller: self, drawings: preferences.drawings)
    }

    private func updateUI() {
        guard let s = snapshot else { return }
        let settings = CompanionStore.settings(s)
        detail.companionStrip.isHidden = false; detail.companionStrip.show(settings,readers:readers,writable:true)
        let text = detail.documentEditor.textView.string
        if renderItems != s.companionItems || renderText != text || renderColour != settings.colour || renderDrawings != preferences.drawings {
            renderItems = s.companionItems; renderText = text; renderColour = settings.colour; renderDrawings = preferences.drawings
            detail.showCompanion(items: s.companionItems, colour: CompanionStrip.colour(settings.colour), controller: self, drawings: preferences.drawings)
        }
        Task {
            await session.configure(mode: CompanionStore.mode(settings.mode), notes: CompanionStore.unpinned(s))
            await updateMeter()
        }
    }
    private func stripChanged() {
        guard let s = snapshot, let id = noteID else { return }
        let settings = detail.companionStrip.settings(readers: readers)
        requestGeneration += 1
        Task { await session.configure(mode: CompanionStore.mode(settings.mode), notes: CompanionStore.unpinned(s)) }
        edit(id) { try CompanionStore.saveSettings(settings,note:$0) }
    }
    func activity(ink:Bool = false) {
        guard noteID != nil else { return }
        // The pen callback only updates a timestamp. Diffing, disk and provider work happen later.
        lastActivity = ProcessInfo.processInfo.systemUptime
        if !ink { pendingChange = true; requestGeneration += 1 }
    }
    private func transferActivity(to session:CompanionSession) async {
        let time = lastActivity, dirty = pendingChange; pendingChange = false
        if dirty { await session.activity(at:time) } else { await session.idleActivity(at:time) }
    }
    private func readAlong() {
        let session = self.session
        Task { [weak self] in
            guard let self else { return }
            await self.transferActivity(to:session)
            self.detail.companionStrip.thinking = await session.isInFlight()
            guard !self.busy, await session.ready(at:ProcessInfo.processInfo.systemUptime) else { return }
            self.send(readingAlong:true)
        }
    }
    func ask(draw:Bool = false, all:Bool = false) {
        guard let s = snapshot, CompanionStore.settings(s).mode != .off else { setStatus("Companion is off for this note"); return }
        if draw && !preferences.drawings { setStatus("Turn on experimental drawings in Companion Settings"); return }
        detail.documentEditor.flush()
        notebook.flushAll { [weak self] ok in
            guard let self, ok else { return }
            guard all else { self.send(draw: draw); return }
            let text = self.detail.documentEditor.textView.string
            self.askReaders(self.readers, text: text, passage: self.selection(in: text), generation: self.requestGeneration)
        }
    }
    private func askReaders(_ remaining:[Reader],text:String,passage:ByteSpan?,generation:Int) {
        guard let first = remaining.first, generation == requestGeneration, detail.documentEditor.textView.string == text else { return }
        send(reader:first,passage:passage) { [weak self] in self?.askReaders(Array(remaining.dropFirst()),text:text,passage:passage,generation:generation) }
    }
    private func selection(in text:String) -> ByteSpan? {
        let selection = detail.documentEditor.textView.selectedRange()
        let ns = text as NSString
        guard selection.location <= ns.length else { return nil }
        let range = selection.length > 0 ? selection : ns.paragraphRange(for:selection)
        guard let r = Range(range,in:text) else { return nil }
        return ByteSpan(start:text[..<r.lowerBound].utf8.count,end:text[..<r.upperBound].utf8.count)
    }
    private func send(readingAlong:Bool = false,draw:Bool = false,reader explicit:Reader? = nil,thread:String? = nil,passage:ByteSpan? = nil,confirmed:Bool = false,done:(() -> Void)? = nil) {
        guard !busy, let s = snapshot, let id = noteID, model.isWritable(id) else { return }
        let settings = CompanionStore.settings(s)
        guard settings.mode != .off else { done?(); return }
        let text = detail.documentEditor.textView.string
        guard !text.isEmpty || s.strokes.contains(where:{!$0.tombstone}), readingAlong == false || text == (s.textBlocks.first(where:{$0.role == .document && !$0.tombstone})?.text ?? "") else { return }
        let reader = explicit ?? readers.first(where:{$0.id == settings.activeReader}) ?? Reader.starters[0]
        let modelID = readingAlong ? preferences.readingModel : preferences.askingModel
        if modelID.isEmpty && debugProvider == nil {
            guard !readingAlong else { return }
            setStatus("Choose a model in Companion Settings")
            showSettings()
            return
        }
        let provider = makeProvider()
        let instruction: String
        if let thread { instruction = "Reply to thread_id \(thread), using reply." }
        else if draw { instruction = "Draw the selected passage using draw." }
        else if readingAlong { instruction = "Read along quietly. Only speak if a question sharpens this thought; at most one margin note." }
        else if explicit != nil { instruction = "Return one margin note on the selected passage as this reader." }
        else { instruction = "Respond to the selected passage with one margin note." }
        var input = RequestInput(tell: settings.tell, reader: reader, markdown: text, changed: nil, selection: passage ?? selection(in: text),
                                 threads: conversations(s, text: text), instruction: instruction, web: settings.web, pagePNG: nil)
        let generation = requestGeneration, folder = model.folder, previous = previousPassText
        let requestedModel = debugProvider != nil ? "recorded" : modelID
        busy = true
        detail.companionStrip.thinking = true
        let session = self.session
        let clock = ProcessInfo.processInfo.systemUptime
        Task { [weak self] in
            do {
                guard let self else { return }
                await self.transferActivity(to:session)
                if !s.strokes.allSatisfy(\.tombstone) {
                    let region = CompanionPage.region(snapshot:s,selection:input.selection)
                    input.pagePNG = await CompanionPage.image(snapshot:s,selection:input.selection,strokes:self.model.drawing?.strokes ?? [],region:region)
                    if input.pagePNG != nil { input.pageRegion = region }
                }
                let capturedInput = input
                let response = try await Task.detached(priority:.utility) {
                    var input = capturedInput
                    if let splice = TextSplice.between(previous,text) { input.changed = ByteSpan(start:Int(splice.offset),end:Int(splice.offset)+splice.insert.utf8.count) }
                    let request = try CompanionRequest(model:requestedModel,input:input,readingAlong:readingAlong)
                    return try await session.send(provider:provider,request:request,at:clock,readingAlong:readingAlong,confirmedBudget:confirmed)
                }.value
                self.busy = false
                guard self.model.folder == folder else { return }
                await self.updateMeter()
                guard let response else { self.updateStatus(); done?(); return }
                guard self.noteID == id, self.model.folder == folder, self.requestGeneration == generation, self.snapshot.map({CompanionStore.settings($0).mode != .off}) == true else { done?(); return }
                self.previousPassText = text
                self.setStatus(response.tools == [.stayQuiet] ? "Syncr stayed quiet" : "Response received")
                let enabled = self.preferences.drawings
                self.edit(id, work: {
                    try CompanionStore.apply(response.tools, note: $0, reader: reader.name, expectedText: text, drawings: enabled,
                                             readingAlong: readingAlong, pageRegion: capturedInput.pageRegion)
                }, done: done)
            } catch {
                guard let self else { return }
                self.busy = false
                if case CompanionError.budget = error, !readingAlong {
                    let alert = NSAlert()
                    alert.messageText = "Daily companion budget reached"
                    alert.informativeText = "Reading along is paused until tomorrow. Send this request anyway?"
                    alert.addButton(withTitle: "Send Request")
                    alert.addButton(withTitle: "Cancel")
                    if alert.runModal() == .alertFirstButtonReturn {
                        self.send(readingAlong: false, draw: draw, reader: explicit, thread: thread, passage: passage, confirmed: true, done: done)
                    }
                } else { self.setStatus(error.localizedDescription); done?() }
            }
        }
    }
    private func updateMeter() async {
        let meter = await session.meter()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        if let data = try? encoder.encode(meter), data != savedBudget {
            savedBudget = data
            model.writeState("companion-budget.json", data)
        }
        shownMeter = meter
        updateStatus()
    }
    private func setStatus(_ text: String) {
        status = text
        updateStatus()
    }
    private func updateStatus() {
        detail.companionStrip.show(meter: shownMeter, status: status)
        if !busy { detail.companionStrip.thinking = false }
    }
    private func conversations(_ s:NoteSnapshot,text:String) -> [Conversation] {
        s.companionItems.compactMap { v in
            guard !v.tombstone, case .marginNote(let anchor,let kind,let body,let reader,let state,_) = v.body else { return nil }
            var quote = ""
            if let a = s.companionItems.first(where:{$0.id == anchor}), case .anchor(.text(_,let start,let end,_)) = a.body, Int(end) <= text.utf8.count { quote = String(decoding:Array(text.utf8)[Int(start)..<Int(end)],as:UTF8.self) }
            let messages = s.companionItems.compactMap { m -> (UInt32,ConversationMessage)? in guard !m.tombstone, case .threadMessage(let id,let order,let who,let text,let sources) = m.body, id == v.id else { return nil }; return (order,ConversationMessage(who:who == .you ? "you" : "companion",text:text,sources:sources.map { Source(title:$0.title,url:$0.url) })) }.sorted {$0.0 < $1.0}.map(\.1)
            return Conversation(id:v.id,quote:quote,kind:String(describing:kind),text:body,reader:reader,state:String(describing:state),messages:messages)
        }
    }
    private func edit(_ id:String,work:@escaping @Sendable (Note) throws -> Void,done:(() -> Void)? = nil) {
        model.editNote(id:id,work:work) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let s): if self.noteID == id { self.snapshot = s; self.updateUI() }; self.model.publishText(s)
            case .failure(let e): self.setStatus(e.localizedDescription)
            }
            done?()
        }
    }
    func setState(_ id:String,_ state:MarginState) { guard let note = noteID else { return }; edit(note) { try CompanionStore.setState(id,state,note:$0) } }
    func addToNote(_ text:String) { detail.documentEditor.textView.insertText(text,replacementRange:detail.documentEditor.textView.selectedRange()); detail.documentEditor.flush() }
    func reply(_ id:String,text:String) {
        guard let note = noteID, snapshot.map({CompanionStore.settings($0).mode != .off}) == true else { return }
        edit(note,work:{try CompanionStore.userReply(id,text:text,note:$0)}) { [weak self] in self?.send(thread:id) }
    }
    func undoAgent(redo: Bool = false) {
        guard let id = noteID else { return }
        edit(id) { handle in
            let s = try handle.agentSnapshot()
            guard let target = redo ? s.redoTarget : s.undoTarget else { return }
            let body: CommandBody = redo ? .redo(targetOperationId: target) : .undo(targetOperationId: target)
            _ = try handle.submitAgent(command: Command(operationId: newId(), dependencies: redo ? s.redoDependencies : s.undoDependencies, body: body))
        }
    }
    func lastRequest() {
        Task {
            let record = await session.lastRequest
            let scroll = NSTextView.scrollableTextView()
            let text = scroll.documentView as! NSTextView
            text.isEditable = false
            text.string = record?.wire ?? "No request has been sent."
            text.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
            text.textContainerInset = NSSize(width: 8, height: 8)
            let window = lastWireWindow ?? NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 600), styleMask: [.titled, .closable, .resizable],
                                                    backing: .buffered, defer: false)
            window.title = "Last Companion Request (authentication omitted)"
            window.contentView = scroll
            window.isReleasedWhenClosed = false
            if lastWireWindow == nil { window.center() }
            window.makeKeyAndOrderFront(nil)
            lastWireWindow = window
        }
    }
    func showSettings() {
        if prefsWindow == nil { prefsWindow = CompanionSettingsWindow(controller:self) }
        prefsWindow?.show(); refreshModels()
    }
    func refreshModels() {
        let provider = makeProvider()
        Task {
            let state = await provider.signInState()
            do {
                catalog = try await provider.models()
                if preferences.readingModel.isEmpty || preferences.askingModel.isEmpty {
                    let claude = preferences.provider == "claude"
                    let fallback = claude ? ClaudeProvider.readingDefault : CodexProvider.newestSol(catalog) ?? catalog.first?.id ?? ""
                    if preferences.readingModel.isEmpty { preferences.readingModel = fallback }
                    if preferences.askingModel.isEmpty { preferences.askingModel = claude ? ClaudeProvider.askingDefault : fallback }
                    persistPreferences()
                }
                prefsWindow?.update(models: catalog, state: state)
            } catch {
                prefsWindow?.update(models: [], state: state + " · " + error.localizedDescription)
            }
        }
    }
    func savePreferences(_ value:CompanionPreferences) {
        let changedProvider = value.provider != preferences.provider
        preferences = value
        if changedProvider { preferences.readingModel = ""; preferences.askingModel = ""; catalog = [] }
        requestGeneration += 1
        Task {
            await session.setLimits(tokens: preferences.tokenLimit, requests: preferences.requestLimit)
            await updateMeter()
        }
        persistPreferences()
        prefsWindow?.update(models: catalog, state: status)
        if changedProvider { refreshModels() }
        updateUI()
    }
    private func persistPreferences() { if let data = try? JSONEncoder().encode(preferences) { model.writeState("companion.json",data) } }
    func saveReaders(_ value:[Reader]) throws { try Reader.save(value,at:model.folder.appendingPathComponent(".mac/readers.json")); readers = value; updateUI() }
    func debugMode(_ mode:Int) { detail.companionStrip.mode.selectItem(at:mode); stripChanged() }
    func debugFixture(_ data:Data,askNow:Bool = true,all:Bool = false) { debugProvider = RecordedProvider(responses:Array(repeating:data,count:32)); if askNow { ask(all:all) } }
    func debugStrip(web:Bool? = nil,preset:Int? = nil,reader:Int? = nil) {
        if let web { detail.companionStrip.web.state = web ? .on : .off }
        if let preset, Reader.tellPresets.indices.contains(preset) { detail.companionStrip.tell.stringValue = Reader.tellPresets[preset] }
        if let reader, readers.indices.contains(reader) { detail.companionStrip.reader.selectItem(at:reader) }
        stripChanged()
    }
    func debugRecord(at url: URL) async {
        if let record = await session.lastRequest, let data = try? JSONEncoder().encode(record) { try? data.write(to: url) }
    }
    func debugReport() async -> String {
        let attempts = await session.attempts
        let items = snapshot?.companionItems.filter { !$0.tombstone } ?? []
        let margins = items.compactMap { v -> String? in
            guard case .marginNote(_, _, _, let reader, let state, _) = v.body else { return nil }
            return v.id + ":" + reader + ":" + String(describing: state)
        }.joined(separator: ",")
        let anchors = items.compactMap { v -> String? in
            guard case .anchor(.text(_, let start, let end, let detached)) = v.body else { return nil }
            return "\(start)-\(end):\(detached)"
        }.joined(separator: ",")
        return "mode=\(snapshot.map { String(describing:CompanionStore.settings($0).mode) } ?? "none") items=\(snapshot?.companionItems.filter{!$0.tombstone}.count ?? 0) attempts=\(attempts) drawings=\(preferences.drawings) margins=\(margins) anchors=\(anchors) status=\(status)"
    }
}
