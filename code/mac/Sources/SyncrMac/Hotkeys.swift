import AppKit

/// Layout-aware character plus explicit modifiers. Caps Lock, Fn and numeric-pad flags are not part
/// of a binding. Events use the layout's unmodified character, keeping Shift explicit.
struct KeyBinding: Codable, Hashable, Sendable {
    let key: String
    let modifiers: UInt
    static let allowedModifiers: NSEvent.ModifierFlags = [.control, .option, .shift, .command]
    var flags: NSEvent.ModifierFlags { NSEvent.ModifierFlags(rawValue: modifiers) }

    init(_ key: String, _ flags: NSEvent.ModifierFlags = .command) {
        self.key = key.lowercased()
        modifiers = flags.intersection(Self.allowedModifiers).rawValue
    }

    init?(event: NSEvent) {
        guard event.type == .keyDown,
              let key = event.charactersIgnoringModifiers, !key.isEmpty else { return nil }
        // Applying no modifiers handles Option-generated characters and Control control codes.
        let base = event.characters(byApplyingModifiers: []) ?? key
        self.init(base, event.modifierFlags)
    }

    static func function(_ number: Int) -> String { String(UnicodeScalar(NSF1FunctionKey + number - 1)!) }
    var functionNumber: Int? {
        guard let value = key.unicodeScalars.first?.value, key.unicodeScalars.count == 1,
              value >= NSF1FunctionKey, value <= NSF35FunctionKey else { return nil }
        return Int(value) - NSF1FunctionKey + 1
    }
    var keycap: String {
        if let number = functionNumber { return "F\(number)" }
        switch key {
        case "\r", "\n": return "Return"
        case "\t": return "Tab"
        case " ": return "Space"
        case "\u{7f}", "\u{8}": return "Delete"
        case "\u{1b}": return "Esc"
        case String(UnicodeScalar(NSLeftArrowFunctionKey)!): return "←"
        case String(UnicodeScalar(NSRightArrowFunctionKey)!): return "→"
        case String(UnicodeScalar(NSUpArrowFunctionKey)!): return "↑"
        case String(UnicodeScalar(NSDownArrowFunctionKey)!): return "↓"
        case String(UnicodeScalar(NSHomeFunctionKey)!): return "Home"
        case String(UnicodeScalar(NSEndFunctionKey)!): return "End"
        case String(UnicodeScalar(NSPageUpFunctionKey)!): return "PgUp"
        case String(UnicodeScalar(NSPageDownFunctionKey)!): return "PgDn"
        case String(UnicodeScalar(NSDeleteFunctionKey)!): return "Del ⌦"
        case String(UnicodeScalar(NSHelpFunctionKey)!): return "Help"
        default: return key.uppercased()
        }
    }
    var keycaps: [String] {
        var result: [String] = []
        if flags.contains(.control) { result.append("Ctrl") }
        if flags.contains(.option) { result.append("Opt") }
        if flags.contains(.shift) { result.append("Shift") }
        if flags.contains(.command) { result.append("⌘") }
        return result + [keycap]
    }
    var display: String { keycaps.joined(separator: " + ") }
    var compact: String {
        (flags.contains(.control) ? "⌃" : "") + (flags.contains(.option) ? "⌥" : "") +
        (flags.contains(.shift) ? "⇧" : "") + (flags.contains(.command) ? "⌘" : "") + keycap
    }

    /// Permit modifier chords and bare F-keys, not letters or navigation keys that steal typing.
    var validationError: String? {
        guard key.count == 1, key == key.lowercased(), modifiers & ~Self.allowedModifiers.rawValue == 0 else { return "Use a single key with modifiers." }
        if key == "\u{1b}" { return "Escape is reserved for cancelling." }
        if functionNumber == nil && flags.intersection([.command, .control, .option]).isEmpty {
            return "Include Ctrl, Option or Command so typing and navigation keep working."
        }
        if flags.contains(.command) && key == "q" { return "This shortcut is reserved for quitting, logging out or locking your Mac." }
        if flags == .command && ["h", "m", "x", "c", "v", "a", "\t", " "].contains(key) {
            return "This shortcut is reserved for macOS or standard text editing."
        }
        if flags == [.command, .option] && ["h", "m", "\u{1b}"].contains(key) { return "This shortcut is reserved for macOS." }
        if flags.contains(.command) && ["\t", " "].contains(key) { return "This shortcut is reserved for macOS." }
        if flags == [.command, .shift] && ["3", "4", "5"].contains(key) { return "This shortcut is reserved for screenshots." }
        return nil
    }

    static func defaultBinding(for command: AppCommand) -> KeyBinding? {
        switch command {
        case .settings: KeyBinding(",")
        case .commandPalette: KeyBinding("p")
        case .askSyncr: KeyBinding("j")
        case .undo: KeyBinding("z")
        case .redo: KeyBinding("z", [.command, .shift])
        case .bold: KeyBinding("b")
        case .italic: KeyBinding("i")
        case .insertLink: KeyBinding("k")
        case .toggleTask: KeyBinding("\r")
        case .findInNote: KeyBinding("f")
        case .searchNotes: KeyBinding("f", [.command, .shift])
        case .dailyNote: KeyBinding("d")
        case .toggleBacklinks: KeyBinding("b", [.command, .option])
        case .documentView: KeyBinding("0", [.command, .option])
        case .newNote: KeyBinding("n")
        case .newNotebook: KeyBinding("n", [.command, .shift])
        case .quickSwitcher: KeyBinding("o")
        case .renameNote: KeyBinding(function(2), [])
        case .deleteNote: KeyBinding("\u{7f}")
        case .closeTab: KeyBinding("w")
        case .nextTab: KeyBinding("]", [.command, .shift])
        case .previousTab: KeyBinding("[", [.command, .shift])
        case .toggleSidebar: KeyBinding("\\")
        case .zoomIn: KeyBinding("=", [.command, .shift])
        case .zoomOut: KeyBinding("-")
        case .fit: KeyBinding("0")
        case .importExport: KeyBinding("i", [.command, .shift])
        case .chooseFolder: KeyBinding("o", [.command, .shift])
        default: nil
        }
    }
}

@MainActor final class HotkeyStore {
    struct File: Codable { var version = 1; var overrides: [String: [KeyBinding]] }
    private let url: URL
    private var overrides: [String: [KeyBinding]] = [:]
    private(set) var loadWarning: String?
    var onChange: (() -> Void)?
    static var commands: [AppCommand] { AppCommand.allCases.filter { !$0.isDebug } }

    init(url: URL? = nil) {
        let folder = ProcessInfo.processInfo.environment["SYNCR_MAC_SETTINGS_FOLDER"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Syncr")
        self.url = url ?? folder.appendingPathComponent("hotkeys.json")
        guard FileManager.default.fileExists(atPath: self.url.path) else { return }
        do {
            let file = try JSONDecoder().decode(File.self, from: Data(contentsOf: self.url))
            guard file.version == 1 else { throw BindingError.message("Unsupported hotkey file version.") }
            // Unknown future commands can be retained. Validate known commands as a single transaction.
            for (raw, values) in file.overrides {
                guard let command = AppCommand(rawValue: raw), !command.isDebug else { continue }
                guard values.count <= 1, values.allSatisfy({ $0.validationError == nil }) else {
                    throw BindingError.message("Invalid saved shortcut for \(command.title).")
                }
            }
            overrides = file.overrides
            var seen: Set<KeyBinding> = []
            for command in Self.commands {
                if let value = binding(for: command), !seen.insert(value).inserted {
                    throw BindingError.message("Saved shortcuts contain a conflict.")
                }
            }
        } catch {
            overrides = [:]
            loadWarning = "Could not load saved hotkeys. Defaults are in use. \(error.localizedDescription)"
        }
    }
    enum BindingError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
    }
    func binding(for command: AppCommand) -> KeyBinding? {
        if let values = overrides[command.rawValue] { return values.first }
        return KeyBinding.defaultBinding(for: command)
    }
    func isCustomized(_ command: AppCommand) -> Bool { overrides[command.rawValue] != nil }
    func conflict(for binding: KeyBinding, excluding command: AppCommand) -> AppCommand? {
        Self.commands.first { $0 != command && self.binding(for: $0) == binding }
    }
    func command(for event: NSEvent) -> AppCommand? {
        guard let binding = KeyBinding(event: event) else { return nil }
        return Self.commands.first { self.binding(for: $0) == binding }
    }
    func set(_ binding: KeyBinding?, for command: AppCommand) throws {
        if let binding {
            if let error = binding.validationError { throw BindingError.message(error) }
            if let other = conflict(for: binding, excluding: command) {
                throw BindingError.message("Already assigned to “\(other.title)”. Clear or rebind that command first.")
            }
        }
        var next = overrides
        next[command.rawValue] = binding.map { [$0] } ?? []
        try save(next)
    }
    func reset(_ command: AppCommand) throws {
        if let binding = KeyBinding.defaultBinding(for: command), let other = conflict(for: binding, excluding: command) {
            throw BindingError.message("The default is now assigned to “\(other.title)”. Clear or rebind it first.")
        }
        var next = overrides
        next.removeValue(forKey: command.rawValue)
        try save(next)
    }
    func resetAll() throws { try save([:]) }
    private func save(_ next: [String: [KeyBinding]]) throws {
        let data = try JSONEncoder().encode(File(overrides: next))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        overrides = next
        loadWarning = nil
        onChange?()
    }
    func hint(_ command: AppCommand, title: String? = nil) -> String {
        let title = title ?? command.title
        return binding(for: command).map { "\(title) (\($0.compact))" } ?? title
    }
}
