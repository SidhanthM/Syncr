import Foundation
import SyncrCore

/// The shared page and canvas-card vectors (`code/shared/layout/*-v1.json`) through the Swift binding.
@MainActor func pagesLayoutChecks() throws {
    func vectors(_ name: String) throws -> [[String: Any]] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/layout/\(name)").standardizedFileURL
        return (try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any])["vectors"] as! [[String: Any]]
    }
    func num(_ any: Any?) -> Float { Float((any as! NSNumber).doubleValue) }
    let pageVectors = try vectors("pages-v1.json")
    try check(pageVectors.count >= 7, "page vectors present")
    for v in pageVectors {
        let name = v["name"] as! String
        let input = v["input"] as! [String: Any]
        let pages = (input["pages"] as! [[String: Any]]).map { PageExtent(sheetId: $0["sheet_id"] as! String, width: num($0["width"]), height: num($0["height"])) }
        let mode: PageMode = switch input["mode"] as! String { case "double": .double; case "cover": .cover; default: .single }
        let got = layoutPages(pages: pages, mode: mode, gap: num(input["gap"]))
        let want = v["expected"] as! [[String: Any]]
        try check(got.count == want.count, "\(name): page count")
        for (g, w) in zip(got, want) {
            try check(g.sheetId == w["sheet_id"] as! String && g.row == UInt32(truncating: w["row"] as! NSNumber)
                      && abs(g.x - num(w["x"])) < 1e-3 && abs(g.y - num(w["y"])) < 1e-3
                      && abs(g.width - num(w["width"])) < 1e-3 && abs(g.height - num(w["height"])) < 1e-3,
                      "\(name): \(g.sheetId) at \(g.x),\(g.y)")
        }
    }
    let cardVectors = try vectors("canvas-cards-v1.json")
    try check(cardVectors.count >= 6, "card vectors present")
    for v in cardVectors {
        let name = v["name"] as! String
        let input = v["input"] as! [String: Any]
        let anchors = (input["anchors"] as! [[String: Any]]).map { CardAnchor(id: $0["id"] as! String, x: num($0["x"]), y: num($0["y"]), width: num($0["width"]), height: num($0["height"])) }
        let cards = (input["cards"] as! [[String: Any]]).map { CanvasCard(id: $0["id"] as! String, anchorId: $0["anchor_id"] as! String, width: num($0["width"]), height: num($0["height"])) }
        let got = layoutCanvasCards(anchors: anchors, cards: cards, gap: num(input["gap"]))
        let want = v["expected"] as! [[String: Any]]
        try check(got.count == want.count, "\(name): card count")
        for (g, w) in zip(got, want) {
            try check(g.id == w["id"] as! String && g.x == num(w["x"]) && g.y == num(w["y"]), "\(name): \(g.id) at \(g.x),\(g.y)")
        }
    }
    try check(pageSizes().count == 6 && matchSize(width: 1600, height: 2262.9)?.name == "A4"
              && matchSize(width: 2262.9, height: 1600)?.name == "A4" && matchSize(width: 1000, height: 1000) == nil,
              "Named sizes match either way round")
}
