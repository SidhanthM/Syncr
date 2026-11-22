import Foundation
import CoreGraphics
import SyncrCore

/// Where a canvas is looked at: no edges, so only a position and a zoom. Pure maths, no views.
///
/// Zoom is xnotes' percentage (1.0 is 100%: one xnotes px per point), so a note unit is `1 / xn` points at 100%.
/// `left`/`top` are the note unit at the view's top-left corner; the view's size decides how much more is visible.
struct CanvasCamera: Equatable, Sendable {
    /// One xnotes content px in note units (1600 / 1240.157).
    static let xn = 1600.0 / 1240.157
    static let minZoom = 0.02, maxZoom = 64.0
    /// Cmd + and Cmd -, the toolbar's buttons.
    static let step = 1.25
    /// "Fit all" leaves 48 xnotes px around the content.
    static let fitPadding = 48.0 * xn
    /// The scale a canvas opens at when it is empty and nothing was saved.
    static let home = CanvasCamera(left: 0, top: 0, zoom: 1)

    var left: Double
    var top: Double
    var zoom: Double

    static func clamp(_ zoom: Double) -> Double {
        guard zoom.isFinite else { return 1 }
        return min(maxZoom, max(minZoom, zoom))
    }

    var pointsPerUnit: Double { zoom / Self.xn }

    /// The note units the view shows.
    func visible(in view: CGSize) -> CGSize {
        CGSize(width: view.width / pointsPerUnit, height: view.height / pointsPerUnit)
    }

    func visibleRect(in view: CGSize) -> CGRect {
        let size = visible(in: view)
        return CGRect(x: left, y: top, width: size.width, height: size.height)
    }

    func centre(in view: CGSize) -> CGPoint {
        let size = visible(in: view)
        return CGPoint(x: left + size.width / 2, y: top + size.height / 2)
    }

    /// The camera whose view is centred on `centre` at `zoom` (waypoints and the saved last view store a centre).
    static func centred(on centre: CGPoint, zoom: Double, in view: CGSize) -> CanvasCamera {
        let z = clamp(zoom)
        let size = CGSize(width: view.width / (z / xn), height: view.height / (z / xn))
        return CanvasCamera(left: Double(centre.x) - Double(size.width) / 2, top: Double(centre.y) - Double(size.height) / 2, zoom: z)
    }

    func unit(at point: CGPoint) -> CGPoint {
        CGPoint(x: left + Double(point.x) / pointsPerUnit, y: top + Double(point.y) / pointsPerUnit)
    }

    func point(for unit: CGPoint) -> CGPoint {
        CGPoint(x: (Double(unit.x) - left) * pointsPerUnit, y: (Double(unit.y) - top) * pointsPerUnit)
    }

    /// A scroll gesture's deltas in points. There is no clamp anywhere: the canvas goes on in every direction.
    func panned(byPoints dx: Double, _ dy: Double) -> CanvasCamera {
        CanvasCamera(left: left + dx / pointsPerUnit, top: top + dy / pointsPerUnit, zoom: zoom)
    }

    /// Zooms with the note unit under `anchor` (a point in the view) staying under it.
    func zoomed(to newZoom: Double, around anchor: CGPoint) -> CanvasCamera {
        let fixed = unit(at: anchor)
        let z = Self.clamp(newZoom)
        let perUnit = z / Self.xn
        return CanvasCamera(left: Double(fixed.x) - Double(anchor.x) / perUnit, top: Double(fixed.y) - Double(anchor.y) / perUnit, zoom: z)
    }

    /// Cmd + and Cmd -: one step about the middle of the view.
    func stepped(_ direction: Int, in view: CGSize) -> CanvasCamera {
        let factor = direction >= 0 ? Self.step : 1 / Self.step
        return zoomed(to: zoom * factor, around: CGPoint(x: view.width / 2, y: view.height / 2))
    }

    /// Cmd-0. Content plus 48 xnotes px of padding fills the view; an empty canvas is 100% with the origin in the middle.
    static func fitAll(content: CGRect?, in view: CGSize) -> CanvasCamera {
        guard view.width > 0, view.height > 0 else { return home }
        guard let content, !content.isNull, content.width.isFinite, content.height.isFinite else {
            return centred(on: .zero, zoom: 1, in: view)
        }
        let padded = content.insetBy(dx: -fitPadding, dy: -fitPadding)
        let perUnit = min(Double(view.width) / Double(padded.width), Double(view.height) / Double(padded.height))
        return centred(on: CGPoint(x: padded.midX, y: padded.midY), zoom: perUnit * xn, in: view)
    }
}

/// The last view of each canvas, kept on this device only (never in the note, never synced).
struct CanvasLastViews: Codable, Equatable, Sendable {
    struct Entry: Codable, Equatable, Sendable { var cx: Double; var cy: Double; var zoom: Double }
    static let file = "canvas-views.json"
    var views: [String: Entry] = [:]

    func camera(for note: String, in view: CGSize) -> CanvasCamera? {
        guard let e = views[note], e.cx.isFinite, e.cy.isFinite, e.zoom.isFinite, e.zoom > 0 else { return nil }
        return .centred(on: CGPoint(x: e.cx, y: e.cy), zoom: e.zoom, in: view)
    }

    mutating func set(_ camera: CanvasCamera, in view: CGSize, for note: String) {
        let c = camera.centre(in: view)
        views[note] = Entry(cx: Double(c.x), cy: Double(c.y), zoom: camera.zoom)
    }

    static func load(_ data: Data?) -> CanvasLastViews {
        data.flatMap { try? JSONDecoder().decode(CanvasLastViews.self, from: $0) } ?? CanvasLastViews()
    }
    func encoded() -> Data? { try? JSONEncoder().encode(self) }
}

extension CanvasCamera {
    /// The `Viewport` of this camera in a view of `view` points. Its zoom is relative to the view's fit of the 4800 x 3000
    /// reference window (what `Viewport` has always meant), so the scale is absolute and a resize does not change it.
    func viewport(in view: CGSize) -> Viewport {
        var v = Viewport()
        v.unbounded = true
        guard view.width > 0, view.height > 0 else { return v }
        let reference = canvasSize()
        let fit = min(Float(view.width) / reference.width, Float(view.height) / reference.viewHeight)
        v.left = Float(left); v.top = Float(top); v.zoom = Float(pointsPerUnit) / max(0.0001, fit)
        return v
    }
}
