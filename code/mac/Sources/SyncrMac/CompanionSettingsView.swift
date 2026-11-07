import AppKit
import SyncrCompanion

/// One persistent form, embedded in Settings (including filtered search results).
/// It never owns a window and never reads credentials into its controls or search metadata.
@MainActor final class CompanionSettingsView: NSView, NSTextFieldDelegate {
    private weak var source: (any CompanionSettingsSource)?
    private let stack = NSStackView()
    private var sections: [SettingItem.ID: NSView] = [:]
    private var didRefresh = false
    private let provider = NSPopUpButton(), reading = NSPopUpButton(), asking = NSPopUpButton()
    private let accountState = SettingsUI.label("Not checked", size: 11.5, colour: Theme.muted)
    private let key = NSSecureTextField(string: "")
    private let keyControls = NSStackView(), accountActions = NSStackView()
    private let tokens = NSTextField(), requests = NSTextField()
    private let usage = SettingsUI.label("", size: 11.5, colour: Theme.muted)
    private let limitsState = SettingsUI.label("", size: 11.5, colour: Theme.needsYou)
    private let pictures = NSSwitch(), handDrawn = NSSwitch()
    private let voice = NSPopUpButton()
    private let speed = NSSlider(value: 0.5, minValue: 0.1, maxValue: 0.7, target: nil, action: nil)
    private let readerStack = NSStackView()
    private let readerState = SettingsUI.label("", size: 11.5, colour: Theme.muted)
    private var readerEditors: [CompanionReaderEditor] = []
    private var addReader: ActionButton!
    private var models: [ProviderModel] = []

    init(source: any CompanionSettingsSource) {
        self.source = source
        super.init(frame: .zero)
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
        buildProvider(); buildModels(); buildUsage(); buildVoice(); buildReaders()
        reloadFromSource()
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func present(sections visible: Set<SettingItem.ID>) {
        for (id, section) in sections { section.isHidden = !visible.contains(id) }
        if !didRefresh { didRefresh = true; source?.refreshModels() }
    }
    /// Library changes reset this form; ordinary async catalog updates do not discard drafts.
    func reloadFromSource() {
        guard let source else { return }
        key.stringValue = ""
        reloadPreferences()
        selectVoice(source.voicePreferences)
        rebuildReaders(source.readers)
        readerState.stringValue = ""; limitsState.stringValue = ""
        didRefresh = false
        update(models: source.settingsModels, state: source.settingsProviderState)
    }
    func reloadPreferences() {
        guard let source else { return }
        let p = source.preferences
        provider.selectItem(at: ["codex", "claude", "openai"].firstIndex(of: p.provider) ?? 0)
        tokens.stringValue = String(p.tokenLimit); requests.stringValue = String(p.requestLimit)
        pictures.state = p.drawings ? .on : .off; handDrawn.state = p.handDrawn ? .on : .off
        handDrawn.isEnabled = p.drawings
        showProviderControls()
        populateModels()
        updateUsage()
    }
    func update(models: [ProviderModel], state: String) {
        self.models = models
        accountState.stringValue = state
        populateModels()
    }
    func clearSensitiveInput() { key.stringValue = "" }
    func updateUsage() {
        guard let meter = source?.settingsMeter else { return }
        usage.stringValue = "Today · \(meter.tokens.formatted()) tokens · \(meter.requests.formatted()) requests · \(meter.unmeteredRequests.formatted()) without reported usage"
    }
    private func showProviderControls() {
        let usesKey = provider.indexOfSelectedItem != 0
        keyControls.isHidden = !usesKey
        accountActions.isHidden = usesKey
        key.placeholderString = provider.indexOfSelectedItem == 2 ? "OpenAI API key" : "Claude API key"
    }

    private func buildProvider() {
        configure(provider, label: "Companion provider", action: #selector(providerChanged))
        provider.addItems(withTitles: ["Codex subscription", "Claude API", "OpenAI API"])
        let refresh = ActionButton(link: "Refresh models", size: 12) { [weak self] in self?.source?.refreshModels() }
        let header = horizontal([provider, NSView(), refresh])
        accountState.setAccessibilityLabel("Provider connection status")
        accountActions.orientation = .horizontal
        accountActions.addArrangedSubview(ActionButton("Sign in with Codex…") { [weak self] in self?.signIn() })
        key.font = Theme.font; key.focusRingType = .none
        key.setAccessibilityLabel("Companion API key")
        key.widthAnchor.constraint(greaterThanOrEqualToConstant: 160).isActive = true
        keyControls.orientation = .vertical; keyControls.alignment = .leading; keyControls.spacing = 8
        let buttons = horizontal([
            key,
            ActionButton("Save key") { [weak self] in self?.saveKey() },
            ActionButton(link: "Remove key", size: 12) { [weak self] in self?.removeKey() }
        ])
        keyControls.addArrangedSubview(buttons)
        buttons.widthAnchor.constraint(equalTo: keyControls.widthAnchor).isActive = true
        keyControls.addArrangedSubview(SettingsUI.label("Stored in macOS Keychain. Your saved key is never displayed here.", size: 11, colour: Theme.muted))
        addSection(.provider, title: "Connection", symbol: "network", rows: [padded([header, accountState, accountActions, keyControls])])
    }
    private func buildModels() {
        configure(reading, label: "Reading model", action: #selector(modelsChanged))
        configure(asking, label: "Answer model", action: #selector(modelsChanged))
        addSection(.models, title: "Models", symbol: "cpu", rows: [
            field("Reading", detail: "Checks whether a passage needs attention.", control: reading, separator: true),
            field("Answering & pictures", detail: "Explains the passage or draws an illustration.", control: asking)
        ])
    }
    private func buildUsage() {
        for (field, name) in [(tokens, "Daily token limit"), (requests, "Unmetered request limit")] {
            field.font = Theme.font; field.alignment = .right; field.focusRingType = .none
            field.delegate = self; field.target = self; field.action = #selector(limitsChanged)
            field.setAccessibilityLabel(name)
            field.widthAnchor.constraint(equalToConstant: 120).isActive = true
        }
        for (control, name) in [(pictures, "Pictures"), (handDrawn, "Hand-drawn lines")] {
            control.controlSize = .small; control.target = self; control.action = #selector(picturesChanged)
            control.setAccessibilityLabel(name)
        }
        addSection(.limits, title: "Pictures & usage", symbol: "slider.horizontal.3", rows: [
            SettingsUI.row(title: "Pictures", detail: "Allow illustrations beside a passage.", control: pictures, separator: true),
            SettingsUI.row(title: "Hand-drawn lines", detail: "Use a pen-like stroke; labels stay crisp.", control: handDrawn, separator: true),
            SettingsUI.row(title: "Daily tokens", detail: "Pause at this limit until you confirm another request.", control: tokens, separator: true),
            SettingsUI.row(title: "Unmetered requests", detail: "Limit requests when a provider does not report token usage.", control: requests, separator: true),
            padded([usage, limitsState])
        ])
    }
    private func buildVoice() {
        configure(voice, label: "Companion voice", action: #selector(voiceChanged))
        voice.addItem(withTitle: "System voice")
        for value in CompanionVoice.voices {
            voice.addItem(withTitle: "\(value.name) · \(value.language)")
            voice.lastItem?.representedObject = value.identifier
        }
        speed.target = self; speed.action = #selector(voiceChanged); speed.isContinuous = false
        speed.setAccessibilityLabel("Speech speed")
        let speedRow = horizontal([
            SettingsUI.label("Slower", size: 11, colour: Theme.muted), speed,
            SettingsUI.label("Faster", size: 11, colour: Theme.muted)
        ])
        addSection(.voice, title: "Voice", symbol: "waveform", rows: [
            field("Read-aloud voice", control: voice, separator: true),
            field("Speech speed", control: speedRow, separator: true),
            padded([SettingsUI.label(CompanionVoice.dictationAvailability, size: 11.5, colour: Theme.muted)])
        ])
    }
    private func buildReaders() {
        readerStack.orientation = .vertical; readerStack.alignment = .leading; readerStack.spacing = 12
        addReader = ActionButton(link: "Add reader", size: 12) { [weak self] in self?.appendReader() }
        let save = ActionButton("Save readers") { [weak self] in self?.saveReaderChanges() }
        save.setAccessibilityLabel("Save readers")
        let actions = horizontal([addReader, NSView(), save])
        let content = padded([
            SettingsUI.label("Choose the perspectives available in your note’s reader menu.", size: 11.5, colour: Theme.muted),
            readerStack, actions, readerState
        ])
        readerStack.widthAnchor.constraint(equalTo: content.widthAnchor, constant: -36).isActive = true
        actions.widthAnchor.constraint(equalTo: readerStack.widthAnchor).isActive = true
        addSection(.readers, title: "Readers", symbol: "person.2", rows: [content])
    }
    private func configure(_ popup: NSPopUpButton, label: String, action: Selector) {
        popup.font = Theme.font; popup.target = self; popup.action = action
        popup.setAccessibilityLabel(label)
        popup.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        popup.heightAnchor.constraint(greaterThanOrEqualToConstant: 28).isActive = true
    }
    private func addSection(_ id: SettingItem.ID, title: String, symbol: String, rows: [NSView]) {
        let icon = NSImageView(image: NSImage(systemSymbolName: symbol, accessibilityDescription: nil) ?? NSImage())
        icon.contentTintColor = Theme.muted
        icon.widthAnchor.constraint(equalToConstant: 15).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 15).isActive = true
        let heading = horizontal([icon, SettingsUI.label(title, size: 13, weight: .medium)])
        let card = SettingsCard(rows: rows)
        let section = NSStackView(views: [heading, card])
        section.orientation = .vertical; section.alignment = .leading; section.spacing = 10
        stack.addArrangedSubview(section); sections[id] = section
        section.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        card.widthAnchor.constraint(equalTo: section.widthAnchor).isActive = true
    }
    private func horizontal(_ views: [NSView]) -> NSStackView {
        let result = NSStackView(views: views); result.spacing = 10; result.alignment = .centerY
        return result
    }
    private func padded(_ views: [NSView], separator: Bool = false) -> NSView {
        let row = ThemedView(); row.bottomLine = separator
        let content = NSStackView(views: views); content.orientation = .vertical; content.alignment = .leading; content.spacing = 10
        content.translatesAutoresizingMaskIntoConstraints = false; row.addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 18), content.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -18),
            content.topAnchor.constraint(equalTo: row.topAnchor, constant: 16), content.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -16)
        ])
        for view in views where view is NSStackView { view.widthAnchor.constraint(equalTo: content.widthAnchor).isActive = true }
        return row
    }
    private func field(_ title: String, detail: String = "", control: NSView, separator: Bool = false) -> NSView {
        var views: [NSView] = [SettingsUI.label(title, size: 12.5, weight: .medium)]
        if !detail.isEmpty { views.append(SettingsUI.label(detail, size: 11.5, colour: Theme.muted)) }
        views.append(control)
        let row = padded(views, separator: separator)
        control.widthAnchor.constraint(equalTo: row.widthAnchor, constant: -36).isActive = true
        return row
    }
    private func populateModels() {
        guard let source else { return }
        for (popup, selected) in [(reading, source.preferences.readingModel), (asking, source.preferences.askingModel)] {
            popup.removeAllItems()
            for model in models {
                popup.addItem(withTitle: model.id)
                popup.lastItem?.representedObject = model.id
            }
            if let index = popup.itemArray.firstIndex(where: { $0.representedObject as? String == selected }) {
                popup.selectItem(at: index)
            } else if !selected.isEmpty {
                popup.addItem(withTitle: "\(selected) · unavailable")
                popup.lastItem?.representedObject = selected
                popup.selectItem(at: popup.numberOfItems - 1)
            } else {
                popup.insertItem(withTitle: models.isEmpty ? "No models loaded" : "Choose a model…", at: 0)
                popup.selectItem(at: 0)
            }
            popup.isEnabled = !models.isEmpty
            popup.toolTip = popup.titleOfSelectedItem
        }
    }
    @objc private func providerChanged() {
        guard let source else { return }
        key.stringValue = ""; models = []
        var p = source.preferences
        p.provider = ["codex", "claude", "openai"][max(0, provider.indexOfSelectedItem)]
        p.readingModel = ""; p.askingModel = ""
        showProviderControls()
        source.savePreferences(p)
        reloadPreferences()
    }
    @objc private func modelsChanged() {
        guard let source else { return }
        var p = source.preferences
        if let id = reading.selectedItem?.representedObject as? String { p.readingModel = id }
        if let id = asking.selectedItem?.representedObject as? String { p.askingModel = id }
        source.savePreferences(p)
    }
    @objc private func picturesChanged() {
        guard let source else { return }
        var p = source.preferences
        p.drawings = pictures.state == .on; p.handDrawn = handDrawn.state == .on
        source.savePreferences(p)
        handDrawn.isEnabled = p.drawings
    }
    @objc private func limitsChanged() {
        guard let source else { return }
        guard let tokenLimit = Int(tokens.stringValue), (1...1_000_000_000).contains(tokenLimit),
              let requestLimit = Int(requests.stringValue), (1...1_000_000).contains(requestLimit) else {
            limitsState.stringValue = "Enter whole numbers: 1–1,000,000,000 tokens and 1–1,000,000 unmetered requests."
            return
        }
        limitsState.stringValue = ""
        var p = source.preferences; p.tokenLimit = tokenLimit; p.requestLimit = requestLimit
        source.savePreferences(p)
    }
    func controlTextDidEndEditing(_ obj: Notification) { limitsChanged() }
    private func selectVoice(_ value: VoicePreferences) {
        voice.selectItem(at: voice.itemArray.firstIndex(where: { ($0.representedObject as? String ?? "") == value.identifier }) ?? 0)
        speed.floatValue = value.rate
    }
    @objc private func voiceChanged() {
        source?.saveVoice(VoicePreferences(identifier: voice.selectedItem?.representedObject as? String ?? "", rate: speed.floatValue))
    }
    private func saveKey() {
        guard !key.stringValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            accountState.stringValue = "Enter a key before saving."; return
        }
        do {
            if provider.indexOfSelectedItem == 2 { try OpenAIKeychain.set(key.stringValue) }
            else if provider.indexOfSelectedItem == 1 { try ClaudeKeychain.set(key.stringValue) }
            else { return }
            key.stringValue = ""; source?.refreshModels()
        } catch { accountState.stringValue = error.localizedDescription }
    }
    private func removeKey() {
        guard provider.indexOfSelectedItem != 0 else { return }
        let removingOpenAI = provider.indexOfSelectedItem == 2
        let alert = NSAlert(); alert.messageText = "Remove the saved API key?"
        alert.informativeText = "Syncr will need a key before it can use this provider again."
        alert.addButton(withTitle: "Remove key"); alert.addButton(withTitle: "Cancel")
        guard let window else { return }
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            do {
                if removingOpenAI { try OpenAIKeychain.delete() }
                else { try ClaudeKeychain.delete() }
                self.key.stringValue = ""; self.source?.refreshModels()
            } catch { self.accountState.stringValue = error.localizedDescription }
        }
    }
    private func signIn() {
        // The provider tool owns its terminal login and token. No credential passes through this form.
        let script = NSAppleScript(source: "tell application \"Terminal\" to do script \"pi\"")
        var error: NSDictionary?
        _ = script?.executeAndReturnError(&error)
        accountState.stringValue = error == nil
            ? "In Terminal: /login → OpenAI Codex, then refresh models here."
            : "Open Terminal, run pi, then /login → OpenAI Codex (or run codex login)."
    }
    private func rebuildReaders(_ values: [Reader]) {
        for view in readerStack.arrangedSubviews { readerStack.removeArrangedSubview(view); view.removeFromSuperview() }
        readerEditors = []
        for value in values { insertEditor(value) }
        updateReaderActions()
    }
    private func insertEditor(_ value: Reader) {
        let editor = CompanionReaderEditor(value)
        editor.onRemove = { [weak self, weak editor] in
            guard let self, let editor, self.readerEditors.count > 1 else { return }
            self.readerEditors.removeAll { $0 === editor }
            self.readerStack.removeArrangedSubview(editor); editor.removeFromSuperview()
            self.readerState.stringValue = "Unsaved reader changes"; self.updateReaderActions()
        }
        editor.onChange = { [weak self] in
            self?.readerState.textColor = Theme.muted
            self?.readerState.stringValue = "Unsaved reader changes"
        }
        readerEditors.append(editor); readerStack.addArrangedSubview(editor)
        editor.widthAnchor.constraint(equalTo: readerStack.widthAnchor).isActive = true
    }
    private func appendReader() {
        guard readerEditors.count < 100 else { return }
        insertEditor(Reader(id: UUID().uuidString.lowercased(), name: "New reader", description: ""))
        readerState.stringValue = "Unsaved reader changes"; updateReaderActions()
    }
    private func updateReaderActions() {
        addReader.isEnabled = readerEditors.count < 100
        for editor in readerEditors { editor.remove.isEnabled = readerEditors.count > 1 }
    }
    private func saveReaderChanges() {
        window?.makeFirstResponder(nil)
        let values = readerEditors.map(\.value)
        guard let source else { return }
        do {
            try Reader.validate(values)
        } catch {
            readerState.textColor = Theme.needsYou
            readerState.stringValue = "Use names of 1–128 UTF-8 bytes and instructions up to 4,096 bytes. Keep at least one reader."
            return
        }
        do {
            try source.saveReaders(values)
            readerState.textColor = Theme.muted; readerState.stringValue = "Readers saved"
        } catch {
            readerState.textColor = Theme.needsYou
            readerState.stringValue = "Readers not saved: \(error.localizedDescription)"
        }
    }
}

@MainActor private final class CompanionReaderEditor: ThemedView, NSTextFieldDelegate, NSTextViewDelegate {
    private let id: String
    private let name = NSTextField()
    private let instructions = NSTextView()
    let remove = ActionButton(link: "Remove", size: 11.5) {}
    var onRemove: (() -> Void)?
    var onChange: (() -> Void)?
    var value: Reader { Reader(id: id, name: name.stringValue.trimmingCharacters(in: .whitespacesAndNewlines), description: instructions.string) }
    init(_ reader: Reader) {
        id = reader.id
        super.init(frame: .zero)
        name.stringValue = reader.name; name.placeholderString = "Reader name"
        name.font = Theme.font; name.focusRingType = .none; name.delegate = self
        name.setAccessibilityLabel("Reader name \(reader.id)")
        remove.run = { [weak self] in self?.onRemove?() }
        remove.setAccessibilityLabel("Remove reader \(reader.id)")
        let top = NSStackView(views: [name, remove]); top.spacing = 12
        let scroll = NSScrollView(); scroll.documentView = instructions
        scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true; scroll.borderType = .noBorder
        scroll.drawsBackground = true; scroll.backgroundColor = Theme.panel
        instructions.isRichText = false; instructions.string = reader.description
        instructions.font = Theme.font; instructions.textColor = Theme.ink; instructions.backgroundColor = Theme.panel
        instructions.insertionPointColor = Theme.ink; instructions.textContainerInset = NSSize(width: 9, height: 8)
        instructions.isAutomaticQuoteSubstitutionEnabled = false; instructions.isAutomaticDashSubstitutionEnabled = false
        instructions.isHorizontallyResizable = false; instructions.isVerticallyResizable = true
        instructions.autoresizingMask = [.width]; instructions.textContainer?.widthTracksTextView = true
        instructions.delegate = self; instructions.setAccessibilityLabel("Reader instructions \(reader.id)")
        let caption = SettingsUI.label("Instructions", size: 11, colour: Theme.muted)
        let stack = NSStackView(views: [top, caption, scroll]); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 7
        stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            top.widthAnchor.constraint(equalTo: stack.widthAnchor), scroll.widthAnchor.constraint(equalTo: stack.widthAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 72)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        instructions.textColor = Theme.ink; instructions.backgroundColor = Theme.panel
        instructions.insertionPointColor = Theme.ink
    }
    func controlTextDidChange(_ obj: Notification) { onChange?() }
    func textDidChange(_ notification: Notification) { onChange?() }
}
