import AppKit
import SyncrCore

/// Syntax colours for fenced code, from the core's tree-sitter highlighter. Colour is a display attribute
/// only: the stored note text never changes. Results are cached by (language, code), so restyling a note
/// re-reads every block it did not touch from memory and re-highlights only the block being edited.
///
/// A block under `inlineLimit` bytes is highlighted on the calling (main) thread: well under a millisecond.
/// A larger one is highlighted off the main thread when the caller passes `onReady`; until the result lands
/// the block draws plain, and the result is used only if the text still asks for exactly that code.
@MainActor enum CodeHighlight {
    static let inlineLimit = 4096
    /// Above this, a block is left plain rather than parsed.
    static let hardLimit = 200_000
    private struct Key: Hashable { var language: String; var code: String }
    private static var cache: [Key: [CodeSpan]] = [:]
    private static var order: [Key] = []
    private static var pending: Set<Key> = []
    private static var wanted: Set<Key> = []
    private static let capacity = 64

    /// The spans for `code`, or nil when a large block is still being highlighted (or is too big to be).
    static func spans(language: String, code: String, onReady: (@MainActor () -> Void)? = nil) -> [CodeSpan]? {
        let key = Key(language: language, code: code)
        if let hit = cache[key] { return hit }
        let size = code.utf8.count
        if size > hardLimit { return nil }
        guard size >= inlineLimit, let onReady else {
            let spans = highlightCode(language: language, code: code)
            store(spans, for: key)
            return spans
        }
        wanted.insert(key)
        if pending.insert(key).inserted {
            Task.detached(priority: .userInitiated) {
                let spans = highlightCode(language: language, code: code)
                await MainActor.run {
                    pending.remove(key)
                    store(spans, for: key)
                    // Stale if the text has moved on since: the result stays cached but nothing restyles.
                    if wanted.remove(key) != nil { onReady() }
                }
            }
        }
        return nil
    }

    /// Forgets which large blocks the latest styling pass asked for.
    static func beginPass() { wanted.removeAll() }

    private static func store(_ spans: [CodeSpan], for key: Key) {
        if cache[key] == nil { order.append(key) }
        cache[key] = spans
        while order.count > capacity { cache[order.removeFirst()] = nil }
    }

    /// Follows the system appearance like `Theme`'s colours, so a light/dark switch needs no restyle.
    static func colour(_ token: CodeToken) -> NSColor {
        NSColor(name: nil) { appearance in
            NSColor(argb: CodeColours.colour(token, appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua))
        }
    }

    /// Variables, operators and punctuation draw in the plain code colour.
    static func isPlain(_ token: CodeToken) -> Bool { token == .variable || token == .operator || token == .punctuation }

    /// Colours the code lines of every fenced block in `text`, over the base ink `attributed` already set.
    static func colourBlocks(_ lines: [MarkdownLine], text: NSString, into out: NSMutableAttributedString, onReady: (@MainActor () -> Void)? = nil) {
        var i = 0
        while i < lines.count {
            guard case .code(let language) = lines[i].kind else { i += 1; continue }
            var j = i
            while j + 1 < lines.count, case .code = lines[j + 1].kind { j += 1 }
            let start = lines[i].range.location, end = NSMaxRange(lines[j].range)
            i = j + 1
            guard end > start, end <= text.length, !language.isEmpty else { continue }
            let code = text.substring(with: NSRange(location: start, length: end - start))
            guard let spans = spans(language: language, code: code, onReady: onReady) else { continue }
            for span in spans {
                let range = NSRange(location: start + Int(span.start), length: Int(span.end - span.start))
                guard NSMaxRange(range) <= out.length else { continue }
                out.addAttribute(.foregroundColor, value: colour(span.token), range: range)
                if CodeColours.isItalic(span.token) { out.addAttribute(.obliqueness, value: 0.16, range: range) }
            }
        }
    }
}
