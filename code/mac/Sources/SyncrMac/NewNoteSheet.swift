import AppKit
import SyncrCore

/// What the New note sheet chose (P1, without a cover or the template gallery). The last choice is the next default.
struct NewNoteOptions: Equatable, Codable, Sendable {
    var title = "Untitled"
    /// A named size from the core ("A4", ...) or "Custom".
    var size = "A4"
    var customWidthMM = 210.0, customHeightMM = 297.0
    var landscape = false
    /// "none", "lines", "dots" or "grid".
    var template = "none"
    /// RGB of the paper; nil is the theme's paper.
    var paper: [UInt8]? = nil
    var patternColour: [UInt8] = [150, 150, 150]
    var patternOpacity = 0.5

    static let customRange = 10.0...2000.0
    /// Millimetres to note units, as the core's page sizes: 1600 across A4's 210.
    static let unitsPerMM: Float = 1600 / 210

    /// The page's width and height in note units, or nil when a custom side is out of range.
    var extent: (width: Float, height: Float)? {
        var w: Float, h: Float
        if size == "Custom" {
            guard Self.customRange.contains(customWidthMM), Self.customRange.contains(customHeightMM) else { return nil }
            w = Float(customWidthMM) * Self.unitsPerMM; h = Float(customHeightMM) * Self.unitsPerMM
        } else if let named = pageSizes().first(where: { $0.name == size }) {
            w = named.width; h = named.height
        } else { (w, h) = PageActions.a4 }
        if size != "Custom", landscape { swap(&w, &h) }
        return (w, h)
    }
    var pattern: PagePattern { switch template { case "lines": .lines; case "dots": .dots; case "grid": .grid; default: .none } }
    var paperColour: Rgba? { paper.map { Rgba(r: $0[0], g: $0[1], b: $0[2], a: 255) } }
    var ruleColour: Rgba { Rgba(r: patternColour[0], g: patternColour[1], b: patternColour[2], a: UInt8((patternOpacity * 255).rounded())) }
}

/// The sheet that opens from the sidebar button, Cmd-N and the palette: name, size, orientation, template, paper.
@MainActor final class NewNoteSheet: NSObject, NSTextFieldDelegate {
    private let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 520, height: 520), styleMask: [.titled], backing: .buffered, defer: false)
    private var options: NewNoteOptions
    private var completion: ((NewNoteOptions?) -> Void)?
    private let nameField = NSTextField()
    private let sizePopup = NSPopUpButton()
    private let widthField = NSTextField(), heightField = NSTextField()
    private let customRow = NSStackView(), errorLabel = NSTextField(labelWithString: "Each side between 10 and 2000 mm")
    private let orientation = NSSegmentedControl(labels: ["Portrait", "Landscape"], trackingMode: .selectOne, target: nil, action: nil)
    private let templates = NSSegmentedControl(labels: ["None", "Lines", "Dots", "Grid"], trackingMode: .selectOne, target: nil, action: nil)
    private let paperRow = NSStackView(), ruleRow = NSStackView()
    private let opacity = NSSlider(value: 0.5, minValue: 0.05, maxValue: 1, target: nil, action: nil)
    private let create = NSButton(title: "Create", target: nil, action: nil)
    private let preview = PagePreview()
    private var swatches: [Swatch] = [], ruleSwatches: [Swatch] = []
    private static let templateNames = ["none", "lines", "dots", "grid"]
    /// Paper colours offered next to "Default".
    static let papers: [[UInt8]] = [[255, 255, 255], [252, 247, 232], [255, 249, 196], [226, 236, 246], [227, 241, 228], [38, 38, 36]]
    static let rules: [[UInt8]] = [[150, 150, 150], [90, 90, 90], [70, 120, 190], [190, 90, 80], [80, 140, 90]]

    init(options: NewNoteOptions, notebook: String?) {
        self.options = options
        super.init()
        build(notebook: notebook)
        sync()
    }

    /// Shows the sheet over `window`; `completion` gets the choices, or nil when cancelled.
    func present(over window: NSWindow, completion: @escaping (NewNoteOptions?) -> Void) {
        self.completion = completion
        window.beginSheet(panel)
        panel.makeFirstResponder(nameField)
        nameField.currentEditor()?.selectAll(nil)
    }

    private func label(_ text: String) -> NSTextField {
        let l = NSTextField(labelWithString: text); l.font = .systemFont(ofSize: 11, weight: .medium); l.textColor = Theme.muted; return l
    }

    private func build(notebook: String?) {
        let title = NSTextField(labelWithString: "New note in \(notebook ?? "Inbox")")
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        nameField.stringValue = options.title; nameField.font = .systemFont(ofSize: 18); nameField.delegate = self
        nameField.placeholderString = "Untitled"
        let sizes = pageSizes().map(\.name) + ["Custom"]
        sizePopup.addItems(withTitles: sizes.map { name in
            if let s = pageSizes().first(where: { $0.name == name }) { return "\(name)   \(Self.mm(s.mmWidth)) × \(Self.mm(s.mmHeight)) mm" }
            return name
        })
        sizePopup.target = self; sizePopup.action = #selector(changed)
        for f in [widthField, heightField] { f.alignment = .right; f.delegate = self; f.widthAnchor.constraint(equalToConstant: 64).isActive = true }
        customRow.orientation = .horizontal; customRow.spacing = 6
        customRow.setViews([label("Width (mm)"), widthField, label("Height (mm)"), heightField], in: .leading)
        errorLabel.font = .systemFont(ofSize: 11); errorLabel.textColor = .systemRed
        orientation.target = self; orientation.action = #selector(changed)
        templates.target = self; templates.action = #selector(changed)
        opacity.target = self; opacity.action = #selector(changed); opacity.widthAnchor.constraint(equalToConstant: 140).isActive = true

        let defaultChip = Swatch(colour: nil, title: "Default") { [weak self] in self?.options.paper = nil; self?.sync() }
        swatches = [defaultChip] + Self.papers.map { rgb in Swatch(colour: rgb, title: nil) { [weak self] in self?.options.paper = rgb; self?.sync() } }
        paperRow.orientation = .horizontal; paperRow.spacing = 8; paperRow.setViews(swatches, in: .leading)
        ruleSwatches = Self.rules.map { rgb in Swatch(colour: rgb, title: nil) { [weak self] in self?.options.patternColour = rgb; self?.sync() } }
        ruleRow.orientation = .horizontal; ruleRow.spacing = 8
        ruleRow.setViews(ruleSwatches + [label("Opacity"), opacity], in: .leading)

        create.keyEquivalent = "\r"; create.target = self; create.action = #selector(accept); create.bezelStyle = .rounded
        let cancel = NSButton(title: "Cancel", target: self, action: #selector(cancel)); cancel.keyEquivalent = "\u{1b}"; cancel.bezelStyle = .rounded
        let buttons = NSStackView(views: [cancel, create]); buttons.spacing = 8

        let form = NSStackView(views: [title, nameField, label("Size"), sizePopup, customRow, errorLabel, orientation, label("Template"), templates,
                                       label("Paper"), paperRow, label("Pattern colour"), ruleRow])
        form.orientation = .vertical; form.alignment = .leading; form.spacing = 8
        form.setCustomSpacing(14, after: nameField); form.setCustomSpacing(14, after: orientation); form.setCustomSpacing(14, after: templates)
        nameField.widthAnchor.constraint(equalToConstant: 300).isActive = true
        sizePopup.widthAnchor.constraint(equalToConstant: 300).isActive = true
        preview.translatesAutoresizingMaskIntoConstraints = false
        let body = NSStackView(views: [form, preview]); body.orientation = .horizontal; body.alignment = .top; body.spacing = 24
        preview.widthAnchor.constraint(equalToConstant: 150).isActive = true
        preview.heightAnchor.constraint(equalToConstant: 230).isActive = true
        let root = NSStackView(views: [body, buttons]); root.orientation = .vertical; root.alignment = .trailing; root.spacing = 18
        root.edgeInsets = NSEdgeInsets(top: 20, left: 24, bottom: 18, right: 24)
        root.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView(); content.addSubview(root)
        NSLayoutConstraint.activate([root.leadingAnchor.constraint(equalTo: content.leadingAnchor), root.trailingAnchor.constraint(equalTo: content.trailingAnchor),
                                     root.topAnchor.constraint(equalTo: content.topAnchor), root.bottomAnchor.constraint(equalTo: content.bottomAnchor)])
        panel.contentView = content
        panel.setContentSize(content.fittingSize)
    }

    private static func mm(_ v: Float) -> String { v == v.rounded() ? String(Int(v)) : String(format: "%g", (v * 10).rounded() / 10) }

    /// Reads the controls into `options`, then shows `options` in them.
    @objc private func changed() {
        let names = pageSizes().map(\.name) + ["Custom"]
        options.size = names[max(0, sizePopup.indexOfSelectedItem)]
        options.landscape = orientation.selectedSegment == 1
        options.template = Self.templateNames[max(0, templates.selectedSegment)]
        options.patternOpacity = opacity.doubleValue
        sync()
    }
    func controlTextDidChange(_ obj: Notification) {
        if let field = obj.object as? NSTextField, field === widthField || field === heightField {
            options.customWidthMM = widthField.doubleValue; options.customHeightMM = heightField.doubleValue
        }
        sync()
    }

    private func sync() {
        let names = pageSizes().map(\.name) + ["Custom"]
        sizePopup.selectItem(at: names.firstIndex(of: options.size) ?? 0)
        let custom = options.size == "Custom"
        customRow.isHidden = !custom
        if custom {
            if widthField.currentEditor() == nil { widthField.stringValue = Self.mm(Float(options.customWidthMM)) }
            if heightField.currentEditor() == nil { heightField.stringValue = Self.mm(Float(options.customHeightMM)) }
        }
        let valid = options.extent != nil
        errorLabel.isHidden = valid || !custom
        create.isEnabled = valid
        // On Custom the two sides are typed as they are; orientation swaps them.
        orientation.selectedSegment = options.landscape ? 1 : 0
        orientation.isEnabled = !custom
        templates.selectedSegment = Self.templateNames.firstIndex(of: options.template) ?? 0
        swatches.enumerated().forEach { i, s in s.selected = (i == 0 ? options.paper == nil : options.paper == Self.papers[i - 1]) }
        ruleSwatches.enumerated().forEach { i, s in s.selected = options.patternColour == Self.rules[i] }
        ruleRow.isHidden = options.template == "none"
        (ruleRow.superview as? NSStackView)?.views.compactMap { $0 as? NSTextField }.filter { $0.stringValue == "Pattern colour" }.forEach { $0.isHidden = options.template == "none" }
        opacity.doubleValue = options.patternOpacity
        preview.options = options
    }

    @objc private func accept() {
        guard options.extent != nil else { return }
        options.title = nameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if options.title.isEmpty { options.title = "Untitled" }
        finish(options)
    }
    @objc private func cancel() { finish(nil) }
    private func finish(_ result: NewNoteOptions?) {
        panel.sheetParent?.endSheet(panel)
        let done = completion; completion = nil
        done?(result)
    }
}

/// A paper or pattern colour you can pick, or the "Default" chip.
private final class Swatch: NSView {
    let colour: [UInt8]?, title: String?, pick: () -> Void
    var selected = false { didSet { needsDisplay = true } }
    init(colour: [UInt8]?, title: String?, pick: @escaping () -> Void) {
        self.colour = colour; self.title = title; self.pick = pick
        super.init(frame: NSRect(x: 0, y: 0, width: title == nil ? 26 : 62, height: 26))
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: frame.width).isActive = true
        heightAnchor.constraint(equalToConstant: 26).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func draw(_ dirtyRect: NSRect) {
        let r = bounds.insetBy(dx: 2, dy: 2)
        let path = NSBezierPath(roundedRect: r, xRadius: title == nil ? 13 : 7, yRadius: title == nil ? 13 : 7)
        if let c = colour { NSColor(srgbRed: CGFloat(c[0]) / 255, green: CGFloat(c[1]) / 255, blue: CGFloat(c[2]) / 255, alpha: 1).setFill() } else { Theme.raise.setFill() }
        path.fill()
        (selected ? Theme.ink : Theme.muted.withAlphaComponent(0.5)).setStroke()
        path.lineWidth = selected ? 2 : 1; path.stroke()
        if let title {
            let s = NSAttributedString(string: title, attributes: [.font: NSFont.systemFont(ofSize: 11), .foregroundColor: Theme.ink])
            let size = s.size(); s.draw(at: NSPoint(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2))
        }
    }
    override func mouseDown(with event: NSEvent) { pick() }
}

/// A small sheet of the chosen paper, to the chosen shape.
private final class PagePreview: NSView {
    var options = NewNoteOptions() { didSet { needsDisplay = true } }
    override func draw(_ dirtyRect: NSRect) {
        guard let e = options.extent else { return }
        let scale = min((bounds.width - 12) / CGFloat(e.width), (bounds.height - 12) / CGFloat(e.height))
        let size = NSSize(width: CGFloat(e.width) * scale, height: CGFloat(e.height) * scale)
        let r = NSRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2, width: size.width, height: size.height)
        if let p = options.paper { NSColor(srgbRed: CGFloat(p[0]) / 255, green: CGFloat(p[1]) / 255, blue: CGFloat(p[2]) / 255, alpha: 1).setFill() }
        else { Theme.page.setFill() }
        r.fill()
        let c = options.patternColour
        NSColor(srgbRed: CGFloat(c[0]) / 255, green: CGFloat(c[1]) / 255, blue: CGFloat(c[2]) / 255, alpha: CGFloat(options.patternOpacity)).setFill()
        let step = CGFloat(82.6) * scale
        if options.template != "none", step > 2 {
            var y = r.minY + step
            while y < r.maxY {
                if options.template == "dots" {
                    var x = r.minX + step; while x < r.maxX { NSRect(x: x - 0.7, y: y - 0.7, width: 1.4, height: 1.4).fill(); x += step }
                } else { NSRect(x: r.minX, y: y, width: r.width, height: 0.7).fill() }
                y += step
            }
            if options.template == "grid" { var x = r.minX + step; while x < r.maxX { NSRect(x: x, y: r.minY, width: 0.7, height: r.height).fill(); x += step } }
        }
        Theme.muted.withAlphaComponent(0.6).setStroke(); NSBezierPath(rect: r).stroke()
    }
}
