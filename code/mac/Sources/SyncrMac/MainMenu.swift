import AppKit

@MainActor enum MainMenu {
    private static func fKey(_ code: Int) -> String { String(UnicodeScalar(code)!) }

    static func build(target: MainWindowController) -> NSMenu {
        let bar = NSMenu()
        func add(_ title: String, _ items: [NSMenuItem]) {
            let menu = NSMenu(title: title)
            items.forEach(menu.addItem)
            let host = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            host.submenu = menu
            bar.addItem(host)
        }
        func command(_ c: AppCommand, _ key: String, _ mods: NSEvent.ModifierFlags = .command, title: String? = nil) -> NSMenuItem {
            let item = NSMenuItem(title: title ?? c.title, action: #selector(MainWindowController.runMenu(_:)), keyEquivalent: key)
            item.keyEquivalentModifierMask = mods
            item.target = target
            item.representedObject = c.rawValue
            return item
        }
        func plain(_ title: String, _ action: Selector, _ key: String, _ mods: NSEvent.ModifierFlags = .command) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.keyEquivalentModifierMask = mods
            return item
        }
        let palette = NSMenuItem(title: "Command Palette…", action: #selector(PaletteResponder.openPalette(_:)), keyEquivalent: "p")
        palette.target = PaletteResponder.shared
        PaletteResponder.shared.controller = target
        let sidebarKey = "\\"
        add("Syncr", [command(.companionSettings, ","), .separator(), plain("Quit Syncr", #selector(NSApplication.terminate(_:)), "q")])
        add("File", [
            command(.newNote, "n"), command(.newNotebook, "n", [.command, .shift]),
            command(.quickSwitcher, "o"), .separator(),
            command(.renameNote, fKey(NSF2FunctionKey), []),
            command(.deleteNote, fKey(0x7f), .command), command(.closeTab, "w"), .separator(),
            command(.restoreNote, ""), command(.dailyNote, "d"), .separator(),
            command(.importMarkdown, ""), command(.exportMarkdown, ""),
            command(.importExport, "i", [.command, .shift]), command(.chooseFolder, "o", [.command, .shift])
        ])
        add("Edit", [
            command(.undo, "z"), command(.redo, "z", [.command, .shift]), .separator(),
            plain("Cut", #selector(NSText.cut(_:)), "x"), plain("Copy", #selector(NSText.copy(_:)), "c"),
            plain("Paste", #selector(NSText.paste(_:)), "v"), plain("Select All", #selector(NSText.selectAll(_:)), "a"), .separator(),
            command(.bold, "b"), command(.italic, "i"), command(.insertLink, "k"), command(.toggleTask, "\r"),
            command(.findInNote, "f"), command(.searchNotes, "f", [.command, .shift])
        ])
        add("View", [
            command(.toggleSidebar, sidebarKey), palette, .separator(),
            command(.nextTab, "]", [.command, .shift]), command(.previousTab, "[", [.command, .shift]), .separator(),
            command(.zoomIn, "+"), command(.zoomOut, "-"), command(.fit, "0"), command(.documentView, ""),
            command(.toggleBacklinks, "b", [.command, .option])
        ])
        add("Companion", [command(.askSyncr, "j"), command(.drawSelection, ""), command(.askAllReaders, ""), .separator(), command(.undoCompanion, ""), command(.redoCompanion, ""), .separator(), command(.lastCompanionRequest, ""), command(.companionSettings, "")])
        add("Phone", [command(.phoneLink, ""), command(.followPhone, ""), .separator(), command(.forgetPhone, "")])
        add("Window", [plain("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m")])
        return bar
    }
}

/// Menu target for the palette: it is not a listed command, so it is not in `AppCommand`.
@MainActor final class PaletteResponder: NSObject {
    static let shared = PaletteResponder()
    weak var controller: MainWindowController?
    @objc func openPalette(_ sender: Any?) { controller?.openCommandPalette() }
}
