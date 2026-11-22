import AppKit

@MainActor enum MainMenu {
    static func build(target: MainWindowController) -> NSMenu {
        let bar = NSMenu()
        func add(_ title: String, _ items: [NSMenuItem]) {
            let menu = NSMenu(title: title)
            items.forEach(menu.addItem)
            let host = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            host.submenu = menu; bar.addItem(host)
        }
        func command(_ c: AppCommand, title: String? = nil) -> NSMenuItem {
            let binding = target.hotkeys.binding(for: c)
            let item = NSMenuItem(title: title ?? c.title, action: #selector(MainWindowController.runMenu(_:)), keyEquivalent: binding?.key ?? "")
            item.keyEquivalentModifierMask = binding?.flags ?? []
            item.target = target; item.representedObject = c.rawValue
            return item
        }
        func plain(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
            NSMenuItem(title: title, action: action, keyEquivalent: key)
        }
        add("Syncr", [command(.settings), command(.hotkeys), .separator(), plain("Quit Syncr", #selector(NSApplication.terminate(_:)), "q")])
        add("File", [
            command(.newNote), command(.newNotebook), command(.quickSwitcher), .separator(),
            command(.renameNote), command(.deleteNote), command(.closeTab), .separator(),
            command(.restoreNote), command(.dailyNote), .separator(),
            command(.importMarkdown), command(.exportMarkdown), command(.importExport), command(.chooseFolder)
        ])
        add("Edit", [
            command(.undo), command(.redo), .separator(),
            plain("Cut", #selector(NSText.cut(_:)), "x"), plain("Copy", #selector(NSText.copy(_:)), "c"),
            plain("Paste", #selector(NSText.paste(_:)), "v"), plain("Select All", #selector(NSText.selectAll(_:)), "a"), .separator(),
            command(.bold), command(.italic), command(.insertLink), command(.toggleTask), command(.findInNote), command(.searchNotes)
        ])
        add("View", [
            command(.toggleSidebar), command(.commandPalette), .separator(),
            command(.showLeftSidebar), command(.hideLeftSidebar), .separator(),
            command(.nextTab), command(.previousTab), .separator(),
            command(.zoomIn), command(.zoomOut), command(.fit), command(.fitPageWidth), command(.fitPageHeight), command(.documentView), .separator(),
            command(.toggleThumbnails), command(.layoutSingle), command(.layoutDouble), command(.layoutCover), command(.toggleFlip), command(.nextPage), command(.previousPage), command(.addPage)
        ])
        add("Companion", [command(.askSyncr), command(.drawSelection), command(.askAllReaders), .separator(), command(.undoCompanion), command(.redoCompanion), .separator(), command(.lastCompanionRequest), command(.companionSettings)])
        add("Phone", [command(.phoneLink), command(.followPhone), .separator(), command(.forgetPhone)])
        add("Window", [plain("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m")])
        return bar
    }
}
