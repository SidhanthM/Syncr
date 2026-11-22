import Foundation
import SyncrCore

/// Where the paper is, in note units. A Note has pages (laid out by the core's `layout_pages`); a Canvas has none and is
/// unbounded. Everything that used to assume a 4800 wide sheet asks this instead: `Viewport.clamp`, the zoom limits and
/// the extent of the overlays. A note written before pages reports one auto-height page 4800 wide (K4), so its view
/// behaves as it always did.
struct NoteGeometry: Equatable, Sendable {
    struct Page: Equatable, Sendable {
        var sheetId: String
        /// Note units. The row it sits in is `row`.
        var rect: CGRect
        var row: Int
        /// The one page of a note written before pages: its height follows the content.
        var autoHeight: Bool
    }

    var isCanvas: Bool
    /// Live pages in reading order. Empty for a Canvas.
    var pages: [Page]
    /// The union of the pages; `.zero` for a Canvas.
    var bounds: CGRect

    init(isCanvas: Bool = false, pages: [Page] = []) {
        self.isCanvas = isCanvas; self.pages = pages
        bounds = pages.dropFirst().reduce(pages.first?.rect ?? .zero) { $0.union($1.rect) }
    }

    /// One auto-height page 4800 wide, like a note that was never given pages.
    static let legacy = NoteGeometry(pages: [Page(sheetId: "", rect: CGRect(x: 0, y: 0, width: 4800, height: 3000), row: 0, autoHeight: true)])
    static let canvas = NoteGeometry(isCanvas: true)

    /// The geometry of a snapshot: the core's pages laid out in `mode`, or none for a Canvas.
    init(snapshot: NoteSnapshot, mode: PageMode = .single, gap: Float = defaultPageGap()) {
        if snapshot.kind == .canvas { self.init(isCanvas: true); return }
        let live = snapshot.pages.filter { !$0.tombstone }
        guard !live.isEmpty else { self = .legacy; return }
        let rects = layoutPages(pages: live.map { PageExtent(sheetId: $0.sheetId, width: $0.width, height: $0.height) }, mode: mode, gap: gap)
        let auto = Set(live.filter(\.autoHeight).map(\.sheetId))
        self.init(pages: rects.map {
            Page(sheetId: $0.sheetId, rect: CGRect(x: CGFloat($0.x), y: CGFloat($0.y), width: CGFloat($0.width), height: CGFloat($0.height)),
                 row: Int($0.row), autoHeight: auto.contains($0.sheetId))
        })
    }

    /// The first page is the note's old single page: the document column lives on it.
    var hasLegacyPage: Bool { pages.first?.autoHeight == true }

    func page(forSheet id: String) -> Page? { pages.first { $0.sheetId == id } }

    /// Where a sheet's own coordinates start in the note. A sheet that is not a page (the further sheets of an old note)
    /// is drawn inside the first page.
    func origin(ofSheet id: String) -> CGPoint { (page(forSheet: id) ?? pages.first)?.rect.origin ?? .zero }

    /// The page a point falls on, or the nearest one.
    func page(at point: CGPoint) -> Page? {
        pages.first { $0.rect.contains(point) } ?? pages.min {
            Self.distance(from: point, to: $0.rect) < Self.distance(from: point, to: $1.rect)
        }
    }
    private static func distance(from p: CGPoint, to r: CGRect) -> CGFloat {
        hypot(max(r.minX - p.x, 0, p.x - r.maxX), max(r.minY - p.y, 0, p.y - r.maxY))
    }

    // MARK: Zoom

    /// The reference the view's `zoom` is relative to: zoom 1 fits the 4800 by 3000 window in the view (what the link and
    /// the pen's view-relative units are defined against, so it does not change with the kind).
    private static func fit(_ view: CGSize) -> Float {
        let reference = canvasSize()
        return min(Float(view.width) / reference.width, Float(view.height) / reference.viewHeight)
    }
    /// xnotes' zoom percent (100% is one xnotes px, 1/1.2902 note units, per point) of a viewport zoom, and back.
    static let unitsPerXnotesPixel: Float = 1600 / 1240.157
    static func percent(zoom: Float, view: CGSize) -> Float { zoom * fit(view) * unitsPerXnotesPixel * 100 }
    static func zoom(percent: Float, view: CGSize) -> Float { percent / 100 / max(0.0001, fit(view) * unitsPerXnotesPixel) }

    /// The viewport zoom limits for a view of this size. Notes keep the 1x to 8x of the window's first release for now;
    /// a canvas is 2% to 6400%.
    func zoomRange(view: CGSize) -> ClosedRange<Float> {
        guard isCanvas, view.width > 0, view.height > 0 else { return 1...8 }
        return Self.zoom(percent: 2, view: view)...Self.zoom(percent: 6400, view: view)
    }
}

extension Viewport {
    /// Keeps the view on the paper. A Canvas has no edge. A Note's right edge is its pages' (4800 for the old page), the
    /// bottom is `scrollBottom` (the lowest ink or text, as it always was), and `minLeft` below zero lets the view show
    /// space left of the page (the centred document view).
    mutating func clamp(pixels: CGSize, geometry: NoteGeometry, scrollBottom: Float, minLeft: Float = 0) {
        guard !geometry.isCanvas else { return }
        let size = visibleSize(pixels)
        left = min(max(minLeft, left), max(minLeft, Float(geometry.bounds.maxX) - size.x))
        top = min(max(0, top), max(0, scrollBottom))
    }
}
