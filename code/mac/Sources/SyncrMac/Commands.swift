import Foundation

/// Every app command the palette lists. Execution lives with the window; availability and
/// filtering live here so they can be checked without a window.
enum AppCommand: String, CaseIterable, Sendable {
    case settings, hotkeys, commandPalette
    case showLeftSidebar, hideLeftSidebar
    case newNote, newCanvas, newNotebook, quickSwitcher, renameNote, moveNote, deleteNote, restoreNote
    case closeTab, nextTab, previousTab, toggleSidebar, zoomIn, zoomOut, fit
    case importExport, chooseFolder
    case askSyncr, drawSelection, askAllReaders, companionSettings, lastCompanionRequest, undoCompanion, redoCompanion
    case undo, redo, browseTags, outline
    case bold, italic, insertLink, toggleTask, findInNote, searchNotes, dailyNote, documentView
    case importMarkdown, exportMarkdown, slashMenu, freeText
    /// Pages of a Note: the thumbnail sidebar, adding a page, the fit commands, layout and flip, and moving between pages.
    case toggleThumbnails, addPage, fitPage, fitPageWidth, fitPageHeight
    case layoutSingle, layoutDouble, layoutCover, toggleFlip, nextPage, previousPage
    /// The phone link: on/off, Follow phone, and forgetting a paired phone.
    case phoneLink, followPhone, forgetPhone
    /// Debug-only (launch flag): in-process live-ink replays. Listed only when `Workspace.debugCommands` is set.
    case replayTestStroke, replayHighlighter, replayCancelled, replayViewRelative, simulateDisconnect

    var title: String {
        switch self {
        case .settings: "Settings…"
        case .hotkeys: "Hotkeys…"
        case .commandPalette: "Command Palette…"
        case .showLeftSidebar: "Show Left Sidebar"
        case .hideLeftSidebar: "Hide Left Sidebar"
        case .askSyncr: "Ask Syncr"
        case .drawSelection: "Draw This Selection (Experimental)"
        case .askAllReaders: "Ask All Readers"
        case .companionSettings: "Companion Settings…"
        case .lastCompanionRequest: "Last Companion Request…"
        case .undoCompanion: "Undo Companion"
        case .redoCompanion: "Redo Companion"
        case .browseTags: "Browse Tags…"
        case .outline: "Go to Heading…"
        case .undo: "Undo Text or Ink"
        case .redo: "Redo Text or Ink"
        case .bold: "Bold"
        case .italic: "Italic"
        case .insertLink: "Insert Link"
        case .toggleTask: "Toggle Task"
        case .findInNote: "Find in Note"
        case .searchNotes: "Search All Notes"
        case .dailyNote: "Open Daily Note"
        case .documentView: "Document View"
        case .importMarkdown: "Import Markdown File or Vault…"
        case .exportMarkdown: "Export Markdown…"
        case .slashMenu: "Insert Slash Command…"
        case .freeText: "New Free Text Block"
        case .toggleThumbnails: "Toggle Page Thumbnails"
        case .addPage: "Add Page"
        case .fitPage: "Fit Whole Page"
        case .fitPageWidth: "Fit Page Width"
        case .fitPageHeight: "Fit Page Height"
        case .layoutSingle: "Pages: Single"
        case .layoutDouble: "Pages: Double"
        case .layoutCover: "Pages: Cover"
        case .toggleFlip: "Pages: Scroll or Flip"
        case .nextPage: "Next Page"
        case .previousPage: "Previous Page"
        case .newNote: "New Note…"
        case .newCanvas: "New canvas…"
        case .newNotebook: "New Notebook"
        case .quickSwitcher: "Open Note…"
        case .renameNote: "Rename Note"
        case .moveNote: "Move Note to Notebook…"
        case .deleteNote: "Delete Note"
        case .restoreNote: "Restore Deleted Note…"
        case .closeTab: "Close Tab"
        case .nextTab: "Next Tab"
        case .previousTab: "Previous Tab"
        case .toggleSidebar: "Toggle Left Sidebar"
        case .zoomIn: "Zoom In"
        case .zoomOut: "Zoom Out"
        case .fit: "Fit to Window"
        case .importExport: "Import Phone Export…"
        case .chooseFolder: "Choose Library Folder…"
        case .phoneLink: "Phone Link On/Off"
        case .followPhone: "Follow Phone On/Off"
        case .forgetPhone: "Forget Phone…"
        case .replayTestStroke: "Replay Test Stroke"
        case .replayHighlighter: "Replay Test Highlighter Stroke"
        case .replayCancelled: "Replay Cancelled Stroke"
        case .replayViewRelative: "Replay View-Relative Stroke"
        case .simulateDisconnect: "Simulate Live Ink Disconnect"
        }
    }

    /// Default hint for model-only consumers. Live UI uses HotkeyStore's effective binding.
    var shortcut: String { KeyBinding.defaultBinding(for: self)?.compact ?? "" }

    var isDebug: Bool {
        switch self {
        case .replayTestStroke, .replayHighlighter, .replayCancelled, .replayViewRelative, .simulateDisconnect: true
        default: false
        }
    }

    var category: String {
        switch self {
        case .settings, .hotkeys, .commandPalette: "Application"
        case .showLeftSidebar, .hideLeftSidebar,
             .toggleSidebar, .nextTab, .previousTab, .zoomIn, .zoomOut, .fit, .documentView,
             .toggleThumbnails, .fitPage, .fitPageWidth, .fitPageHeight, .layoutSingle, .layoutDouble, .layoutCover, .toggleFlip, .nextPage, .previousPage: "Workspace"
        case .askSyncr, .drawSelection, .askAllReaders, .companionSettings, .lastCompanionRequest, .undoCompanion, .redoCompanion: "Companion"
        case .phoneLink, .followPhone, .forgetPhone: "Phone"
        case .undo, .redo, .bold, .italic, .insertLink, .toggleTask, .slashMenu, .freeText, .addPage: "Editor"
        default: "Notes"
        }
    }

    /// Available commands matching the query, best match first, ties in declaration order.
    static func matching(_ query: String, available: Set<AppCommand>) -> [AppCommand] {
        allCases.enumerated().compactMap { index, command -> (Int, Int, AppCommand)? in
            guard available.contains(command), let score = Matching.score(query, in: command.title) else { return nil }
            return (score, index, command)
        }.sorted { ($0.0, $0.1) < ($1.0, $1.1) }.map { $0.2 }
    }
}
