import Foundation

/// Every app command the palette lists. Execution lives with the window; availability and
/// filtering live here so they can be checked without a window.
enum AppCommand: String, CaseIterable, Sendable {
    case newNote, newNotebook, quickSwitcher, renameNote, moveNote, deleteNote, restoreNote
    case closeTab, nextTab, previousTab, toggleSidebar, zoomIn, zoomOut, fit
    case importExport, chooseFolder
    case askSyncr, drawSelection, askAllReaders, companionSettings, lastCompanionRequest, undoCompanion, redoCompanion
    case undo, redo, browseTags, outline
    case bold, italic, insertLink, toggleTask, findInNote, searchNotes, dailyNote, toggleBacklinks, documentView
    case importMarkdown, exportMarkdown, slashMenu, freeText
    /// The phone link: on/off, Follow phone, and forgetting a paired phone.
    case phoneLink, followPhone, forgetPhone
    /// Debug-only (launch flag): in-process live-ink replays. Listed only when `Workspace.debugCommands` is set.
    case replayTestStroke, replayHighlighter, replayCancelled, replayViewRelative, simulateDisconnect

    var title: String {
        switch self {
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
        case .toggleBacklinks: "Toggle Backlinks and Outline"
        case .documentView: "Document View"
        case .importMarkdown: "Import Markdown File or Vault…"
        case .exportMarkdown: "Export Markdown…"
        case .slashMenu: "Insert Slash Command…"
        case .freeText: "New Free Text Block"
        case .newNote: "New Note"
        case .newNotebook: "New Notebook"
        case .quickSwitcher: "Open Note…"
        case .renameNote: "Rename Note"
        case .moveNote: "Move Note to Notebook…"
        case .deleteNote: "Delete Note"
        case .restoreNote: "Restore Deleted Note…"
        case .closeTab: "Close Tab"
        case .nextTab: "Next Tab"
        case .previousTab: "Previous Tab"
        case .toggleSidebar: "Toggle Sidebar"
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

    var shortcut: String {
        switch self {
        case .askSyncr: "⌘J"
        case .drawSelection, .askAllReaders, .companionSettings, .lastCompanionRequest, .undoCompanion, .redoCompanion: ""
        case .undo: "⌘Z"
        case .redo: "⇧⌘Z"
        case .bold: "⌘B"
        case .italic: "⌘I"
        case .insertLink: "⌘K"
        case .toggleTask: "⌘↩"
        case .findInNote: "⌘F"
        case .searchNotes: "⇧⌘F"
        case .dailyNote: "⌘D"
        case .toggleBacklinks: "⌥⌘B"
        case .browseTags, .outline, .documentView, .importMarkdown, .exportMarkdown, .slashMenu, .freeText: ""
        case .newNote: "⌘N"
        case .newNotebook: "⇧⌘N"
        case .quickSwitcher: "⌘O"
        case .renameNote: "F2"
        case .deleteNote: "⌘⌫"
        case .closeTab: "⌘W"
        case .nextTab: "⇧⌘]"
        case .previousTab: "⇧⌘["
        case .toggleSidebar: "⌘\\"
        case .zoomIn: "⌘+"
        case .zoomOut: "⌘−"
        case .fit: "⌘0"
        case .importExport: "⇧⌘I"
        case .chooseFolder: "⇧⌘O"
        case .moveNote, .restoreNote, .phoneLink, .followPhone, .forgetPhone, .replayTestStroke, .replayHighlighter, .replayCancelled, .replayViewRelative,
             .simulateDisconnect: ""
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
