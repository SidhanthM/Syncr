import Foundation
import SyncrCore

/// The page geometry the Mac view clamps to and zooms by.
@MainActor func noteGeometryChecks() throws {
    let legacy = NoteGeometry.legacy
    try check(legacy.hasLegacyPage && legacy.pages.count == 1 && legacy.bounds == CGRect(x: 0, y: 0, width: 4800, height: 3000), "legacy geometry is one 4800 page")
    try check(legacy.origin(ofSheet: "anything") == .zero, "a sheet that is no page is drawn inside the first page")
    try check(NoteGeometry.canvas.isCanvas && NoteGeometry.canvas.pages.isEmpty && NoteGeometry.canvas.bounds == .zero, "a canvas has no pages")

    let pages = layoutPages(pages: (1...3).map { PageExtent(sheetId: "p\($0)", width: 1600, height: 2263) }, mode: .double, gap: 20.6)
    let two = NoteGeometry(pages: pages.map { NoteGeometry.Page(sheetId: $0.sheetId, rect: CGRect(x: CGFloat($0.x), y: CGFloat($0.y), width: CGFloat($0.width), height: CGFloat($0.height)), row: Int($0.row), autoHeight: false) })
    try check(two.page(forSheet: "p3")?.row == 1 && !two.hasLegacyPage, "double layout puts page 3 on the second row")
    try check(two.page(at: CGPoint(x: 1700, y: 100))?.sheetId == "p2" && two.page(at: CGPoint(x: -500, y: 9000))?.sheetId == "p3", "the page under a point, else the nearest")
    try check(abs(two.bounds.width - 3220.6) < 0.1, "bounds are the union of the rows")

    let view = CGSize(width: 1000, height: 600)
    let zoom = NoteGeometry.zoom(percent: 100, view: view)
    try check(abs(NoteGeometry.percent(zoom: zoom, view: view) - 100) < 0.01, "zoom percent round-trips")
    let range = NoteGeometry.canvas.zoomRange(view: view)
    try check(abs(NoteGeometry.percent(zoom: range.lowerBound, view: view) - 2) < 0.01 && abs(NoteGeometry.percent(zoom: range.upperBound, view: view) - 6400) < 0.5, "canvas zoom is 2% to 6400%")
    let notes = legacy.zoomRange(view: view)
    try check(abs(NoteGeometry.percent(zoom: notes.lowerBound, view: view) - 12) < 0.01 && abs(NoteGeometry.percent(zoom: notes.upperBound, view: view) - 1600) < 1, "a note is 12% to 1600%")
    let fitted = two.fitting(two.pages[0], .width, view: view)
    try check(abs(fitted.visibleSize(view).x - Float(1600 + 2 * NoteGeometry.sideMargin)) < 0.1, "fit width shows the page and its side margins")
    try check(two.currentPage(in: CGRect(x: 1700, y: 0, width: 800, height: 600))?.sheetId == "p2", "the current page is the one most in view")

    var inside = Viewport(left: -900, top: -900, zoom: 2)
    inside.clamp(pixels: view, geometry: two, scrollBottom: 0)
    try check(inside.top == Float(-NoteGeometry.verticalMargin) && inside.left > -900, "a page view stops a margin above the first row")
    let flat = NoteGeometry(pages: [NoteGeometry.Page(sheetId: "a", rect: CGRect(x: 0, y: 0, width: 1600, height: 2263), row: 0, autoHeight: false)])
    var wide = Viewport(left: 5, top: 0, zoom: 0.2)
    wide.clamp(pixels: view, geometry: flat, scrollBottom: 0)
    try check(abs((wide.left + wide.visibleSize(view).x / 2) - 800) < 0.1, "a page narrower than the view is centred")

    var options = NewNoteOptions()
    try check(abs((options.extent?.width ?? 0) - 1600) < 0.1 && abs((options.extent?.height ?? 0) - 2262.9) < 0.2, "the New note sheet defaults to A4 portrait")
    options.landscape = true
    try check(abs((options.extent?.width ?? 0) - 2262.9) < 0.2, "landscape swaps the sides")
    options.size = "Custom"; options.customWidthMM = 5
    try check(options.extent == nil, "a custom side under 10 mm is refused")
}
