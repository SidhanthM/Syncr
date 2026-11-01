import AppKit
import MetalKit
import SyncrCore

/// The right-hand side: tab bar, the companion's two rows, the Metal canvas for the open note, and the
/// right pane (outline, backlinks, threads). Pan and zoom are gestures; Fit and zoom are commands.
@MainActor final class NoteDetailView: NSView, MTKViewDelegate, CAMetalDisplayLinkDelegate {
    let tabBar = TabBarView()
    private let model: LibraryModel
    private let canvas: CanvasView
    private let renderer: MeshRenderer?
    let device: MTLDevice?
    private var viewport = Viewport() { didSet { live?.viewChanged(viewport); positionText() } }
    private var pinchStart: Viewport?
    private var pinchAnchor = SIMD2<Float>(0.5, 0.5)
    let documentEditor = DocumentEditor()
    let notebookSidebar = NotebookSidebar()
    let companionStrip = CompanionStrip()
    var companionThreads: CompanionThreads { notebookSidebar.threads }
    private let companionOverlay = CompanionOverlay()
    private var companionCards: [(NSView, CGFloat, CGFloat, CGFloat, CGFloat)] = []
    var onCompanionActivity: ((Bool) -> Void)?
    private var notebookSidebarWidth: NSLayoutConstraint!
    var onRightPaneVisibility: ((Bool) -> Void)?
    func toggleNotebookSidebar() { setRightPane(visible: notebookSidebar.isHidden) }
    func setRightPane(visible: Bool) {
        // Move keyboard focus out before hiding a thread's reply field.
        if !visible, let responder = window?.firstResponder as? NSView, responder.isDescendant(of: notebookSidebar) {
            window?.makeFirstResponder(documentVisible ? documentEditor.textView : nil)
        }
        notebookSidebar.isHidden = !visible
        notebookSidebarWidth.constant = visible ? 240 : 0
        onRightPaneVisibility?(visible)
        needsLayout = true
        requestRedraw()
    }
    private let textLayer = CanvasTextLayer()
    private var documentVisible = false
    private var freeEditors: [(x: CGFloat, y: CGFloat, width: CGFloat, editor: DocumentEditor)] = []
    var onFreeText: ((CGFloat, CGFloat) -> Void)?
    var onFreeMove: ((Int, CGFloat, CGFloat) -> Void)?
    var onFreeEnd: ((Int) -> Void)?
    private weak var live: LiveInkCoordinator?
    /// Live-ink status for the window's status bar.
    var onLiveStatus: ((String?) -> Void)?
    /// Runs only while live ink is on screen: on the main run loop, one callback per display refresh.
    private var link: CAMetalDisplayLink?
    private var lastVersion: UInt64 = .max
    private var linkDirty = true
    private var linkCallbacks = 0
    private let empty = NSTextField(labelWithString: "")
    private let pairingCard = ThemedView()
    private let pairingCaption = NSTextField(wrappingLabelWithString: "")
    private let pairingDigits = NSTextField(labelWithString: "")
    var onError: ((Error) -> Void)?

    init(model: LibraryModel) {
        self.model = model
        let device = MTLCreateSystemDefaultDevice()
        self.device = device
        renderer = device.flatMap { try? MeshRenderer(device: $0) }
        canvas = CanvasView(frame: .zero, device: device)
        super.init(frame: .zero)
        canvas.colorPixelFormat = .bgra8Unorm
        canvas.framebufferOnly = false
        canvas.isPaused = true
        canvas.enableSetNeedsDisplay = true
        canvas.delegate = self
        canvas.onScroll = { [weak self] x, y in self?.scroll(x: x, y: y) }
        let pinch = NSMagnificationGestureRecognizer(target: self, action: #selector(magnifyNote(_:)))
        addGestureRecognizer(pinch)
        documentEditor.onScroll = canvas.onScroll
        documentEditor.onWidthPreview = { [weak self] in self?.positionText() }
        documentEditor.onReveal = { [weak self] in self?.ensureTextVisible($0) }
        canvas.onDoubleClick = { [weak self] point in
            guard let self else { return }
            let size = self.viewport.visibleSize(self.canvas.bounds.size)
            let x = CGFloat(self.viewport.left) + point.x * CGFloat(size.x) / max(1, self.canvas.bounds.width)
            let y = CGFloat(self.viewport.top) + point.y * CGFloat(size.y) / max(1, self.canvas.bounds.height)
            if !self.documentVisible || x > self.documentEditor.pageWidth { self.onFreeText?(x, y) }
        }
        textLayer.wantsLayer = true; textLayer.layer?.masksToBounds = true
        textLayer.addSubview(documentEditor)
        textLayer.addSubview(companionOverlay)
        documentEditor.onActivity = { [weak self] in self?.onCompanionActivity?(false) }
        documentEditor.isHidden = true

        empty.font = .systemFont(ofSize: 14)
        empty.textColor = Theme.muted
        wantsLayer = true
        empty.alignment = .center
        empty.stringValue = "No note open\n⌘N new note   ⌘O open note   ⌘P commands"
        empty.maximumNumberOfLines = 3
        pairingCard.fill = Theme.panel
        pairingCard.radius = 12
        pairingCard.wantsLayer = true
        pairingCard.shadow = NSShadow()
        pairingCard.layer?.shadowOpacity = 0.25
        pairingCard.layer?.shadowRadius = 12
        pairingCard.layer?.shadowOffset = CGSize(width: 0, height: -3)
        pairingCard.isHidden = true
        pairingCaption.font = .systemFont(ofSize: 13)
        pairingCaption.textColor = Theme.muted
        pairingCaption.alignment = .center
        pairingDigits.font = .monospacedDigitSystemFont(ofSize: 54, weight: .semibold)
        pairingDigits.textColor = Theme.ink
        pairingDigits.alignment = .center
        let cardStack = NSStackView(views: [pairingDigits, pairingCaption])
        cardStack.orientation = .vertical
        cardStack.spacing = 6
        cardStack.edgeInsets = NSEdgeInsets(top: 16, left: 20, bottom: 16, right: 20)
        cardStack.translatesAutoresizingMaskIntoConstraints = false
        pairingCard.addSubview(cardStack)
        NSLayoutConstraint.activate([
            cardStack.leadingAnchor.constraint(equalTo: pairingCard.leadingAnchor), cardStack.trailingAnchor.constraint(equalTo: pairingCard.trailingAnchor),
            cardStack.topAnchor.constraint(equalTo: pairingCard.topAnchor), cardStack.bottomAnchor.constraint(equalTo: pairingCard.bottomAnchor)
        ])
        notebookSidebarWidth = notebookSidebar.widthAnchor.constraint(equalToConstant: 240)
        for view in [tabBar, companionStrip, canvas, textLayer, notebookSidebar, empty, pairingCard] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            tabBar.topAnchor.constraint(equalTo: topAnchor), tabBar.leadingAnchor.constraint(equalTo: leadingAnchor),
            tabBar.trailingAnchor.constraint(equalTo: notebookSidebar.leadingAnchor), tabBar.heightAnchor.constraint(equalToConstant: 32),
            companionStrip.topAnchor.constraint(equalTo: tabBar.bottomAnchor), companionStrip.leadingAnchor.constraint(equalTo: leadingAnchor),
            companionStrip.trailingAnchor.constraint(equalTo: notebookSidebar.leadingAnchor), companionStrip.height,
            canvas.topAnchor.constraint(equalTo: companionStrip.bottomAnchor), canvas.leadingAnchor.constraint(equalTo: leadingAnchor),
            canvas.trailingAnchor.constraint(equalTo: notebookSidebar.leadingAnchor), canvas.bottomAnchor.constraint(equalTo: bottomAnchor),
            notebookSidebar.trailingAnchor.constraint(equalTo: trailingAnchor), notebookSidebar.topAnchor.constraint(equalTo: topAnchor),
            notebookSidebar.bottomAnchor.constraint(equalTo: bottomAnchor), notebookSidebarWidth,
            textLayer.topAnchor.constraint(equalTo: canvas.topAnchor), textLayer.leadingAnchor.constraint(equalTo: canvas.leadingAnchor),
            textLayer.trailingAnchor.constraint(equalTo: canvas.trailingAnchor), textLayer.bottomAnchor.constraint(equalTo: canvas.bottomAnchor),
            empty.centerXAnchor.constraint(equalTo: canvas.centerXAnchor), empty.centerYAnchor.constraint(equalTo: centerYAnchor),
            pairingCard.centerXAnchor.constraint(equalTo: canvas.centerXAnchor), pairingCard.topAnchor.constraint(equalTo: tabBar.bottomAnchor, constant: 56),
            pairingCard.widthAnchor.constraint(equalToConstant: 340)
        ])
        show(title: nil, readOnly: false)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() { layer?.backgroundColor = Theme.page.cgColor }

    /// nil title = nothing open (empty state, canvas hidden).
    func show(title: String?, readOnly: Bool) {
        empty.isHidden = title != nil
        canvas.isHidden = title == nil
        requestRedraw()
    }

    /// The four digits a phone must type to pair, or nil to hide them.
    func showPairing(_ info: (phone: String, code: String)?) {
        pairingCard.isHidden = info == nil
        guard let info else { return }
        pairingDigits.stringValue = info.code
        pairingCaption.stringValue = "“\(info.phone)” wants to pair.\nType this code on the phone within 2 minutes."
    }

    func noteChanged() { requestRedraw() }

    func updateHotkeys(_ store: HotkeyStore) {
        let hints = [(AppCommand.newNote, "new note"), (.quickSwitcher, "open note"), (.commandPalette, "commands")].compactMap { command, title in
            store.binding(for: command).map { "\($0.compact) \(title)" }
        }
        empty.stringValue = "No note open\n" + hints.joined(separator: "   ")
        companionStrip.updateHotkeys(store)
    }

    func showDocument(_ document: (any TextDocument)?, title: String, width: CGFloat = 1600, opening: Bool = false) {
        documentVisible = document != nil
        documentEditor.isHidden = document == nil
        if let document { documentEditor.show(document: document, title: title, width: width) }
        if opening && document != nil { documentView() }
        positionText()
    }
    func showFree(_ blocks: [(CGFloat, CGFloat, CGFloat, any TextDocument)]) {
        let previous = freeEditors
        freeEditors = blocks.enumerated().map { index, block in
            let editor = previous.first { $0.editor.represents(block.3) }?.editor ?? DocumentEditor()
            editor.isFree = true
            editor.show(document: block.3, title: "⋮⋮", width: block.2)
            editor.title.isEditable = false
            editor.onScroll = canvas.onScroll
            editor.onFind = documentEditor.onFind
            editor.onLink = documentEditor.onLink; editor.resolve = documentEditor.resolve; editor.complete = documentEditor.complete
            editor.onMove = { [weak self] dx, dy in self?.onFreeMove?(index, block.0 + dx, block.1 + dy) }
            editor.onEndEditing = { [weak self] in self?.onFreeEnd?(index) }
            if editor.superview == nil { textLayer.addSubview(editor) }
            return (block.0, block.1, block.2, editor)
        }
        for old in previous where !freeEditors.contains(where: { $0.editor === old.editor }) { old.editor.flush(); old.editor.removeFromSuperview() }
        positionText()
        if let last = freeEditors.last, !previous.contains(where: { $0.editor === last.editor }), blocks.last?.3.writable == true, blocks.last?.3.text.isEmpty == true {
            window?.makeFirstResponder(last.editor.textView)
        }
    }
    func refreshTextPreview() { documentEditor.refreshPreview(); freeEditors.forEach { $0.editor.refreshPreview() }; positionText() }
    func debugDragFree(_ index: Int, dx: CGFloat, dy: CGFloat) {
        if freeEditors.indices.contains(index) { freeEditors[index].editor.debugDragFree(dx: dx, dy: dy) }
    }
    func debugEditFree(_ index: Int, text: String) {
        guard freeEditors.indices.contains(index) else { return }
        freeEditors[index].editor.debugSetText(text); freeEditors[index].editor.flush()
    }
    func flushText() {
        documentEditor.flush()
        freeEditors.forEach { block in
            block.editor.flush()
            if block.editor.textView.string.isEmpty { block.editor.onEndEditing?() }
        }
    }
    func documentView() { viewport = Viewport(zoom: 1.5); requestRedraw() }
    private func ensureTextVisible(_ offset: Int) {
        let view = documentEditor.textView
        guard offset < view.string.utf16.count, let manager = view.layoutManager else { return }
        let glyph = manager.glyphIndexForCharacter(at: offset)
        let rect = manager.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil).offsetBy(dx: 100, dy: 260)
        let height = CGFloat(viewport.visibleSize(textLayer.bounds.size).y), top = CGFloat(viewport.top)
        if rect.minY < top + 30 { viewport.top = Float(max(0, rect.minY - 60)); requestRedraw() }
        else if rect.maxY > top + height - 30 { viewport.top = Float(max(0, rect.maxY - height + 60)); requestRedraw() }
    }
    func revealText(_ offset: Int) {
        documentEditor.reveal(offset)
        let page = DocumentPage(title: "", text: documentEditor.textView.string)
        if let line = DocumentLayout.lines(note: page, width: documentEditor.pageWidth - 200).first(where: { NSLocationInRange(offset, $0.range) || $0.range.location == offset }) {
            viewport.top = offset == 0 ? 0 : Float(max(0, line.rect.minY - 120)); requestRedraw()
        }
    }
    override func layout() { super.layout(); positionText() }
    private func positionText() {
        guard textLayer.bounds.width > 0 else { return }
        let visible = viewport.visibleSize(textLayer.bounds.size)
        let scale = textLayer.bounds.width / CGFloat(visible.x)
        func place(_ editor: DocumentEditor, x: CGFloat, y: CGFloat, width: CGFloat) {
            let height = max(4000, editor.textView.frame.maxY + 200)
            editor.frame = CGRect(x: (x - CGFloat(viewport.left)) * scale, y: (y - CGFloat(viewport.top)) * scale, width: width * scale, height: height * scale)
            editor.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        }
        if documentVisible { place(documentEditor, x: 0, y: 0, width: documentEditor.pageWidth) }
        for b in freeEditors { place(b.editor, x: b.x, y: b.y, width: b.width) }
        let overlayHeight = max(8000, documentEditor.textView.frame.maxY + 3000)
        companionOverlay.frame = CGRect(x: -CGFloat(viewport.left) * scale, y: -CGFloat(viewport.top) * scale, width: 4800 * scale, height: overlayHeight * scale)
        companionOverlay.bounds = CGRect(x: 0, y: 0, width: 4800, height: overlayHeight)
        for (view,x,y,w,h) in companionCards {
            view.frame = CGRect(x:x,y:y,width:w,height:h)
            if let card = view as? DrawingCard { card.bounds = CGRect(x:0,y:0,width:max(1100,card.drawing.width),height:card.drawing.height+180) }
        }
    }

    func showCompanion(items: [CompanionItemView], open: Set<String>, colour: NSColor, controller: CompanionController, drawings: Bool = false) {
        companionOverlay.subviews.forEach { $0.removeFromSuperview() }; companionCards = []
        guard !items.isEmpty else {
            documentEditor.drawingHeights = [:]
            documentEditor.textView.passages = []
            companionThreads.show([], open: [])
            notebookSidebar.needsLayout = true
            return
        }
        let text = documentEditor.textView.string
        var heights: [String: CGFloat] = [:]
        if drawings { for item in items where !item.tombstone { if case .drawing(_,_,let width,let height,_) = item.body { heights[item.id] = (height + 180) * min(1,(documentEditor.pageWidth-200)/max(1100,width)) } } }
        documentEditor.drawingHeights = heights
        var page = DocumentPage(title: "", text: text); page.drawingHeights = heights
        let lines = DocumentLayout.lines(note: page, width: documentEditor.pageWidth-200)
        func utf16(_ byte: UInt32) -> Int { let bytes = Array(text.utf8); guard Int(byte) <= bytes.count else { return 0 }; return String(decoding: bytes[..<Int(byte)], as: UTF8.self).utf16.count }
        func y(_ offset: Int) -> CGFloat { lines.first(where: { NSLocationInRange(offset, $0.range) || $0.range.location == offset })?.rect.minY ?? 260 }
        let byID = Dictionary(uniqueKeysWithValues: items.map { ($0.id,$0) })
        func passageTop(_ item:CompanionItemView) -> CGFloat {
            guard case .marginNote(let id,_,_,_,_,_) = item.body, let anchor = byID[id], case .anchor(let target) = anchor.body else { return 260 }
            switch target { case .text(_,let start,_,_): return y(utf16(start)); case .canvas(_,_,let cy,_,_): return cy }
        }
        let margins = items.filter { if case .marginNote = $0.body { return !$0.tombstone }; return false }.sorted { let a = passageTop($0), b = passageTop($1); return a == b ? $0.id < $1.id : a < b }
        // The passage a note answers carries a thin muted underline: the only link between text and margin.
        let whole = NSRange(location: 0, length: documentEditor.textView.string.utf16.count)
        var passages: [NSRange] = []
        defer { documentEditor.textView.passages = passages }
        var bottom: CGFloat = 240
        for item in margins {
            guard case .marginNote(let a,let kind,let body,let reader,let state,let urls) = item.body, state != .dismissed else { continue }
            let anchor = byID[a].flatMap { $0.tombstone ? nil : $0 }
            var top: CGFloat = 260, detached = false, range = NSRange(location:0,length:0)
            if let anchor, case .anchor(let target) = anchor.body {
                switch target {
                case .text(_,let start,let end,let lost): top = y(utf16(start)); detached = lost; range = NSRange(location:utf16(start),length:max(0,utf16(end)-utf16(start)))
                case .canvas(_,_,let cy,_,_): top = cy
                }
            } else { detached = true }
            top = max(top, bottom + 24)
            if range.length > 0, NSMaxRange(range) <= whole.length { passages.append(range) }
            let caption = [detached ? "Detached" : nil, state == .pinned ? "Pinned" : nil, reader, String(describing: kind).capitalized].compactMap { $0 }.joined(separator: " · ")
            let card = MarginCard(text: body, caption: caption, sources: urls, colour: colour,
                                  open: { [weak controller] in controller?.openThread(item.id) },
                                  pin: { [weak controller] in controller?.setState(item.id, state == .pinned ? .open : .pinned) }, pinned: state == .pinned,
                                  dismiss: { [weak controller] in controller?.setState(item.id, .dismissed) },
                                  add: { [weak controller] in controller?.addToNote(body) })
            card.hover = { [weak self] on in
                guard let view = self?.documentEditor.textView, let layout = view.layoutManager else { return }
                layout.removeTemporaryAttribute(.backgroundColor, forCharacterRange: NSRange(location: 0, length: view.string.utf16.count))
                if on && NSMaxRange(range) <= view.string.utf16.count { layout.addTemporaryAttribute(.backgroundColor, value: colour.withAlphaComponent(0.15), forCharacterRange: range) }
            }
            companionOverlay.addSubview(card)
            companionCards.append((card, documentEditor.pageWidth + 40, top, MarginCard.width, card.fittedHeight))
            bottom = top + card.fittedHeight
        }
        if drawings { for item in items where !item.tombstone {
            let embed = "![[syncr-drawing:\(item.id)]]"
            guard let range = text.range(of:embed), let drawing = CompanionStore.drawing(item.body) else { continue }
            let card = DrawingCard(drawing), ratio = min(1,(documentEditor.pageWidth-200)/max(1100,drawing.width))
            card.bounds = CGRect(x:0,y:0,width:max(1100,drawing.width),height:drawing.height+180)
            companionOverlay.addSubview(card); companionCards.append((card,170,y(text[..<range.lowerBound].utf16.count),max(1100,drawing.width)*ratio,(drawing.height+180)*ratio))
        }}
        let hasThreads = items.contains { v in if case .marginNote(_,_,_,_,let state,_) = v.body { return !v.tombstone && state != .dismissed && (state == .pinned || open.contains(v.id)) }; return false }
        companionThreads.show(items, open: open)
        notebookSidebar.needsLayout = true
        // Opening a thread shows the pane it lives in.
        if hasThreads && notebookSidebar.isHidden && !open.isEmpty { setRightPane(visible: true) }
        positionText()
    }

    func debugDrawing(_ action:String) { (companionOverlay.subviews.first { $0 is DrawingCard } as? DrawingCard)?.debug(action) }

    // MARK: Debug control (DebugControl.swift, debug flag only)

    var debugViewport: Viewport {
        get { viewport }
        set { viewport = newValue; requestRedraw() }
    }

    /// Draws the shown note and the live layer into a small offscreen texture and counts the pixels that are
    /// not paper: what "the note renders" means without looking at the screen. Nil without a GPU.
    func debugInkPixels() -> Int? {
        guard let renderer, let device else { return nil }
        let w = 960, h = 600
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: desc) else { return nil }
        let view = Viewport(left: viewport.left, top: viewport.top, zoom: viewport.zoom)
        do { try renderer.render(model.drawing, wet: live?.engine.currentFrame, viewport: view, dark: false, target: texture) }
        catch { return nil }
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        texture.getBytes(&bytes, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
        let paper = Array(bytes[0..<4])
        var n = 0
        for i in stride(from: 0, to: bytes.count, by: 4) where Array(bytes[i..<i + 4]) != paper { n += 1 }
        return n
    }
    func resetViewport() { viewport = Viewport(); requestRedraw() }

    /// With live ink wired, every draw goes through the display link: a layer driven by a
    /// CAMetalDisplayLink must not have `nextDrawable` called by anyone else (MTKView would trap). The
    /// link is unpaused for a redraw and pauses itself again when nothing is live, so an idle view
    /// does not redraw. Without it (no GPU or no live layer) MTKView draws on demand as before.
    private var linkDriven: Bool { live != nil && renderer != nil && canvas.layer is CAMetalLayer }
    private func requestRedraw() {
        guard linkDriven else { canvas.setNeedsDisplay(canvas.bounds); return }
        linkDirty = true
        wake()
    }

    func attach(_ live: LiveInkCoordinator) {
        self.live = live
        live.viewport = { [weak self] in self?.viewport ?? Viewport() }
        live.onWake = { [weak self] in self?.onCompanionActivity?(true); self?.wake() }
        live.onStatus = { [weak self] text in self?.onLiveStatus?(text) }
        live.viewChanged(viewport)
    }

    /// Presentation modes, selected with `SYNCR_MAC_PRESENT_MODE` (see README for the measurements).
    /// `SYNCR_MAC_DRAWABLES` (2 or 3) and `SYNCR_MAC_FRAME_LATENCY` (link only) tune them.
    private let mode = PresentMode.current
    private var inFlight = false
    private var again = false
    private var cold = false
    private var lastDrawnEmpty = true

    private func configure(_ layer: CAMetalLayer) {
        let env = ProcessInfo.processInfo.environment
        layer.maximumDrawableCount = Int(env["SYNCR_MAC_DRAWABLES"] ?? "") .map { min(3, max(2, $0)) } ?? 2
        layer.displaySyncEnabled = mode != .arrival
        layer.presentsWithTransaction = mode == .arrivalTransaction
        if env["SYNCR_MAC_OPAQUE"] == "1" { layer.isOpaque = true }
    }

    /// New wet frame or redraw request. Link mode: unpause the display link, which draws on its next
    /// refresh. Arrival modes: draw now, with at most one frame in flight.
    private func wake() {
        guard let layer = canvas.layer as? CAMetalLayer, renderer != nil else { return }
        if !configured { configure(layer); configured = true }
        if mode == .link { wakeLink(layer) } else { arrive(layer) }
    }
    private var configured = false

    private func wakeLink(_ layer: CAMetalLayer) {
        if link == nil {
            let new = CAMetalDisplayLink(metalLayer: layer)
            new.delegate = self
            new.preferredFrameLatency = Float(ProcessInfo.processInfo.environment["SYNCR_MAC_FRAME_LATENCY"] ?? "") ?? 1
            new.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
            new.add(to: .main, forMode: .common)
            link = new
        }
        if link?.isPaused != false { cold = true }
        link?.isPaused = false
    }

    /// `nextDrawable` may wait for the compositor to release one. Prefetching it on another queue was
    /// tried and measured no better (the frame then waits for the drawable instead), so it is taken here.
    private func arrive(_ layer: CAMetalLayer) {
        guard let live else { return }
        if inFlight { again = true; return }
        let taken = live.engine.take()
        guard taken.frame.version != lastVersion || linkDirty || !taken.times.isEmpty else { return }
        guard let drawable = layer.nextDrawable() else { linkDirty = true; return }
        lastVersion = taken.frame.version; linkDirty = false; inFlight = true
        let wasIdle = lastDrawnEmpty
        lastDrawnEmpty = taken.frame.isEmpty
        present(drawable, wet: taken.frame, times: taken.times, sent: taken.sent, cold: wasIdle && !taken.times.isEmpty) { [weak self] in
            DispatchQueue.main.async { MainActor.assumeIsolated {
                guard let self else { return }
                self.inFlight = false
                if self.again { self.again = false; self.wake() }
            } }
        }
    }

    nonisolated func metalDisplayLink(_ link: CAMetalDisplayLink, needsUpdate update: CAMetalDisplayLink.Update) {
        nonisolated(unsafe) let drawable = update.drawable  // delivered on the main run loop
        MainActor.assumeIsolated { linkFired(drawable) }
    }

    private func linkFired(_ drawable: CAMetalDrawable) {
        guard let live else { link?.isPaused = true; return }
        let taken = live.engine.take()
        if taken.frame.version != lastVersion || linkDirty || !taken.times.isEmpty {
            lastVersion = taken.frame.version; linkDirty = false
            present(drawable, wet: taken.frame, times: taken.times, sent: taken.sent, cold: cold && !taken.times.isEmpty)
            cold = false
        }
        linkCallbacks += 1
        if live.debug && linkCallbacks % 30 == 0 { live.statusChanged() }
        // Nothing live any more (that last draw showed the committed ink): back to on-demand drawing.
        if taken.frame.isEmpty { link?.isPaused = true }
    }

    private func present(_ drawable: CAMetalDrawable, wet: WetFrame?, times: [UInt64], sent: [UInt64] = [], cold: Bool, done: (@Sendable () -> Void)? = nil) {
        guard let renderer else { return }
        let dark = canvas.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let engine = live?.engine
        let failed: @Sendable (String?) -> Void = { [weak self] message in
            done?()
            guard let message else { return }
            DispatchQueue.main.async { [weak self] in
                self?.onError?(NSError(domain: "SyncrMetal", code: 1, userInfo: [NSLocalizedDescriptionKey: message]))
            }
        }
        var stage: (@Sendable (String, UInt64) -> Void)?
        if !times.isEmpty, let engine { stage = engine.stageSink(times, cold: cold, sent: sent) }
        do {
            try renderer.render(model.drawing, wet: wet, viewport: viewport, dark: dark, target: drawable.texture,
                                presenting: drawable, completion: failed, stage: stage,
                                afterScheduled: mode == .arrivalTransaction)
        } catch { done?(); onError?(error) }
    }

    private func move(_ x: Float, _ y: Float) {
        viewport.left += x
        viewport.top += y
        viewport.clamp(pixels: canvas.drawableSize, bottom: max(model.drawing?.lowestInk ?? 0, documentVisible ? Float(documentEditor.textView.frame.maxY) : 0))
        requestRedraw()
    }
    private func scroll(x: Float, y: Float) {
        let offset = viewport.scrollOffset(x: x, y: y, pixels: canvas.drawableSize)
        move(offset.x, offset.y)
    }
    @objc private func magnifyNote(_ gesture: NSMagnificationGestureRecognizer) {
        if gesture.state == .began {
            guard !canvas.isHidden, canvas.bounds.width > 0, canvas.bounds.height > 0 else { return }
            let point = gesture.location(in: canvas)
            guard canvas.bounds.contains(point) else { return }
            pinchStart = viewport
            pinchAnchor = SIMD2(Float(point.x / canvas.bounds.width),
                                Float(1 - point.y / canvas.bounds.height))
        } else if gesture.state == .changed, let start = pinchStart {
            viewport = start.zoomed(to: start.zoom * Float(max(0.01, 1 + gesture.magnification)),
                                    around: pinchAnchor, pixels: canvas.bounds.size)
            move(0, 0)
        } else if gesture.state == .ended || gesture.state == .cancelled || gesture.state == .failed {
            pinchStart = nil
        }
    }
    @objc func zoomIn() { viewport.zoom = min(8, viewport.zoom * 1.25); move(0, 0) }
    @objc func zoomOut() { viewport.zoom = max(1, viewport.zoom / 1.25); move(0, 0) }
    @objc func fit() { resetViewport() }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) { requestRedraw() }
    func draw(in view: MTKView) {
        guard !linkDriven else { requestRedraw(); return }  // the link owns the drawables
        guard let drawable = view.currentDrawable else { return }
        let taken = live?.engine.take()
        present(drawable, wet: taken?.frame, times: taken?.times ?? [], sent: taken?.sent ?? [], cold: false)
    }
}

private final class CanvasTextLayer: NSView {
    override var isFlipped: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? {
        let hit = super.hitTest(point)
        return hit === self ? nil : hit
    }
}

private final class CanvasView: MTKView {
    var onScroll: ((Float, Float) -> Void)?
    var onDoubleClick: ((CGPoint) -> Void)?
    override func mouseDown(with event: NSEvent) {
        if event.clickCount == 2 {
            let p = convert(event.locationInWindow, from: nil)
            onDoubleClick?(CGPoint(x: p.x, y: bounds.height - p.y))
        }
    }
    override func scrollWheel(with event: NSEvent) {
        let (x, y) = AppPreferences.shared.canvasScroll(event)
        onScroll?(x, y)
    }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        setNeedsDisplay(bounds)
    }
}
