import Foundation

public enum PlannedAddition: Sendable, Equatable {
    case margin(span: ByteSpan, kind: String, text: String, sources: [Source])
    case canvasMargin(region: CanvasRegion, kind: String, text: String, sources: [Source])
    case drawing(span: ByteSpan, drawing: Drawing)
    case reply(threadID: String, text: String, sources: [Source])
}
/// Pure final boundary before core mutations: never turn a failed quote into a guessed offset,
/// never let a reply create a thread, and never ship an experimental drawing without opt-in.
public enum ResponsePlan {
    public static func additions(_ tools: [ValidatedTool], markdown: String, threadIDs: Set<String>, drawingsEnabled: Bool, readingAlong: Bool = false, unpinnedNotes: Int = 0, pageRegion: CanvasRegion? = nil) -> [PlannedAddition] {
        var result: [PlannedAddition] = []; var margins = 0
        for tool in tools {
            switch tool {
            case .stayQuiet: break
            case .marginNote(let quote, let kind, let text, let sources):
                guard (!readingAlong || (margins == 0 && unpinnedNotes < 3)), let span = PassageFinder.find(quote, in: markdown) else { continue }
                result.append(.margin(span: span, kind: kind, text: text, sources: sources)); margins += 1
            case .canvasNote(let region,let kind,let text,let sources):
                guard (!readingAlong || (margins == 0 && unpinnedNotes < 3)), let crop = pageRegion else { continue }
                result.append(.canvasMargin(region:CanvasRegion(x:crop.x+region.x*crop.width,y:crop.y+region.y*crop.height,width:region.width*crop.width,height:region.height*crop.height),kind:kind,text:text,sources:sources)); margins += 1
            case .draw(let drawing):
                guard !readingAlong, drawingsEnabled, let span = PassageFinder.find(drawing.quote, in: markdown) else { continue }
                result.append(.drawing(span: span, drawing: drawing))
            case .reply(let id, let text, let sources):
                guard !readingAlong, threadIDs.contains(id) else { continue }
                result.append(.reply(threadID: id, text: text, sources: sources))
            }
        }
        return result
    }
}
public struct MarginLine: Sendable {
    public var span: ByteSpan; public var top: Double
    public init(span: ByteSpan, top: Double) { self.span = span; self.top = top }
}
public struct MarginPosition: Sendable, Equatable {
    public var id: String; public var top: Double; public var detached: Bool
}
public enum MarginLayout {
    /// Preserve passage alignment where possible; stack collisions downward without losing the
    /// source anchor. The host highlights the source passage on hover, not the card's display y.
    public static func positions(anchors: [(id: String, span: ByteSpan?, height: Double)], lines: [MarginLine], detachedTop: Double = 260, gap: Double = 18) -> [MarginPosition] {
        var bottom = -Double.infinity
        return anchors.map { anchor in
            let source = anchor.span.flatMap { span in lines.first { $0.span.start <= span.start && $0.span.end > span.start }?.top }
            let top = max(source ?? detachedTop, bottom + gap)
            bottom = top + anchor.height
            return MarginPosition(id: anchor.id, top: top, detached: anchor.span == nil)
        }
    }
}
