import AppKit

/// The contents of the "Pair a phone" window: the QR code, then the four digits, then "Paired". Drawn from a `PairingModel.Stage`
/// so the checks can render every stage without a link.
@MainActor final class PairingView: ThemedView {
    static let size = NSSize(width: 420, height: 470)
    var onNewCode: (() -> Void)?
    var onDone: (() -> Void)?

    private let title = NSTextField(labelWithString: "Pair a phone")
    private let subtitle = NSTextField(wrappingLabelWithString: "")
    private let card = ThemedView()
    private let qr = NSImageView()
    private let digits = NSTextField(labelWithString: "")
    private let caption = NSTextField(wrappingLabelWithString: "")
    private let detail = NSTextField(wrappingLabelWithString: "")
    private lazy var newCode = ActionButton("Show a new code") { [weak self] in self?.onNewCode?() }
    private lazy var done = ActionButton("Done") { [weak self] in self?.onDone?() }

    init() {
        super.init(frame: NSRect(origin: .zero, size: Self.size))
        fill = Theme.panel
        title.font = Theme.serif(26, weight: .medium); title.textColor = Theme.ink
        subtitle.font = Theme.font; subtitle.textColor = Theme.muted; subtitle.alignment = .center
        card.fill = NSColor.white; card.radius = 14
        qr.imageScaling = .scaleProportionallyUpOrDown
        qr.setAccessibilityLabel("QR code for pairing a phone")
        digits.font = NSFont.monospacedDigitSystemFont(ofSize: 64, weight: .medium); digits.textColor = Theme.ink
        digits.alignment = .center
        caption.font = Theme.font; caption.textColor = Theme.ink; caption.alignment = .center
        detail.font = Theme.smallFont; detail.textColor = Theme.muted; detail.alignment = .center
        newCode.controlSize = .large; done.controlSize = .large
        title.alignment = .center
        let stack = NSStackView(views: [title, subtitle, card, digits, caption, detail, newCode, done])
        stack.orientation = .vertical; stack.alignment = .centerX; stack.spacing = 12
        stack.setCustomSpacing(6, after: title); stack.setCustomSpacing(22, after: subtitle); stack.setCustomSpacing(18, after: card)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        card.addSubview(qr)
        qr.translatesAutoresizingMaskIntoConstraints = false; card.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor), stack.topAnchor.constraint(equalTo: topAnchor, constant: 38),
            stack.widthAnchor.constraint(equalToConstant: Self.size.width - 64),
            subtitle.widthAnchor.constraint(equalTo: stack.widthAnchor), caption.widthAnchor.constraint(equalTo: stack.widthAnchor),
            detail.widthAnchor.constraint(equalTo: stack.widthAnchor), digits.widthAnchor.constraint(equalTo: stack.widthAnchor),
            card.widthAnchor.constraint(equalToConstant: 244), card.heightAnchor.constraint(equalToConstant: 244),
            qr.centerXAnchor.constraint(equalTo: card.centerXAnchor), qr.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            qr.widthAnchor.constraint(equalToConstant: 224), qr.heightAnchor.constraint(equalToConstant: 224),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    /// Shows `stage`. `remaining` is the seconds left on a QR code.
    func show(_ stage: PairingModel.Stage, remaining: Int?) {
        card.isHidden = true; digits.isHidden = true; newCode.isHidden = true; done.isHidden = true
        switch stage {
        case .unavailable(let reason):
            subtitle.stringValue = reason; caption.stringValue = ""; detail.stringValue = ""
            newCode.isHidden = false
        case .code(let link, _):
            subtitle.stringValue = "Scan with your phone's camera"
            card.isHidden = false
            qr.image = QRCode.image(link.url, side: 224)
            caption.stringValue = "This code works once and expires in two minutes."
            detail.stringValue = remaining.map { String(format: "Expires in %d:%02d", $0 / 60, $0 % 60) } ?? ""
        case .expired:
            subtitle.stringValue = "This code has expired."
            caption.stringValue = ""; detail.stringValue = "Show a new one to pair a phone."
            newCode.isHidden = false
        case .digits(let phone, let code):
            subtitle.stringValue = "\(phone) wants to pair"
            digits.isHidden = false
            let centred = NSMutableParagraphStyle(); centred.alignment = .center
            digits.attributedStringValue = NSAttributedString(string: code.map(String.init).joined(separator: " "), attributes: [
                .font: NSFont.monospacedDigitSystemFont(ofSize: 64, weight: .medium), .foregroundColor: Theme.ink, .kern: 4, .paragraphStyle: centred])
            caption.stringValue = "Type these four digits on the phone."
            detail.stringValue = "Five tries, within two minutes."
        case .paired(let phone):
            subtitle.stringValue = "Paired with \(phone)"
            caption.stringValue = "Your notes now copy between this Mac and the phone."
            detail.stringValue = "The phone finds this Mac again whenever you are on the same Wi-Fi."
            done.isHidden = false
        }
    }

    /// The window's picture, for the evidence and the checks.
    func snapshot(dark: Bool) -> NSBitmapImageRep? {
        appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        layoutSubtreeIfNeeded()
        guard let rep = bitmapImageRepForCachingDisplay(in: bounds) else { return nil }
        cacheDisplay(in: bounds, to: rep)
        return rep
    }
}

/// "Pair a phone": the window and its hold on the link. It shows a QR code for this Mac, swaps it for the phone's four digits the moment
/// a phone connects, and says "Paired" when the digits were typed. The link's messages are the ones it always had.
@MainActor final class PairingWindowController: NSObject, NSWindowDelegate {
    private let link: LinkController
    private let view = PairingView()
    private let window: NSWindow
    private var model = PairingModel()
    private var timer: Timer?

    init(link: LinkController) {
        self.link = link
        window = NSWindow(contentRect: NSRect(origin: .zero, size: PairingView.size), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        super.init()
        window.title = "Pair a phone"
        window.titlebarAppearsTransparent = true
        window.isReleasedWhenClosed = false
        window.backgroundColor = Theme.panel
        window.contentView = view
        window.delegate = self
        window.center()
        view.onNewCode = { [weak self] in self?.offerCode() }
        view.onDone = { [weak self] in self?.window.close() }
    }

    func show() {
        link.setEnabledForPairing()
        offerCode()
        if let current = link.pairingCode { model.digits(current); render() }
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    /// A phone's digits appeared on the Mac, or went away.
    func pairingChanged(_ info: (phone: String, code: String)?) {
        guard window.isVisible else { return }
        model.digits(info); render()
    }

    func phonePaired(_ phone: String) {
        guard window.isVisible else { return }
        model.paired(phone: phone); render()
    }

    private func offerCode() {
        let made = link.pairingLink()
        model.offer(made.link, reason: made.reason, now: Date())
        render()
    }

    private func tick() {
        model.tick(now: Date())
        render()
    }

    private func render() { view.show(model.stage, remaining: model.remaining(now: Date())) }

    /// Debug control only: the window as it is now, as a PNG file.
    func writeSnapshot(to path: String) {
        guard let rep = view.snapshot(dark: NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: path))
    }

    func windowWillClose(_ notification: Notification) { timer?.invalidate(); timer = nil }
}
