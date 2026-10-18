import Foundation
import SyncrCore
import SyncrCompanion

/// One validated write path for recorded and live providers. Only the model's serial worker
/// calls these functions; a provider never receives a core handle or writes the user's words.
enum CompanionStore {
    static func settings(_ snapshot: NoteSnapshot) -> CompanionSettings {
        snapshot.companionSettings?.settings ?? CompanionSettings(tell: "", mode: .whenAsked, web: false, activeReader: "staff-engineer", colour: .blue)
    }
    static func mode(_ value: SyncrCore.CompanionMode) -> SyncrCompanion.CompanionMode {
        switch value { case .off: .off; case .whenAsked: .whenAsked; case .thinksWithYou: .thinksWithYou }
    }
    static func unpinned(_ snapshot: NoteSnapshot) -> Int {
        snapshot.companionItems.filter { if case .marginNote(_, _, _, _, .open, _) = $0.body { return !$0.tombstone }; return false }.count
    }
    static func saveSettings(_ settings: CompanionSettings, note: Note) throws {
        let s = try note.snapshot()
        guard let initial = s.sheets.first?.creationOperationId else { return }
        let head = s.companionSettings?.head ?? EntityHead(operationId: initial, confirmedRevision: nil)
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [head.operationId], body: .setCompanionSettings(settings: settings, base: .operation(operationId: head.operationId))))
    }
    static func apply(_ tools: [ValidatedTool], note: Note, reader: String, expectedText: String, drawings: Bool, readingAlong: Bool = false, pageRegion: CanvasRegion? = nil) throws {
        let s = try note.snapshot()
        let block = s.textBlocks.first(where: { $0.role == .document && !$0.tombstone })
        guard settings(s).mode != .off, (block?.text ?? "") == expectedText else { return }
        let threadIDs = Set(s.companionItems.compactMap { v -> String? in if case .marginNote = v.body, !v.tombstone { return v.id }; return nil })
        let plan = ResponsePlan.additions(tools, markdown: block?.text ?? "", threadIDs: threadIDs, drawingsEnabled: drawings, readingAlong: readingAlong, unpinnedNotes: unpinned(s), pageRegion: pageRegion)
        var mutations: [CompanionMutation] = [], deps = Set(block.map { [$0.head.operationId] } ?? []), embeds: [(String, Int)] = []
        var nextOrders: [String: UInt32] = [:]
        func anchor(_ span: ByteSpan) -> String {
            let id = newId(); mutations.append(CompanionMutation(id: id, base: .absent, tombstone: false, body: .anchor(target: .text(blockId: block!.id, start: UInt32(span.start), end: UInt32(span.end), detached: false)))); return id
        }
        for addition in plan {
            switch addition {
            case .margin(let span, let kind, let text, let sources):
                let anchorID = anchor(span)
                mutations.append(CompanionMutation(id: newId(), base: .absent, tombstone: false, body: .marginNote(anchorId: anchorID, kind: kind == "flag" ? .flag : kind == "question" ? .question : .explanation, text: text, reader: reader, state: .open, sources: urls(sources))))
            case .canvasMargin(let region,let kind,let text,let sources):
                guard let sheet = s.sheets.first else { continue }; deps.insert(sheet.creationOperationId)
                let anchorID = newId()
                mutations.append(CompanionMutation(id:anchorID,base:.absent,tombstone:false,body:.anchor(target:.canvas(sheetId:sheet.id,x:region.x-Double(sheet.originX),y:region.y-Double(sheet.originY),width:region.width,height:region.height))))
                mutations.append(CompanionMutation(id:newId(),base:.absent,tombstone:false,body:.marginNote(anchorId:anchorID,kind:kind == "flag" ? .flag : kind == "question" ? .question : .explanation,text:text,reader:reader,state:.open,sources:urls(sources))))
            case .reply(let id, let text, let sources):
                guard let margin = s.companionItems.first(where: { $0.id == id }) else { continue }; deps.insert(margin.head.operationId)
                let order = nextOrders[id] ?? s.companionItems.compactMap { v -> UInt32? in if case .threadMessage(let parent, let order, _, _, _) = v.body, parent == id { return order }; return nil }.max().map { $0 + 1 } ?? 0
                nextOrders[id] = order + 1
                mutations.append(CompanionMutation(id: newId(), base: .absent, tombstone: false, body: .threadMessage(marginId: id, order: order, who: .companion, text: text, sources: urls(sources))))
            case .drawing(let span, let drawing):
                guard let block else { continue }
                let id = newId(), anchorID = anchor(span)
                mutations.append(CompanionMutation(id: id, base: .absent, tombstone: false, body: .drawing(anchorId: anchorID, title: drawing.title, width: drawing.width, height: drawing.height, steps: drawing.steps.map { CompanionDrawingStep(caption: $0.caption, primitives: $0.primitives.map(primitive)) })))
                let bytes = Array(block.text.utf8); let end = bytes[span.end...].firstIndex(of: 10) ?? bytes.count
                embeds.append((id,end))
            }
        }
        guard !mutations.isEmpty else { return }
        _ = try note.submitAgent(command: Command(operationId: newId(), dependencies: deps.sorted(), body: .companionMarks(mutations: mutations)))
        for (id,offset) in embeds.sorted(by: { $0.1 > $1.1 }) {
            guard let block, let b = try note.snapshot().textBlocks.first(where: { $0.id == block.id }) else { continue }
            _ = try note.submitAgent(command: Command(operationId: newId(), dependencies: [b.head.operationId], body: .marks(mutations: [.spliceText(markId: b.id, base: .operation(operationId: b.head.operationId), offset: UInt32(offset), delete: 0, insert: "\n![[syncr-drawing:\(id)]]\n")])))
        }
    }
    static func urls(_ sources: [Source]) -> [CompanionSource] { sources.map { CompanionSource(title: $0.title, url: $0.url) } }
    static func setState(_ id: String, _ state: MarginState, note: Note) throws {
        let s = try note.snapshot(); guard let v = s.companionItems.first(where: { $0.id == id }), case .marginNote(let a, let k, let text, let r, _, let urls) = v.body else { return }
        guard let anchor = s.companionItems.first(where: { $0.id == a }) else { return }
        _ = try note.submitAgent(command: Command(operationId: newId(), dependencies: [v.head.operationId,anchor.head.operationId], body: .companionMarks(mutations: [CompanionMutation(id: id, base: .operation(operationId: v.head.operationId), tombstone: false, body: .marginNote(anchorId: a, kind: k, text: text, reader: r, state: state, sources: urls))])))
    }
    static func userReply(_ id: String, text: String, note: Note) throws {
        let s = try note.snapshot(); guard let margin = s.companionItems.first(where: { $0.id == id && !$0.tombstone }) else { return }
        let order = s.companionItems.compactMap { v -> UInt32? in if case .threadMessage(let p, let n, _, _, _) = v.body, p == id { return n }; return nil }.max().map { $0 + 1 } ?? 0
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [margin.head.operationId], body: .companionMarks(mutations: [CompanionMutation(id: newId(), base: .absent, tombstone: false, body: .threadMessage(marginId: id, order: order, who: .you, text: text, sources: []))])))
    }
    private static func primitive(_ p: Primitive) -> DrawingPrimitive {
        func point(_ p: Point) -> DrawingPoint { DrawingPoint(x:p.x,y:p.y) }
        switch p.type {
        case "line": return .line(from: point(p.points![0]), to: point(p.points![1]))
        case "polyline": return .polyline(points: p.points!.map(point))
        case "arrow": return .arrow(from: point(p.points![0]), to: point(p.points![1]))
        case "shaded_region": return .shadedRegion(points: p.points!.map(point))
        case "rectangle": return .rectangle(x:p.x!,y:p.y!,width:p.width!,height:p.height!)
        case "ellipse": return .ellipse(x:p.x!,y:p.y!,width:p.width!,height:p.height!)
        case "dot": return .dot(x:p.x!,y:p.y!,size:p.size!)
        default: return .label(x:p.x!,y:p.y!,text:p.text!,size:p.size!)
        }
    }
    static func drawing(_ body: CompanionBody) -> SyncrCompanion.Drawing? {
        guard case .drawing(_, let title, let width, let height, let steps) = body else { return nil }
        // Build the public schema and pass it through the same validator before rendering.
        func point(_ p: DrawingPoint) -> [String: Any] { ["x": p.x, "y": p.y] }
        func primitive(_ p: DrawingPrimitive) -> [String: Any] {
            switch p {
            case .line(let a,let b): return ["type":"line","points":[point(a),point(b)]]
            case .arrow(let a,let b): return ["type":"arrow","points":[point(a),point(b)]]
            case .polyline(let p): return ["type":"polyline","points":p.map(point)]
            case .shadedRegion(let p): return ["type":"shaded_region","points":p.map(point)]
            case .rectangle(let x,let y,let w,let h): return ["type":"rectangle","x":x,"y":y,"width":w,"height":h]
            case .ellipse(let x,let y,let w,let h): return ["type":"ellipse","x":x,"y":y,"width":w,"height":h]
            case .dot(let x,let y,let s): return ["type":"dot","x":x,"y":y,"size":s]
            case .label(let x,let y,let t,let s): return ["type":"label","x":x,"y":y,"text":t,"size":s]
            }
        }
        let args: [String: Any] = ["quote":"stored drawing","title":title,"width":width,"height":height,"steps":steps.map { ["caption":$0.caption,"primitives":$0.primitives.map(primitive)] }]
        guard let data = try? ToolValidator.envelope(nativeCalls: [["name":"draw","arguments":args]]), let tools = try? ToolValidator.validate(data), case .draw(let drawing) = tools[0] else { return nil }; return drawing
    }
}
