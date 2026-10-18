import AppKit
import MetalKit
import SyncrCore
import UniformTypeIdentifiers

@main
@MainActor
final class SyncrMacApp: NSObject, NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate, MTKViewDelegate {
    private let model = LibraryModel()
    private var window: NSWindow!
    private var table: NSTableView!
    private var canvas: CanvasView!
    private var renderer: MeshRenderer?
    private var viewport = Viewport()
    private var titleLabel: NSTextField!

    static func main() {
        let app = NSApplication.shared
        let delegate = SyncrMacApp()
        app.delegate = delegate
        app.setActivationPolicy(.regular)
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        titleLabel = NSTextField(labelWithString: "Select or import a note")
        let device = MTLCreateSystemDefaultDevice()
        if let device { renderer = try? MeshRenderer(device: device) }
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1200, height: 800),
                          styleMask: [.titled, .closable, .resizable, .miniaturizable],
                          backing: .buffered, defer: false)
        window.title = "Syncr — Read-only"
        window.center()
        let split = NSSplitView()
        split.isVertical = true
        split.dividerStyle = .thin
        split.autoresizingMask = [.width, .height]
        split.frame = window.contentView!.bounds
        window.contentView!.addSubview(split)

        let sidebar = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: 800))
        let sidebarButtons = NSStackView(views: [button("Import…", #selector(importNote)),
                                                  button("Folder…", #selector(chooseFolder))])
        sidebarButtons.orientation = .horizontal
        sidebarButtons.translatesAutoresizingMaskIntoConstraints = false
        sidebar.addSubview(sidebarButtons)
        table = NSTableView()
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("notes"))
        column.title = "Library"
        table.addTableColumn(column)
        table.headerView = nil
        table.delegate = self
        table.dataSource = self
        table.target = self
        table.action = #selector(openSelected)
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.translatesAutoresizingMaskIntoConstraints = false
        sidebar.addSubview(scroll)
        NSLayoutConstraint.activate([
            sidebarButtons.topAnchor.constraint(equalTo: sidebar.topAnchor, constant: 10),
            sidebarButtons.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 10),
            scroll.topAnchor.constraint(equalTo: sidebarButtons.bottomAnchor, constant: 10),
            scroll.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: sidebar.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: sidebar.bottomAnchor)
        ])
        let detail = NSView(frame: NSRect(x: 0, y: 0, width: 940, height: 800))
        let controls = NSStackView(views: [titleLabel, button("←", #selector(left)),
            button("→", #selector(right)), button("↑", #selector(up)), button("↓", #selector(down)),
            button("−", #selector(zoomOut)), button("+", #selector(zoomIn)),
            button("Fit", #selector(fit))])
        controls.orientation = .horizontal
        controls.spacing = 7
        controls.translatesAutoresizingMaskIntoConstraints = false
        detail.addSubview(controls)
        canvas = CanvasView(frame: .zero, device: device)
        canvas.colorPixelFormat = .bgra8Unorm
        canvas.framebufferOnly = false
        canvas.isPaused = true
        canvas.enableSetNeedsDisplay = true
        canvas.delegate = self
        canvas.onScroll = { [weak self] x, y in self?.scroll(x: x, y: y) }
        canvas.translatesAutoresizingMaskIntoConstraints = false
        detail.addSubview(canvas)
        NSLayoutConstraint.activate([
            controls.topAnchor.constraint(equalTo: detail.topAnchor, constant: 10),
            controls.leadingAnchor.constraint(equalTo: detail.leadingAnchor, constant: 10),
            controls.trailingAnchor.constraint(lessThanOrEqualTo: detail.trailingAnchor, constant: -10),
            canvas.topAnchor.constraint(equalTo: controls.bottomAnchor, constant: 10),
            canvas.leadingAnchor.constraint(equalTo: detail.leadingAnchor),
            canvas.trailingAnchor.constraint(equalTo: detail.trailingAnchor),
            canvas.bottomAnchor.constraint(equalTo: detail.bottomAnchor)
        ])
        split.addArrangedSubview(sidebar)
        split.addArrangedSubview(detail)
        sidebar.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
        let menu = NSMenu()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit Syncr", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let appItem = NSMenuItem(title: "Syncr", action: nil, keyEquivalent: "")
        appItem.submenu = appMenu
        menu.addItem(appItem)
        let fileMenu = NSMenu(title: "File")
        let importItem = NSMenuItem(title: "Import Note Copy…", action: #selector(importNote), keyEquivalent: "o")
        importItem.target = self
        fileMenu.addItem(importItem)
        let fileItem = NSMenuItem(title: "File", action: nil, keyEquivalent: "")
        fileItem.submenu = fileMenu
        menu.addItem(fileItem)
        NSApplication.shared.mainMenu = menu
        table.reloadData()
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        showErrorIfNeeded()
    }

    private func button(_ text: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: text, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    func numberOfRows(in tableView: NSTableView) -> Int { model.notes.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let note = model.notes[row]
        let label = NSTextField(labelWithString: note.title.isEmpty ? "Untitled" : note.title)
        label.toolTip = note.notebook ?? "Inbox"
        return label
    }

    @objc private func openSelected() {
        guard table.selectedRow >= 0, table.selectedRow < model.notes.count else { return }
        do {
            try model.open(id: model.notes[table.selectedRow].id)
            viewport = Viewport()
            titleLabel.stringValue = model.drawing?.title ?? "Untitled"
            canvas.setNeedsDisplay(canvas.bounds)
        } catch { present(error) }
    }

    @objc private func importNote() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "syncr")!]
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let added = try model.importCopy(from: url)
            table.reloadData()
            if let row = model.notes.firstIndex(where: { $0.id == added.id }) { table.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false) }
            titleLabel.stringValue = model.drawing?.title ?? "Untitled"
            viewport = Viewport()
            canvas.setNeedsDisplay(canvas.bounds)
        } catch { present(error) }
    }

    @objc private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Use Folder"
        if panel.runModal() == .OK, let url = panel.url {
            model.setFolder(url)
            table.reloadData()
            titleLabel.stringValue = "Select or import a note"
            canvas.setNeedsDisplay(canvas.bounds)
            showErrorIfNeeded()
        }
    }

    private func showErrorIfNeeded() { if let error = model.error { present(NSError(domain: "Syncr", code: 1, userInfo: [NSLocalizedDescriptionKey: error])) } }
    private func present(_ error: Error) { window.presentError(error) }
    private func move(_ x: Float, _ y: Float) {
        viewport.left += x
        viewport.top += y
        viewport.clamp(pixels: canvas.drawableSize, bottom: model.drawing?.lowestInk ?? 0)
        canvas.setNeedsDisplay(canvas.bounds)
    }
    private func scroll(x: Float, y: Float) {
        let fit = min(Float(canvas.drawableSize.width) / canvasSize().width,
                      Float(canvas.drawableSize.height) / canvasSize().viewHeight)
        move(-x / max(0.001, fit * viewport.zoom), y / max(0.001, fit * viewport.zoom))
    }
    @objc private func left() { move(-400, 0) }
    @objc private func right() { move(400, 0) }
    @objc private func up() { move(0, -400) }
    @objc private func down() { move(0, 400) }
    @objc private func zoomIn() { viewport.zoom = min(8, viewport.zoom * 1.25); move(0, 0) }
    @objc private func zoomOut() { viewport.zoom = max(1, viewport.zoom / 1.25); move(0, 0) }
    @objc private func fit() { viewport = Viewport(); canvas.setNeedsDisplay(canvas.bounds) }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) { view.setNeedsDisplay(view.bounds) }
    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable, let renderer else { return }
        let dark = view.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        try? renderer.render(model.drawing, viewport: viewport, dark: dark, target: drawable.texture)
        drawable.present()
    }
}

private final class CanvasView: MTKView {
    var onScroll: ((Float, Float) -> Void)?
    override func scrollWheel(with event: NSEvent) {
        onScroll?(Float(event.scrollingDeltaX), Float(event.scrollingDeltaY))
    }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        setNeedsDisplay(bounds)
    }
}
