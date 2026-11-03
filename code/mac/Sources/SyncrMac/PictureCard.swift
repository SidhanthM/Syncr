import AppKit

@MainActor final class PictureCanvas: NSView {
    var picture = MacPicture.fixture { didSet { needsDisplay = true } }
    var step = 1 { didSet { needsDisplay = true } }
    var progress = 1.0 { didSet { needsDisplay = true } }
    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) { PictureRenderer.draw(picture, in: bounds, through: step, progress: progress) }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); needsDisplay = true }
}

@MainActor final class PictureCard: NSView {
    let canvas = PictureCanvas()
    private let caption = NSTextField(wrappingLabelWithString: "")
    private let counter = NSTextField(labelWithString: "")
    private var timer: Timer?
    private var larger: NSWindow?
    private(set) var picture: MacPicture
    static let width: CGFloat = 1100
    var fittedHeight: CGFloat { Self.fittedHeight(picture) }
    static func fittedHeight(_ picture: MacPicture) -> CGFloat { width * PictureRenderer.bounds(picture).height / max(1, PictureRenderer.bounds(picture).width) + 200 }
    override var isFlipped: Bool { true }

    init(_ picture: MacPicture, animate: Bool = true, pin: (() -> Void)? = nil) {
        self.picture = picture
        super.init(frame: .zero)
        canvas.picture = picture; canvas.step = picture.steps.count
        caption.font = Theme.serif(38); caption.textColor = Theme.soft
        counter.font = .monospacedDigitSystemFont(ofSize: 30, weight: .regular); counter.textColor = Theme.muted
        var buttons: [NSView] = [
            ActionButton(link: "‹", size: 36) { [weak self] in self?.advance(-1) }, counter,
            ActionButton(link: "›", size: 36) { [weak self] in self?.advance(1) },
            ActionButton(link: "↻", size: 34) { [weak self] in self?.replay() },
            ActionButton(link: "See it larger", size: 32) { [weak self] in self?.enlarge() }
        ]
        if let pin { buttons.append(ActionButton(link: "Pin", size: 32, action: pin)) }
        let controls = NSStackView(views: buttons)
        controls.spacing = 32
        let stack = NSStackView(views: [canvas, caption, controls])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor), stack.topAnchor.constraint(equalTo: topAnchor),
            canvas.widthAnchor.constraint(equalTo: widthAnchor), canvas.heightAnchor.constraint(equalToConstant: fittedHeight-200),
            caption.widthAnchor.constraint(equalTo: widthAnchor), controls.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor)
        ])
        refresh()
        if animate { replay() }
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func viewWillMove(toSuperview newSuperview: NSView?) { if newSuperview == nil { timer?.invalidate() }; super.viewWillMove(toSuperview: newSuperview) }
    private func refresh() {
        let count = picture.steps.count, step = min(count, max(0, canvas.step))
        counter.stringValue = "\(step)/\(count)"
        caption.stringValue = step > 0 ? picture.steps[step-1].caption : ""
    }
    private func advance(_ delta: Int) { timer?.invalidate(); canvas.step = min(picture.steps.count, max(0, canvas.step+delta)); canvas.progress = 1; refresh() }
    func replay() {
        timer?.invalidate()
        guard !picture.steps.isEmpty else { return }
        canvas.step = 1; canvas.progress = AppPreferences.shared.reducedMotion ? 1 : 0; refresh()
        let start = ProcessInfo.processInfo.systemUptime
        timer = Timer.scheduledTimer(withTimeInterval: 1/30, repeats: true) { [weak self] timer in
            let finished = MainActor.assumeIsolated {
                guard let self else { return true }
                let elapsed = ProcessInfo.processInfo.systemUptime-start
                let step = min(self.picture.steps.count, Int(elapsed/1.2)+1)
                self.canvas.step = step
                self.canvas.progress = AppPreferences.shared.reducedMotion ? 1 : min(1, elapsed-Double(step-1)*1.2)
                self.refresh()
                let finished = elapsed >= Double(self.picture.steps.count)*1.2
                if finished { self.canvas.progress = 1 }
                return finished
            }
            if finished { timer.invalidate() }
        }
    }
    /// Streaming: show steps up to `step`, the newest drawing itself along its length unless `animating` is off.
    func present(step: Int, animating: Bool) {
        timer?.invalidate()
        canvas.step = min(picture.steps.count, max(0, step)); refresh()
        guard animating, !AppPreferences.shared.reducedMotion else { canvas.progress = 1; return }
        canvas.progress = 0
        let start = ProcessInfo.processInfo.systemUptime
        timer = Timer.scheduledTimer(withTimeInterval: 1/30, repeats: true) { [weak self] timer in
            let finished = MainActor.assumeIsolated {
                guard let self else { return true }
                let t = min(1, (ProcessInfo.processInfo.systemUptime-start)/1.2)
                self.canvas.progress = t; return t >= 1
            }
            if finished { timer.invalidate() }
        }
    }
    func debug(_ action: String) {
        switch action { case "previous": advance(-1); case "next": advance(1); case "replay": replay(); case "larger": enlarge(); default: break }
    }
    private func enlarge() {
        let view = PictureCard(picture, animate: false), scroll = NSScrollView()
        scroll.hasVerticalScroller = true; scroll.drawsBackground = false
        view.frame = CGRect(x: 0,y: 0,width: Self.width,height: view.fittedHeight); scroll.documentView = view
        let window = NSWindow(contentRect: CGRect(x: 0,y: 0,width: 900,height: min(800, view.fittedHeight)), styleMask: [.titled,.closable,.resizable], backing: .buffered, defer: false)
        window.title = "Picture"; window.backgroundColor = Theme.page; window.contentView = scroll; window.isReleasedWhenClosed = false
        window.center(); window.makeKeyAndOrderFront(nil); larger = window
    }
}

/// An image stored in the note, drawn under its passage at the size the core holds, with the small "seen" mark
/// once the companion has looked at it. The note's own bytes are never altered.
@MainActor final class StoredImageView: NSView {
    private let image: NSImage?
    private let seen: Bool
    override var isFlipped: Bool { true }
    init(image: NSImage?, seen: Bool) { self.image = image; self.seen = seen; super.init(frame: .zero) }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func draw(_ dirtyRect: NSRect) {
        if let image { image.draw(in: bounds, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: [.interpolation: NSImageInterpolation.high]) }
        else { Theme.wash.setFill(); bounds.fill() }
        guard seen else { return }
        let mark = NSAttributedString(string: "● seen", attributes: [.font: NSFont.systemFont(ofSize: 26), .foregroundColor: Theme.muted])
        let size = mark.size()
        let pill = CGRect(x: bounds.maxX - size.width - 30, y: bounds.maxY - size.height - 20, width: size.width + 20, height: size.height + 8)
        Theme.page.withAlphaComponent(0.8).setFill(); NSBezierPath(roundedRect: pill, xRadius: 10, yRadius: 10).fill()
        mark.draw(at: CGPoint(x: pill.minX + 10, y: pill.minY + 4))
    }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); needsDisplay = true }
}
