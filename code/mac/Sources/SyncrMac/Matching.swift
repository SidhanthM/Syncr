import Foundation

/// Type-to-filter scoring shared by the quick switcher, command palette and sidebar filter.
enum Matching {
    /// Lower is better; nil means no match. Tiers: exact, prefix, word prefix, substring,
    /// then in-order letters. An empty query matches everything equally.
    static func score(_ query: String, in text: String) -> Int? {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return 0 }
        let t = text.lowercased()
        if t == q { return 0 }
        if t.hasPrefix(q) { return 1_000 }
        if let r = t.range(of: q) {
            let start = t.distance(from: t.startIndex, to: r.lowerBound)
            let before = t[t.index(before: r.lowerBound)]
            return (before == " " || before == "-" || before == "_" ? 2_000 : 3_000) + start
        }
        var rest = t[...]
        for ch in q {
            guard let hit = rest.firstIndex(of: ch) else { return nil }
            rest = rest[rest.index(after: hit)...]
        }
        return 4_000 + t.count
    }
}
