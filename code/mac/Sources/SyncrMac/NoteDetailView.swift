import AppKit
import MetalKit
import SyncrCore

/// The note workspace: tab bar, the companion's two rows, and the full-width Metal canvas.
/// Pan and zoom are gestures; Fit and zoom are commands.
@MainActor final class NoteDetailView: NSView, MTKViewDelegate, CAMetalDisplayLinkDelegate {
    let tabBar = TabBarView()
    private let model: LibraryModel
    private let canvas: CanvasView
    private let renderer: MeshRenderer?
    let device: MTLDevice?
    // Input may advance while a Metal frame is in flight. AppKit must stay at the
    // viewport of the presented frame, not run ahead of the ink on each event.
    private var displayedViewport = Viewport()
    private var viewport = Viewport() {
        didSet {
            live?.viewChanged(viewport)
            if renderer == nil { displayedViewport = viewport; positionText() }
            stickyScrolled()
        }
    }
    // The sticky title (look-and-ink v1 §4.2): shows once the page's own title has scrolled off, steps aside for typing
    // and pad ink, and returns on mouse movement or a quiet moment.
    private let stickyTitle = StickyTitleView()
    private var sticky = StickyTitleState() { didSet { stickyTitle.setShown(sticky.visible) } }
    private var stickyTimer: Timer?
    private var stickyTracking: NSTrackingArea?
    private var pinchStart: Viewport?
    private var pinchAnchor = SIMD2<Float>(0.5, 0.5)
    /// The document view keeps the text column in the middle of the window by showing space left of the canvas (a
    /// negative `left`): ink and text shift together, so nothing moves relative to anything else. The side notes keep
    /// their room on the right, so in a narrow view the column sits left of the middle rather than pushing them off.
    /// The view follows the window, the zoom and the column's borders until the owner pans sideways.
    private var centred = false
    private var slide: Timer?
    let documentEditor = DocumentEditor()
    let companionStrip = CompanionStrip()
    private let companionOverlay = CompanionOverlay()
    private let streamOverlay = CompanionOverlay()
    private var streamPicture: PictureCard?
    /// A stippled stand-in for the picture the companion has begun to draw, of the size its card will have.
    private var streamPlaceholder: DitherLines?
    private var streamAnswer: AnswerCard?
    /// The ask thread whose answer is being written: its stored card waits until the pass ends.
    var streamingAsk: String? { didSet { if streamingAsk != oldValue { onStreamingAskChanged?() } } }
    var onStreamingAskChanged: (() -> Void)?
    private var companionGaps: [Int: CGFloat] = [:]
    private var streamGap: (offset: Int, height: CGFloat)?
    /// What the answer being written last showed, so an unchanged refresh leaves the card and its stipple alone.
    private var streamShown: (end: Int, text: String, step: String?)?
    /// When the answer's card and its first words arrived: the card is rebuilt as the text grows, and a dissolve
    /// under way carries on in the new one.
    private var streamArrived: (card: CFTimeInterval, words: CFTimeInterval?)?
    /// The words of an answer whose pass just ended: its stored card shows them until the stored reply arrives.
    private var finishedAnswer: (thread: String, text: String, at: CFTimeInterval)?
    /// Companion items already on screen for this note; nil until its first load. Only later arrivals dissolve in.
    private var knownItems: Set<String>?
    private var knownImages: Set<String>?
    private var arrivedAt: [String: CFTimeInterval] = [:]
    /// Paragraph gaps as laid out right now, gliding towards `targetGaps` when an answer grows or settles.
    private var shownGaps: [Int: CGFloat] = [:]
    private var targetGaps: [Int: CGFloat] = [:]
    private var openingGap: Int?
    private var glide: Timer?
    private var pictureCache: [String: MacPicture] = [:]
    private var shownPictures = Set<String>()
    private var streamText: NSTextField?
    private var companionCards: [(NSView, CGFloat, CGFloat, CGFloat, CGFloat)] = []
    var onCompanionActivity: ((Bool) -> Void)?
    private let textLayer = CanvasTextLayer()
    /// The pad's pen frame (Local mode), in canvas units; nil hides the outline.
    private var padFrame: CGRect? { didSet { if padFrame != oldValue { positionPadFrame() } } }
    private let padOutline = PadFrameOutline()
    private var padTexture: MTLTexture?
    private var documentVisible = false
    /// Cards of stored companion items by item id, so one that goes (dismissed, undone) can dissolve out in place.
    private var cardIDs: [String: NSView] = [:]
    private var leaving: [NSView] = []
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
    private let emptyArt = EmptyArtView(EmptyArtData.notebook)
    private var emptyPlayed = false
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
        textLayer.addSubview(streamOverlay)
        padOutline.isHidden = true
        textLayer.addSubview(padOutline)
        documentEditor.onActivity = { [weak self] in self?.onCompanionActivity?(false); self?.stickyActivity() }
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
        for view in [tabBar, companionStrip, canvas, textLayer, stickyTitle, emptyArt, empty, pairingCard] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            tabBar.topAnchor.constraint(equalTo: topAnchor), tabBar.leadingAnchor.constraint(equalTo: leadingAnchor),
            tabBar.trailingAnchor.constraint(equalTo: trailingAnchor), tabBar.heightAnchor.constraint(equalToConstant: 32),
            companionStrip.topAnchor.constraint(equalTo: tabBar.bottomAnchor), companionStrip.leadingAnchor.constraint(equalTo: leadingAnchor),
            companionStrip.trailingAnchor.constraint(equalTo: trailingAnchor), companionStrip.height,
            canvas.topAnchor.constraint(equalTo: companionStrip.bottomAnchor), canvas.leadingAnchor.constraint(equalTo: leadingAnchor),
            canvas.trailingAnchor.constraint(equalTo: trailingAnchor), canvas.bottomAnchor.constraint(equalTo: bottomAnchor),
            textLayer.topAnchor.constraint(equalTo: canvas.topAnchor), textLayer.leadingAnchor.constraint(equalTo: canvas.leadingAnchor),
            textLayer.trailingAnchor.constraint(equalTo: canvas.trailingAnchor), textLayer.bottomAnchor.constraint(equalTo: canvas.bottomAnchor),
            stickyTitle.topAnchor.constraint(equalTo: canvas.topAnchor), stickyTitle.leadingAnchor.constraint(equalTo: canvas.leadingAnchor),
            stickyTitle.trailingAnchor.constraint(equalTo: canvas.trailingAnchor), stickyTitle.heightAnchor.constraint(equalToConstant: StickyTitleView.height),
            empty.centerXAnchor.constraint(equalTo: canvas.centerXAnchor), empty.centerYAnchor.constraint(equalTo: centerYAnchor, constant: 40),
            emptyArt.centerXAnchor.constraint(equalTo: canvas.centerXAnchor), emptyArt.bottomAnchor.constraint(equalTo: empty.topAnchor, constant: -14),
            emptyArt.widthAnchor.constraint(equalToConstant: EmptyArtData.size.width), emptyArt.heightAnchor.constraint(equalToConstant: EmptyArtData.size.height),
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
        let wasEmpty = !empty.isHidden
        empty.isHidden = title != nil
        emptyArt.isHidden = title != nil
        if title == nil && !wasEmpty { emptyArt.play(); emptyPlayed = true }
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
        if document == nil {
            knownItems = nil; knownImages = nil; finishedAnswer = nil
            centred = false; slide?.invalidate(); slide = nil
            if viewport.left < 0 { viewport.left = 0 }
        }
        if let document { documentEditor.show(document: document, title: title, width: width) }
        if opening && document != nil { documentView() } else { recentre() }
        positionText()
        stickyScrolled()
    }
    func showFree(_ blocks: [(CGFloat, CGFloat, CGFloat, any TextDocument)]) {
        let previous = freeEditors
        freeEditors = blocks.enumerated().map { index, block in
            let editor = previous.first { $0.editor.represents(block.3) }?.editor ?? DocumentEditor()
            editor.isFree = true
            editor.show(document: block.3, title: "⋮⋮", width: block.2)
            editor.title.isEditable = false
            editor.onScroll = canvas.onScroll
            editor.onFind = documentEditor.onFind; editor.slashCommands = documentEditor.slashCommands; editor.onAsk = documentEditor.onAsk
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
    func refreshTextPreview() {
        documentEditor.refreshPreview(); freeEditors.forEach { $0.editor.refreshPreview() }
        // A border was let go or reset: the column glides back to the middle.
        recentre(animated: true)
        positionText()
    }
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
    func documentView() {
        slide?.invalidate(); slide = nil
        centred = documentVisible
        viewport = Viewport(left: centredLeft(zoom: 1.5), zoom: 1.5); requestRedraw()
    }
    /// Center the document itself, leaving equal usable annotation space on either side.
    private func centredLeft(zoom: Float) -> Float {
        guard documentVisible, canvas.bounds.width > 0, canvas.bounds.height > 0 else { return 0 }
        let visible = CGFloat(Viewport(zoom: zoom).visibleSize(canvas.bounds.size).x)
        return Float(CompanionMarginLayout.centeredLeft(columnLeft: documentEditor.columnLeft,
            columnRight: documentEditor.columnRight, visibleWidth: visible))
    }
    /// Puts a centred view back in the middle after the window, the zoom or a border changed.
    private func recentre(animated: Bool = false) {
        guard centred else { return }
        let target = centredLeft(zoom: viewport.zoom)
        guard abs(target - viewport.left) > 0.5 else { return }
        slide?.invalidate(); slide = nil
        guard animated, window != nil, !AppPreferences.shared.reducedMotion else { viewport.left = target; requestRedraw(); return }
        let from = viewport.left, start = CACurrentMediaTime()
        let timer = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] timer in
            let finished = MainActor.assumeIsolated {
                guard let self, self.centred else { return true }
                let t = min(1, (CACurrentMediaTime() - start) / 0.24), eased = Float(1 - pow(1 - t, 3))
                self.viewport.left = from + (target - from) * eased; self.requestRedraw()
                if t >= 1 { self.slide = nil }
                return t >= 1
            }
            if finished { timer.invalidate() }
        }
        RunLoop.main.add(timer, forMode: .common); slide = timer
    }
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
        if let line = DocumentLayout.lines(note: page, width: documentEditor.textWidth).first(where: { NSLocationInRange(offset, $0.range) || $0.range.location == offset }) {
            viewport.top = offset == 0 ? 0 : Float(max(0, line.rect.minY - 120)); requestRedraw()
        }
    }
    private func stickyScrolled() {
        // The page's own title sits at canvas y 70 and is about one line tall; it is gone once the view's top passes it.
        let gone = documentVisible && CGFloat(viewport.top) > 70 + 90 * AppPreferences.shared.textScale
        stickyTitle.set(title: documentEditor.title.stringValue)
        if sticky.titleOnScreen == gone { sticky.masthead(onScreen: !gone) }
    }
    private func stickyActivity() {
        sticky.activity(at: CACurrentMediaTime())
        scheduleStickyReturn()
    }
    private func scheduleStickyReturn() {
        stickyTimer?.invalidate(); stickyTimer = nil
        guard let wait = sticky.wake(at: CACurrentMediaTime()) else { return }
        let timer = Timer(timeInterval: wait, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.sticky.tick(at: CACurrentMediaTime()); self.scheduleStickyReturn()
            }
        }
        RunLoop.main.add(timer, forMode: .common); stickyTimer = timer
    }
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow(); window?.acceptsMouseMovedEvents = true
        if !emptyPlayed, !empty.isHidden { emptyPlayed = true; emptyArt.play() }
    }
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let area = stickyTracking { removeTrackingArea(area) }
        let area = NSTrackingArea(rect: .zero, options: [.mouseMoved, .activeInKeyWindow, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(area); stickyTracking = area
    }
    override func mouseMoved(with event: NSEvent) {
        super.mouseMoved(with: event)
        guard sticky.hidden else { return }
        sticky.mouseMoved(at: CACurrentMediaTime()); scheduleStickyReturn()
    }
    override func layout() { super.layout(); recentre(); positionText(); live?.viewChanged(viewport) }
    private func positionText() {
        let viewport = displayedViewport
        guard textLayer.bounds.width > 0 else { return }
        // Panning is a direct manipulation, not an animated layout change. Keep
        // existing TextKit/layer contents and move their origins without resizing.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        func placeView(_ view: NSView, frame: CGRect, bounds: CGRect) {
            if view.frame.size != frame.size { view.setFrameSize(frame.size) }
            if view.frame.origin != frame.origin { view.setFrameOrigin(frame.origin) }
            if view.bounds != bounds { view.bounds = bounds }
        }
        let visible = viewport.visibleSize(textLayer.bounds.size)
        let scale = textLayer.bounds.width / CGFloat(visible.x)
        func place(_ editor: DocumentEditor, x: CGFloat, y: CGFloat, width: CGFloat) {
            let height = max(4000, editor.textView.frame.maxY + 200)
            placeView(editor,
                      frame: CGRect(x: (x - CGFloat(viewport.left)) * scale, y: (y - CGFloat(viewport.top)) * scale, width: width * scale, height: height * scale),
                      bounds: CGRect(x: 0, y: 0, width: width, height: height))
        }
        if documentVisible { place(documentEditor, x: 0, y: 0, width: documentEditor.pageWidth) }
        for b in freeEditors { place(b.editor, x: b.x, y: b.y, width: b.width) }
        let overlayHeight = max(8000, documentEditor.textView.frame.maxY + 3000)
        // Include negative canvas coordinates so left-margin cards both draw and receive clicks.
        let overlayLeft = min(0, documentEditor.columnLeft - 140 - MarginCard.width)
        let overlayWidth = max(4800, documentEditor.columnRight + 140 + PictureCard.width) - overlayLeft
        let overlayFrame = CGRect(x: (overlayLeft - CGFloat(viewport.left)) * scale, y: -CGFloat(viewport.top) * scale, width: overlayWidth * scale, height: overlayHeight * scale)
        let overlayBounds = CGRect(x: overlayLeft, y: 0, width: overlayWidth, height: overlayHeight)
        placeView(companionOverlay, frame: overlayFrame, bounds: overlayBounds)
        placeView(streamOverlay, frame: overlayFrame, bounds: overlayBounds)
        streamText?.frame = CGRect(x: documentEditor.columnLeft - 140 - MarginCard.width,y: 260,width: MarginCard.width,height: 360)
        if let card = streamPicture {
            card.frame = CGRect(x: documentEditor.pageWidth+40,y: streamText == nil ? 260 : 660,width: PictureCard.width,height: card.fittedHeight)
        }
        streamPlaceholder?.frame.origin = CGPoint(x: documentEditor.pageWidth+40, y: streamText == nil ? 260 : 660)
        for (view,x,y,w,h) in companionCards {
            var bounds = CGRect(x: 0, y: 0, width: w, height: h)
            if let card = view as? DrawingCard { bounds = CGRect(x:0,y:0,width:max(1100,card.drawing.width),height:card.drawing.height+180) }
            if let card = view as? PictureCard { bounds = CGRect(x:0,y:0,width:PictureCard.width,height:card.fittedHeight) }
            placeView(view, frame: CGRect(x:x,y:y,width:w,height:h), bounds: bounds)
        }
        positionPadFrame()
    }

    // MARK: The phone's pad (rework-v1 §V)

    /// What the view shows, in canvas units.
    var visibleCanvasRect: CGRect {
        let size = viewport.visibleSize(canvas.bounds.size)
        return CGRect(x: CGFloat(viewport.left), y: CGFloat(viewport.top), width: CGFloat(size.x), height: CGFloat(size.y))
    }

    /// The pad's pen frame reached the edge of the view: the view follows it, by canvas units.
    func padScroll(dx: CGFloat, dy: CGFloat) {
        if dx != 0 { centred = false; slide?.invalidate(); slide = nil }
        move(Float(dx), Float(dy))
    }

    private func positionPadFrame() {
        let viewport = displayedViewport
        guard let rect = padFrame, textLayer.bounds.width > 0 else { padOutline.isHidden = true; return }
        let scale = textLayer.bounds.width / CGFloat(viewport.visibleSize(textLayer.bounds.size).x)
        padOutline.frame = CGRect(x: (rect.minX - CGFloat(viewport.left)) * scale, y: (rect.minY - CGFloat(viewport.top)) * scale,
                                  width: rect.width * scale, height: rect.height * scale).insetBy(dx: -1, dy: -1)
        padOutline.isHidden = false
    }

    /// A picture of `region` (canvas units) as the note looks now: the ink from the renderer, then the typed text and
    /// the companion's notes from the views over it. Main thread; a few milliseconds. Nil without a GPU or a note.
    func padPicture(region: CGRect, width: Int) -> CGImage? {
        guard let renderer, let device, model.drawing != nil, region.width > 1, region.height > 1, textLayer.bounds.width > 0 else { return nil }
        let w = min(2048, max(64, width)), h = min(2048, max(32, Int((CGFloat(w) * region.height / region.width).rounded())))
        if padTexture?.width != w || padTexture?.height != h {
            let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
            desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
            padTexture = device.makeTexture(descriptor: desc)
        }
        guard let texture = padTexture,
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
              let pixels = ctx.data else { return nil }
        let page = canvasSize()
        let fit = min(Float(w) / page.width, Float(h) / page.viewHeight)
        let view = Viewport(left: Float(region.minX), top: Float(region.minY), zoom: Float(w) / (fit * Float(region.width)))
        let dark = canvas.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        do { try renderer.render(model.drawing, wet: nil, viewport: view, dark: dark, target: texture) } catch { return nil }
        texture.getBytes(pixels, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
        // The text layer is flipped (origin at the top left) and laid out in points at the window's scale.
        let scale = textLayer.bounds.width / CGFloat(viewport.visibleSize(textLayer.bounds.size).x)
        let k = CGFloat(w) / (region.width * scale)
        let outlineHidden = padOutline.isHidden
        padOutline.isHidden = true
        ctx.saveGState()
        ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: k, y: -k)
        ctx.translateBy(x: -(region.minX - CGFloat(viewport.left)) * scale, y: -(region.minY - CGFloat(viewport.top)) * scale)
        textLayer.layer?.render(in: ctx)
        ctx.restoreGState()
        padOutline.isHidden = outlineHidden
        return ctx.makeImage()
    }

    func showCompanion(items: [CompanionItemView], colour: NSColor, controller: CompanionController, drawings: Bool = false, images: [ImageMarkView] = [], warnings: [String: String] = [:]) {
        let previousCards = companionCards, previousIDs = cardIDs
        companionOverlay.subviews.filter { v in !leaving.contains { $0 === v } }.forEach { $0.removeFromSuperview() }
        companionCards = companionCards.filter { c in leaving.contains { $0 === c.0 } }; cardIDs = [:]
        defer { dissolveGone(previousIDs, previousCards, items: items) }
        let fresh = knownItems.map { Set(items.map(\.id)).subtracting($0) } ?? []
        knownItems = Set(items.map(\.id))
        let freshImages = knownImages.map { Set(images.map(\.id)).subtracting($0) } ?? []
        knownImages = Set(images.map(\.id))
        // Something that arrived within the last dissolve keeps dissolving through a rebuild of the cards.
        var arrivals: [(NSView, CFTimeInterval)] = []
        let now = CACurrentMediaTime()
        func arrive(_ view: NSView, _ id: String, fresh: Bool) {
            if fresh { arrivedAt[id] = now }
            if let t = arrivedAt[id], Dither.freeze != nil || now - t < Motion.reveal { arrivals.append((view, now - t)) }
        }
        guard !items.isEmpty || !images.isEmpty else {
            documentEditor.drawingHeights = [:]; companionGaps = [:]; applyGaps()
            documentEditor.textView.passages = []
            return
        }
        let text = documentEditor.textView.string
        var heights: [String: CGFloat] = [:]
        let inlineRatio = min(1,documentEditor.textWidth/1100)
        pictureCache = pictureCache.filter { id, _ in items.contains { $0.id == id } }
        if drawings { for item in items where !item.tombstone {
            if case .drawing(_,_,let width,let height,_) = item.body { heights[item.id] = (height + 180) * min(1,documentEditor.textWidth/max(1100,width)) }
            if case .picture(_,_,let svg) = item.body {
                if pictureCache[item.id] == nil { pictureCache[item.id] = try? CorePictureInspector.mac(svg: svg) }
                if let picture = pictureCache[item.id] { heights[item.id] = PictureCard.fittedHeight(picture) * inlineRatio }
            }
        } }
        documentEditor.drawingHeights = heights
        func utf16(_ byte: UInt32) -> Int { let bytes = Array(text.utf8); guard Int(byte) <= bytes.count else { return 0 }; return String(decoding: bytes[..<Int(byte)], as: UTF8.self).utf16.count }
        // Stored images take their height as space after the paragraph they sit under.
        let anchorEnd = Dictionary(uniqueKeysWithValues: items.compactMap { item -> (String, UInt32)? in
            if case .anchor(.text(_,_,let end,let lost)) = item.body, !item.tombstone, !lost { return (item.id, end) }; return nil
        })
        var gaps: [Int: CGFloat] = [:], imageSpots: [(ImageMarkView, Int)] = []
        for image in images where !image.tombstone {
            guard case .text(let anchor, _, let height) = image.placement, let end = anchorEnd[anchor] else { continue }
            let at = utf16(end > 0 ? end - 1 : 0)
            gaps[at, default: 0] += CGFloat(height) + 40; imageSpots.append((image, at))
        }
        // Direct answers: each takes its height as space after the paragraph it answers.
        var answerSpots: [(AnswerCard, Int)] = []
        let itemByID = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
        for item in items where !item.tombstone && CompanionStore.isAsk(item) && item.id != streamingAsk {
            guard case .marginNote(let a, _, _, _, let state, _) = item.body, state != .dismissed, let anchor = itemByID[a], !anchor.tombstone,
                  case .anchor(.text(_, _, let end, let lost)) = anchor.body, !lost else { continue }
            let thread = items.compactMap { m -> (UInt32, String, String)? in
                guard !m.tombstone, case .threadMessage(let id, let order, let who, let t, _) = m.body, id == item.id else { return nil }
                return (order, who == .you ? "You" : "Syncr", t)
            }.sorted { $0.0 < $1.0 }
            let replies = thread.dropFirst()
            let first = Array(replies.prefix { $0.1 == "Syncr" }), rest = replies.dropFirst(first.count)
            let at = utf16(end > 0 ? end - 1 : 0), id = item.id
            var answer = first.map(\.2).joined(separator: "\n\n")
            if first.isEmpty, rest.isEmpty, let kept = finishedAnswer, kept.thread == id, CACurrentMediaTime() - kept.at < Self.keptAnswerSeconds { answer = kept.text }
            else if finishedAnswer?.thread == id { finishedAnswer = nil }
            let card = AnswerCard(text: answer.isEmpty && rest.isEmpty ? "No answer yet." : answer, step: nil, width: documentEditor.textWidth,
                                  more: rest.map { ($0.1, $0.2) },
                                  writeBack: { [weak controller] in controller?.openThread(id) },
                                  dismiss: { [weak controller] in controller?.setState(id, .dismissed) },
                                  read: { [weak controller] in controller?.readAloud(.threadMessage(marginId: id, order: 0, who: .companion, text: first.map(\.2).joined(separator: " "), sources: [])) },
                                  open: { AnswerPopup.show(id: id, title: "Syncr", messages: thread.dropFirst().map { ($0.1, $0.2) }, writeBack: { [weak controller] in controller?.openThread(id) }) })
            gaps[at, default: 0] += card.fittedHeight + 40; answerSpots.append((card, at)); cardIDs[id] = card
        }
        companionGaps = gaps; applyGaps()
        var page = DocumentPage(title: "", text: text); page.drawingHeights = heights; page.imageGaps = gaps
        let lines = DocumentLayout.lines(note: page, width: documentEditor.textWidth)
        func y(_ offset: Int) -> CGFloat { lines.first(where: { NSLocationInRange(offset, $0.range) || $0.range.location == offset })?.rect.minY ?? 260 }
        let byID = Dictionary(uniqueKeysWithValues: items.map { ($0.id,$0) })
        // The core orders and places the margin cards in one pass (CompanionMarginLayout); here they are only measured and built.
        let margins = items.filter { if case .marginNote = $0.body { return !$0.tombstone && !CompanionStore.isAsk($0) }; return false }
        // The passage a note answers carries a thin muted underline: the only link between text and margin.
        let whole = NSRange(location: 0, length: documentEditor.textView.string.utf16.count)
        var passages: [NSRange] = []
        defer { documentEditor.textView.passages = passages }
        var marginLayout = CompanionMarginLayout(columnLeft: documentEditor.columnLeft,
            columnRight: documentEditor.columnRight, width: MarginCard.width)
        var marginViews: [String: (view: NSView, height: CGFloat, fresh: Bool)] = [:]
        for item in margins {
            guard case .marginNote(let a,let kind,let body,let reader,let state,let urls) = item.body, state != .dismissed else { continue }
            let anchor = byID[a].flatMap { $0.tombstone ? nil : $0 }
            var marginAnchor = MarginAnchor.none, detached = false, range = NSRange(location:0,length:0)
            if let anchor, case .anchor(let target) = anchor.body {
                switch target {
                case .text(_,let start,let end,let lost): marginAnchor = .text(offset: UInt32(utf16(start))); detached = lost; range = NSRange(location:utf16(start),length:max(0,utf16(end)-utf16(start)))
                case .canvas(_,_,let cy,_,_): marginAnchor = .canvas(y: Float(cy))
                }
            } else { detached = true }
            if range.length > 0, NSMaxRange(range) <= whole.length { passages.append(range) }
            let caption = [detached ? "Detached" : nil, state == .pinned ? "Pinned" : nil, reader, String(describing: kind).capitalized].compactMap { $0 }.joined(separator: " · ")
            let card = MarginCard(text: body, caption: caption, sources: urls, colour: colour,
                                  pin: { [weak controller] in controller?.setState(item.id, state == .pinned ? .open : .pinned) }, pinned: state == .pinned,
                                  dismiss: { [weak controller] in controller?.setState(item.id, .dismissed) },
                                  add: { [weak controller] in controller?.addToNote(body) },
                                  read: { [weak controller] in controller?.readAloud(item.body) },
                                  discuss: { [weak controller] in controller?.openThread(item.id) },
                                  footnote: warnings[item.id])
            card.hover = { [weak self] on in
                guard let view = self?.documentEditor.textView, let layout = view.layoutManager else { return }
                layout.removeTemporaryAttribute(.backgroundColor, forCharacterRange: NSRange(location: 0, length: view.string.utf16.count))
                if on && NSMaxRange(range) <= view.string.utf16.count { layout.addTemporaryAttribute(.backgroundColor, value: colour.withAlphaComponent(0.15), forCharacterRange: range) }
            }
            companionOverlay.addSubview(card); cardIDs[item.id] = card
            marginLayout.addNote(item.id, anchor: marginAnchor, height: card.fittedHeight)
            marginViews[item.id] = (card, card.fittedHeight, fresh.contains(item.id))
        }
        if drawings { for item in items where !item.tombstone {
            let embed = "![[syncr-drawing:\(item.id)]]"
            if let range = text.range(of:embed), let drawing = CompanionStore.drawing(item.body) {
                let card = DrawingCard(drawing), ratio = min(1,documentEditor.textWidth/max(1100,drawing.width))
                card.bounds = CGRect(x:0,y:0,width:max(1100,drawing.width),height:drawing.height+180)
                companionOverlay.addSubview(card); cardIDs[item.id] = card; companionCards.append((card,documentEditor.columnLeft + 70,y(text[..<range.lowerBound].utf16.count),max(1100,drawing.width)*ratio,(drawing.height+180)*ratio))
                arrive(card, item.id, fresh: fresh.contains(item.id))
                continue
            }
            guard case .picture(let anchorID,_,_) = item.body, let picture = pictureCache[item.id] else { continue }
            let fresh = !shownPictures.contains(item.id); shownPictures.insert(item.id)
            let card = PictureCard(picture, animate: fresh, pin: { [weak controller] in controller?.pinPicture(item.id) })
            let height = PictureCard.fittedHeight(picture)
            card.bounds = CGRect(x:0,y:0,width:PictureCard.width,height:height)
            cardIDs[item.id] = card
            if let range = text.range(of:embed) {
                // Inline: under the passage, on the page, where its embed line holds the space.
                companionOverlay.addSubview(card); companionCards.append((card,documentEditor.columnLeft + 70,y(text[..<range.lowerBound].utf16.count),PictureCard.width*inlineRatio,height*inlineRatio))
            } else {
                // Its embed line is gone: the picture waits in the margin beside the passage it explains.
                let at = anchorEnd[anchorID].map { MarginAnchor.text(offset: UInt32(utf16($0 > 0 ? $0 - 1 : 0))) } ?? .none
                marginLayout.addPicture(item.id, anchor: at, height: height * MarginCard.width / PictureCard.width)
                companionOverlay.addSubview(card); marginViews[item.id] = (card, height * MarginCard.width / PictureCard.width, false)
            }
        }}
        let placed = marginLayout.place(lines: lines.map { .init(range: $0.range, top: $0.rect.minY) })
        for p in placed.placements {
            guard let (view, height, isFresh) = marginViews[p.id] else { continue }
            companionCards.append((view, CGFloat(p.x), CGFloat(p.y), MarginCard.width, height))
            if view is MarginCard { arrive(view, p.id, fresh: isFresh) }
        }
        for (card, at) in answerSpots {
            companionOverlay.addSubview(card); companionCards.append((card, documentEditor.columnLeft, paragraphBottom(at, lines: lines, text: text) + 16, documentEditor.textWidth, card.fittedHeight))
        }
        for (image, at) in imageSpots {
            guard case .text(_, let width, let height) = image.placement else { continue }
            let paragraph = (text as NSString).paragraphRange(for: NSRange(location: min(at, max(0, (text as NSString).length - 1)), length: 0))
            let lastLine = lines.filter { NSIntersectionRange($0.range, paragraph).length > 0 || $0.range.location == paragraph.location }.map { $0.rect.maxY }.max() ?? y(at)
            let view = StoredImageView(image: controller.storedImage(image.id), seen: image.seenByCompanion)
            companionOverlay.addSubview(view); companionCards.append((view,documentEditor.columnLeft + 70,lastLine + 16,CGFloat(width),CGFloat(height)))
            arrive(view, image.id, fresh: freshImages.contains(image.id))
        }
        positionText()
        arrivals.forEach { Dither.reveal($0.0, elapsed: $0.1) }
    }
    /// A stored companion item that is no longer shown but still exists (dismissed, or undone): its old card stays where
    /// it was and dissolves out, then goes. A card that merely moved or was rebuilt is not one of these.
    private func dissolveGone(_ before: [String: NSView], _ cards: [(NSView, CGFloat, CGFloat, CGFloat, CGFloat)], items: [CompanionItemView]) {
        guard knownBeforeThisPass else { return }
        let existing = Set(items.map(\.id))
        for (id, view) in before where cardIDs[id] == nil && existing.contains(id) && !leaving.contains(where: { $0 === view }) {
            guard let tuple = cards.first(where: { $0.0 === view }) else { continue }
            companionOverlay.addSubview(view); companionCards.append(tuple); leaving.append(view)
            view.layoutSubtreeIfNeeded()
            positionText()
            Dither.dissolveOut(view) { [weak self, weak view] in
                guard let self, let view else { return }
                view.removeFromSuperview()
                self.leaving.removeAll { $0 === view }
                self.companionCards.removeAll { $0.0 === view }
            }
        }
    }
    private var knownBeforeThisPass: Bool { knownItems != nil }
    /// How long a finished answer's words stand in for the stored reply before the card says there is none.
    private static let keptAnswerSeconds: CFTimeInterval = 6

    private func paragraphBottom(_ at: Int, lines: [DocumentLayoutLine], text: String) -> CGFloat {
        let ns = text as NSString
        guard ns.length > 0 else { return 260 }
        let paragraph = ns.paragraphRange(for: NSRange(location: min(at, ns.length - 1), length: 0))
        return lines.filter { NSIntersectionRange($0.range, paragraph).length > 0 || $0.range.location == paragraph.location }.map { $0.rect.maxY }.max()
            ?? lines.first(where: { NSLocationInRange(at, $0.range) })?.rect.minY ?? 260
    }
    /// Space the document leaves under paragraphs for stored images and answers, with the answer being written.
    /// Offsets are folded to their paragraph, which is where the layout applies the space.
    private func applyGaps() {
        let ns = documentEditor.textView.string as NSString
        var merged: [Int: CGFloat] = [:]
        var all = companionGaps
        if let streamGap { all[streamGap.offset, default: 0] += streamGap.height }
        for (offset, gap) in all {
            let start = ns.length > 0 ? ns.paragraphRange(for: NSRange(location: min(max(0, offset), ns.length - 1), length: 0)).location : 0
            merged[start, default: 0] += gap
        }
        targetGaps = merged
        // A gap whose height changes glides (an answer growing line by line, or settling into its stored card), and so
        // does the one an answer opens as it starts. Any other gap is set at once: typing moves their offsets, and a
        // note being opened should not slide into place.
        let still = AppPreferences.shared.reducedMotion || window == nil || ns.length > 20_000
        var next: [Int: CGFloat] = [:]
        for (start, gap) in merged { next[start] = still ? gap : shownGaps[start] ?? (start == openingGap ? 0 : gap) }
        openingGap = nil
        shownGaps = next
        documentEditor.imageGaps = next
        if next == merged { glide?.invalidate(); glide = nil } else if glide == nil {
            let timer = Timer(timeInterval: 1.0 / 30, repeats: true) { [weak self] timer in
                let finished = MainActor.assumeIsolated {
                    guard let self else { return true }
                    for (start, gap) in self.shownGaps {
                        let target = self.targetGaps[start] ?? gap
                        self.shownGaps[start] = abs(target - gap) < 2 ? target : gap + (target - gap) * 0.45
                    }
                    self.documentEditor.imageGaps = self.shownGaps
                    self.positionText()
                    if self.shownGaps == self.targetGaps { self.glide = nil; return true }
                    return false
                }
                if finished { timer.invalidate() }
            }
            RunLoop.main.add(timer, forMode: .common); glide = timer
        }
    }
    /// The answer being written, under the passage it answers, with the named step. `passageEnd` is a UTF-16 offset in
    /// the text; nil clears it.
    func showStreamAnswer(passageEnd: Int?, text: String, step: String?, thread: String? = nil, keep: Bool = false) {
        guard let end = passageEnd else {
            if keep, let asked = streamingAsk, let shown = streamShown, !shown.text.isEmpty {
                finishedAnswer = (asked, shown.text, CACurrentMediaTime())
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.keptAnswerSeconds + 0.1) { [weak self] in
                    MainActor.assumeIsolated {
                        guard let self, self.finishedAnswer?.thread == asked else { return }
                        self.finishedAnswer = nil; self.onStreamingAskChanged?()
                    }
                }
            }
            // It dissolves out where it stood (cancelled, or replaced by the stored card), then goes.
            if let card = streamAnswer { Dither.dissolveOut(card) { [weak card] in card?.removeFromSuperview() } }
            streamAnswer = nil; streamShown = nil; streamArrived = nil
            // The stored card is laid out as the ask is released, before the gap is applied again: the space the
            // answer held glides to the stored card's height and never closes in between.
            let held = streamGap != nil
            streamGap = nil
            if streamingAsk != nil { streamingAsk = nil }
            if held { applyGaps() }
            return
        }
        if streamingAsk != thread { streamingAsk = thread }
        // Nothing new: the card and its drifting stipple are left alone.
        if let shown = streamShown, streamAnswer != nil, shown.end == end, shown.text == text, shown.step == step { return }
        let before = streamShown
        streamAnswer?.removeFromSuperview(); streamAnswer = nil
        let card = AnswerCard(text: text, step: step, width: documentEditor.textWidth)
        let at = max(0, end - 1), height = card.fittedHeight + 40
        if streamGap == nil { openingGap = paragraphStart(at) }
        if streamGap?.offset != at || abs((streamGap?.height ?? 0) - height) > 1 { streamGap = (at, height); applyGaps() }
        let string = documentEditor.textView.string
        var page = DocumentPage(title: "", text: string); page.drawingHeights = documentEditor.drawingHeights; page.imageGaps = documentEditor.imageGaps
        let lines = DocumentLayout.lines(note: page, width: documentEditor.textWidth)
        card.frame.origin = CGPoint(x: documentEditor.columnLeft, y: paragraphBottom(at, lines: lines, text: string) + 16)
        streamOverlay.addSubview(card); streamAnswer = card
        streamShown = (end, text, step)
        positionText()
        // The card dissolves in when it arrives and its words when the first of them do; a change of step fades the
        // name in, and growing text just grows.
        let now = CACurrentMediaTime()
        if before == nil { streamArrived = (now, text.isEmpty ? nil : now) }
        else if streamArrived?.words == nil, !text.isEmpty { streamArrived?.words = now }
        if let arrived = streamArrived {
            if now - arrived.card < Dither.revealSeconds { Dither.reveal(card, elapsed: now - arrived.card) }
            else if let words = arrived.words, now - words < Dither.revealSeconds { card.revealWords(elapsed: now - words) }
        }
        if before != nil, before?.step != step { card.fadeStep() }
    }
    private func paragraphStart(_ offset: Int) -> Int {
        let ns = documentEditor.textView.string as NSString
        return ns.length > 0 ? ns.paragraphRange(for: NSRange(location: min(max(0, offset), ns.length - 1), length: 0)).location : 0
    }

    func showStreamText(_ text: String?) {
        let arriving = streamText == nil
        streamText?.removeFromSuperview(); streamText = nil
        if let text {
            let label = NSTextField(wrappingLabelWithString: text)
            label.font = Theme.serif(50); label.textColor = Theme.ink; label.preferredMaxLayoutWidth = 1000
            streamOverlay.addSubview(label); streamText = label
        }
        positionText()
        if arriving, let streamText { Dither.reveal(streamText) }
    }
    /// The house style's picture is 320 by 230 units; until its first step says otherwise that is the size shown.
    static let placeholderAspect: CGFloat = 230 / 320
    func showStreamPlaceholder(_ on: Bool) {
        guard on != (streamPlaceholder != nil) else { return }
        streamPlaceholder?.removeFromSuperview(); streamPlaceholder = nil
        if on, streamPicture == nil {
            let height = PictureCard.width * Self.placeholderAspect
            let holder = DitherLines(width: PictureCard.width, lines: 1, lineHeight: height)
            streamOverlay.addSubview(holder); streamPlaceholder = holder
            positionText()
            Dither.reveal(holder)
        }
    }
    func showStreamPicture(_ picture: MacPicture?, step: Int? = nil, animate: Bool = true) {
        streamPlaceholder?.removeFromSuperview(); streamPlaceholder = nil
        streamPicture?.removeFromSuperview(); streamPicture = nil
        if let picture {
            let card = PictureCard(picture, animate: step == nil)
            if let step { card.present(step: step, animating: animate) }
            streamOverlay.addSubview(card); streamPicture = card
        }
        positionText()
    }
    /// `keepAnswer`: the pass ended with an answer to store, so its words stay up until the stored reply arrives.
    func clearStream(keepAnswer: Bool = false) { showStreamText(nil); showStreamPicture(nil); showStreamAnswer(passageEnd: nil, text: "", step: nil, keep: keepAnswer) }
    func debugDrawing(_ action:String) {
        if let streamPicture { streamPicture.debug(action) }
        else if let card = companionOverlay.subviews.first(where: { $0 is PictureCard }) as? PictureCard { card.debug(action) }
        else { (companionOverlay.subviews.first { $0 is DrawingCard } as? DrawingCard)?.debug(action) }
    }

    // MARK: Debug pan trace (debug flag only)

    /// One presented frame while the pan test runs: the viewport the ink was drawn at, the viewport the text layer's
    /// frame says it is at right after the same present call, and how the frame was presented.
    private struct PanFrame { var ink: Viewport; var text: Viewport; var transaction: Bool; var moved: Bool }
    private var panTrace: [PanFrame]?
    private var panLast: Viewport?

    private func tracePan(ink: Viewport, transaction: Bool) {
        guard documentVisible, textLayer.bounds.width > 0 else { return }
        let scale = textLayer.bounds.width / CGFloat(ink.visibleSize(textLayer.bounds.size).x)
        let origin = documentEditor.frame.origin
        let text = Viewport(left: Float(-origin.x / scale), top: Float(-origin.y / scale), zoom: ink.zoom)
        panTrace?.append(PanFrame(ink: ink, text: text, transaction: transaction, moved: panLast.map { $0 != ink } ?? false))
        panLast = ink
    }

    /// Pans the view programmatically for `seconds` (a sweep of the page, 120 steps a second) while the caller
    /// replays ink, and traces every presented frame. `debugPanReport` reads the result.
    func debugPanStart(seconds: Double) {
        panTrace = []; panLast = nil
        let start = CACurrentMediaTime(), base = viewport
        let timer = Timer(timeInterval: 1.0 / 120, repeats: true) { [weak self] timer in
            let finished = MainActor.assumeIsolated {
                guard let self else { return true }
                let t = CACurrentMediaTime() - start
                if t >= seconds { return true }
                var next = base
                next.top = base.top + Float(400 * (1 - cos(t * 6)) / 2)
                next.left = base.left + Float(60 * sin(t * 9))
                self.centred = false
                self.viewport = next; self.requestRedraw()
                return false
            }
            if finished { timer.invalidate() }
        }
        RunLoop.main.add(timer, forMode: .common)
    }
    /// Frames traced, frames where text and ink viewports differ, frames that moved and were not presented through the
    /// transaction (both must be 0), and how many frames each way.
    func debugPanReport() -> String? {
        guard let trace = panTrace else { return nil }
        panTrace = nil
        func differs(_ a: Viewport, _ b: Viewport) -> Bool { abs(a.left - b.left) > 0.05 || abs(a.top - b.top) > 0.05 }
        let split = trace.filter { differs($0.ink, $0.text) }.count
        let moving = trace.filter { $0.moved }
        let unsynced = moving.filter { !$0.transaction }.count
        return "pan-sync [\(mode.rawValue)]: \(trace.count) frames, \(split) with text and ink at different viewports, \(moving.count) moved, \(unsynced) moved without the transaction, \(trace.filter { $0.transaction }.count) transaction, \(trace.filter { !$0.transaction }.count) arrival"
    }

    // MARK: Debug control (DebugControl.swift, debug flag only)

    var debugViewport: Viewport {
        get { viewport }
        set { centred = false; slide?.invalidate(); slide = nil; viewport = newValue; requestRedraw() }
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
    func resetViewport() { centred = false; slide?.invalidate(); slide = nil; viewport = Viewport(); requestRedraw() }

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
        live.onWake = { [weak self] in self?.onCompanionActivity?(true); self?.stickyActivity(); self?.wake() }
        live.onStatus = { [weak self] text in self?.onLiveStatus?(text) }
        live.visibleRect = { [weak self] in self?.visibleCanvasRect ?? .zero }
        live.scrollBy = { [weak self] dx, dy in self?.padScroll(dx: dx, dy: dy) }
        live.showPadFrame = { [weak self] rect in self?.padFrame = rect }
        live.padPicture = { [weak self] region, width in self?.padPicture(region: region, width: width) }
        live.pageBottom = { [weak self] in
            guard let self else { return 0 }
            return CGFloat(max(self.model.drawing?.lowestInk ?? 0, self.documentVisible ? Float(self.documentEditor.textView.frame.maxY) : 0))
        }
        live.viewChanged(viewport)
    }

    /// Presentation modes, selected with `SYNCR_MAC_PRESENT_MODE` (see README for the measurements).
    /// `SYNCR_MAC_DRAWABLES` (2 or 3) and `SYNCR_MAC_FRAME_LATENCY` (link only) tune them.
    private let mode = PresentMode.current
    private var presentPolicy = PresentPolicy()
    /// Same rule as `presentPolicy`, kept for every mode: the dry-ink cache is used only on frames where the view is still.
    private var motionPolicy = PresentPolicy()
    private var inFlight = false
    private var again = false
    private var cold = false
    private var lastDrawnEmpty = true

    private func configure(_ layer: CAMetalLayer) {
        let env = ProcessInfo.processInfo.environment
        layer.maximumDrawableCount = Int(env["SYNCR_MAC_DRAWABLES"] ?? "") .map { min(3, max(2, $0)) } ?? 2
        // Adaptive starts as `arrival`; present() switches both per frame.
        layer.displaySyncEnabled = mode != .arrival && mode != .arrivalAdaptive
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
        if !configured, let layer = canvas.layer as? CAMetalLayer { configure(layer); configured = true }
        let frameViewport = viewport
        var transaction = mode == .arrivalTransaction
        if mode == .arrivalAdaptive, let layer = canvas.layer as? CAMetalLayer {
            transaction = presentPolicy.transaction(viewport: frameViewport, size: canvas.bounds.size,
                                                    animating: slide != nil || canvas.inLiveResize)
            // Same settings as the fixed modes, chosen for this frame only.
            if layer.presentsWithTransaction != transaction { layer.presentsWithTransaction = transaction; layer.displaySyncEnabled = transaction }
        }
        let moving = motionPolicy.transaction(viewport: frameViewport, size: canvas.bounds.size, animating: slide != nil || canvas.inLiveResize)
        // Present the drawable and move the AppKit overlays in one CA commit.
        // In particular, never show new text coordinates over an older ink frame.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
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
            try renderer.render(model.drawing, wet: wet, viewport: frameViewport, dark: dark, target: drawable.texture,
                                presenting: drawable, completion: failed, stage: stage,
                                afterScheduled: transaction, cacheDry: !moving)
            displayedViewport = frameViewport
            positionText()
            if panTrace != nil { tracePan(ink: frameViewport, transaction: transaction) }
        } catch { done?(); onError?(error) }
    }

    private func move(_ x: Float, _ y: Float) {
        var next = viewport
        next.left += x
        next.top += y
        let minLeft = documentVisible
            ? min(centredLeft(zoom: next.zoom), Float(documentEditor.columnLeft - 140 - MarginCard.width - 20))
            : 0
        next.clamp(pixels: canvas.drawableSize, bottom: max(model.drawing?.lowestInk ?? 0, documentVisible ? Float(documentEditor.textView.frame.maxY) : 0), minLeft: minLeft)
        if next != viewport { viewport = next }
        requestRedraw()
    }
    private func scroll(x: Float, y: Float) {
        // A direct gesture owns both axes. Do not suppress diagonal deltas or let
        // an outstanding centring animation pull against a direction reversal.
        guard x != 0 || y != 0 else { return }
        centred = false
        slide?.invalidate(); slide = nil
        // NSEvent deltas are logical points, not backing pixels. Using drawableSize
        // here makes a Retina canvas travel at 1 / backingScaleFactor of the gesture.
        let offset = viewport.scrollOffset(x: x, y: y, viewSize: canvas.bounds.size)
        move(offset.x, offset.y)
    }
    @objc private func magnifyNote(_ gesture: NSMagnificationGestureRecognizer) {
        if gesture.state == .began {
            guard !canvas.isHidden, canvas.bounds.width > 0, canvas.bounds.height > 0 else { return }
            let point = gesture.location(in: canvas)
            guard canvas.bounds.contains(point) else { return }
            centred = false; slide?.invalidate(); slide = nil
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
    @objc func zoomIn() { zoom(to: min(8, viewport.zoom * 1.25)) }
    @objc func zoomOut() { zoom(to: max(1, viewport.zoom / 1.25)) }
    private func zoom(to value: Float) {
        slide?.invalidate(); slide = nil
        var next = viewport
        next.zoom = value
        if centred { next.left = centredLeft(zoom: value) }
        viewport = next
        move(0, 0)
    }
    @objc func fit() { resetViewport() }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) { requestRedraw() }
    func draw(in view: MTKView) {
        guard !linkDriven else { requestRedraw(); return }  // the link owns the drawables
        guard let drawable = view.currentDrawable else { return }
        let taken = live?.engine.take()
        present(drawable, wet: taken?.frame, times: taken?.times ?? [], sent: taken?.sent ?? [], cold: false)
    }
}

/// The pen frame's outline: where the phone's pad writes. Never takes a click.
private final class PadFrameOutline: NSView {
    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.borderWidth = 1.5
        layer?.cornerRadius = 6
        layer?.borderColor = NSColor(calibratedRed: 0.83, green: 0.60, blue: 0.60, alpha: 0.7).cgColor
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
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
