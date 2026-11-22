import AppKit
import Foundation

/// Reading view (rework-v1 M): nothing the owner does changes the text, task boxes still flip, the choice outlives the note and the app.
@MainActor func readingViewChecks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }

    // The editor: every way to change the text is refused; a task box still flips; links still open.
    let original = "# Title\n\n- [ ] milk\n\nSee [[Other]] and https://example.com"
    let memory = MemoryTextDocument(original)
    let editor = DocumentEditor()
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled], backing: .buffered, defer: true)
    let host = NSView(frame: CGRect(x: 0, y: 0, width: 1000, height: 800)); window.contentView = host
    editor.frame = host.bounds; host.addSubview(editor)
    editor.show(document: memory, title: "T", width: 1600)
    let view = editor.textView
    window.makeFirstResponder(view)
    try check(view.isEditable && editor.title.isEditable, "an ordinary note is editable")
    editor.reading = true
    try check(!view.isEditable && !editor.title.isEditable, "reading: no caret, no editing")
    try check(window.firstResponder !== view, "reading: the text view lets go of focus")
    view.setSelectedRange(NSRange(location: original.utf16.count, length: 0))
    view.insertText("typed", replacementRange: view.selectedRange())
    view.insertText("", replacementRange: NSRange(location: 0, length: 3))
    editor.debugSetText("scripted")
    editor.shortcut("bold"); editor.shortcut("task"); editor.shortcut("link")
    editor.insertSlash(SlashCatalog.builtIn[0])
    _ = editor.textView(view, doCommandBy: #selector(NSResponder.insertNewline(_:)))
    _ = editor.textView(view, doCommandBy: #selector(NSResponder.deleteBackward(_:)))
    let board = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString)); board.clearContents(); board.setString("pasted", forType: .string)
    view.pasteboard = board; view.selectAll(nil); view.paste(nil)
    view.onHistory?(false); view.onHistory?(true)
    var moved = false
    editor.onMove = { _, _ in moved = true }
    editor.title.onDrag?(30, 30)
    editor.flush()
    try check(memory.text == original && view.string == original && !moved, "reading: typing, paste, shortcuts, slash, undo and drag change nothing")

    // A task box still toggles; nothing else does.
    let boxLine = (original as NSString).range(of: "- [ ] milk")
    editor.debugSetText(original) // refused: the text above is still the stored one
    view.setSelectedRange(NSRange(location: 0, length: 0))
    // Click the box through the same path a mouse takes.
    if let manager = view.layoutManager, let container = view.textContainer {
        let glyph = manager.glyphIndexForCharacter(at: boxLine.location)
        let rect = manager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        let at = view.convert(CGPoint(x: rect.minX + 6, y: rect.midY), to: nil)
        if let down = NSEvent.mouseEvent(with: .leftMouseDown, location: at, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1) { view.mouseDown(with: down) }
    }
    try check(memory.text == original.replacingOccurrences(of: "- [ ] milk", with: "- [x] milk"), "reading: a task box still toggles (got \(memory.text.debugDescription))")
    memory.text = original

    // Back to editing: everything works again.
    editor.reading = false
    try check(view.isEditable, "editing again")
    editor.debugSetText("typed again")
    try check(memory.text == "typed again", "editing again: text changes")

    // A phone note (not writable) stays unwritable whatever the mode.
    let phone = MemoryTextDocument("from phone"); phone.writable = false
    editor.show(document: phone, title: "P", width: 1600); editor.reading = true; editor.reading = false
    try check(!view.isEditable, "a read-only note stays read-only after leaving reading")

    // The choice is kept per note, in the preferences file, and outlives the window.
    let prefsURL = dir.appendingPathComponent("preferences.json")
    let prefs = AppPreferences(url: prefsURL)
    let workspace = Workspace(model: LibraryModel(folder: dir.appendingPathComponent("lib")))
    workspace.preferences = prefs
    try check(!workspace.isReading("a") && workspace.setReading("a", true) && workspace.isReading("a") && !workspace.isReading("b"), "reading is per note")
    let reopened = AppPreferences(url: prefsURL)
    try check(reopened.values.readingNotes == ["a"], "the mode survives closing and reopening")
    workspace.setReading("a", false)
    try check(AppPreferences(url: prefsURL).values.readingNotes.isEmpty, "back to editing is kept too")
    try check(!(try String(contentsOf: prefsURL, encoding: .utf8)).contains("\"a\""), "nothing about the mode lingers")

    // Commands: Cmd-E is listed with a note open; the changing ones go while reading.
    try check(AppCommand.toggleReading.shortcut == "⌘E", "Cmd-E switches")
    let changing = AppCommand.changesNote
    try check(changing.isSubset(of: Set(AppCommand.allCases)) && changing.contains(.undo) && changing.contains(.freeText) && !changing.contains(.toggleReading), "the commands refused while reading")

    // The control at the right of the tab row.
    let tabs = TabBarView(frame: CGRect(x: 0, y: 0, width: 900, height: 32))
    try check(tabs.debugModeTitle == nil, "no note, no control")
    tabs.showMode(reading: false)
    try check(tabs.debugModeTitle == "Editing", "the control says Editing")
    var clicks = 0
    tabs.onToggleReading = { clicks += 1 }
    tabs.debugClickMode()
    tabs.showMode(reading: true)
    try check(tabs.debugModeTitle == "Reading" && clicks == 1, "the control says Reading and a click asks to switch")
    print("Reading view checks: OK (no edit by typing, paste, shortcut, slash, undo or drag; task box toggles; kept per note across reopen; Cmd-E; tab-row control)")
}
