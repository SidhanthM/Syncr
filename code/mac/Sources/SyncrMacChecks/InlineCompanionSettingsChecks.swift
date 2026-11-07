import AppKit
import SyncrCompanion

@MainActor private final class SettingsCompanionSource: CompanionSettingsSource {
    var preferences = CompanionPreferences()
    var readers = Reader.starters
    var voicePreferences = VoicePreferences()
    var settingsModels: [ProviderModel] = []
    var settingsProviderState = "Recorded account · no network"
    var settingsMeter = BudgetMeter(day: BudgetMeter.dayKey())
    var refreshes = 0, preferenceSaves = 0, readerSaves = 0, voiceSaves = 0
    var failReaders = false
    func refreshModels() { refreshes += 1 }
    func savePreferences(_ value: CompanionPreferences) {
        let changed = preferences.provider != value.provider
        preferences = value; preferenceSaves += 1
        if changed { preferences.readingModel = ""; preferences.askingModel = ""; settingsModels = []; refreshModels() }
    }
    func saveReaders(_ value: [Reader]) throws {
        if failReaders { throw CocoaError(.fileWriteNoPermission) }
        try Reader.validate(value); readers = value; readerSaves += 1
    }
    func saveVoice(_ value: VoicePreferences) { voicePreferences = value; voiceSaves += 1 }
}

@MainActor func inlineCompanionSettingsChecks() throws {
    func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap { descendants($0) } }
    func find<T: NSView>(_ type: T.Type, _ label: String, in root: NSView) throws -> T {
        guard let view = descendants(root).compactMap({ $0 as? T }).first(where: { $0.accessibilityLabel() == label }) else {
            throw NSError(domain: "InlineSettings", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing control: \(label)"])
        }
        return view
    }
    func button(_ title: String, in root: NSView) throws -> NSButton {
        guard let view = descendants(root).compactMap({ $0 as? NSButton }).first(where: { $0.title == title }) else {
            throw NSError(domain: "InlineSettings", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing button: \(title)"])
        }
        return view
    }
    func act(_ control: NSControl) throws {
        guard let action = control.action, control.sendAction(action, to: control.target) else {
            throw NSError(domain: "InlineSettings", code: 3, userInfo: [NSLocalizedDescriptionKey: "Control action failed"])
        }
    }
    let source = SettingsCompanionSource()
    source.preferences.readingModel = "saved-reading-model"
    source.preferences.askingModel = "saved-answer-model"
    let view = CompanionSettingsView(source: source)
    view.present(sections: [.provider, .models, .limits, .voice, .readers])
    try check(source.refreshes == 1 && view.window == nil, "inline form refreshes once without creating its own window")
    let reading = try find(NSPopUpButton.self, "Reading model", in: view)
    let asking = try find(NSPopUpButton.self, "Answer model", in: view)
    try check(!reading.isEnabled && reading.selectedItem?.representedObject as? String == "saved-reading-model", "unavailable saved model keeps its underlying ID")

    // Fake catalogs only: never inspect real sign-in state, secrets, or provider models.
    source.settingsModels = [ProviderModel(id: "small", name: "Small"), ProviderModel(id: "large", name: "Large")]
    view.update(models: source.settingsModels, state: source.settingsProviderState)
    reading.selectItem(withTitle: "small"); asking.selectItem(withTitle: "large"); try act(reading)
    try check(source.preferences.readingModel == "small" && source.preferences.askingModel == "large", "model changes save immediately")
    let key = try find(NSSecureTextField.self, "Companion API key", in: view)
    key.stringValue = "unsaved-key-placeholder"
    let provider = try find(NSPopUpButton.self, "Companion provider", in: view)
    provider.selectItem(at: 2); try act(provider)
    try check(source.preferences.provider == "openai" && source.preferences.readingModel.isEmpty && source.preferences.askingModel.isEmpty, "provider change clears old model choices")
    try check(key.stringValue.isEmpty && !key.isHiddenOrHasHiddenAncestor, "switching provider clears any unsaved key and reveals secure input")
    provider.selectItem(at: 0); try act(provider)
    try check(key.isHiddenOrHasHiddenAncestor, "Codex hides API key fields")

    let tokens = try find(NSTextField.self, "Daily token limit", in: view)
    let requests = try find(NSTextField.self, "Unmetered request limit", in: view)
    tokens.stringValue = "not a number"; try act(tokens)
    try check(source.preferences.tokenLimit == 100_000, "invalid limit does not silently overwrite saved values")
    tokens.stringValue = "42000"; requests.stringValue = "75"; try act(tokens)
    try check(source.preferences.tokenLimit == 42000 && source.preferences.requestLimit == 75, "valid limits save inline")
    let pictures = try find(NSSwitch.self, "Pictures", in: view)
    let hand = try find(NSSwitch.self, "Hand-drawn lines", in: view)
    pictures.state = .off; try act(pictures)
    try check(!source.preferences.drawings && !hand.isEnabled, "picture toggle saves and disables irrelevant styling")
    let speed = try find(NSSlider.self, "Speech speed", in: view)
    speed.floatValue = 0.4; try act(speed)
    try check(source.voiceSaves == 1 && abs(source.voicePreferences.rate - 0.4) < 0.001, "voice controls save inline")

    let name = try find(NSTextField.self, "Reader name staff-engineer", in: view)
    let instructions = try find(NSTextView.self, "Reader instructions staff-engineer", in: view)
    name.stringValue = "My reader"; instructions.string = "Question the assumptions."
    view.update(models: [], state: "Models unavailable")
    try check(name.stringValue == "My reader" && instructions.string == "Question the assumptions.", "async catalog updates preserve reader drafts")
    try button("Save readers", in: view).performClick(nil)
    try check(source.readers[0].id == "staff-engineer" && source.readers[0].name == "My reader", "structured reader edits keep stable IDs")
    let saved = source.readerSaves
    name.stringValue = " "; try button("Save readers", in: view).performClick(nil)
    try check(source.readerSaves == saved, "empty reader names are rejected")
    name.stringValue = "Unsaved draft"; source.failReaders = true
    try button("Save readers", in: view).performClick(nil)
    try check(source.readers[0].name == "My reader" && name.stringValue == "Unsaved draft", "failed reader write preserves existing data and draft")
    source.failReaders = false
    try button("Add reader", in: view).performClick(nil)
    try button("Save readers", in: view).performClick(nil)
    try check(source.readers.count == Reader.starters.count + 1, "reader creation works without JSON")
    let last = source.readers.last!.id
    try find(NSButton.self, "Remove reader \(last)", in: view).performClick(nil)
    try button("Save readers", in: view).performClick(nil)
    try check(source.readers.count == Reader.starters.count, "reader removal saves inline")

    let root = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-inline-settings-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: root) }
    let preferences = AppPreferences(url: root.appendingPathComponent("preferences.json"))
    let store = HotkeyStore(url: root.appendingPathComponent("hotkeys.json"))
    let settings = SettingsWindow(store: store, preferences: preferences)
    var created = 0
    settings.companionContent = { created += 1; return view }
    let windowsBefore = NSApp.windows.count
    settings.show(page: .companion)
    guard let window = NSApp.windows.first(where: { $0.title == "Syncr Settings" }), let content = window.contentView else {
        throw NSError(domain: "InlineSettings", code: 4)
    }
    defer { window.close() }
    window.contentView?.layoutSubtreeIfNeeded()
    try check(view.window === window && created == 1 && NSApp.windows.count <= windowsBefore + 1 && !NSApp.windows.contains(where: { $0.title == "Companion Settings" }), "Companion uses the existing Settings window only")
    try check(!descendants(content).compactMap { $0 as? NSButton }.contains { $0.title.contains("Configure") }, "no Configure launchers remain")
    let search = try find(NSSearchField.self, "Search all settings", in: content)
    search.stringValue = "voice"; try act(search)
    let voice = try find(NSPopUpButton.self, "Companion voice", in: content)
    try check(!voice.isHiddenOrHasHiddenAncestor && provider.isHiddenOrHasHiddenAncestor, "global search shows matching inline sections, not another window")
    search.stringValue = "keychain"; try act(search)
    try check(!provider.isHiddenOrHasHiddenAncestor && voice.isHiddenOrHasHiddenAncestor, "provider search exposes the same inline form")
    settings.show(page: .general); settings.show(page: .companion)
    try check(created == 1 && name.stringValue == "Unsaved draft", "navigation reuses the form without resetting drafts")
    try check(source.refreshes == 3, "search and navigation do not repeatedly refresh provider catalogs")
    try check(SettingsCatalog.results("voice speed").map(\.id) == [.voice], "voice controls are searchable without credential values")

    if let folder = ProcessInfo.processInfo.environment["SYNCR_SETTINGS_PREVIEW_FOLDER"] {
        try FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
        for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            settings.show(page: .companion)
            window.appearance = NSAppearance(named: appearance)
            window.setContentSize(NSSize(width: 1060, height: 900))
            content.layoutSubtreeIfNeeded(); window.displayIfNeeded()
            if let bitmap = content.bitmapImageRepForCachingDisplay(in: content.bounds) {
                content.cacheDisplay(in: content.bounds, to: bitmap)
                try bitmap.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: folder).appendingPathComponent("companion-\(name).png"))
            }
            let readerSave = try button("Save readers", in: view)
            _ = readerSave.scrollToVisible(readerSave.bounds)
            content.layoutSubtreeIfNeeded(); window.displayIfNeeded()
            if let bitmap = content.bitmapImageRepForCachingDisplay(in: content.bounds) {
                content.cacheDisplay(in: content.bounds, to: bitmap)
                try bitmap.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: folder).appendingPathComponent("companion-readers-\(name).png"))
            }
        }
    }
    source.readers = [Reader(id: "only-reader", name: "Only reader", description: "New library instructions")]
    key.stringValue = "unsaved-key-placeholder"
    view.reloadFromSource()
    let onlyReader = try find(NSTextField.self, "Reader name only-reader", in: view)
    let removeOnly = try find(NSButton.self, "Remove reader only-reader", in: view)
    try check(onlyReader.stringValue == "Only reader" && key.stringValue.isEmpty && !removeOnly.isEnabled,
              "library reload discards old drafts, clears key input and prevents removing the last reader")
    key.stringValue = "unsaved-key-placeholder"
    window.close()
    try check(key.stringValue.isEmpty, "closing Settings clears unsaved secure input")
    print("Inline Companion settings: OK (single window, models/provider, secure input, limits, pictures, voice, reader drafts/save/failure, search)")
}
