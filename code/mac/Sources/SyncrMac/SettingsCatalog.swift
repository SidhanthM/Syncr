import Foundation

enum SettingsCategory: String, CaseIterable, Identifiable {
    case general, appearance, editor, hotkeys, companion, library
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var symbol: String {
        switch self {
        case .general: "slider.horizontal.3"
        case .appearance: "paintpalette"
        case .editor: "pencil.line"
        case .hotkeys: "keyboard"
        case .companion: "sparkles"
        case .library: "folder"
        }
    }
}

struct SettingItem: Identifiable {
    enum ID: String { case restoreSession, appearance, reduceMotion, textSize, lineSpacing, spellCheck, naturalScroll, hotkeys, provider, models, readers, limits, libraryFolder, revealLibrary, importNote, importMarkdown }
    let id: ID
    let category: SettingsCategory
    let title: String
    let detail: String
    let keywords: String
    func matches(_ query: String) -> Bool {
        SettingsCatalog.matches(query, in: "\(category.title) \(title) \(detail) \(keywords)")
    }
}

enum SettingsCatalog {
    static let items: [SettingItem] = [
        .init(id: .restoreSession, category: .general, title: "Restore session", detail: "Restore your open tabs and active note when Syncr starts.", keywords: "startup launch session reopen remember"),
        .init(id: .appearance, category: .appearance, title: "Colour mode", detail: "Follow your Mac, or make Syncr always light or dark.", keywords: "theme appearance system light dark color"),
        .init(id: .reduceMotion, category: .appearance, title: "Reduce motion", detail: "Keep sidebar transitions and companion indicators still. Your Mac’s accessibility preference is also respected.", keywords: "animation accessibility pulse movement"),
        .init(id: .textSize, category: .editor, title: "Text size", detail: "Scale the document preview and editor on this Mac. Your note’s text and ink are not changed.", keywords: "font typography zoom size reading"),
        .init(id: .lineSpacing, category: .editor, title: "Line spacing", detail: "Set the spacing between lines to compact, default or relaxed.", keywords: "line height paragraph leading density"),
        .init(id: .spellCheck, category: .editor, title: "Check spelling as you type", detail: "Underline possible spelling mistakes without replacing your words.", keywords: "spellcheck spelling language proofread"),
        .init(id: .naturalScroll, category: .editor, title: "Natural canvas scrolling", detail: "Move the note with your fingers when scrolling. Turn off for the opposite direction. Pinch-to-zoom stays the same.", keywords: "trackpad gestures scroll direction invert pan zoom"),
        .init(id: .hotkeys, category: .hotkeys, title: "Customize hotkeys", detail: "Find any command, record a shortcut or restore its default.", keywords: "keyboard shortcuts bindings keys commands"),
        .init(id: .provider, category: .companion, title: "Provider & sign-in", detail: "Connect your Codex subscription or manage your Claude API key.", keywords: "AI authentication account keychain credentials"),
        .init(id: .models, category: .companion, title: "Reading & asking models", detail: "Choose models for reading along, answering and drawing.", keywords: "AI inference model refresh"),
        .init(id: .readers, category: .companion, title: "Readers", detail: "Configure companion readers and their instructions.", keywords: "AI reader personas descriptions JSON"),
        .init(id: .limits, category: .companion, title: "Daily usage & experiments", detail: "Manage token budgets, request limits and experimental drawings.", keywords: "AI budget tokens requests limits drawings"),
        .init(id: .libraryFolder, category: .library, title: "Library folder", detail: "Choose which library to open. Existing notes are not moved.", keywords: "storage location directory files path"),
        .init(id: .revealLibrary, category: .library, title: "Show library in Finder", detail: "Open your current library folder in Finder.", keywords: "storage location directory files reveal path"),
        .init(id: .importNote, category: .library, title: "Import a phone export", detail: "Bring a saved .syncr export into this library using a validated copy.", keywords: "import backup saved snapshot phone"),
        .init(id: .importMarkdown, category: .library, title: "Import Markdown", detail: "Bring in a Markdown file or a vault of linked notes.", keywords: "import md obsidian files vault"),
    ]
    static func matches(_ query: String, in text: String) -> Bool {
        let normalized = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        return query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .split(whereSeparator: { $0.isWhitespace }).allSatisfy { normalized.contains($0) }
    }
    static func results(_ query: String) -> [SettingItem] { items.filter { $0.matches(query) } }
}
