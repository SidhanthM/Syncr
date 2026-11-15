import Foundation
import SyncrCore

/// The shared margin vectors (`code/shared/layout/margin-v1.json`) through the Mac's adapter and the core, plus the
/// adapter's own mapping from NSRange lines to the core's offsets.
@MainActor func marginLayoutChecks() throws {
    let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/layout/margin-v1.json").standardizedFileURL
    let vectors = (try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any])["vectors"] as! [[String: Any]]
    try check(vectors.count >= 8, "margin vectors present")
    for v in vectors {
        let name = v["name"] as! String
        let input = try marginInput(v["input"] as! [String: Any])
        let out = layoutMargins(input: input)
        let expected = v["expected"] as! [String: Any]
        let places = expected["placements"] as! [[String: Any]]
        try check(out.placements.count == places.count, "\(name): placement count")
        for (got, want) in zip(out.placements, places) {
            let lane: MarginLane = (want["lane"] as! String) == "left" ? .left : .right
            try check(got.id == want["id"] as! String && got.lane == lane &&
                      got.x == num(want["x"]) && got.y == num(want["y"]) &&
                      got.width == num(want["width"]) && got.height == num(want["height"]),
                      "\(name): \(got.id) at \(got.x),\(got.y) \(got.width)x\(got.height)")
        }
        try check(out.bottom == num(expected["bottom"]), "\(name): bottom")
    }
    // The adapter: NSRange lines, notes and a picture, through the same call the note view makes.
    var margins = CompanionMarginLayout(columnLeft: 100, columnRight: 1500, width: 1000)
    margins.addNote("a", anchor: .canvas(y: 300), height: 400)
    margins.addNote("b", anchor: .canvas(y: 300), height: 200)
    margins.addNote("c", anchor: .text(offset: 10), height: 100)
    margins.addPicture("p", anchor: .none, height: 50)
    let lines = [CompanionMarginLayout.Line(range: NSRange(location: 0, length: 10), top: 260),
                 CompanionMarginLayout.Line(range: NSRange(location: 10, length: 10), top: 900)]
    let out = margins.place(lines: lines)
    let by = Dictionary(uniqueKeysWithValues: out.placements.map { ($0.id, $0) })
    try check(by["a"]?.x == -1040 && by["b"]?.x == 1640, "Annotations use both margins, including negative canvas coordinates")
    try check(by["c"]?.y == 900 && by["c"]?.lane == .right, "A text anchor attaches to the line holding its offset")
    try check(out.placements.last?.id == "p", "Pictures are placed after the notes")
}

private func num(_ any: Any?) -> Float { Float((any as! NSNumber).doubleValue) }

private func marginInput(_ j: [String: Any]) throws -> MarginInput {
    let lines = (j["lines"] as! [[String: Any]]).map { MarginLine(start: UInt32(truncating: $0["start"] as! NSNumber), end: UInt32(truncating: $0["end"] as! NSNumber), top: num($0["top"])) }
    let items = (j["items"] as! [[String: Any]]).map { i -> MarginItem in
        let a = i["anchor"] as! [String: Any]
        let anchor: MarginAnchor
        switch a["kind"] as! String {
        case "text": anchor = .text(offset: UInt32(truncating: a["offset"] as! NSNumber))
        case "canvas": anchor = .canvas(y: num(a["y"]))
        default: anchor = .none
        }
        return MarginItem(id: i["id"] as! String, kind: (i["kind"] as! String) == "picture" ? .picture : .note, anchor: anchor, height: num(i["height"]))
    }
    return MarginInput(policy: (j["policy"] as! String) == "phone" ? .phone : .desktop, columnLeft: num(j["column_left"]),
                       columnRight: num(j["column_right"]), cardWidth: num(j["card_width"]), gap: num(j["gap"]),
                       laneTop: num(j["lane_top"]), spacing: num(j["spacing"]), defaultTop: num(j["default_top"]), lines: lines, items: items)
}
