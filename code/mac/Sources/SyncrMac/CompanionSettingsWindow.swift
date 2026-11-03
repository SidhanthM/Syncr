import AppKit
import SyncrCore
import SyncrCompanion

@MainActor final class CompanionSettingsWindow: NSObject {
    private unowned let controller: CompanionController
    private let window: NSWindow
    private let provider = NSPopUpButton(), reading = NSPopUpButton(), asking = NSPopUpButton()
    private let state = NSTextField(wrappingLabelWithString: "Checking sign-in…"), key = NSSecureTextField(string: "")
    private let tokens = NSTextField(string: "100000"), requests = NSTextField(string: "100")
    private let voice = NSPopUpButton()
    private let speed = NSSlider(value: 0.5, minValue: 0.1, maxValue: 0.7, target: nil, action: nil)
    private let drawings = NSButton(checkboxWithTitle: "Pictures", target: nil, action: nil)
    private let handDrawn = NSButton(checkboxWithTitle: "Hand-drawn lines", target: nil, action: nil)
    private let scroll = NSTextView.scrollableTextView()
    private var readers: NSTextView { scroll.documentView as! NSTextView }
    private var grid: NSGridView!

    init(controller: CompanionController) {
        self.controller = controller
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 720), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        super.init()
        window.title = "Companion Settings"
        window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 560, height: 600)
        provider.addItems(withTitles: ["Codex subscription", "Claude API key", "OpenAI API key"])
        provider.selectItem(at: controller.preferences.provider == "claude" ? 1 : controller.preferences.provider == "openai" ? 2 : 0)
        provider.target = self
        provider.action = #selector(providerChanged)
        provider.toolTip = "Codex uses pi or the Codex CLI (which also handles web search); Claude and OpenAI use an API key kept in the Keychain"
        state.textColor = .secondaryLabelColor
        state.font = .systemFont(ofSize: 11)
        key.placeholderString = "sk-ant-…  (stored only in the Keychain)"
        voice.addItem(withTitle: "System voice")
        for value in CompanionVoice.voices { voice.addItem(withTitle: "\(value.name) (\(value.language))"); voice.lastItem?.representedObject = value.identifier }
        if let index = voice.itemArray.firstIndex(where: { $0.representedObject as? String == controller.voicePreferences.identifier }) { voice.selectItem(at: index) }
        speed.floatValue = controller.voicePreferences.rate
        drawings.state = controller.preferences.drawings ? .on : .off
        drawings.toolTip = "Step-by-step pictures under a passage. Off until the second sketch trial has been looked at"
        handDrawn.state = controller.preferences.handDrawn ? .on : .off
        handDrawn.toolTip = "A slight, steady wobble and a faint second pass on lines, like a pen on paper; labels stay crisp"
        handDrawn.target = self; handDrawn.action = #selector(styleChanged)
        tokens.stringValue = String(controller.preferences.tokenLimit)
        requests.stringValue = String(controller.preferences.requestLimit)
        for field in [tokens, requests] { field.alignment = .right; field.widthAnchor.constraint(equalToConstant: 110).isActive = true }

        let signIn = ActionButton("Sign In with Codex…") { [weak self] in self?.signIn() }
        let refresh = ActionButton("Refresh Models") { [weak controller] in controller?.refreshModels() }
        let setKey = ActionButton("Save Key") { [weak self] in
            guard let self else { return }
            do {
                if self.provider.indexOfSelectedItem == 2 { try OpenAIKeychain.set(self.key.stringValue) }
                else { try ClaudeKeychain.set(self.key.stringValue) }
                self.key.stringValue = ""; self.controller.refreshModels()
            }
            catch { self.state.stringValue = error.localizedDescription }
        }
        let deleteKey = ActionButton("Delete Key") { [weak self] in
            do {
                if self?.provider.indexOfSelectedItem == 2 { try OpenAIKeychain.delete() }
                else { try ClaudeKeychain.delete() }
                self?.controller.refreshModels()
            }
            catch { self?.state.stringValue = error.localizedDescription }
        }

        scroll.borderType = .bezelBorder
        readers.isRichText = false
        readers.isAutomaticQuoteSubstitutionEnabled = false
        readers.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        readers.textContainerInset = NSSize(width: 4, height: 6)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        readers.string = String(decoding: (try? encoder.encode(controller.readers)) ?? Data(), as: UTF8.self)
        let saveReaders = ActionButton("Save Readers") { [weak self] in
            guard let self else { return }
            do {
                let values = try JSONDecoder().decode([Reader].self, from: Data(self.readers.string.utf8))
                try self.controller.saveReaders(values)
                self.state.stringValue = "Readers saved"
            } catch { self.state.stringValue = "Readers not saved: \(error.localizedDescription)" }
        }
        let save = ActionButton("Save") { [weak self] in self?.save() }
        save.keyEquivalent = "\r"
        save.controlSize = .regular

        func label(_ text: String) -> NSTextField {
            let field = NSTextField(labelWithString: text)
            field.alignment = .right
            return field
        }
        func row(_ views: NSView...) -> NSStackView {
            let stack = NSStackView(views: views)
            stack.spacing = 8
            return stack
        }
        func section(_ text: String) -> NSTextField {
            let field = NSTextField(labelWithString: text)
            field.font = .systemFont(ofSize: 11, weight: .semibold)
            field.textColor = .secondaryLabelColor
            return field
        }
        let empty = NSGridCell.emptyContentView
        grid = NSGridView(views: [
            [section("PROVIDER"), empty],
            [label("Provider:"), provider],
            [empty, state],
            [empty, row(signIn, refresh)],
            [label("API key:"), row(key, setKey, deleteKey)],
            [section("MODELS"), empty],
            [label("Gate (reading along):"), reading],
            [label("Answer (asking, pictures):"), asking],
            [section("LIMITS"), empty],
            [label("Daily tokens:"), tokens],
            [label("Unmetered requests:"), requests],
            [empty, drawings],
            [empty, handDrawn],
            [label("Voice:"), voice],
            [label("Speech speed:"), speed],
            [empty, NSTextField(wrappingLabelWithString: CompanionVoice.dictationAvailability)]
        ])
        grid.rowSpacing = 8
        grid.columnSpacing = 10
        grid.column(at: 0).xPlacement = .trailing
        grid.rowAlignment = .firstBaseline
        for index in [0, 5, 8] {
            grid.row(at: index).topPadding = index == 0 ? 0 : 10
            grid.row(at: index).mergeCells(in: NSRange(location: 0, length: 2))
            grid.cell(atColumnIndex: 0, rowIndex: index).xPlacement = .leading
        }
        let readersHeader = section("READERS")
        let readersHint = NSTextField(wrappingLabelWithString: "A JSON array of readers, each with an id, name and description.")
        readersHint.font = .systemFont(ofSize: 11)
        readersHint.textColor = .secondaryLabelColor
        let buttons = row(saveReaders, NSView(), save)
        let stack = NSStackView(views: [grid, readersHeader, readersHint, scroll, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 8
        stack.setCustomSpacing(18, after: grid)
        stack.setCustomSpacing(12, after: scroll)
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView()
        content.addSubview(stack)
        window.contentView = content
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor), stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor), stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 160),
            scroll.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -40),
            buttons.widthAnchor.constraint(equalTo: scroll.widthAnchor),
            key.widthAnchor.constraint(greaterThanOrEqualToConstant: 240),
            state.widthAnchor.constraint(lessThanOrEqualToConstant: 400),
            reading.widthAnchor.constraint(greaterThanOrEqualToConstant: 300),
            asking.widthAnchor.constraint(equalTo: reading.widthAnchor)
        ])
        showProviderRows()
    }

    /// Codex signs in through its own tool; Claude needs a key. Show only the relevant row.
    private func showProviderRows() {
        let usesKey = provider.indexOfSelectedItem != 0
        grid.row(at: 3).isHidden = usesKey
        grid.row(at: 4).isHidden = !usesKey
        key.placeholderString = provider.indexOfSelectedItem == 2 ? "OpenAI key (Keychain only)" : "Claude key (Keychain only)"
    }

    func show() {
        if !window.isVisible { window.center() }
        window.makeKeyAndOrderFront(nil)
    }
    func close() { window.close() }
    func update(models: [ProviderModel], state: String) {
        self.state.stringValue = state
        for (popup, selected) in [(reading, controller.preferences.readingModel), (asking, controller.preferences.askingModel)] {
            popup.removeAllItems()
            popup.addItems(withTitles: models.map(\.id))
            if popup.itemTitles.contains(selected) { popup.selectItem(withTitle: selected) }
            else if !selected.isEmpty {
                popup.addItem(withTitle: selected + " (not offered)")
                popup.selectItem(at: popup.numberOfItems - 1)
            }
        }
    }
    @objc private func styleChanged() {
        var p = controller.preferences
        p.handDrawn = handDrawn.state == .on
        controller.savePreferences(p)
    }
    @objc private func providerChanged() {
        var p = controller.preferences
        p.provider = ["codex", "claude", "openai"][max(0,provider.indexOfSelectedItem)]
        showProviderRows()
        controller.savePreferences(p)
    }
    private func save() {
        var p = controller.preferences
        p.provider = ["codex", "claude", "openai"][max(0,provider.indexOfSelectedItem)]
        if let value = reading.titleOfSelectedItem, !value.contains("(not offered)") { p.readingModel = value }
        if let value = asking.titleOfSelectedItem, !value.contains("(not offered)") { p.askingModel = value }
        p.tokenLimit = max(1, min(1_000_000_000, Int(tokens.stringValue) ?? 100_000))
        p.requestLimit = max(1, min(1_000_000, Int(requests.stringValue) ?? 100))
        p.drawings = drawings.state == .on
        p.handDrawn = handDrawn.state == .on
        tokens.stringValue = String(p.tokenLimit)
        requests.stringValue = String(p.requestLimit)
        controller.savePreferences(p)
        controller.saveVoice(VoicePreferences(identifier: voice.selectedItem?.representedObject as? String ?? "", rate: speed.floatValue))
        state.stringValue = "Settings saved"
    }
    private func signIn() {
        // The tool's own terminal sign-in UI owns its token. No shell interpolation or token exchange.
        let script = NSAppleScript(source:"tell application \"Terminal\" to do script \"pi\"")
        var error: NSDictionary?
        _ = script?.executeAndReturnError(&error)
        state.stringValue = error == nil ? "In Terminal choose /login → OpenAI Codex, then Refresh models. If pi is absent, run codex login." : "Open Terminal and run pi, then /login → OpenAI Codex (or codex login)."
    }
}
