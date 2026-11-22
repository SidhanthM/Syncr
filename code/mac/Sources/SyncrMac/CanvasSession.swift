import AppKit
import SyncrCore

/// Everything a canvas view does that is not drawing: the camera and what moves it (scroll, pinch, Cmd +/-, Fit all,
/// waypoints, the minimap), the last view kept on this device, and the little panels. The note view owns one while a
/// canvas is open and turns `camera` into its viewport; nothing here touches Metal or the note.
@MainActor final class CanvasSession {
    let controls = CanvasControlsView()
    let minimap = CanvasMinimapView()
    /// The view's size in points. Set by the host on layout.
    var viewSize: CGSize = .zero { didSet { if viewSize != oldValue { viewResized(from: oldValue) } } }
    private(set) var camera = CanvasCamera.home { didSet { if camera != oldValue { cameraChanged() } } }
    /// Each item's box in note units (strokes, text, pictures, cards), for the minimap and Fit all.
    var items: [CGRect] = [] { didSet { content = Self.union(items); minimap.items = items; minimap.content = content; refreshMinimap() } }
    private(set) var content: CGRect?
    var waypoints: [Waypoint] = []
    var showsMinimap = true { didSet { minimap.isHidden = !showsMinimap || !active; controls.show(zoom: camera.zoom, minimap: showsMinimap); defaults.set(showsMinimap, forKey: Self.minimapKey) } }

    // Outputs.
    var onCamera: ((CanvasCamera) -> Void)?
    var onWaypoints: (([Waypoint]) -> Void)?
    /// Where the per-device last views live (JSON); the host reads and writes the file.
    var loadViews: (() -> Data?)?
    var saveViews: ((Data) -> Void)?

    private(set) var noteId: String?
    private(set) var active = false
    private var views = CanvasLastViews()
    private var pinchStart: CanvasCamera?
    private var pinchAnchor = CGPoint.zero
    private var saveTimer: Timer?
    private var restored = false
    private var popover: NSPopover?
    var panelOpen: Bool { popover?.isShown == true }
    private let defaults: UserDefaults
    private static let minimapKey = "canvasMinimap"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        showsMinimap = defaults.object(forKey: Self.minimapKey) as? Bool ?? true
        controls.onZoomIn = { [weak self] in self?.step(1) }
        controls.onZoomOut = { [weak self] in self?.step(-1) }
        controls.onFit = { [weak self] in self?.fitAll() }
        controls.onMinimap = { [weak self] in self?.showsMinimap.toggle() }
        controls.onWaypoints = { [weak self] anchor in self?.showWaypoints(from: anchor) }
        minimap.onCentre = { [weak self] unit in self?.centre(on: unit) }
        controls.show(zoom: 1, minimap: showsMinimap)
    }

    static func union(_ rects: [CGRect]) -> CGRect? {
        var result: CGRect?
        for r in rects where !r.isNull && r.minX.isFinite && r.minY.isFinite && r.maxX.isFinite && r.maxY.isFinite { result = result.map { $0.union(r) } ?? r }
        return result
    }

    // MARK: Opening and closing

    /// A canvas was opened: the view opens at its last view, otherwise Fit all, otherwise the origin (an empty canvas).
    func open(note: String) {
        flushViews()
        noteId = note; active = true; restored = false
        views = CanvasLastViews.load(loadViews?())
        controls.isHidden = false; minimap.isHidden = !showsMinimap
        applyOpeningView()
    }

    func close() {
        flushViews()
        noteId = nil; active = false; restored = false
        controls.isHidden = true; minimap.isHidden = true
        popover?.close()
    }

    /// Called again once the view has a size and once the strokes have loaded: the opening view is chosen the first
    /// time there is something to choose it from, and never again, so a late stroke does not move you.
    func applyOpeningView(contentLoaded: Bool = false) {
        guard active, !restored, viewSize.width > 0, viewSize.height > 0, let noteId else { return }
        if let saved = views.camera(for: noteId, in: viewSize) { camera = saved; restored = true; return }
        if contentLoaded || content != nil { camera = CanvasCamera.fitAll(content: content, in: viewSize); restored = true; return }
        camera = CanvasCamera.fitAll(content: nil, in: viewSize)   // the strokes may still be on their way: stay unrestored
    }

    private func viewResized(from old: CGSize) {
        guard active else { return }
        if !restored { applyOpeningView(); return }
        // Keep the centre of what you were looking at when the window changes size.
        if old.width > 0, old.height > 0 {
            let centre = camera.centre(in: old)
            camera = .centred(on: centre, zoom: camera.zoom, in: viewSize)
        }
    }

    // MARK: Moving

    func set(_ next: CanvasCamera) { camera = next }
    func scroll(points dx: Double, _ dy: Double) { camera = camera.panned(byPoints: dx, dy) }
    func step(_ direction: Int) { camera = camera.stepped(direction, in: viewSize) }
    func fitAll() { camera = .fitAll(content: content, in: viewSize) }
    func centre(on unit: CGPoint) { camera = .centred(on: unit, zoom: camera.zoom, in: viewSize) }
    func zoom(to value: Double, around anchor: CGPoint? = nil) {
        camera = camera.zoomed(to: value, around: anchor ?? CGPoint(x: viewSize.width / 2, y: viewSize.height / 2))
    }

    /// `anchor` in view points from the top left.
    func pinchBegan(at anchor: CGPoint) { pinchStart = camera; pinchAnchor = anchor }
    func pinchChanged(magnification: Double) {
        guard let start = pinchStart else { return }
        camera = start.zoomed(to: start.zoom * max(0.01, 1 + magnification), around: pinchAnchor)
    }
    func pinchEnded() { pinchStart = nil }

    // MARK: Change handling

    private func cameraChanged() {
        controls.show(zoom: camera.zoom, minimap: showsMinimap)
        refreshMinimap()
        onCamera?(camera)
        scheduleSave()
    }

    private func refreshMinimap() { minimap.viewport = camera.visibleRect(in: viewSize) }

    private func scheduleSave() {
        guard active, restored || content != nil else { return }
        saveTimer?.invalidate()
        saveTimer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.flushViews() } }
    }

    /// Writes the current view to this device's file. Only a view the owner has been in is kept.
    func flushViews() {
        saveTimer?.invalidate(); saveTimer = nil
        guard active, restored, let noteId, viewSize.width > 0 else { return }
        views.set(camera, in: viewSize, for: noteId)
        if let data = views.encoded() { saveViews?(data) }
    }

    // MARK: Panels

    private func show(_ controller: NSViewController, from anchor: NSView) {
        popover?.close()
        let p = NSPopover(); p.behavior = .transient; p.contentViewController = controller
        p.show(relativeTo: anchor.bounds, of: anchor, preferredEdge: .maxY)
        popover = p
    }

    func showWaypoints(from anchor: NSView) {
        let panel = CanvasWaypointsPanel(waypoints: waypoints)
        panel.suggestedName = { [weak self] in CanvasWaypoints.nextName(self?.waypoints ?? []) }
        panel.onSave = { [weak self, weak panel] name in
            guard let self, let next = CanvasWaypoints.saving(self.waypoints, name: name, camera: self.camera, in: self.viewSize) else { NSSound.beep(); return }
            self.waypoints = next; panel?.waypoints = next; self.onWaypoints?(next)
        }
        panel.onJump = { [weak self] w in
            guard let self else { return }
            self.camera = CanvasWaypoints.camera(w, in: self.viewSize)
        }
        panel.onRemove = { [weak self, weak panel] w in
            guard let self else { return }
            let next = CanvasWaypoints.removing(self.waypoints, name: w.name)
            self.waypoints = next; panel?.waypoints = next; self.onWaypoints?(next)
        }
        show(panel, from: anchor)
    }
}
