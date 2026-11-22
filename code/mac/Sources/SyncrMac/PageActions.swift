import Foundation
import SyncrCore

/// The page commands of a Note, each one command (so one undo step). They run on the library's worker with a note handle,
/// read the note fresh, and throw the core's own message when it refuses (the last page cannot be deleted).
enum PageActions {
    /// A4 portrait, short side first, in note units.
    static let a4 = (width: Float(1600), height: Float(2262.9))

    private static func base(_ head: EntityHead) -> Base { NotebookController.base(head) }
    private static func submit(_ note: Note, _ dependencies: [String], _ mutations: [MarkMutation]) throws {
        _ = try note.submit(command: Command(operationId: newId(), dependencies: Array(Set(dependencies)), body: .marks(mutations: mutations)))
    }
    private static func live(_ snapshot: NoteSnapshot) -> [PageView] { snapshot.pages.filter { !$0.tombstone } }

    /// The size a page added after `page` has: its own, unless it is the old auto-height page (then A4).
    static func newPageSize(after page: PageView?) -> (width: Float, height: Float) {
        guard let page, !page.autoHeight else { return a4 }
        return (page.width, page.height)
    }

    /// Adds a blank page after `after` (the last page when nil). Returns its sheet ID.
    @discardableResult static func add(_ note: Note, after: String?, size: (width: Float, height: Float)? = nil) throws -> String {
        let snapshot = try note.snapshot()
        let pages = live(snapshot)
        let prev = after.flatMap { id in pages.first { $0.sheetId == id } } ?? pages.last
        guard let prev else { throw ViewerError.unknownSheet }
        guard let sheet = snapshot.sheets.first(where: { $0.id == prev.sheetId }) else { throw ViewerError.unknownSheet }
        let size = size ?? newPageSize(after: prev)
        let id = newId()
        try submit(note, [sheet.creationOperationId], [.addPage(sheetId: id, after: prev.sheetId, width: size.width, height: size.height)])
        return id
    }

    /// Resizes without moving or scaling the content.
    static func resize(_ note: Note, sheet: String, width: Float, height: Float) throws {
        guard let page = live(try note.snapshot()).first(where: { $0.sheetId == sheet }) else { throw ViewerError.unknownSheet }
        try submit(note, [page.head.operationId], [.resizePage(sheetId: sheet, base: base(page.head), width: width, height: height)])
    }

    /// Moves the pages (in the order given) to just after `after` (the first place when nil), as one step.
    static func move(_ note: Note, sheets: [String], after: String?) throws {
        let pages = try note.snapshot().pages
        var deps: [String] = [], mutations: [MarkMutation] = []
        var previous = after
        for id in sheets {
            guard let page = pages.first(where: { $0.sheetId == id }) else { throw ViewerError.unknownSheet }
            deps.append(page.head.operationId)
            mutations.append(.movePage(sheetId: id, base: base(page.head), after: previous))
            previous = id
        }
        try submit(note, deps, mutations)
    }

    /// Deletes the pages; the core refuses to leave none.
    static func delete(_ note: Note, sheets: [String]) throws {
        let pages = try note.snapshot().pages
        var deps: [String] = [], mutations: [MarkMutation] = []
        for id in sheets {
            guard let page = pages.first(where: { $0.sheetId == id && !$0.tombstone }) else { continue }
            deps.append(page.head.operationId)
            mutations.append(.deletePage(sheetId: id, base: base(page.head)))
        }
        guard !mutations.isEmpty else { return }
        try submit(note, deps, mutations)
    }

    private static func apply(_ t: Affine, _ x: Double, _ y: Double) -> (Double, Double) { (t.a * x + t.c * y + t.tx, t.b * x + t.d * y + t.ty) }
    /// `outer` after `inner`.
    static func compose(_ outer: Affine, _ inner: Affine) -> Affine {
        Affine(a: outer.a * inner.a + outer.c * inner.b, b: outer.b * inner.a + outer.d * inner.b,
               c: outer.a * inner.c + outer.c * inner.d, d: outer.b * inner.c + outer.d * inner.d,
               tx: outer.a * inner.tx + outer.c * inner.ty + outer.tx, ty: outer.b * inner.tx + outer.d * inner.ty + outer.ty)
    }

    /// Copies of the pages, with their ink and free text, placed after the last of them. The copies are added in reverse so each
    /// lands right after the last picked page and they keep their order. The core cannot add content to a sheet created in the
    /// same command (a command cannot depend on itself), so this is two commands: the pages, then what is on them.
    static func duplicate(_ note: Note, sheets picked: [String]) throws {
        let snapshot = try note.snapshot()
        let chosen = live(snapshot).filter { picked.contains($0.sheetId) }
        guard let last = chosen.last, let lastSheet = snapshot.sheets.first(where: { $0.id == last.sheetId }) else { return }
        var copies: [(copy: String, from: PageView)] = []
        for page in chosen.reversed() { copies.append((newId(), page)) }
        try submit(note, [lastSheet.creationOperationId], copies.map { .addPage(sheetId: $0.copy, after: last.sheetId, width: $0.from.width, height: $0.from.height) })
        let made = Dictionary(uniqueKeysWithValues: try note.snapshot().sheets.map { ($0.id, $0.creationOperationId) })
        var content: [MarkMutation] = []
        var deps: [String] = []
        for (copy, page) in copies {
            guard let creation = made[copy] else { throw ViewerError.unknownSheet }
            var any = false
            for stroke in snapshot.strokes where stroke.sheetId == page.sheetId && !stroke.tombstone {
                var channels = try decodeSamples(blob: note.getSamples(markId: stroke.id))
                let identity = stroke.transform == Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)
                if !identity {
                    for i in 0..<channels.x.count {
                        let p = apply(stroke.transform, Double(channels.x[i]), Double(channels.y[i]))
                        channels.x[i] = Float(p.0); channels.y[i] = Float(p.1)
                    }
                }
                content.append(.addStroke(markId: newId(), sheetId: copy, samples: identity ? try note.getSamples(markId: stroke.id) : try encodeSamples(channels: channels), brush: stroke.brush))
                any = true
            }
            for text in snapshot.textBlocks where text.sheetId == page.sheetId && !text.tombstone && text.role == .free {
                content.append(.addText(markId: newId(), sheetId: copy, role: .free, originX: text.originX + Float(text.transform.tx), originY: text.originY + Float(text.transform.ty),
                                        width: text.width, text: text.text, style: text.style))
                any = true
            }
            if any { deps.append(creation) }
        }
        if !content.isEmpty { try submit(note, deps, content) }
    }

    /// A quarter turn (`quarters` 1 clockwise, 3 anticlockwise, 2 half): the page's size swaps and the ink turns with it.
    /// Text boxes keep upright; their corner moves with the page.
    static func rotate(_ note: Note, sheet: String, quarters: Int) throws {
        let snapshot = try note.snapshot()
        guard let page = live(snapshot).first(where: { $0.sheetId == sheet }) else { throw ViewerError.unknownSheet }
        let turns = ((quarters % 4) + 4) % 4
        guard turns != 0 else { return }
        let w = Double(page.width), h = Double(page.height)
        let swap = turns % 2 == 1
        let turn: Affine = switch turns {
        case 1: Affine(a: 0, b: 1, c: -1, d: 0, tx: h, ty: 0)
        case 2: Affine(a: -1, b: 0, c: 0, d: -1, tx: w, ty: h)
        default: Affine(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: w)
        }
        var deps = [page.head.operationId]
        var mutations: [MarkMutation] = [.resizePage(sheetId: sheet, base: base(page.head), width: swap ? page.height : page.width, height: swap ? page.width : page.height)]
        for stroke in snapshot.strokes where stroke.sheetId == sheet && !stroke.tombstone {
            deps.append(stroke.head.operationId)
            mutations.append(.transformStroke(markId: stroke.id, base: base(stroke.head), transform: compose(turn, stroke.transform)))
        }
        for text in snapshot.textBlocks where text.sheetId == sheet && !text.tombstone {
            deps.append(text.head.operationId)
            let here = (Double(text.originX) + text.transform.tx, Double(text.originY) + text.transform.ty)
            let there = apply(turn, here.0, here.1)
            mutations.append(.transformText(markId: text.id, base: base(text.head),
                transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: there.0 - Double(text.originX), ty: there.1 - Double(text.originY))))
        }
        try submit(note, deps, mutations)
    }

    /// The note's page style (ruling, paper) for every page that has no style of its own.
    static func setStyle(_ note: Note, template: PagePattern, paper: Rgba?, patternColour: Rgba?, spacing: Float?) throws {
        guard let style = try note.snapshot().pageStyle else { throw ViewerError.unavailable }
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [style.head.operationId],
            body: .setPageStyle(sheetId: nil, template: template, paper: paper, patternColour: patternColour, spacing: spacing, base: base(style.head))))
    }
}
