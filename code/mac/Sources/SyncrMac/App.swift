import AppKit

@main
@MainActor
final class SyncrMacApp: NSObject, NSApplicationDelegate {
    private let model = LibraryModel()
    private lazy var workspace = Workspace(model: model)
    private var controller: MainWindowController!

    static func main() {
        let app = NSApplication.shared
        let delegate = SyncrMacApp()
        app.delegate = delegate
        app.setActivationPolicy(.regular)
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        AppPreferences.shared.applyAppearance()
        controller = MainWindowController(model: model, workspace: workspace)
        NSApplication.shared.mainMenu = MainMenu.build(target: controller)
        if AppPreferences.shared.values.restoreSession { workspace.restoreSession() }
        controller.show()
        controller.debugAutoReplay()
        // Explicit local-file import, using exactly the same copy-only path as File > Import.
        let arguments = CommandLine.arguments
        if arguments.count == 3, arguments[1] == "--import" {
            controller.importAndOpen(URL(fileURLWithPath: arguments[2]))
        }
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        controller?.flushBeforeQuit { ok in DispatchQueue.main.async { sender.reply(toApplicationShouldTerminate: ok) } }
        return .terminateLater
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) { workspace.saveSession(); controller?.quit() }
}
