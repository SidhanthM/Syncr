import AppKit
import SyncrCore
import SyncrCompanion

@MainActor final class CompanionOverlay: NSView {
    override var isFlipped: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? { let hit = super.hitTest(point); return hit === self ? nil : hit }
}

@MainActor final class ActionButton: NSButton {
    var run: (() -> Void)?
    convenience init(_ title: String, action: @escaping () -> Void) { self.init(title: title, target: nil, action: nil); target = self; self.action = #selector(clicked); run = action; bezelStyle = .rounded; controlSize = .small }
    @objc private func clicked() { run?() }
}
@MainActor final class CompanionStrip: NSView {
    let tell = NSTextField(string: "")
    let mode = NSPopUpButton(), reader = NSPopUpButton(), presets = NSPopUpButton(), colour = NSPopUpButton()
    let web = NSButton(checkboxWithTitle: "Web", target: nil, action: nil)
    let meter = NSTextField(labelWithString: "0 tokens")
    let flight = NSProgressIndicator()
    var changed: (() -> Void)?; var ask: (() -> Void)?; var settings: (() -> Void)?
    override init(frame: NSRect) {
        super.init(frame: frame)
        tell.placeholderString = "Tell Syncr — what is this note for?"; tell.font = .systemFont(ofSize: 12)
        mode.addItems(withTitles: ["Off", "When you ask", "Thinks with you"])
        presets.addItems(withTitles: ["Tell presets…"] + Reader.tellPresets)
        colour.addItems(withTitles:["Ink", "Red", "Green", "Blue", "Orange"])
        for popup in [mode,reader,presets,colour] { popup.target = self; popup.action = #selector(change) }
        web.target = self; web.action = #selector(change); tell.target = self; tell.action = #selector(change)
        flight.style = .spinning; flight.controlSize = .small; flight.isDisplayedWhenStopped = false
        meter.font = .systemFont(ofSize: 10); meter.textColor = .secondaryLabelColor
        let ask = ActionButton("Ask Syncr ⌘J") { [weak self] in self?.ask?() }
        let settings = ActionButton("⚙") { [weak self] in self?.settings?() }
        let top = NSStackView(views:[tell,mode,web,reader,settings]); top.spacing = 6
        let bottom = NSStackView(views:[presets,colour,ask,flight,meter]); bottom.spacing = 8
        let stack = NSStackView(views:[top,bottom]); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:leadingAnchor,constant:12),stack.trailingAnchor.constraint(equalTo:trailingAnchor,constant:-12),stack.topAnchor.constraint(equalTo:topAnchor,constant:5),stack.bottomAnchor.constraint(equalTo:bottomAnchor,constant:-5),top.widthAnchor.constraint(equalTo:stack.widthAnchor),tell.widthAnchor.constraint(greaterThanOrEqualToConstant:120)])
    }
    required init?(coder: NSCoder) { fatalError() }
    @objc private func change(_ sender: Any?) {
        if let popup = sender as? NSPopUpButton, popup === presets, presets.indexOfSelectedItem > 0 { tell.stringValue = Reader.tellPresets[presets.indexOfSelectedItem-1]; presets.selectItem(at:0) }
        changed?()
    }
    func show(_ value: CompanionSettings, readers: [Reader], writable: Bool) {
        if tell.currentEditor() == nil { tell.stringValue = value.tell }
        mode.selectItem(at:value.mode == .off ? 0 : value.mode == .whenAsked ? 1 : 2); web.state = value.web ? .on : .off
        colour.selectItem(at:[InkColour.ink,.red,.green,.blue,.orange].firstIndex(of:value.colour) ?? 3)
        let names = readers.map(\.name)
        if reader.itemTitles != names { reader.removeAllItems(); reader.addItems(withTitles:names) }
        if let index = readers.firstIndex(where:{$0.id == value.activeReader}) { reader.selectItem(at:index) }
        for control in [tell,mode,web,reader,presets,colour] as [NSControl] { control.isEnabled = writable }
    }
}
@MainActor final class MarginCard: NSView {
    var hover: ((Bool) -> Void)?
    override var isFlipped: Bool { true }
    init(text: String, caption: String, sources: [CompanionSource], colour: NSColor, open: @escaping () -> Void, pin: @escaping () -> Void, dismiss: @escaping () -> Void, add: @escaping () -> Void) {
        super.init(frame:.zero); wantsLayer = true; layer?.backgroundColor = colour.withAlphaComponent(0.055).cgColor; layer?.cornerRadius = 18
        let title = NSTextField(labelWithString:caption); title.font = .systemFont(ofSize:31,weight:.medium); title.textColor = colour
        let label = NSTextField(wrappingLabelWithString:text); label.font = NSFont(name:"Georgia",size:44) ?? .systemFont(ofSize:44); label.textColor = colour
        let buttons = NSStackView(views:[ActionButton("Thread",action:open),ActionButton("Pin",action:pin),ActionButton("Dismiss",action:dismiss),ActionButton("Add to note",action:add)])
        for button in buttons.views.compactMap({$0 as? NSButton}) { button.font = .systemFont(ofSize:30); button.controlSize = .large }
        let sourceLabel = NSTextField(wrappingLabelWithString:sources.map { "🌐 \($0.title)\n\($0.url)" }.joined(separator:"\n")); sourceLabel.font = .systemFont(ofSize:26); sourceLabel.textColor = .secondaryLabelColor; sourceLabel.isSelectable = true
        let stack = NSStackView(views:[title,label,buttons,sourceLabel]); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 16; stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack); NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:leadingAnchor,constant:22),stack.trailingAnchor.constraint(equalTo:trailingAnchor,constant:-22),stack.topAnchor.constraint(equalTo:topAnchor,constant:18),label.widthAnchor.constraint(equalTo:stack.widthAnchor),sourceLabel.widthAnchor.constraint(equalTo:stack.widthAnchor)])
    }
    required init?(coder: NSCoder) { fatalError() }
    override func updateTrackingAreas() { super.updateTrackingAreas(); trackingAreas.forEach(removeTrackingArea); addTrackingArea(NSTrackingArea(rect:bounds,options:[.mouseEnteredAndExited,.activeInKeyWindow],owner:self)) }
    override func mouseEntered(with event:NSEvent) { hover?(true) }
    override func mouseExited(with event:NSEvent) { hover?(false) }
}
@MainActor final class CompanionThreads: NSView {
    private let scroll = NSScrollView(), stack = NSStackView()
    var onReply: ((String,String) -> Void)?
    var onState: ((String,MarginState) -> Void)?
    override init(frame:NSRect) {
        super.init(frame:frame); scroll.hasVerticalScroller = true; scroll.drawsBackground = false; stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 12; stack.edgeInsets = NSEdgeInsets(top:12,left:12,bottom:12,right:12)
        scroll.documentView = stack; scroll.translatesAutoresizingMaskIntoConstraints = false; stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(scroll)
        NSLayoutConstraint.activate([scroll.leadingAnchor.constraint(equalTo:leadingAnchor),scroll.trailingAnchor.constraint(equalTo:trailingAnchor),scroll.topAnchor.constraint(equalTo:topAnchor),scroll.bottomAnchor.constraint(equalTo:bottomAnchor),stack.leadingAnchor.constraint(equalTo:scroll.contentView.leadingAnchor),stack.trailingAnchor.constraint(equalTo:scroll.contentView.trailingAnchor),stack.topAnchor.constraint(equalTo:scroll.contentView.topAnchor)])
    }
    required init?(coder:NSCoder){fatalError()}
    func show(_ items:[CompanionItemView], open:Set<String>) {
        stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() }
        for margin in items where !margin.tombstone {
            guard case .marginNote(_,_,let text,let reader,let state,let sources) = margin.body, state != .dismissed, (state == .pinned || open.contains(margin.id)) else { continue }
            let title = NSTextField(labelWithString:"\(state == .pinned ? "📌 " : "")\(reader)"); title.font = .systemFont(ofSize:13,weight:.semibold)
            let question = NSTextField(wrappingLabelWithString:text); question.font = NSFont(name:"Georgia",size:14); question.widthAnchor.constraint(equalToConstant:240).isActive = true
            stack.addArrangedSubview(title); stack.addArrangedSubview(question)
            let replies = items.filter { v in if case .threadMessage(let id,_,_,_,_) = v.body { return id == margin.id && !v.tombstone }; return false }.sorted { a,b in
                if case .threadMessage(_,let n,_,_,_) = a.body, case .threadMessage(_,let m,_,_,_) = b.body { return n < m }; return false
            }
            for reply in replies { if case .threadMessage(_,_,let who,let text,let urls) = reply.body {
                let label = NSTextField(wrappingLabelWithString:"\(who == .you ? "You" : "Syncr"): \(text)" + urls.map { "\n🌐 \($0.title) \($0.url)" }.joined()); label.font = .systemFont(ofSize:13); label.isSelectable = true; label.widthAnchor.constraint(equalToConstant:240).isActive = true; stack.addArrangedSubview(label)
            }}
            if !sources.isEmpty { let label = NSTextField(wrappingLabelWithString:sources.map { "🌐 \($0.title)\n\($0.url)" }.joined(separator:"\n")); label.font = .systemFont(ofSize:11); label.widthAnchor.constraint(equalToConstant:240).isActive = true; stack.addArrangedSubview(label) }
            let field = NSTextField(string:""); field.placeholderString = "Ask why, push back…"; field.widthAnchor.constraint(equalToConstant:240).isActive = true
            stack.addArrangedSubview(field)
            stack.addArrangedSubview(NSStackView(views:[ActionButton("Reply") { [weak self,weak field] in guard let text = field?.stringValue, !text.isEmpty else { return }; self?.onReply?(margin.id,text); field?.stringValue = "" },ActionButton(state == .pinned ? "Unpin" : "Pin") { [weak self] in self?.onState?(margin.id,state == .pinned ? .open : .pinned) },ActionButton("Dismiss") { [weak self] in self?.onState?(margin.id,.dismissed) }]))
        }
    }
}
@MainActor final class DrawingCard:NSView {
    let drawing:SyncrCompanion.Drawing; private let image = NSImageView(), caption = NSTextField(labelWithString:""), counter = NSTextField(labelWithString:"")
    private var step = 0; private var timer:Timer?; private var larger:NSWindow?
    override var isFlipped:Bool { true }
    init(_ drawing:SyncrCompanion.Drawing) {
        self.drawing = drawing; super.init(frame:.zero)
        let row = NSStackView(views:[ActionButton("Previous") { [weak self] in self?.advance(-1) },counter,ActionButton("Next") { [weak self] in self?.advance(1) },ActionButton("Replay") { [weak self] in self?.replay() },ActionButton("See it larger") { [weak self] in self?.enlarge() }])
        for b in row.views.compactMap({$0 as? NSButton}) { b.font = .systemFont(ofSize:30) }; counter.font = .systemFont(ofSize:30); caption.font = .systemFont(ofSize:30); caption.lineBreakMode = .byTruncatingTail
        image.imageScaling = .scaleProportionallyUpOrDown
        let stack = NSStackView(views:[row,image,caption]); stack.orientation = .vertical; stack.alignment = .leading; stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:leadingAnchor),stack.trailingAnchor.constraint(equalTo:trailingAnchor),stack.topAnchor.constraint(equalTo:topAnchor),stack.bottomAnchor.constraint(equalTo:bottomAnchor),image.widthAnchor.constraint(equalTo:widthAnchor),image.heightAnchor.constraint(equalToConstant:drawing.height)])
        step = drawing.steps.count; render()
    }
    required init?(coder:NSCoder){fatalError()}
    func debug(_ action:String) { switch action { case "previous": advance(-1); case "next": advance(1); case "replay": replay(); case "larger": enlarge(); default: break } }
    private func advance(_ n:Int) { timer?.invalidate(); step = max(0,min(drawing.steps.count,step+n)); render() }
    private func render() { if let cg = SketchRenderer.image(drawing,through:step) { image.image = NSImage(cgImage:cg,size:NSSize(width:drawing.width,height:drawing.height)) }; counter.stringValue = "\(step)/\(drawing.steps.count)"; caption.stringValue = step > 0 ? drawing.steps[step-1].caption : drawing.title }
    private func replay() { timer?.invalidate(); step = 0; render(); timer = Timer.scheduledTimer(withTimeInterval:0.8,repeats:true) { [weak self] _ in MainActor.assumeIsolated { guard let self else { return }; self.step += 1; self.render(); if self.step >= self.drawing.steps.count { self.timer?.invalidate() } } } }
    private func enlarge() { let view = DrawingCard(drawing); let window = NSWindow(contentRect:NSRect(x:0,y:0,width:min(1100,drawing.width),height:min(850,drawing.height+160)),styleMask:[.titled,.closable,.resizable],backing:.buffered,defer:false); window.title = drawing.title; let scroll = NSScrollView(); scroll.hasVerticalScroller = true; scroll.hasHorizontalScroller = true; view.frame = CGRect(x:0,y:0,width:max(1100,drawing.width),height:drawing.height+180); scroll.documentView = view; window.contentView = scroll; window.isReleasedWhenClosed = false; window.center(); window.makeKeyAndOrderFront(nil); larger = window }
}
