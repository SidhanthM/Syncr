import Foundation
import SyncrCore

/// Where the paper is, in note units. A Note has pages (laid out by the core's `layout_pages`); a Canvas has none and is
/// unbounded. Everything that used to assume a 4800 wide sheet asks this instead: `Viewport.clamp`, the zoom limits and
/// the extent of the overlays. A note written before pages reports one auto-height page 4800 wide (K4), so its view
/// behaves as it always did.
struct NoteGeometry: Equatable, Sendable {
    /// How a page is ruled. Mirrors the core's resolved style (page, then note, then default), in plain values.
    struct Style: Equatable, Sendable {
        /// 0 none, 1 lines, 2 dots, 3 grid.
        var pattern: UInt8 = 0
        /// RGBA; nil is the theme's paper.
        var paper: SIMD4<UInt8>? = nil
        var patternColour = SIMD4<UInt8>(150, 150, 150, 128)
        var spacing: Float = 82.6
    }

    struct Page: Equatable, Sendable {
        var sheetId: String
        /// Note units. The row it sits in is `row`.
        var rect: CGRect
        var row: Int
        /// The one page of a note written before pages: its height follows the content.
        var autoHeight: Bool
        var style = Style()
    }

    /// Single, Double (pairs 1-2, 3-4) or Cover (page 1 alone, then 2-3, 4-5): the core's `layout_pages` modes.
    enum LayoutMode: String, CaseIterable, Sendable {
        case single, double, cover
        var core: PageMode { switch self { case .single: .single; case .double: .double; case .cover: .cover } }
        var title: String { switch self { case .single: "Single"; case .double: "Double"; case .cover: "Cover" } }
    }
    /// A note's view of its pages. Kept on this Mac, not in the note (as xnotes keeps it app-side).
    struct View: Equatable, Sendable {
        var mode = LayoutMode.single
        /// Paginated: one row at a time, flipped sideways, instead of one long vertical scroll.
        var flip = false
    }

    var isCanvas: Bool
    /// Live pages in reading order. Empty for a Canvas.
    var pages: [Page]
    /// The union of the pages; `.zero` for a Canvas.
    var bounds: CGRect
    var view = View()
    /// Sheets whose content is not drawn: the pages that were deleted.
    var hidden: Set<String> = []

    init(isCanvas: Bool = false, pages: [Page] = [], view: View = View(), hidden: Set<String> = []) {
        self.isCanvas = isCanvas; self.pages = pages; self.view = view; self.hidden = hidden
        bounds = pages.dropFirst().reduce(pages.first?.rect ?? .zero) { $0.union($1.rect) }
    }

    /// One auto-height page 4800 wide, like a note that was never given pages.
    static let legacy = NoteGeometry(pages: [Page(sheetId: "", rect: CGRect(x: 0, y: 0, width: 4800, height: 3000), row: 0, autoHeight: true)])
    static let canvas = NoteGeometry(isCanvas: true)

    /// The geometry of a snapshot: the core's pages laid out in `view`, or none for a Canvas.
    init(snapshot: NoteSnapshot, view: View = View(), gap: Float = defaultPageGap()) {
        self.init(kind: snapshot.kind, pages: snapshot.pages, view: view, gap: gap)
    }

    init(kind: NoteKind, pages all: [PageView], view: View = View(), gap: Float = defaultPageGap()) {
        if kind == .canvas { self.init(isCanvas: true); return }
        let live = all.filter { !$0.tombstone }
        guard !live.isEmpty else { self = .legacy; self.view = view; return }
        let rects = layoutPages(pages: live.map { PageExtent(sheetId: $0.sheetId, width: $0.width, height: $0.height) }, mode: view.mode.core, gap: gap)
        let byId = Dictionary(uniqueKeysWithValues: live.map { ($0.sheetId, $0) })
        var placed = rects.map { r -> Page in
            let v = byId[r.sheetId]
            let style = v.map { Style(pattern: Self.pattern($0.resolvedStyle.pattern), paper: $0.resolvedStyle.paper.map(Self.rgba),
                                      patternColour: Self.rgba($0.resolvedStyle.patternColour), spacing: $0.resolvedStyle.spacing) } ?? Style()
            return Page(sheetId: r.sheetId, rect: CGRect(x: CGFloat(r.x), y: CGFloat(r.y), width: CGFloat(r.width), height: CGFloat(r.height)),
                        row: Int(r.row), autoHeight: v?.autoHeight ?? false, style: style)
        }
        if view.flip { placed = Self.sideBySide(placed) }
        self.init(pages: placed, view: view, hidden: Set(all.filter(\.tombstone).map(\.sheetId)))
    }
    private static func pattern(_ p: PagePattern) -> UInt8 { switch p { case .none: 0; case .lines: 1; case .dots: 2; case .grid: 3 } }
    private static func rgba(_ c: Rgba) -> SIMD4<UInt8> { SIMD4(c.r, c.g, c.b, c.a) }

    /// Paginated mode: the same rows, side by side, each starting at the top.
    static let flipGap: CGFloat = 400
    private static func sideBySide(_ pages: [Page]) -> [Page] {
        let rows = Dictionary(grouping: pages, by: \.row)
        let width = pages.map { $0.rect.maxX }.max() ?? 0
        return pages.map { page in
            var moved = page
            let top = rows[page.row]?.map { $0.rect.minY }.min() ?? 0
            moved.rect = page.rect.offsetBy(dx: CGFloat(page.row) * (width + flipGap), dy: -top)
            return moved
        }
    }

    /// The rows' rectangles, in order. In a vertical layout they stack; when flipped they sit side by side.
    var rows: [CGRect] {
        Dictionary(grouping: pages, by: \.row).sorted { $0.key < $1.key }.map { _, row in row.dropFirst().reduce(row[0].rect) { $0.union($1.rect) } }
    }

    /// The old page is as tall as its content (plus the core's 1000 units of room), never less than 3000.
    mutating func setLegacyHeight(_ contentBottom: CGFloat) {
        guard hasLegacyPage else { return }
        var next = pages
        next[0].rect.size.height = max(3000, contentBottom + 1000)
        self = NoteGeometry(isCanvas: isCanvas, pages: next, view: view, hidden: hidden)
    }

    /// Whether anything is drawn for the pages. The `.legacy` placeholder (a drawing with no snapshot behind it) is not.
    var drawsPaper: Bool { !isCanvas && pages.first.map { !$0.sheetId.isEmpty } == true }

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
    static func fit(_ view: CGSize) -> Float {
        let reference = canvasSize()
        return min(Float(view.width) / reference.width, Float(view.height) / reference.viewHeight)
    }
    /// xnotes' zoom percent (100% is one xnotes px, 1/1.2902 note units, per point) of a viewport zoom, and back.
    static let unitsPerXnotesPixel: Float = 1600 / 1240.157
    static func percent(zoom: Float, view: CGSize) -> Float { zoom * fit(view) * unitsPerXnotesPixel * 100 }
    static func zoom(percent: Float, view: CGSize) -> Float { percent / 100 / max(0.0001, fit(view) * unitsPerXnotesPixel) }

    /// The viewport zoom the view must have to show `units` of the note across its width (or height), and the view's size in
    /// units at a zoom. Used by the fit commands and the fit-width snap.
    static func zoom(visibleWidth units: Float, view: CGSize) -> Float { Float(view.width) / max(0.0001, fit(view) * units) }
    static func zoom(visibleHeight units: Float, view: CGSize) -> Float { Float(view.height) / max(0.0001, fit(view) * units) }

    /// The viewport zoom limits for a view of this size: a Note is 12% to 1600% (P2), a Canvas 2% to 6400% (P3).
    func zoomRange(view: CGSize) -> ClosedRange<Float> {
        guard view.width > 0, view.height > 0 else { return 1...8 }
        return isCanvas ? Self.zoom(percent: 2, view: view)...Self.zoom(percent: 6400, view: view)
                        : Self.zoom(percent: 12, view: view)...Self.zoom(percent: 1600, view: view)
    }
    static let zoomStep: Float = 1.25
}

extension NoteGeometry {
    /// The space left and above and right and below the pages that the view may show: 20.6 units (16 xnotes px) at the sides
    /// and 62 units (48 px) above and below, as xnotes.
    static let sideMargin: CGFloat = 20.6
    static let verticalMargin: CGFloat = 62

    /// What the view's `left` and `top` may be for a view `size` units across. `scrollBottom` is the old page's bottom (the
    /// lowest ink or text); `region` limits a flipped note to the row it shows. Nil for a Canvas: no limits.
    func scrollRange(size: SIMD2<Float>, scrollBottom: Float, minLeft: Float = 0, region: CGRect? = nil) -> (left: ClosedRange<Float>, top: ClosedRange<Float>)? {
        guard !isCanvas else { return nil }
        if hasLegacyPage {
            // The old page keeps its old limits (the view stops with the lowest content at its top), except that the end of
            // the page can always be brought into view so a page can be pulled on below it.
            let end = Float(bounds.maxY + Self.verticalMargin) - size.y
            let right = Float(bounds.maxX)
            return (minLeft...max(minLeft, right - size.x), 0...max(0, scrollBottom, end))
        }
        let box = region ?? bounds
        let lo = Float(box.minX - Self.sideMargin), hi = Float(box.maxX + Self.sideMargin)
        let left: ClosedRange<Float> = hi - lo <= size.x ? { let c = (lo + hi - size.x) / 2; return c...c }() : lo...(hi - size.x)
        let top = Float(box.minY - Self.verticalMargin), bottom = Float(box.maxY + Self.verticalMargin)
        return (left, top...max(top, bottom - size.y))
    }
}

extension Viewport {
    /// Keeps the view on the paper. A Canvas has no edge. A Note's right edge is its pages' (4800 for the old page), the
    /// bottom is `scrollBottom` (the lowest ink or text, as it always was), and `minLeft` below zero lets the view show
    /// space left of the page (the centred document view). Pages other than the old one are centred when narrower than the
    /// view. `stretch` lets the view go that many units past the bottom, and `stretchX` past the side (the pulls that add a page or flip).
    mutating func clamp(pixels: CGSize, geometry: NoteGeometry, scrollBottom: Float, minLeft: Float = 0, region: CGRect? = nil, stretch: Float = 0, stretchX: Float = 0) {
        guard let range = geometry.scrollRange(size: visibleSize(pixels), scrollBottom: scrollBottom, minLeft: minLeft, region: region) else { return }
        left = min(max(range.left.lowerBound - max(0, -stretchX), left), range.left.upperBound + max(0, stretchX))
        top = min(max(range.top.lowerBound, top), range.top.upperBound + max(0, stretch))
    }
}

extension NoteGeometry {
    /// The page most of `visible` shows (the earlier on a tie); nil for a Canvas.
    func currentPage(in visible: CGRect) -> Page? {
        var best: (Page, CGFloat)?
        for page in pages {
            let overlap = page.rect.intersection(visible)
            let area = overlap.isNull ? 0 : overlap.width * overlap.height
            if best == nil || area > best!.1 + 0.5 { best = (page, area) }
        }
        return best?.0
    }
    func index(ofSheet id: String) -> Int? { pages.firstIndex { $0.sheetId == id } }

    /// The fit commands' view of `page`: whole page, its width, or its height, in a view of `size` points.
    enum Fit { case whole, width, height }
    func fitting(_ page: Page, _ fit: Fit, view: CGSize) -> Viewport {
        let w = Float(page.rect.width + 2 * Self.sideMargin), h = Float(page.rect.height + 2 * Self.verticalMargin)
        let zoom: Float = switch fit {
        case .width: Self.zoom(visibleWidth: w, view: view)
        case .height: Self.zoom(visibleHeight: h, view: view)
        case .whole: min(Self.zoom(visibleWidth: w, view: view), Self.zoom(visibleHeight: h, view: view))
        }
        var v = Viewport(left: 0, top: 0, zoom: zoom)
        let size = v.visibleSize(view)
        v.left = Float(page.rect.midX) - size.x / 2
        v.top = fit == .height || fit == .whole ? Float(page.rect.midY) - size.y / 2 : Float(page.rect.minY - Self.verticalMargin)
        return v
    }
}
