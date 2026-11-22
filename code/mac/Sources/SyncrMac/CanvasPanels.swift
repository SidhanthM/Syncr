import AppKit
import SyncrCore

/// Waypoints: save the current view, jump to one, remove one.
@MainActor final class CanvasWaypointsPanel: NSViewController, NSTableViewDataSource, NSTableViewDelegate {
    var waypoints: [Waypoint] { didSet { table?.reloadData() } }
    var suggestedName: () -> String = { "Waypoint" }
    var onSave: ((String) -> Void)?
    var onJump: ((Waypoint) -> Void)?
    var onRemove: ((Waypoint) -> Void)?
    private var table: NSTableView?
    private let name = NSTextField()

    init(waypoints: [Waypoint]) { self.waypoints = waypoints; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        name.placeholderString = "Name this view"; name.stringValue = suggestedName(); name.font = Theme.font
        let save = ActionButton("Save view") { [weak self] in
            guard let self else { return }
            self.onSave?(self.name.stringValue); self.name.stringValue = self.suggestedName()
        }
        let head = NSStackView(views: [name, save]); head.spacing = 8
        let table = NSTableView()
        let column = NSTableColumn(identifier: .init("w")); column.width = 230
        table.addTableColumn(column); table.headerView = nil; table.rowHeight = 26
        table.dataSource = self; table.delegate = self; table.target = self; table.doubleAction = #selector(jump); table.action = #selector(jump)
        self.table = table
        let scroll = NSScrollView(); scroll.documentView = table; scroll.hasVerticalScroller = true; scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 150).isActive = true
        scroll.widthAnchor.constraint(equalToConstant: 260).isActive = true
        let stack = NSStackView(views: [head, scroll]); stack.orientation = .vertical; stack.spacing = 8
        stack.edgeInsets = NSEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        name.widthAnchor.constraint(equalToConstant: 170).isActive = true
        view = stack
    }

    func numberOfRows(in tableView: NSTableView) -> Int { waypoints.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let w = waypoints[row]
        let label = NSTextField(labelWithString: w.name); label.font = Theme.font; label.lineBreakMode = .byTruncatingTail
        let zoom = NSTextField(labelWithString: "\(Int((Double(w.zoom) * 100).rounded()))%"); zoom.font = Theme.smallFont; zoom.textColor = Theme.muted
        let remove = ActionButton(symbol: "xmark", label: "Remove \(w.name)") { [weak self] in self?.onRemove?(w) }
        let spacer = NSView(); spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let rowView = NSStackView(views: [label, spacer, zoom, remove]); rowView.spacing = 6
        return rowView
    }
    @objc private func jump() {
        guard let row = table?.clickedRow, row >= 0, waypoints.indices.contains(row) else { return }
        onJump?(waypoints[row])
    }
}

/// The canvas's quiet control strip, bottom-left: zoom out, the zoom, zoom in, Fit all, waypoints, minimap.
@MainActor final class CanvasControlsView: NSView {
    var onZoomOut: (() -> Void)?, onZoomIn: (() -> Void)?, onFit: (() -> Void)?
    var onWaypoints: ((NSView) -> Void)?, onMinimap: (() -> Void)?
    private let zoomLabel = NSTextField(labelWithString: "100%")
    private var waypointsButton: ActionButton!
    private var minimapButton: ActionButton!

    init() {
        super.init(frame: .zero)
        wantsLayer = true; layer?.cornerRadius = 9; layer?.borderWidth = 1
        zoomLabel.font = .monospacedDigitSystemFont(ofSize: 11.5, weight: .regular); zoomLabel.textColor = Theme.muted
        zoomLabel.alignment = .center
        zoomLabel.translatesAutoresizingMaskIntoConstraints = false
        zoomLabel.widthAnchor.constraint(equalToConstant: 48).isActive = true
        waypointsButton = ActionButton(symbol: "mappin.and.ellipse", label: "Waypoints") { [weak self] in if let s = self { s.onWaypoints?(s.waypointsButton) } }
        minimapButton = ActionButton(symbol: "rectangle.inset.bottomright.filled", label: "Minimap") { [weak self] in self?.onMinimap?() }
        let views: [NSView] = [
            ActionButton(symbol: "minus", label: "Zoom out") { [weak self] in self?.onZoomOut?() }, zoomLabel,
            ActionButton(symbol: "plus", label: "Zoom in") { [weak self] in self?.onZoomIn?() },
            ActionButton(symbol: "arrow.up.left.and.arrow.down.right", label: "Fit all") { [weak self] in self?.onFit?() },
            waypointsButton, minimapButton]
        let stack = NSStackView(views: views); stack.spacing = 4
        stack.edgeInsets = NSEdgeInsets(top: 4, left: 8, bottom: 4, right: 8)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
                                     stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor)])
        setAccessibilityLabel("Canvas controls")
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() {
        layer?.backgroundColor = Theme.panel.withAlphaComponent(0.92).cgColor
        layer?.borderColor = Theme.muted.withAlphaComponent(0.35).cgColor
    }
    func show(zoom: Double, minimap: Bool) {
        zoomLabel.stringValue = zoom >= 0.995 ? "\(Int((zoom * 100).rounded()))%" : String(format: "%.0f%%", zoom * 100)
        minimapButton.contentTintColor = minimap ? NSColor.controlAccentColor : Theme.muted
    }
    override func hitTest(_ point: NSPoint) -> NSView? { isHidden ? nil : super.hitTest(point) }
}
