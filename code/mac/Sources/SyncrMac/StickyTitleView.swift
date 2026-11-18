import AppKit

/// A quiet band at the top of the page area that names the note once its own title has scrolled away. It dissolves in
/// and out on the house stipple ([Motion.title]); with Reduce motion it just appears. It takes no clicks.
@MainActor final class StickyTitleView: NSView {
    private let label = NSTextField(labelWithString: "")
    private(set) var shown = false
    static let height: CGFloat = 30

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        label.font = NSFont.systemFont(ofSize: 12.5, weight: .semibold)
        label.textColor = Theme.muted
        label.lineBreakMode = .byTruncatingTail
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: centerXAnchor), label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24)
        ])
        isHidden = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() { layer?.backgroundColor = Theme.page.withAlphaComponent(0.94).cgColor }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
    func set(title: String) { label.stringValue = title.isEmpty ? "Untitled" : title }

    func setShown(_ on: Bool) {
        guard on != shown else { return }
        shown = on
        if on {
            isHidden = false
            Dither.reveal(self, duration: Motion.title)
        } else {
            Dither.dissolveOut(self, duration: Motion.title) { [weak self] in
                guard let self, !self.shown else { return }
                self.isHidden = true; self.layer?.mask = nil
            }
        }
    }
}
