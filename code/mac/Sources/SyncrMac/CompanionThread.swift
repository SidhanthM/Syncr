import AppKit
import SyncrCore

/// A thread window keeps long discussion out of the passage and leaves note text untouched.
@MainActor final class CompanionThread {
    let window: NSWindow
    let composer = NSTextView()
    private let messages = NSStackView()
    private let send: (String) -> Void
    private let read: (String) -> Void

    init(send: @escaping (String) -> Void, read: @escaping (String) -> Void) {
        self.send = send; self.read = read
        window = NSWindow(contentRect: CGRect(x: 0,y: 0,width: 520,height: 620), styleMask: [.titled,.closable,.resizable], backing: .buffered, defer: false)
        window.title = "Companion thread"; window.isReleasedWhenClosed = false; window.backgroundColor = Theme.page
        let history = NSScrollView(), input = NSScrollView()
        messages.orientation = .vertical; messages.alignment = .leading; messages.spacing = 18
        messages.edgeInsets = NSEdgeInsets(top: 16,left: 16,bottom: 16,right: 16)
        history.documentView = messages; history.hasVerticalScroller = true; history.drawsBackground = false
        composer.isRichText = false; composer.font = Theme.serif(16); composer.textColor = Theme.ink
        composer.drawsBackground = false; composer.textContainerInset = CGSize(width: 8,height: 8)
        composer.isVerticallyResizable = true; composer.autoresizingMask = [.width]
        input.documentView = composer; input.hasVerticalScroller = true; input.drawsBackground = false
        let sendButton = ActionButton("Send") { [weak self] in
            guard let self else { return }; let text = self.composer.string.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return }; self.composer.string = ""; self.send(text)
        }
        let hint = NSTextField(wrappingLabelWithString: CompanionVoice.dictationAvailability)
        hint.font = Theme.smallFont; hint.textColor = Theme.muted
        let stack = NSStackView(views: [history,input,hint,sendButton]); stack.orientation = .vertical; stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 12,left: 12,bottom: 12,right: 12); stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView(); content.addSubview(stack); window.contentView = content
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor), stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor), stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            history.widthAnchor.constraint(equalTo: stack.widthAnchor,constant: -24), history.heightAnchor.constraint(greaterThanOrEqualToConstant: 220),
            input.widthAnchor.constraint(equalTo: history.widthAnchor), input.heightAnchor.constraint(equalToConstant: 100)
        ])
    }
    func show(_ rows: [(String,String,Bool)]) {
        messages.arrangedSubviews.forEach { messages.removeArrangedSubview($0); $0.removeFromSuperview() }
        let width = max(300,window.contentView!.bounds.width-64)
        for (who,text,companion) in rows {
            let label = NSTextField(wrappingLabelWithString: who + "\n" + text)
            label.font = Theme.serif(16); label.textColor = Theme.ink; label.isSelectable = true; label.preferredMaxLayoutWidth = width
            messages.addArrangedSubview(label)
            if companion { messages.addArrangedSubview(ActionButton(link: "Read aloud",size: 12) { [weak self] in self?.read(text) }) }
        }
        messages.frame = CGRect(x: 0,y: 0,width: width+32,height: messages.fittingSize.height)
        if !window.isVisible { window.center() }; window.makeKeyAndOrderFront(nil)
    }
}
