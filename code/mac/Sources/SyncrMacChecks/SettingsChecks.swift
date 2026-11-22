import AppKit
import Foundation

@MainActor private final class SettingsTestDocument: TextDocument {
    var text = "# A heading\nA quiet paragraph."
    var writable = true
    var writes = 0
    func replace(with text: String) { writes += 1; self.text = text }
    func flush() { writes += 1 }
    func history(redo: Bool) { writes += 1 }
}

@MainActor func settingsChecks() throws {
    try marginLayoutChecks()
    try pagesLayoutChecks()
    for width: CGFloat in [1000, 3000, 5000] {
        let left = CompanionMarginLayout.centeredLeft(columnLeft: 100, columnRight: 1500, visibleWidth: width)
        precondition(abs((800 - left) - width / 2) < 0.001, "Text stays centered at every viewport width")
    }
    let fm = FileManager.default
    let root = fm.temporaryDirectory.appendingPathComponent("syncr-settings-checks-\(UUID().uuidString)")
    try fm.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? fm.removeItem(at: root) }
    let url = root.appendingPathComponent("prefs.json")
    let preferences = AppPreferences(url: url)
    try check(preferences.values == AppPreferenceValues(), "missing preferences use safe defaults")
    try check(preferences.values.naturalScroll, "natural canvas scrolling is the default")
    try check(AppPreferences.scrollMultiplier(natural: true, systemInverted: true) == -1 &&
              AppPreferences.scrollMultiplier(natural: true, systemInverted: false) == 1 &&
              AppPreferences.scrollMultiplier(natural: false, systemInverted: true) == 1 &&
              AppPreferences.scrollMultiplier(natural: false, systemInverted: false) == -1,
              "canvas scrolling direction is independent of the Mac system setting")
    let viewport = Viewport(left: 500, top: 500, zoom: 2)
    let pixels = CGSize(width: 960, height: 600)
    let visible = viewport.visibleSize(pixels)
    for natural in [false, true] {
        for systemInverted in [false, true] {
            // The same physical gesture arrives with opposite OS deltas depending
            // on the system setting. Natural mode must move content with it on BOTH axes.
            let raw: Float = systemInverted ? 12 : -12
            let factor = AppPreferences.scrollMultiplier(natural: natural, systemInverted: systemInverted)
            let offset = viewport.scrollOffset(x: raw * factor, y: raw * factor, viewSize: pixels)
            let contentMotion = -offset * SIMD2(Float(pixels.width), Float(pixels.height)) / visible
            let expected: Float = natural ? 12 : -12
            try check(abs(contentMotion.x - expected) < 0.001 && abs(contentMotion.y - expected) < 0.001,
                      "horizontal and vertical scrolling follow the same preference for both system directions")
        }
    }
    let horizontal = viewport.scrollOffset(x: 12, y: 0, viewSize: pixels)
    let vertical = viewport.scrollOffset(x: 0, y: 12, viewSize: pixels)
    try check(horizontal.x > 0 && horizontal.y == 0 && vertical.x == 0 && vertical.y > 0,
              "single-axis scrolling does not move the other axis")
    let zoomed = Viewport(left: 500, top: 500, zoom: 4).scrollOffset(x: 12, y: 0, viewSize: pixels)
    try check(abs(zoomed.x - horizontal.x / 2) < 0.001, "scroll distance scales with zoom without changing direction")
    // Project through the actual backing-pixel viewport, then return to screen points.
    // A 12-point gesture must move both text and ink 12 points on 1× and Retina screens.
    for backingScale in [Float(1), Float(2)] {
        let drawable = CGSize(width: pixels.width * CGFloat(backingScale), height: pixels.height * CGFloat(backingScale))
        let visible = viewport.visibleSize(drawable)
        let travelled = horizontal.x * Float(drawable.width) / visible.x / backingScale
        try check(abs(travelled - 12) < 0.001, "scroll tracks logical points independently of display backing scale")
    }
    try check(!fm.fileExists(atPath: url.path), "reading defaults does not write preferences")
    try preferences.update {
        $0.appearance = .dark; $0.reduceMotion = true; $0.textSize = .large
        $0.lineSpacing = .relaxed; $0.spellCheck = false; $0.restoreSession = false; $0.naturalScroll = false
    }
    try check(AppPreferences(url: url).values == preferences.values, "all preferences survive restart")
    try check(preferences.reducedMotion, "explicit reduce motion is always respected")

    try Data(#"{"version":1,"values":{"appearance":"cosmic","textSize":999,"lineSpacing":"wild","spellCheck":"wrong"}}"#.utf8).write(to: url)
    try check(AppPreferences(url: url).values == AppPreferenceValues(), "unknown values and missing fields fall back safely")
    try Data("not json".utf8).write(to: url)
    let corrupt = AppPreferences(url: url)
    try check(corrupt.loadWarning != nil && corrupt.values == AppPreferenceValues(), "corrupt preferences show warning, not crash")
    let futureData = Data(#"{"version":99,"values":{}}"#.utf8)
    try futureData.write(to: url)
    let future = AppPreferences(url: url)
    try expectError({ try future.update { $0.appearance = .light } }, "newer preference version refuses downgrade write")
    try check(try Data(contentsOf: url) == futureData, "future-version file preserved")

    let blocker = root.appendingPathComponent("blocker")
    try Data([0]).write(to: blocker)
    let blocked = AppPreferences(url: blocker.appendingPathComponent("prefs.json"))
    try expectError({ try blocked.update { $0.spellCheck = false } }, "failed atomic write surfaces an error")
    try check(blocked.values.spellCheck, "failed write does not change effective preferences")

    try check(Set(SettingsCatalog.items.map(\.id)).count == SettingsCatalog.items.count, "setting IDs are unique")
    for category in SettingsCategory.allCases {
        try check(SettingsCatalog.items.contains { $0.category == category }, "every category has searchable settings")
    }
    try check(SettingsCatalog.results("dark theme").map(\.id) == [.appearance], "multiword theme aliases")
    try check(SettingsCatalog.results("FONT size").map(\.id) == [.textSize], "case-insensitive font alias")
    try check(SettingsCatalog.results("natural scroll").map(\.id) == [.naturalScroll], "canvas scroll option is searchable")
    try check(SettingsCatalog.results("daily tokens").map(\.id) == [.limits], "nested companion settings are discoverable")
    try check(SettingsCatalog.results("keychain").contains { $0.id == .provider }, "keychain routes to secure provider configuration")
    try check(SettingsCatalog.results("\n \t").count == SettingsCatalog.items.count, "whitespace query shows all settings")
    try check(SettingsCatalog.results("nonexistent-giraffe").isEmpty, "unknown query is empty")
    try check(SettingsCatalog.matches("resume café", in: "Résumé CAFÉ"), "search folds diacritics")

    // Never mutate the user's settings when checks run without an isolated folder.
    if ProcessInfo.processInfo.environment["SYNCR_MAC_SETTINGS_FOLDER"] != nil {
        let shared = AppPreferences.shared
        let before = shared.values
        defer { try? shared.update { $0 = before } }
        try shared.update { $0 = AppPreferenceValues() }
        defer { NSApp.appearance = nil }
        try shared.update { $0.appearance = .light }; shared.applyAppearance()
        try check(NSApp.appearance?.name == .aqua, "Light overrides system appearance")
        try shared.update { $0.appearance = .dark }; shared.applyAppearance()
        try check(NSApp.appearance?.name == .darkAqua, "Dark overrides system appearance")
        try shared.update { $0.appearance = .system }; shared.applyAppearance()
        try check(NSApp.appearance == nil, "System clears the application override")
        let document = SettingsTestDocument(), editor = DocumentEditor()
        editor.show(document: document, title: "Unchanged", width: 1600)
        let selection = NSRange(location: 4, length: 3)
        editor.textView.setSelectedRange(selection)
        let original = document.text
        try shared.update { $0.textSize = .large; $0.lineSpacing = .relaxed; $0.spellCheck = false }
        let font = editor.textView.textStorage?.attribute(.font, at: editor.textView.string.utf16.count - 2, effectiveRange: nil) as? NSFont
        try check(abs((font?.pointSize ?? 0) - 55.2) < 0.001, "open editor receives text-size change immediately")
        let paragraph = editor.textView.textStorage?.attribute(.paragraphStyle, at: editor.textView.string.utf16.count - 2, effectiveRange: nil) as? NSParagraphStyle
        try check(abs((paragraph?.lineSpacing ?? 0) - 33.12) < 0.001, "open editor receives line-spacing change")
        try check(!editor.textView.isContinuousSpellCheckingEnabled, "spellcheck change applied")
        try check(editor.textView.selectedRange() == selection, "preference change preserves selection")
        try check(document.text == original && document.writes == 0, "presentation changes do not write note content or operations")
    }
    try inlineCompanionSettingsChecks()
    print("Settings checks: OK (persistence, fallback, failed writes, search, two-axis natural scrolling, live editor presentation)")
}
