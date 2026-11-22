import AppKit
import SyncrCore
import SyncrCompanion

/// One engine pass for the open note: what streamed in, what is staged to be written, and its timings.
@MainActor final class CompanionPass {
    let noteID: String; let reader: Reader; let text: String; let generation: Int; let reading: Bool
    var staged: [StreamItem] = []
    var preview: StreamTextPreview?
    var picture: MacPicture?; var pictureStep = 0
    var warning: String?
    var imageIDs: [String] = []
    var start = 0.0
    var firstText: Double?; var firstStep: Double?; var gateSeconds: Double?
    var ended = false
    /// A direct ask: the thread the answer goes to, the UTF-16 offset where the asked passage ends, and the named step.
    var askThread: String?; var passageEnd = 0; var step: String?
    var done: (() -> Void)?
    init(noteID: String, reader: Reader, text: String, generation: Int, reading: Bool) { self.noteID = noteID; self.reader = reader; self.text = text; self.generation = generation; self.reading = reading }
}
@MainActor final class CompanionController: CompanionSettingsSource {
    let model: LibraryModel; let workspace: Workspace; let notebook: NotebookController
    let detail: NoteDetailView
    private(set) var snapshot: NoteSnapshot?
    private(set) var readers = Reader.starters
    private(set) var preferences = CompanionPreferences()
    let voice = CompanionVoice()
    let images = NoteImageStore()
    private(set) var voiceNotes: Set<String> = []
    private(set) var voicePreferences = VoicePreferences()
    private var spoken = Set<String>()
    private var threads: [String: CompanionThread] = [:]
    /// One engine per open note tab: created when the tab's note is first used, shut down when the tab closes.
    private var engines: [String: CompanionEngine] = [:]
    private var pass: CompanionPass?
    /// Ink-only notes have no text to quote, so the engine (quotes only) cannot answer them: the one-shot session path stays for those.
    private var inkSession: CompanionSession
    private var revision = ""; private var noteID: String?; private var requestGeneration = 0
    private var timer: Timer?
    private weak var settingsView: CompanionSettingsView?
    var onShowSettings: (() -> Void)?
    private var catalogGeneration = 0
    private(set) var settingsProviderState = "Not checked"
    var settingsModels: [ProviderModel] { catalog }
    var settingsMeter: BudgetMeter {
        var meter = shownMeter; meter.rollover(day: BudgetMeter.dayKey()); return meter
    }
    private var previousPassText = ""
    private var lastActivity = -TimeInterval.infinity; private var pendingChange = false
    private var changedSincePause = false; private var lastPause = -TimeInterval.infinity
    private var folder: URL
    private var renderItems: [CompanionItemView] = []; private var renderText = ""; private var renderImages: [ImageMarkView] = []
    private var renderColour: InkColour?; private var renderDrawings = false
    private var pictureWarnings: [String: String] = [:]
    private var savedBudget: Data?
    private var shownMeter = BudgetMeter(day: BudgetMeter.dayKey())
    private var busy: Bool { pass != nil || inkBusy }
    private var inkBusy = false; private var catalog: [ProviderModel] = []
    private var streamRefresh: DispatchWorkItem?
    private(set) var status = "Ready"; private var lastWireWindow: NSWindow?
    private(set) var lastTiming = "none"
    var debugProvider: (any CompanionProvider)?
    private var stateFolder: URL { model.folder.appendingPathComponent(".mac", isDirectory: true) }
    private var decisions: LocalDecisionLog { LocalDecisionLog(stateFolder: stateFolder) }

    init(model:LibraryModel,workspace:Workspace,notebook:NotebookController,detail:NoteDetailView) {
        self.model = model
        self.workspace = workspace
        self.notebook = notebook
        self.detail = detail
        folder = model.folder
        let state = Self.libraryState(model)
        preferences = state.preferences
        readers = state.readers
        PictureRenderer.handDrawn = state.preferences.handDrawn
        shownMeter = state.meter
        inkSession = CompanionSession(budget: state.meter)
        loadVoice()
        voice.target = { [weak self, weak detail] in
            if let thread = self?.threads.values.first(where: { $0.window.firstResponder === $0.composer && $0.window.isKeyWindow }) { return thread.composer }
            guard let detail, detail.window?.firstResponder === detail.documentEditor.textView else { return nil }
            return detail.documentEditor.textView
        }
        voice.status = { [weak self] in self?.setStatus($0) }
        voice.install()
        detail.companionStrip.voiceChanged = { [weak self] enabled in
            guard let self, let id = self.noteID else { return }
            if enabled { self.voiceNotes.insert(id) } else { self.voiceNotes.remove(id); self.voice.silence() }
            if let data = try? JSONEncoder().encode(self.voiceNotes) { self.model.writeState("companion-voice-notes.json", data) }
        }
        detail.companionStrip.ask = { [weak self] in self?.ask() }
        detail.companionStrip.settings = { [weak self] in self?.showSettings() }
        detail.companionStrip.askAll = { [weak self] in self?.ask(all: true) }
        detail.companionStrip.draw = { [weak self] in self?.ask(draw: true) }
        detail.companionStrip.lastRequest = { [weak self] in self?.lastRequest() }
        detail.companionStrip.changed = { [weak self] in self?.stripChanged() }
        detail.onCompanionActivity = { [weak self] ink in self?.activity(ink: ink) }
        detail.documentEditor.textView.onImage = { [weak self] data, offset in self?.storeImage(data, at: offset) }
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
        switch preferences.provider {
        case "claude": return ClaudeProvider()
        case "openai": return OpenAIProvider()
        default: return CodexProvider()
        }
    }
    private var modelSettings: ModelSettings {
        debugProvider != nil ? ModelSettings(gate: "recorded", answer: "recorded") : ModelSettings(gate: preferences.readingModel, answer: preferences.askingModel)
    }
    private func engine(for id: String) -> CompanionEngine {
        if let engine = engines[id] { return engine }
        let engine = CompanionEngine(provider: makeProvider(), models: modelSettings, inspector: CorePictureInspector(), stateFolder: stateFolder, budget: shownMeter)
        engines[id] = engine
        return engine
    }
    private func closeEngines(except keep: Set<String> = []) {
        for (id, engine) in engines where !keep.contains(id) { engines[id] = nil; Task { await engine.shutdown() } }
    }

    func observe() {
        if folder != model.folder {
            folder = model.folder
            discardPass(); closeEngines()
            noteID = nil; snapshot = nil; revision = ""; requestGeneration += 1
            let state = Self.libraryState(model)
            preferences = state.preferences
            PictureRenderer.handDrawn = preferences.handDrawn
            readers = state.readers; shownMeter = state.meter
            inkSession = CompanionSession(budget: state.meter)
            loadVoice(); images.forget(); pictureWarnings = [:]
            savedBudget = nil; renderColour = nil
            catalogGeneration += 1; catalog = []; debugProvider = nil
            settingsProviderState = "Not checked"
            settingsView?.reloadFromSource()
            if settingsView?.window?.isVisible == true { refreshModels() }
        }
        // A closed tab takes its engine (and its pi child) with it.
        closeEngines(except: Set(workspace.tabs).union(noteID.map { [$0] } ?? []))
        guard let id = workspace.active, model.shownNote == id, model.isWritable(id) else {
            if noteID != nil { discardPass(); noteID = nil; snapshot = nil; revision = ""; requestGeneration += 1; Task { await inkSession.reset() } }
            detail.companionStrip.isHidden = true
            detail.showCompanion(items: [], colour: .systemBlue, controller: self)
            return
        }
        if noteID != id {
            discardPass()
            voice.stop(); voice.silence()
            threads.values.forEach { $0.window.close() }; threads = [:]
            noteID = id; requestGeneration += 1; snapshot = nil; revision = ""
            previousPassText = detail.documentEditor.textView.string; pendingChange = false; changedSincePause = false
            renderText = ""; renderItems = []; renderImages = []; renderColour = nil; pictureWarnings = [:]
            detail.showCompanion(items: [], colour: .systemBlue, controller: self)
            Task { await inkSession.reset() }
        }
        let key = model.folder.path + id + String(model.notes.first { $0.id == id }?.localSequence ?? 0)
        guard key != revision else { return }; revision = key
        model.readSnapshot(id:id) { [weak self] result in
            guard let self, self.noteID == id else { return }
            if case .success(let s) = result, s.localSequence >= (self.snapshot?.localSequence ?? 0) {
                if let old = self.snapshot, old.strokes != s.strokes { self.activity() }
                if self.snapshot == nil { self.spoken.formUnion(s.companionItems.map(\.id)) }
                self.snapshot = s; self.updateUI()
            }
        }
    }
    /// Reflow margin anchors after local typography changes without configuring the
    /// engine, issuing a provider request, or submitting a note command.
    func refreshPresentation() {
        guard snapshot != nil, noteID == workspace.active else { return }
        showAll()
    }
    private func showAll() {
        guard let s = snapshot else { return }
        detail.showCompanion(items: s.companionItems, colour: CompanionStrip.colour(CompanionStore.settings(s).colour), controller: self,
                             drawings: preferences.drawings, images: s.images, warnings: pictureWarnings)
    }

    private func updateUI() {
        guard let s = snapshot, let id = noteID else { return }
        let settings = CompanionStore.settings(s)
        let stripWasHidden = detail.companionStrip.isHidden
        detail.companionStrip.isHidden = false; detail.companionStrip.show(settings,readers:readers,writable:true)
        if stripWasHidden { Dither.reveal(detail.companionStrip, duration: Motion.dissolve) }
        detail.companionStrip.voice.state = voiceNotes.contains(id) ? .on : .off
        let speak = voiceNotes.contains(id)
        for item in s.companionItems where !item.tombstone && !spoken.contains(item.id) {
            spoken.insert(item.id)
            if speak { readAloud(item.body) }
        }
        let text = detail.documentEditor.textView.string
        if renderItems != s.companionItems || renderText != text || renderImages != s.images || renderColour != settings.colour || renderDrawings != preferences.drawings {
            renderItems = s.companionItems; renderText = text; renderImages = s.images; renderColour = settings.colour; renderDrawings = preferences.drawings
            showAll()
        }
        for id in threads.keys where threads[id]?.window.isVisible == true { openThread(id) }
        let engine = self.engine(for: id), mode = CompanionStore.mode(settings.mode), unpinned = CompanionStore.unpinned(s)
        Task {
            await engine.configure(mode: mode, unpinnedNotes: unpinned)
            await inkSession.configure(mode: mode, notes: unpinned)
            await updateMeter()
        }
    }
    private func stripChanged() {
        guard let s = snapshot, let id = noteID else { return }
        let settings = detail.companionStrip.settings(readers: readers)
        requestGeneration += 1
        let engine = self.engine(for: id), mode = CompanionStore.mode(settings.mode), unpinned = CompanionStore.unpinned(s)
        Task { await engine.configure(mode: mode, unpinnedNotes: unpinned); await inkSession.configure(mode: mode, notes: unpinned) }
        if settings.mode == .off { discardPass() }
        edit(id) { try CompanionStore.saveSettings(settings,note:$0) }
    }

    // MARK: Activity and reading along

    func activity(ink:Bool = false) {
        guard noteID != nil else { return }
        // The pen callback only updates a timestamp. Diffing, disk and provider work happen later.
        lastActivity = ProcessInfo.processInfo.systemUptime
        if !ink {
            pendingChange = true; changedSincePause = true; requestGeneration += 1
            // Typing drops the pass in flight now; the engine confirms with `.cancelled`, nothing is written.
            if let pass, !pass.ended, let id = noteID, let engine = engines[id] {
                discardPass(); let time = lastActivity; Task { await engine.activity(at: time) }
            }
        }
    }
    /// The same timing rule as the engine's trigger, so the host does not build a request for every half-second tick.
    nonisolated static func pauseDue(now: TimeInterval, lastActivity: TimeInterval, lastPause: TimeInterval, changed: Bool, mode: SyncrCompanion.CompanionMode) -> Bool {
        mode == .thinksWithYou && changed && now - lastActivity >= 3 && now - lastPause >= 20
    }
    private func readAlong() {
        guard let id = noteID, let s = snapshot, let engine = engines[id] else { return }
        let time = lastActivity, dirty = pendingChange; pendingChange = false
        Task { if dirty { await engine.activity(at: time) } else { await engine.idleActivity(at: time) } }
        let now = ProcessInfo.processInfo.systemUptime
        let mode = CompanionStore.mode(CompanionStore.settings(s).mode)
        guard !busy, Self.pauseDue(now: now, lastActivity: lastActivity, lastPause: lastPause, changed: changedSincePause, mode: mode), CompanionStore.unpinned(s) < 3 else { return }
        lastPause = now; changedSincePause = false
        startPass(.pause)
    }
    func ask(draw:Bool = false, all:Bool = false) {
        guard let s = snapshot, CompanionStore.settings(s).mode != .off else { setStatus("Companion is off for this note"); return }
        if draw && !preferences.drawings { setStatus("Turn on pictures in Companion Settings"); return }
        detail.documentEditor.flush()
        notebook.flushAll { [weak self] ok in
            guard let self, ok else { return }
            guard all else { if draw { self.startPass(.ask, draw: true) } else { self.startDirectAsk() }; return }
            let text = self.detail.documentEditor.textView.string
            self.askReaders(self.readers, text: text, passage: self.selection(in: text), generation: self.requestGeneration)
        }
    }
    private func askReaders(_ remaining:[Reader],text:String,passage:ByteSpan?,generation:Int) {
        guard let first = remaining.first, generation == requestGeneration, detail.documentEditor.textView.string == text else { return }
        startPass(.ask, reader: first, passage: passage) { [weak self] in self?.askReaders(Array(remaining.dropFirst()),text:text,passage:passage,generation:generation) }
    }
    private func selection(in text:String) -> ByteSpan? {
        let selection = detail.documentEditor.textView.selectedRange()
        let ns = text as NSString
        guard selection.location <= ns.length else { return nil }
        let range = selection.length > 0 ? selection : ns.paragraphRange(for:selection)
        guard let r = Range(range,in:text) else { return nil }
        return ByteSpan(start:text[..<r.lowerBound].utf8.count,end:text[..<r.upperBound].utf8.count)
    }

    // MARK: The engine pass

    private enum PassKind { case ask, reply(String), pause }

    /// The named step as the strip and the answer show it.
    static func stepLabel(_ step: CompanionStep) -> String {
        switch step {
        case .deciding: "Deciding"
        case .thinking: "Thinking"
        case .writing: "Writing"
        case .sketching: "Sketching"
        case .checking: "Checking the picture"
        case .fixing: "Fixing the picture"
        }
    }

    /// ⌘J: the owner's passage becomes a thread (the ask is its first message), then the model answers on it and the
    /// answer appears directly under the passage. Without text to quote, or without a model chosen, the old path runs.
    private func startDirectAsk() {
        let text = detail.documentEditor.textView.string
        guard pass == nil, !inkBusy, let id = noteID, model.isWritable(id), !text.isEmpty, let span = selection(in: text),
              debugProvider != nil || (!preferences.readingModel.isEmpty && !preferences.askingModel.isEmpty) else { startPass(.ask); return }
        let bytes = Array(text.utf8), ask = String(decoding: bytes[span.start..<min(bytes.count, span.end)], as: UTF8.self)
        let thread = newId()
        edit(id, work: { try CompanionStore.addAsk(threadID: thread, span: span, ask: ask, note: $0) }, done: { [weak self] in
            self?.bindScript(thread: thread)
            self?.startPass(.ask, passage: span, askThread: thread)
        })
    }

    private func startPass(_ kind: PassKind, draw: Bool = false, reader explicit: Reader? = nil, passage: ByteSpan? = nil, confirmed: Bool = false, askThread: String? = nil, done: (() -> Void)? = nil) {
        guard pass == nil, !inkBusy, let s = snapshot, let id = noteID, model.isWritable(id) else { done?(); return }
        let settings = CompanionStore.settings(s)
        guard settings.mode != .off else { done?(); return }
        var reading = false; if case .pause = kind { reading = true }
        let text = detail.documentEditor.textView.string
        let stored = s.textBlocks.first(where:{$0.role == .document && !$0.tombstone})?.text ?? ""
        if text.isEmpty {
            // Nothing to quote: only the ink page can be read, by the one-shot path with its region notes.
            if !reading, s.strokes.contains(where:{!$0.tombstone}) { sendInk(explicit: explicit, confirmed: confirmed, done: done) } else { done?() }
            return
        }
        guard !reading || text == stored else { done?(); return }
        let reader = explicit ?? readers.first(where:{$0.id == settings.activeReader}) ?? Reader.starters[0]
        if debugProvider == nil, preferences.readingModel.isEmpty || preferences.askingModel.isEmpty {
            guard !reading else { done?(); return }
            setStatus("Choose a model in Companion Settings"); showSettings(); done?(); return
        }
        var instruction: String
        switch kind {
        case .reply(let thread): instruction = "Reply to thread_id \(thread), using reply."
        case .pause: instruction = "Read along quietly. Only speak if it sharpens this thought; at most one margin note."
        case .ask:
            if let askThread { instruction = "Answer the owner's question in the selected passage on thread_id \(askThread), using reply." }
            else if draw { instruction = "Explain the selected passage with a step-by-step picture, then two or three sentences on how to read it." }
            else if explicit != nil { instruction = "Return one margin note on the selected passage as this reader." }
            else { instruction = "Respond to the selected passage with one margin note." }
        }
        if !preferences.drawings { instruction += " Answer in words only; do not draw a picture." }
        var input = RequestInput(tell: settings.tell, reader: reader, markdown: text, changed: nil, selection: passage ?? selection(in: text),
                                 threads: conversations(s, text: text), instruction: instruction, web: settings.web, pagePNG: nil, askThreadID: askThread, pictures: preferences.drawings)
        let state = CompanionPass(noteID: id, reader: reader, text: text, generation: requestGeneration, reading: reading)
        state.done = done
        if let askThread, let span = input.selection {
            state.askThread = askThread
            state.passageEnd = String(decoding: Array(text.utf8)[..<min(text.utf8.count, span.end)], as: UTF8.self).utf16.count
        }
        pass = state
        detail.onStreamingAskChanged = { [weak self] in self?.showAll() }
        detail.companionStrip.thinking = true
        setStatus(reading ? "Deciding…" : "Thinking…"); state.step = reading ? "Deciding" : "Thinking"
        if askThread != nil { scheduleStreamRefresh(state) }
        let engine = self.engine(for: id), previous = previousPassText
        let event: (RequestInput) -> EngineEvent = { value in
            switch kind { case .ask: .ask(value); case .reply: .reply(value); case .pause: .pause(value) }
        }
        Task { [weak self] in
            guard let self else { return }
            let time = self.lastActivity, dirty = self.pendingChange; self.pendingChange = false
            if dirty { await engine.activity(at: time) } else { await engine.idleActivity(at: time) }
            guard self.pass === state else { return }
            if !s.strokes.allSatisfy(\.tombstone) {
                let region = CompanionPage.region(snapshot:s,selection:input.selection)
                input.pagePNG = await CompanionPage.image(snapshot:s,selection:input.selection,strokes:self.model.drawing?.strokes ?? [],region:region)
                if input.pagePNG != nil { input.pageRegion = region }
            }
            // Stored images near the passage, scaled to 1280 on the worker; "seen" is set after the pass finishes.
            let near = reading ? [] : NoteImagesNear.ids(s, text: text, passage: input.selection)
            let copies = await self.images.providerCopies(near, note: id, model: self.model)
            input.images = copies.map { NoteImage(id: $0.id, mimeType: $0.mime, data: $0.data) }
            state.imageIDs = copies.map(\.id)
            guard self.pass === state else { return }
            let capture = input
            input.changed = await Task.detached(priority:.utility) {
                TextSplice.between(previous,text).map { ByteSpan(start:Int($0.offset),end:Int($0.offset)+$0.insert.utf8.count) } ?? capture.changed
            }.value
            guard self.pass === state else { return }
            state.start = ProcessInfo.processInfo.systemUptime
            do {
                let stream = try await engine.events(for: event(input), at: state.start, confirmedBudget: confirmed)
                for await output in stream { self.handle(output, state) }
                // An ineligible pause (or a stream that ended without a terminal event) leaves nothing to write.
                self.end(state, commit: false)
            } catch {
                self.end(state, commit: false)
                if case CompanionError.budget = error, !reading {
                    let alert = NSAlert()
                    alert.messageText = "Daily companion budget reached"
                    alert.informativeText = "Reading along is paused until tomorrow. Send this request anyway?"
                    alert.addButton(withTitle: "Send Request")
                    alert.addButton(withTitle: "Cancel")
                    if alert.runModal() == .alertFirstButtonReturn { self.startPass(kind, draw: draw, reader: explicit, passage: passage, confirmed: true, askThread: askThread, done: done) }
                } else { self.setStatus(error.localizedDescription) }
            }
        }
    }

    private func elapsed(_ state: CompanionPass) -> Double { ProcessInfo.processInfo.systemUptime - state.start }

    /// Debug control only: one line per engine output with its time since the pass began.
    var trace: ((String) -> Void)?
    private func handle(_ output: EngineOutput, _ state: CompanionPass) {
        if let trace {
            let name: String
            switch output {
            case .step(let step): name = "step(\(step.rawValue))"
            case .firstToken: name = "firstToken"; case .textPreview(let p): name = "textPreview(\(p?.text.count ?? -1))"
            case .item(let i): name = "item(\(String(describing: i).prefix(24)))"; case .pictureStep(_, _, let n): name = "pictureStep(\(n))"
            case .pictureReplace(_, let svg, let f): name = "pictureReplace(svg=\(svg != nil) findings=\(f.map(\.rule)))"
            case .usage: name = "usage"; case .gate(let d, let s): name = "gate(\(d.decision) \(s))"
            case .finished: name = "finished"; case .cancelled: name = "cancelled"; case .failed(let m): name = "failed(\(m))"
            }
            trace(String(format: "t=%.2f %@", elapsed(state), name))
        }
        guard pass === state, !state.ended else { return }
        switch output {
        case .step(let step):
            state.step = Self.stepLabel(step); setStatus(Self.stepLabel(step) + "…"); scheduleStreamRefresh(state)
            if step == .sketching, state.picture == nil { detail.showStreamPlaceholder(true) }
        case .firstToken(let seconds): state.firstText = seconds
        case .textPreview(let preview):
            state.preview = preview; scheduleStreamRefresh(state)
        case .item(let item):
            state.preview = nil
            if case .quiet = item { break }
            state.staged.append(item); scheduleStreamRefresh(state)
            if state.firstText == nil, case .margin = item { state.firstText = elapsed(state) }
        case .pictureStep(_, let svg, let step):
            guard let picture = try? CorePictureInspector.mac(svg: svg) else { break }
            if state.firstStep == nil { state.firstStep = elapsed(state) }
            state.picture = picture; state.pictureStep = step
            detail.showStreamPicture(picture, step: step)
        case .pictureReplace(_, let svg, let findings):
            if let svg, let picture = try? CorePictureInspector.mac(svg: svg) { state.picture = picture; state.pictureStep = picture.steps.count; detail.showStreamPicture(picture, step: picture.steps.count, animate: false) }
            else { state.picture = nil; detail.showStreamPicture(nil) }
            if findings.contains(where: { $0.rule == "fallback" }) { state.warning = "The picture didn't come out; the words above stand on their own." }
        case .gate(let decision, let seconds):
            state.gateSeconds = seconds
            setStatus(decision.speaks ? "Syncr has something to say" : "Syncr stayed quiet")
        case .usage: break
        case .finished: end(state, commit: true)
        case .cancelled: end(state, commit: false)
        case .failed(let message): setStatus("Companion stopped: " + message); end(state, commit: true)
        }
    }
    /// Provisional words (staged complete items first, then the text still arriving) as one quiet block at the margin; replies go
    /// to their thread window. Coalesced so token-rate updates do not rebuild a view each time.
    private func scheduleStreamRefresh(_ state: CompanionPass) {
        guard streamRefresh == nil else { return }
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.streamRefresh = nil
                guard let state = self.pass, !state.ended else { return }
                var margin: [String] = [], answer: [String] = []
                for item in state.staged {
                    switch item {
                    case .margin(_, _, let text): margin.append(text)
                    case .reply(let id, let text):
                        if id == state.askThread { answer.append(text) } else { self.openThread(id, provisional: text) }
                    default: break
                    }
                }
                if let preview = state.preview {
                    if let id = preview.threadID, id == state.askThread { answer.append(preview.text) }
                    else if let id = preview.threadID { self.openThread(id, provisional: preview.text) } else { margin.append(preview.text) }
                }
                if state.askThread != nil { self.detail.showStreamAnswer(passageEnd: state.passageEnd, text: answer.joined(separator: "\n\n"), step: state.step, thread: state.askThread) }
                self.detail.showStreamText(margin.isEmpty ? nil : margin.joined(separator: "\n\n"))
            }
        }
        streamRefresh = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.066, execute: work)
    }
    /// Drops everything provisional. Used on typing, Off and note switches; the engine is told separately.
    private func discardPass() {
        guard let state = pass else { return }
        end(state, commit: false, cancelEngine: true)
    }
    private func end(_ state: CompanionPass, commit: Bool, cancelEngine: Bool = false) {
        guard pass === state, !state.ended else { return }
        state.ended = true
        if status.hasSuffix("…") { setStatus("Ready") }
        streamRefresh?.cancel(); streamRefresh = nil
        detail.clearStream(keepAnswer: commit)
        pass = nil
        detail.companionStrip.thinking = false
        if cancelEngine, let engine = engines[state.noteID] { Task { await engine.cancel() } }
        let seconds = state.firstText.map { String(format: "%.2f", $0) } ?? "-", step = state.firstStep.map { String(format: "%.2f", $0) } ?? "-"
        lastTiming = "pass reading=\(state.reading) first-text=\(seconds) first-step=\(step) last=\(String(format: "%.2f", elapsed(state))) staged=\(state.staged.count) committed=\(commit)"
        Task { await updateMeter() }
        guard commit, noteID == state.noteID, requestGeneration == state.generation, snapshot.map({CompanionStore.settings($0).mode != .off}) == true else { state.done?(); return }
        let (tools, pictures) = CompanionStore.additions(state.staged)
        let reader = state.reader, text = state.text, reading = state.reading, enabled = preferences.drawings
        let unpinned = snapshot.map(CompanionStore.unpinned) ?? 0
        let seen = state.imageIDs, before = Set(snapshot?.companionItems.map(\.id) ?? [])
        guard !(tools.isEmpty && pictures.isEmpty && seen.isEmpty) else { setStatus(state.reading ? "Syncr stayed quiet" : status); state.done?(); return }
        previousPassText = text
        if !tools.isEmpty || !pictures.isEmpty { setStatus("Response received") }
        edit(state.noteID, work: {
            try CompanionStore.apply(tools, note: $0, reader: reader.name, expectedText: text, drawings: false, readingAlong: reading, pictures: enabled ? pictures : [])
            try NoteImageStore.markSeen(seen, in: $0)
        }, done: { [weak self] in
            guard let self else { return }
            if let warning = state.warning, let new = self.snapshot?.companionItems.first(where: { !before.contains($0.id) && !$0.tombstone && { if case .marginNote = $0.body { return true }; return false }($0) }) {
                self.pictureWarnings[new.id] = warning; self.showAll()
            }
            state.done?()
        })
    }
    private func sendInk(explicit: Reader?, confirmed: Bool, done: (() -> Void)?) {
        guard let s = snapshot, let id = noteID else { done?(); return }
        let settings = CompanionStore.settings(s)
        let reader = explicit ?? readers.first(where:{$0.id == settings.activeReader}) ?? Reader.starters[0]
        let modelID = preferences.askingModel
        if modelID.isEmpty && debugProvider == nil { setStatus("Choose a model in Companion Settings"); showSettings(); done?(); return }
        let provider = makeProvider(), session = inkSession, folder = model.folder, generation = requestGeneration
        var input = RequestInput(tell: settings.tell, reader: reader, markdown: "", changed: nil, selection: nil, threads: [], instruction: "Respond to the handwriting with one margin note.", web: settings.web, pagePNG: nil)
        inkBusy = true; detail.companionStrip.thinking = true
        let model = debugProvider != nil ? "recorded" : modelID, clock = ProcessInfo.processInfo.systemUptime
        Task { [weak self] in
            guard let self else { return }
            defer { self.inkBusy = false; self.detail.companionStrip.thinking = false }
            do {
                let region = CompanionPage.region(snapshot:s,selection:nil)
                input.pagePNG = await CompanionPage.image(snapshot:s,selection:nil,strokes:self.model.drawing?.strokes ?? [],region:region)
                if input.pagePNG != nil { input.pageRegion = region }
                let captured = input
                let response = try await Task.detached(priority:.utility) {
                    try await session.send(provider:provider,request:try CompanionRequest(model:model,input:captured),at:clock,readingAlong:false,confirmedBudget:confirmed)
                }.value
                guard let response, self.model.folder == folder, self.noteID == id, self.requestGeneration == generation else { done?(); return }
                self.edit(id, work: { try CompanionStore.apply(response.tools, note: $0, reader: reader.name, expectedText: "", drawings: false, pageRegion: captured.pageRegion) }, done: done)
            } catch { self.setStatus(error.localizedDescription); done?() }
        }
    }

    private func updateMeter() async {
        guard let id = noteID, let engine = engines[id] else { return }
        let meter = await engine.meter()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        if let data = try? encoder.encode(meter), data != savedBudget {
            savedBudget = data
            model.writeState("companion-budget.json", data)
        }
        shownMeter = meter
        settingsView?.updateUsage()
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
    private func quote(of anchorID: String, in s: NoteSnapshot, text: String) -> String {
        guard let a = s.companionItems.first(where:{$0.id == anchorID}), case .anchor(.text(_,let start,let end,_)) = a.body, Int(end) <= text.utf8.count else { return "" }
        return String(decoding:Array(text.utf8)[Int(start)..<Int(end)],as:UTF8.self)
    }
    private func conversations(_ s:NoteSnapshot,text:String) -> [Conversation] {
        s.companionItems.compactMap { v in
            guard !v.tombstone, case .marginNote(let anchor,let kind,let body,let reader,let state,_) = v.body else { return nil }
            let quote = quote(of: anchor, in: s, text: text)
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

    // MARK: Voice

    private func loadVoice() {
        voiceNotes = model.readState("companion-voice-notes.json").flatMap { try? JSONDecoder().decode(Set<String>.self, from: $0) } ?? []
        voicePreferences = model.readState("companion-voice.json").flatMap { try? JSONDecoder().decode(VoicePreferences.self, from: $0) } ?? VoicePreferences()
        voice.voiceID = voicePreferences.identifier; voice.rate = voicePreferences.rate
        spoken = []
    }
    func saveVoice(_ value: VoicePreferences) {
        voicePreferences = value; voice.voiceID = value.identifier; voice.rate = value.rate
        if let data = try? JSONEncoder().encode(value) { model.writeState("companion-voice.json", data) }
    }
    func readAloud(_ body: CompanionBody) {
        switch body {
        case .marginNote(_, _, let text, _, let state, _): if state != .dismissed { voice.speak(text) }
        case .threadMessage(_, _, .companion, let text, _): voice.speak(text)
        default: break
        }
    }

    // MARK: Margin actions

    func setState(_ id:String,_ state:MarginState) {
        guard let note = noteID else { return }
        if let s = snapshot, let item = s.companionItems.first(where:{$0.id == id}), case .marginNote(let anchor,_,_,_,_,_) = item.body, state != .open {
            let passage = quote(of: anchor, in: s, text: detail.documentEditor.textView.string), action: FeedbackAction = state == .pinned ? .pin : .dismiss, log = decisions
            Task { try? await log.recordFeedback(action: action, passage: passage, itemID: id) }
        }
        edit(note) { try CompanionStore.setState(id,state,note:$0) }
    }
    func addToNote(_ text:String) {
        if let s = snapshot, let item = s.companionItems.first(where:{ if case .marginNote(_,_,let t,_,_,_) = $0.body { return t == text && !$0.tombstone }; return false }), case .marginNote(let anchor,_,_,_,_,_) = item.body {
            let passage = quote(of: anchor, in: s, text: detail.documentEditor.textView.string), log = decisions, id = item.id
            Task { try? await log.recordFeedback(action: .addToNote, passage: passage, itemID: id) }
        }
        detail.documentEditor.textView.insertText(text,replacementRange:detail.documentEditor.textView.selectedRange()); detail.documentEditor.flush()
    }
    /// Pin on a picture adds it to the pattern book future requests draw their closest examples from.
    func pinPicture(_ id: String) {
        guard let s = snapshot, let item = s.companionItems.first(where:{$0.id == id}), case .picture(_, let title, let svg) = item.body else { return }
        let words = title.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init)
        do { try PatternBook.addPinned(svg: svg, description: String(title.prefix(160)), tags: Array(words.prefix(10)), at: stateFolder, inspector: CorePictureInspector()); setStatus("Picture added to the pattern book") }
        catch { setStatus("Not added to the pattern book: \(error.localizedDescription)") }
        let log = decisions
        Task { try? await log.recordFeedback(action: .pin, passage: title, itemID: id) }
    }
    func openThread(_ id: String, provisional: String? = nil) {
        guard let s = snapshot, let item = s.companionItems.first(where: { $0.id == id }), case .marginNote(_,_,let text,let reader,_,_) = item.body else { return }
        let view = threads[id] ?? CompanionThread(send: { [weak self] in self?.reply(id,text: $0) }, read: { [weak self] in self?.voice.speak($0) })
        threads[id] = view
        var messages = s.companionItems.compactMap { item -> (UInt32,String,String,Bool)? in
            guard !item.tombstone, case .threadMessage(let parent,let order,let who,let text,_) = item.body, parent == id else { return nil }
            return (order,who == .you ? "You" : "Syncr",text,who == .companion)
        }.sorted { $0.0 < $1.0 }.map { ($0.1,$0.2,$0.3) }
        if let provisional { messages.append(("Syncr",provisional,false)) }
        view.show([(reader,text,true)] + messages)
    }
    func reply(_ id:String,text:String) {
        guard let note = noteID, snapshot.map({CompanionStore.settings($0).mode != .off}) == true else { return }
        edit(note,work:{try CompanionStore.userReply(id,text:text,note:$0)}) { [weak self] in self?.startPass(.reply(id)) }
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

    // MARK: Images

    /// The decoded stored image for drawing, or nil while its bytes are still being read (a redraw follows).
    func storedImage(_ id: String) -> NSImage? {
        guard let note = noteID else { return nil }
        return images.image(id, note: note, model: model) { [weak self] in self?.showAll() }
    }

    /// Paste or drop: convert and downscale off the main thread, then store through the core under the caret's line.
    func storeImage(_ data: Data, at utf16Offset: Int) {
        guard let id = noteID, model.isWritable(id) else { setStatus("This note cannot take images"); return }
        let text = detail.documentEditor.textView.string
        let ns = text as NSString, offset = max(0, min(utf16Offset, ns.length))
        let utf8 = text.utf8.distance(from: text.utf8.startIndex, to: String.Index(utf16Offset: offset, in: text))
        setStatus("Adding image…")
        detail.documentEditor.flush()
        notebook.flushAll { [weak self] ok in
            guard let self, ok else { return }
            Task { [weak self] in
                guard let self else { return }
                do {
                    let prepared = try await Task.detached(priority: .userInitiated) { try NoteImages.prepare(data) }.value
                    self.edit(id, work: { try NoteImageStore.place(prepared, atUTF8: utf8, in: $0) }, done: { [weak self] in self?.setStatus("Image added") })
                } catch { self.setStatus(error.localizedDescription) }
            }
        }
    }

    // MARK: Inspection

    func lastRequest() {
        Task {
            var wire = "No request has been sent."
            if let id = noteID, let engine = engines[id], let last = await engine.lastRequest { wire = last }
            else if let record = await inkSession.lastRequest { wire = record.wire }
            let scroll = NSTextView.scrollableTextView()
            let text = scroll.documentView as! NSTextView
            text.isEditable = false
            text.string = wire
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
    func showSettings() { onShowSettings?() }
    func makeSettingsView() -> CompanionSettingsView {
        let view = CompanionSettingsView(source: self)
        settingsView = view
        view.update(models: catalog, state: settingsProviderState)
        return view
    }
    func refreshModels() {
        catalogGeneration += 1
        let generation = catalogGeneration, library = folder
        let provider = makeProvider()
        settingsProviderState = "Checking connection…"
        settingsView?.update(models: catalog, state: settingsProviderState)
        Task {
            let state = await provider.signInState()
            do {
                let offered = try await provider.models()
                guard catalogGeneration == generation, folder == library, model.folder == library else { return }
                catalog = offered
                if preferences.readingModel.isEmpty || preferences.askingModel.isEmpty, let defaults = try? ModelSettings.defaults(from: catalog) {
                    if preferences.readingModel.isEmpty { preferences.readingModel = defaults.gate }
                    if preferences.askingModel.isEmpty { preferences.askingModel = defaults.answer }
                    persistPreferences()
                }
                settingsProviderState = state
                settingsView?.update(models: catalog, state: state)
            } catch {
                guard catalogGeneration == generation, folder == library, model.folder == library else { return }
                settingsProviderState = state + " · " + error.localizedDescription
                catalog = []
                settingsView?.update(models: [], state: settingsProviderState)
            }
        }
    }
    func savePreferences(_ value:CompanionPreferences) {
        let changedProvider = value.provider != preferences.provider
        let changedModels = value.readingModel != preferences.readingModel || value.askingModel != preferences.askingModel
        let changedStyle = value.handDrawn != preferences.handDrawn || value.drawings != preferences.drawings
        preferences = value
        PictureRenderer.handDrawn = value.handDrawn
        if changedProvider { preferences.readingModel = ""; preferences.askingModel = ""; catalog = []; discardPass(); closeEngines() }
        else if changedModels { let models = modelSettings; discardPass(); for engine in engines.values { Task { await engine.setModels(models) } } }
        requestGeneration += 1
        let limits = (preferences.tokenLimit, preferences.requestLimit)
        Task {
            for engine in engines.values { await engine.setLimits(tokens: limits.0, requests: limits.1) }
            await inkSession.setLimits(tokens: limits.0, requests: limits.1)
            await updateMeter()
        }
        persistPreferences()
        settingsView?.reloadPreferences()
        if changedProvider { refreshModels() }
        if changedStyle { renderDrawings = !preferences.drawings }
        updateUI()
    }
    private func persistPreferences() { if let data = try? JSONEncoder().encode(preferences) { model.writeState("companion.json",data) } }
    func saveReaders(_ value:[Reader]) throws { try Reader.save(value,at:model.folder.appendingPathComponent(".mac/readers.json")); readers = value; updateUI() }

    // MARK: Debug control (debug flag only)

    func debugMode(_ mode:Int) { detail.companionStrip.mode.selectItem(at:mode); stripChanged() }
    /// Replays recorded provider text, one pass per `-----` line, delivered in small chunks so streaming is visible.
    private var debugScriptSource: (script: String, chunk: Int, delayMs: Int)?
    private func scriptProvider(_ script: String, chunk: Int, delayMs: Int) -> RecordedStreamProvider {
        let passes = script.components(separatedBy: "\n-----\n").map { text -> RecordedStreamPass in
            let characters = Array(text.hasSuffix("\n") ? text : text + "\n")
            var frames: [RecordedStreamFrame] = []
            for start in stride(from: 0, to: characters.count, by: max(1, chunk)) {
                frames.append(RecordedStreamFrame(.text(String(characters[start..<min(characters.count, start + chunk)])), afterNanoseconds: UInt64(delayMs) * 1_000_000))
            }
            frames.append(RecordedStreamFrame(.usage(Usage(input: 10, output: 5))))
            return RecordedStreamPass(frames: frames)
        }
        return RecordedStreamProvider(passes: Array(repeating: passes, count: 16).flatMap { $0 })
    }
    /// A recorded script may name the ask thread as `{{thread}}`; the id exists only once the ask has been stored.
    private func bindScript(thread: String) {
        guard let source = debugScriptSource, source.script.contains("{{thread}}"), let id = noteID else { return }
        debugProvider = scriptProvider(source.script.replacingOccurrences(of: "{{thread}}", with: thread), chunk: source.chunk, delayMs: source.delayMs)
        closeEngines(); _ = engine(for: id)
    }
    func debugScript(_ script: String, chunk: Int = 24, delayMs: Int = 12, askNow: Bool = true, all: Bool = false, draw: Bool = false) {
        debugScriptSource = (script, chunk, delayMs)
        debugProvider = scriptProvider(script, chunk: chunk, delayMs: delayMs)
        closeEngines()
        if let id = noteID { _ = engine(for: id) }
        updateUI()
        if askNow { ask(draw: draw, all: all) }
    }
    func debugStrip(web:Bool? = nil,preset:Int? = nil,reader:Int? = nil) {
        if let web { detail.companionStrip.web.state = web ? .on : .off }
        if let preset, Reader.tellPresets.indices.contains(preset) { detail.companionStrip.tell.stringValue = Reader.tellPresets[preset] }
        if let reader, readers.indices.contains(reader) { detail.companionStrip.reader.selectItem(at:reader) }
        stripChanged()
    }
    func debugRecord(at url: URL) async {
        guard let id = noteID, let engine = engines[id], let wire = await engine.lastRequest else { return }
        try? Data(wire.utf8).write(to: url)
    }
    func debugCancel() { discardPass() }
    /// A recorded canvas margin note at this region (canvas units), as a handwriting pass on a canvas would add it.
    func debugCanvasCard(x: Double, y: Double, width: Double, height: Double, kind: String, text: String) {
        guard let id = noteID else { return }
        let region = CanvasRegion(x: x, y: y, width: width, height: height)
        edit(id, work: { try CompanionStore.apply([.canvasNote(region: CanvasRegion(x: 0, y: 0, width: 1, height: 1), kind: kind, text: text, sources: [])], note: $0, reader: "Editor", expectedText: "", drawings: false, pageRegion: region) })
    }
    /// Acceptance 3: a scripted hour of typing, with the host's own pause rule and the engine's, at simulated times.
    func debugHour(script: String) async -> String {
        guard snapshot != nil else { return "no note" }
        debugScript(script, chunk: 400, delayMs: 0, askNow: false)
        try? await Task.sleep(nanoseconds: 300_000_000)
        guard let id = noteID else { return "no note" }
        let engine = self.engine(for: id)
        let text = detail.documentEditor.textView.string
        let passage = ByteSpan(start: 0, end: min(text.utf8.count, 40))
        let provider = debugProvider as? RecordedStreamProvider
        await engine.configure(mode: .thinksWithYou, unpinnedNotes: 0)
        var lastTyped = -100.0, lastPassAt = -100.0, changed = false, gates = 0, answers = 0, asked = 0
        // 60 minutes in seconds: 40 s of typing, 4 s pause, repeated; one pause in four is long enough to count.
        for second in stride(from: 0.0, to: 3600.0, by: 1) {
            let phase = second.truncatingRemainder(dividingBy: 44)
            if phase < 40 { lastTyped = second; changed = true; await engine.activity(at: second); continue }
            await engine.idleActivity(at: lastTyped)
            guard Self.pauseDue(now: second, lastActivity: lastTyped, lastPause: lastPassAt, changed: changed, mode: .thinksWithYou) else { continue }
            lastPassAt = second; changed = false; asked += 1
            let input = RequestInput(markdown: text, changed: passage, instruction: "Read along quietly.")
            guard let stream = try? await engine.events(for: .pause(input), at: second) else { continue }
            for await out in stream { if case .gate(let d, _) = out { gates += 1; if d.speaks { answers += 1 } } }
        }
        let attempts = provider?.attempts ?? -1
        return "hour typing-blocks=\(Int(3600/44)) pause-attempts=\(asked) gate-decisions=\(gates) speak=\(answers) provider-calls=\(attempts) (gate+answer)"
    }
    func debugReport() async -> String {
        let attempts = (debugProvider as? RecordedStreamProvider)?.attempts ?? -1
        let items = snapshot?.companionItems.filter { !$0.tombstone } ?? []
        let margins = items.compactMap { v -> String? in
            guard case .marginNote(_, _, _, let reader, let state, _) = v.body else { return nil }
            return v.id + ":" + reader + ":" + String(describing: state)
        }.joined(separator: ",")
        let anchors = items.compactMap { v -> String? in
            guard case .anchor(.text(_, let start, let end, let detached)) = v.body else { return nil }
            return "\(start)-\(end):\(detached)"
        }.joined(separator: ",")
        let pictures = items.filter { if case .picture = $0.body { return true }; return false }.map(\.id).joined(separator: ",")
        let imageMarks = snapshot?.images.filter { !$0.tombstone }.map { "\($0.id):\($0.pixelWidth)x\($0.pixelHeight):seen=\($0.seenByCompanion)" }.joined(separator: ",") ?? ""
        return "mode=\(snapshot.map { String(describing:CompanionStore.settings($0).mode) } ?? "none") items=\(items.count) attempts=\(attempts) engines=\(engines.count) pictures=[\(pictures)] images=[\(imageMarks)] drawings=\(preferences.drawings) hand=\(preferences.handDrawn) margins=\(margins) anchors=\(anchors) timing=(\(lastTiming)) status=\(status)"
    }
}
