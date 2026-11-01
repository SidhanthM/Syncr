import AppKit

/// Presentation only: never serialized into a note or shared with the phone.
struct AppPreferenceValues: Codable, Equatable {
    enum Appearance: String, Codable, CaseIterable { case system, light, dark
        var title: String { rawValue.capitalized }
    }
    enum TextSize: Int, Codable, CaseIterable { case small = 85, standard = 100, large = 115, extraLarge = 130
        var title: String { self == .standard ? "100% · Default" : "\(rawValue)%" }
    }
    enum LineSpacing: String, Codable, CaseIterable { case compact, standard, relaxed
        var title: String { self == .standard ? "Default" : rawValue.capitalized }
        var multiplier: CGFloat { switch self { case .compact: 0.2; case .standard: 1.0 / 3.0; case .relaxed: 0.6 } }
    }
    var appearance: Appearance = .system
    var textSize: TextSize = .standard
    var lineSpacing: LineSpacing = .standard
    var reduceMotion = false
    var spellCheck = true
    var restoreSession = true
    var naturalScroll = true

    init() {}
    private enum CodingKeys: CodingKey { case appearance, textSize, lineSpacing, reduceMotion, spellCheck, restoreSession, naturalScroll }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        appearance = (try? c.decode(Appearance.self, forKey: .appearance)) ?? .system
        textSize = (try? c.decode(TextSize.self, forKey: .textSize)) ?? .standard
        lineSpacing = (try? c.decode(LineSpacing.self, forKey: .lineSpacing)) ?? .standard
        reduceMotion = (try? c.decode(Bool.self, forKey: .reduceMotion)) ?? false
        spellCheck = (try? c.decode(Bool.self, forKey: .spellCheck)) ?? true
        restoreSession = (try? c.decode(Bool.self, forKey: .restoreSession)) ?? true
        naturalScroll = (try? c.decode(Bool.self, forKey: .naturalScroll)) ?? true
    }
}

@MainActor final class AppPreferences {
    static let shared = AppPreferences()
    static let didChange = Notification.Name("SyncrPresentationPreferencesChanged")
    private struct File: Codable { var version = 1; var values: AppPreferenceValues }
    private let url: URL
    private(set) var values = AppPreferenceValues()
    private(set) var loadWarning: String?
    private var unsupportedVersion = false

    init(url: URL? = nil) {
        let folder = ProcessInfo.processInfo.environment["SYNCR_MAC_SETTINGS_FOLDER"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Syncr")
        self.url = url ?? folder.appendingPathComponent("preferences.json")
        guard FileManager.default.fileExists(atPath: self.url.path) else { return }
        do {
            let file = try JSONDecoder().decode(File.self, from: Data(contentsOf: self.url))
            guard file.version == 1 else {
                unsupportedVersion = true
                loadWarning = "These preferences were saved by a newer Syncr. Defaults are in use; the file will not be overwritten."
                return
            }
            values = file.values
        } catch { loadWarning = "Preferences could not be read. Defaults are in use; changing a setting will save a new preferences file." }
    }
    var textScale: CGFloat { CGFloat(values.textSize.rawValue) / 100 }
    // Canvas coordinates grow downwards; normalize the OS scroll direction so this
    // preference behaves the same regardless of the Mac's system scroll setting.
    static func scrollMultiplier(natural: Bool, systemInverted: Bool) -> Float {
        (natural == systemInverted) ? -1 : 1
    }
    func canvasScroll(_ event: NSEvent) -> (Float, Float) {
        let factor = Self.scrollMultiplier(natural: values.naturalScroll, systemInverted: event.isDirectionInvertedFromDevice)
        return (Float(event.scrollingDeltaX) * factor, Float(event.scrollingDeltaY) * factor)
    }
    var reducedMotion: Bool { values.reduceMotion || NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    func update(_ change: (inout AppPreferenceValues) -> Void) throws {
        guard !unsupportedVersion else { throw NSError(domain: "SyncrPreferences", code: 1, userInfo: [NSLocalizedDescriptionKey: loadWarning!]) }
        var next = values; change(&next)
        guard next != values else { return }
        let data = try JSONEncoder().encode(File(values: next))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        values = next; loadWarning = nil
        NotificationCenter.default.post(name: Self.didChange, object: self)
    }
    func applyAppearance() {
        NSApp?.appearance = switch values.appearance {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
}
