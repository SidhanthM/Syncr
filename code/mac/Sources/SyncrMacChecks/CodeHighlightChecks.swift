import AppKit
import SyncrCore

/// Fenced code is coloured by the core's highlighter (code-highlight-v1 H3); the note text never changes.
@MainActor func codeHighlightChecks() throws {
    let python = "def greet(name):\n    # say hello\n    return \"hi \" + name\n"
    let note = "Intro \u{1F600} café\n\n```python\n\(python)```\n\nAfter the block\n"
    let out = DocumentLayout.attributed(note)
    try check(out.string == note, "colouring leaves the text byte-identical")
    func colour(_ needle: String, in s: NSAttributedString) -> NSColor? {
        let r = (s.string as NSString).range(of: needle)
        return r.location == NSNotFound ? nil : s.attribute(.foregroundColor, at: r.location, effectiveRange: nil) as? NSColor
    }
    func resolved(_ c: NSColor?, dark: Bool) -> UInt32? {
        guard let c else { return nil }
        var result: UInt32?
        NSAppearance(named: dark ? .darkAqua : .aqua)!.performAsCurrentDrawingAppearance {
            let rgb = c.usingColorSpace(.sRGB)!
            result = 0xff000000 | UInt32(rgb.redComponent * 255 + 0.5) << 16 | UInt32(rgb.greenComponent * 255 + 0.5) << 8 | UInt32(rgb.blueComponent * 255 + 0.5)
        }
        return result
    }
    for dark in [false, true] {
        let keyword = resolved(colour("def", in: out), dark: dark), string = resolved(colour("\"hi \"", in: out), dark: dark)
        let comment = resolved(colour("# say hello", in: out), dark: dark), plain = resolved(colour("Intro", in: out), dark: dark)
        try check(keyword == CodeColours.keyword(dark), "python keyword gets the keyword colour (\(dark ? "dark" : "light"))")
        try check(string == CodeColours.string(dark), "python string gets the string colour (\(dark ? "dark" : "light"))")
        try check(comment == CodeColours.comment(dark), "python comment gets the comment colour (\(dark ? "dark" : "light"))")
        try check(plain == resolved(Theme.ink, dark: dark), "text outside the block keeps its ink (\(dark ? "dark" : "light"))")
    }
    let commentRange = (out.string as NSString).range(of: "# say hello")
    try check((out.attribute(.obliqueness, at: commentRange.location, effectiveRange: nil) as? Double ?? 0) > 0, "comments lean")
    try check(colour("```python", in: out) != nil, "fence lines keep their own style")
    // Offsets are UTF-16: an emoji inside a string must not shift the colours after it.
    let emoji = DocumentLayout.attributed("```python\nx = \"\u{1F600}\u{1F600}\" # ok\n```\n")
    try check(resolved(colour("# ok", in: emoji), dark: false) == CodeColours.comment(false), "colours line up after astral characters")
    try check(DocumentLayout.attributed("```\nlet x = 1\n```\n").string == "```\nlet x = 1\n```\n", "a fence with no language is left plain")
    try check(resolved(colour("let", in: DocumentLayout.attributed("```cobol\nlet x = 1\n```\n")), dark: false) == resolved(Theme.ink, dark: false), "an unknown language is plain")

    // Companion replies colour their blocks too.
    let reply = CompanionMarkup.attributed("Try this.\n\n```python\n\(python)\n\n# end\n```\n", size: 40)
    try check(resolved(colour("def", in: reply), dark: false) == CodeColours.keyword(false), "companion code block is coloured")
    try check(resolved(colour("# end", in: reply), dark: false) == CodeColours.comment(false), "companion colours land after a blank line")

    // A block over 4 KB is not parsed on the calling thread: plain first, coloured when the background result lands.
    var big = "```python\n"
    while big.utf8.count < CodeHighlight.inlineLimit + 600 { big += "value = compute(\"text\", 42)  # note\n" }
    big += "```\n"
    var landed = false
    let first = DocumentLayout.attributed(big, onCodeReady: { landed = true })
    try check(resolved(colour("compute", in: first), dark: false) == resolved(Theme.ink, dark: false), "a large block draws plain at first")
    let deadline = Date().addingTimeInterval(5)
    while !landed && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
    try check(landed, "the large block's colours arrive off the main thread")
    let second = DocumentLayout.attributed(big, onCodeReady: { })
    try check(resolved(colour("# note", in: second), dark: false) == CodeColours.comment(false), "and are used once they have landed")
    try check(second.string == big, "the large block's text is unchanged")
    // A result for text that has since changed must not restyle anything.
    var stale = false
    var changed = big.replacingOccurrences(of: "42", with: "43")
    changed += "\n"
    _ = DocumentLayout.attributed(changed, onCodeReady: { stale = true })
    _ = DocumentLayout.attributed("moved on\n", onCodeReady: { })
    let wait = Date().addingTimeInterval(1)
    while Date() < wait { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
    try check(!stale, "a result for text that has moved on is not applied")
}
