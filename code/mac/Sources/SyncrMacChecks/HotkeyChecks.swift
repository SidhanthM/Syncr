import AppKit

@MainActor func hotkeyChecks() throws {
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-hotkeys-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: temp) }
    let url = temp.appendingPathComponent("settings/hotkeys.json")
    let store = HotkeyStore(url: url)
    try check(store.loadWarning == nil, "missing hotkey file starts with defaults")
    try check(store.binding(for: .toggleSidebar) == KeyBinding("\\"), "left sidebar default")
    try check(store.binding(for: .settings) == KeyBinding(","), "settings default")
    try check(store.binding(for: .showLeftSidebar) == nil, "show left starts empty")
    try check(!HotkeyStore.commands.contains(where: \.isDebug), "debug commands excluded")
    var defaults: Set<KeyBinding> = []
    for command in HotkeyStore.commands {
        if let binding = store.binding(for: command) {
            try check(binding.validationError == nil, "valid default for \(command.title)")
            try check(defaults.insert(binding).inserted, "unique defaults")
        }
    }
    let custom = KeyBinding("m", [.control, .shift])
    try check(custom.keycaps == ["Ctrl", "Shift", "M"], "individual modifier and key keycaps")
    var changes = 0
    store.onChange = { changes += 1 }
    try store.set(custom, for: .toggleSidebar)
    try check(changes == 1, "one notification after save")
    try check(store.binding(for: .toggleSidebar) == custom && store.isCustomized(.toggleSidebar), "custom binding effective")
    let reopened = HotkeyStore(url: url)
    try check(reopened.binding(for: .toggleSidebar) == custom, "binding survives restart")
    try check(reopened.loadWarning == nil, "valid settings reload")
    try expectError({ try store.set(custom, for: .newNote) }, "duplicate refused")
    try check(changes == 1 && store.binding(for: .newNote) == KeyBinding("n"), "conflict leaves existing bindings intact")
    try store.set(nil, for: .toggleSidebar)
    try check(store.binding(for: .toggleSidebar) == nil && store.isCustomized(.toggleSidebar), "cleared means disabled, not default")
    try check(HotkeyStore(url: url).binding(for: .toggleSidebar) == nil, "disabled survives restart")
    try store.reset(.toggleSidebar)
    try check(store.binding(for: .toggleSidebar) == KeyBinding("\\") && !store.isCustomized(.toggleSidebar), "per-command default restored")
    try store.set(nil, for: .toggleSidebar)
    try store.set(KeyBinding("\\"), for: .showLeftSidebar)
    try expectError({ try store.reset(.toggleSidebar) }, "reset must not steal another command's binding")
    try store.resetAll()
    try check(!store.isCustomized(.toggleSidebar) && !store.isCustomized(.showLeftSidebar), "restore all discards overrides atomically")
    for binding in [KeyBinding("a", []), KeyBinding("m", .shift), KeyBinding("\t", []), KeyBinding("\u{1b}", .control), KeyBinding("q"), KeyBinding("c"), KeyBinding("m"), KeyBinding(" "), KeyBinding("4", [.command, .shift])] {
        try check(binding.validationError != nil, "reserved/typing key rejected: \(binding.display)")
    }
    try check(KeyBinding("m", [.control, .shift, .capsLock, .function]) == custom, "irrelevant event flags ignored")
    try check(KeyBinding.defaultBinding(for: .zoomIn) == KeyBinding("=", [.command, .shift]), "zoom plus has explicit shift")
    try check(KeyBinding(KeyBinding.function(2), []).keycap == "F2", "function keys displayed")
    let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [.control, .shift], timestamp: 0, windowNumber: 0, context: nil, characters: "M", charactersIgnoringModifiers: "M", isARepeat: false, keyCode: 46)!
    try check(KeyBinding(event: event) == custom, "record Ctrl Shift M")
    try store.set(custom, for: .toggleSidebar)
    try check(store.command(for: event) == .toggleSidebar, "recorded chord dispatches to assigned action")
    let old = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: .command, timestamp: 0, windowNumber: 0, context: nil, characters: "\\", charactersIgnoringModifiers: "\\", isARepeat: false, keyCode: 42)!
    try check(store.command(for: old) == nil, "old shortcut no longer dispatches")
    let shifted = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [.command, .shift], timestamp: 0, windowNumber: 0, context: nil, characters: "}", charactersIgnoringModifiers: "}", isARepeat: false, keyCode: 30)!
    try check(store.command(for: shifted) == .nextTab, "shifted bracket event matches default")
    let plus = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [.command, .shift], timestamp: 0, windowNumber: 0, context: nil, characters: "+", charactersIgnoringModifiers: "+", isARepeat: false, keyCode: 24)!
    try check(store.command(for: plus) == .zoomIn, "shifted plus event matches default")
    let obstacle = temp.appendingPathComponent("file")
    try Data().write(to: obstacle)
    let failing = HotkeyStore(url: obstacle.appendingPathComponent("hotkeys.json"))
    try expectError({ try failing.set(custom, for: .toggleSidebar) }, "failed save reported")
    try check(failing.binding(for: .toggleSidebar) == KeyBinding("\\"), "failed save keeps live defaults")
    try Data("not JSON".utf8).write(to: url)
    let corrupt = HotkeyStore(url: url)
    try check(corrupt.loadWarning != nil && corrupt.binding(for: .toggleSidebar) == KeyBinding("\\"), "corrupt settings recover with warning")
    let duplicates = HotkeyStore.File(overrides: [AppCommand.toggleSidebar.rawValue: [KeyBinding("n")]])
    try JSONEncoder().encode(duplicates).write(to: url)
    try check(HotkeyStore(url: url).loadWarning != nil, "hand-edited conflicting settings rejected")
    print("Hotkey checks: OK (defaults, keycaps, recording, dispatch, conflicts, reserved keys, clear/reset, persistence, failed saves)")
}
