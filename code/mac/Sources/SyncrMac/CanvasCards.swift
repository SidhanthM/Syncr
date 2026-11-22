import Foundation
import SyncrCore

/// Companion cards on a canvas, placed by the shared `layout_canvas_cards` (vectors in `code/shared/layout/canvas-cards-v1.json`):
/// each card sits to the right of its anchor's box at `gap`, pushed down until it overlaps no earlier card. The Mac only measures.
enum CanvasCards {
    /// 16 xnotes px, like the page gap.
    static let gap: Float = 20.6

    struct Request { var id: String; var anchor: String; var width: CGFloat; var height: CGFloat }

    /// `anchors` are the note-unit boxes the companion's canvas anchors point at, keyed by anchor id.
    static func place(anchors: [String: CGRect], cards: [Request], gap: Float = gap) -> [String: CGPoint] {
        let anchorList = anchors.sorted { $0.key < $1.key }.map {
            CardAnchor(id: $0.key, x: Float($0.value.minX), y: Float($0.value.minY), width: Float($0.value.width), height: Float($0.value.height))
        }
        let cardList = cards.map { CanvasCard(id: $0.id, anchorId: $0.anchor, width: Float($0.width), height: Float($0.height)) }
        var out: [String: CGPoint] = [:]
        for p in layoutCanvasCards(anchors: anchorList, cards: cardList, gap: gap) { out[p.id] = CGPoint(x: CGFloat(p.x), y: CGFloat(p.y)) }
        return out
    }
}
