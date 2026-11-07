import AppKit
import SyncrCore
import SyncrCompanion

private let rotatedSVG = """
<svg viewBox="0 0 320 160"><text x="16" y="24" font-size="16" font-family="var(--font-voice)" fill="var(--ink)">Title label</text>
<g data-step="1" data-caption="A line."><line x1="30" y1="120" x2="250" y2="120" stroke="var(--voice)" stroke-width="1.5"/><text x="150" y="110" font-size="12" font-family="var(--font-sans)" fill="var(--muted)" text-anchor="middle" transform="rotate(-8 150 110)">slope</text></g>
<g data-step="2" data-caption="A box."><rect x="40" y="50" width="120" height="40" stroke="var(--ink)" fill="none"/><text x="100" y="75" font-size="14" font-family="var(--font-voice)" fill="var(--soft)" text-anchor="middle">box</text></g></svg>
"""

@MainActor private func render(_ content: NSView, dark: Bool) -> NSBitmapImageRep {
    let view = ThemedView(frame: content.bounds)
    view.fill = Theme.page
    content.removeFromSuperview(); view.addSubview(content)
    view.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    view.layoutSubtreeIfNeeded()
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(view.bounds.width), pixelsHigh: Int(view.bounds.height), bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    bitmap.size = view.bounds.size
    view.cacheDisplay(in: view.bounds, to: bitmap)
    return bitmap
}

/// Mean absolute difference per channel (0 to 255) over the pixels where either image differs from `paper`.
@MainActor private func difference(_ a: NSBitmapImageRep, _ b: NSBitmapImageRep) -> (mean: Double, changed: Int) {
    var total = 0.0, changed = 0
    let w = min(a.pixelsWide, b.pixelsWide), h = min(a.pixelsHigh, b.pixelsHigh)
    for y in 0..<h { for x in 0..<w {
        guard let p = a.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB), let q = b.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
        let d = abs(p.redComponent - q.redComponent) + abs(p.greenComponent - q.greenComponent) + abs(p.blueComponent - q.blueComponent)
        if d > 0.12 { changed += 1 }
        total += Double(d) / 3
    } }
    return (total / Double(max(1, w * h)) * 255, changed)
}

@MainActor private func wait(_ done: () -> Bool) throws {
    let end = Date().addingTimeInterval(15)
    while !done() {
        guard Date() < end else { throw CheckFailure(description: "round 2 worker timed out") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}

/// Notebook round 2: the text column, formatted companion text, ask threads, selectable picture labels.
@MainActor func notebookRound2Checks() throws {
    // Formatted text: paragraphs, lists, inline code, fenced blocks (also unterminated while streaming).
    let sample = "Use a hash map.\n\n1. Walk the list.\n2. Look up `target - n`.\n\n- fast\n- simple\n\n```python\ndef two_sum(nums, target):\n    seen = {}\n    return []\n```\nDone."
    let blocks = CompanionMarkup.blocks(sample)
    try check(blocks == [.paragraph("Use a hash map."), .numbered(1, "Walk the list."), .numbered(2, "Look up `target - n`."), .bullet("fast"), .bullet("simple"),
                         .code(language: "python", text: "def two_sum(nums, target):\n    seen = {}\n    return []"), .paragraph("Done.")], "blocks: paragraph, ordered, unordered, fenced code")
    if case .code(_, let streaming)? = CompanionMarkup.blocks("Intro\n\n```swift\nlet x = 1\nlet y").last { try check(streaming == "let x = 1\nlet y", "unterminated fence is code to the end") }
    else { throw CheckFailure(description: "unterminated fence kept as code") }
    let attributed = CompanionMarkup.attributed(sample, size: 40)
    var mono = 0, tagged = 0, inlineCode = false
    attributed.enumerateAttributes(in: NSRange(location: 0, length: attributed.length)) { attrs, range, _ in
        if let font = attrs[.font] as? NSFont, font.isFixedPitch { mono += range.length; if attrs[CompanionMarkup.codeBlock] != nil { tagged += range.length } else if (attributed.string as NSString).substring(with: range) == "target - n" { inlineCode = true } }
    }
    try check(tagged >= 50 && inlineCode && mono > tagged, "code block lines and inline code are monospaced (\(tagged) block chars)")
    let rich = RichTextView(); let height = rich.show(sample, size: 40, width: 800)
    let rects = rich.codeRects()
    try check(height > 300 && rects.count == 1 && rects[0].width == 800 && rects[0].height > 100, "code block gets one fill rectangle; text laid out at width (height \(Int(height)))")
    let copy = rich.attributedSubstring(forProposedRange: NSRange(location: 0, length: rich.string.utf16.count), actualRange: nil)?.string ?? ""
    try check(copy.contains("def two_sum(nums, target):") && rich.isSelectable && !rich.isEditable, "text selectable and copyable")
    let card = AnswerCard(text: String(repeating: "A long sentence about the approach. ", count: 120), step: nil, width: 1400, writeBack: {}, open: {})
    try check(card.truncated && card.fittedHeight < AnswerCard.maxText + 400, "long answer is cut to a block that opens larger (\(Int(card.fittedHeight)))")
    let short = AnswerCard(text: "Short.", step: "Writing", width: 1400)
    try check(!short.truncated && short.fittedHeight < 300, "short answer shows whole, with its step")

    AnswerPopup.show(id: "check", title: "Syncr", messages: [("You", "Question"), ("Syncr", sample)], writeBack: {})
    try check(NSApplication.shared.windows.contains { $0.title == "Syncr" && $0.isVisible }, "long answer opens in a popup window")
    NSApplication.shared.windows.filter { $0.title == "Syncr" }.forEach { $0.close() }

    // Text column: defaults are today's layout; borders are preferences; each border independent.
    let editor = DocumentEditor(frame: .zero)
    let memory = MemoryTextDocument("One line of text.")
    editor.show(document: memory, title: "T", width: 1600)
    try check(editor.columnLeft == 100 && editor.columnRight == 1500 && editor.pageWidth == 1600 && editor.textWidth == 1400, "default column is today's 100 to 1500")
    try check(editor.textView.frame.minX == 100 && editor.textView.frame.minY == 260 && editor.textView.frame.width == 1400, "text sits where it always has")
    try AppPreferences.shared.update { $0.columnLeft = 400 }
    try check(editor.columnLeft == 400 && editor.columnRight == 1500 && editor.textView.frame.minX == 400 && editor.textView.frame.width == 1100, "left border moves alone")
    try AppPreferences.shared.update { $0.columnRight = 2000 }
    try check(editor.columnRight == 2000 && editor.pageWidth == 2100 && editor.textWidth == 1600, "right border moves alone, page width follows")
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1400, height: 900), styleMask: [.titled], backing: .buffered, defer: false)
    editor.frame = CGRect(x: 0, y: 0, width: 2100, height: 4000); window.contentView = editor
    var saved: (CGFloat?, CGFloat?)?
    editor.onColumn = { saved = ($0, $1) }
    editor.debugDragWidth(1800)
    try check(editor.columnRight == 1800 && saved?.1 == 1800 && saved?.0 == nil, "dragging the right border reports only that edge (\(editor.columnRight))")
    editor.debugDragWidth(700, left: true)
    try check(editor.columnLeft == 700 && saved?.0 == 700, "dragging the left border (\(editor.columnLeft))")
    editor.debugDragWidth(5000)
    try check(editor.columnRight == 3000, "right border stops at its limit")
    editor.debugDragWidth(0, left: true)
    try check(editor.columnLeft == 100, "left border stops at the default edge")
    try AppPreferences.shared.update { $0.columnLeft = nil; $0.columnRight = nil }
    try check(editor.columnLeft == 100 && editor.columnRight == 1500, "resetting returns to the defaults")
    try AppPreferences.shared.update { $0.columnLeft = 300; $0.columnRight = 1900 }
    let reread = AppPreferenceValues()
    try check(reread.columnLeft == nil && AppPreferences.shared.values.columnLeft == 300, "borders live in preferences, off by default")
    let decoded = try JSONDecoder().decode(AppPreferenceValues.self, from: Data("{\"appearance\":\"system\"}".utf8))
    try check(decoded.columnLeft == nil && decoded.columnRight == nil, "old preference files decode with default borders")
    try AppPreferences.shared.update { $0.columnLeft = nil; $0.columnRight = nil }

    // Ask threads: stored with what the core has, shown under the passage, not in the margin.
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-r2-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    let model = LibraryModel(folder: dir)
    let id = try model.createNote(notebook: nil)
    let text = "Run me through two sum.\nSecond line.\n"
    func edit(_ work: @escaping @Sendable (Note) throws -> Void) throws -> NoteSnapshot {
        var result: Result<NoteSnapshot, Error>?
        model.editNote(id: id, work: work) { result = $0 }
        try wait { result != nil }; return try result!.get()
    }
    _ = try edit { note in
        let sheet = try note.snapshot().sheets[0]
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [.addText(markId: newId(), sheetId: sheet.id, role: .document, originX: 0, originY: 0, width: 1600, text: text, style: TextStyle(size: .medium, colour: .ink))])))
    }
    let thread = newId()
    var s = try edit { try CompanionStore.addAsk(threadID: thread, span: ByteSpan(start: 0, end: 23), ask: "Run me through two sum.", note: $0) }
    let askItem = s.companionItems.first { $0.id == thread }
    try check(askItem.map(CompanionStore.isAsk) == true, "the ask is a question thread by You")
    let first = s.companionItems.first { if case .threadMessage(thread, 0, .you, "Run me through two sum.", _) = $0.body { return true }; return false }
    try check(first != nil, "the owner's ask is message 0 from You")
    s = try edit { note in try CompanionStore.apply([.reply(threadID: thread, text: "Use a hash map.\n\n```python\nprint(1)\n```", sources: [])], note: note, reader: "Editor", expectedText: text, drawings: false) }
    let answers = s.companionItems.compactMap { v -> String? in if case .threadMessage(thread, _, .companion, let t, _) = v.body { return t }; return nil }
    try check(answers.count == 1 && answers[0].contains("```python"), "the answer is a Companion message on the thread")
    try check(s.textBlocks.first { $0.role == .document }?.text == text, "the note's own text is untouched by the ask and the answer")

    // Picture labels: real text, one selection at a time, the same pixels as the drawn labels, in both themes.
    let inspector = CorePictureInspector()
    let rotated = try CorePictureInspector.mac(svg: try inspector.filter(svg: rotatedSVG))
    for (name, picture) in [("fixture", MacPicture.fixture), ("rotated", rotated)] {
        let fit = PictureRenderer.bounds(picture), width: CGFloat = 640, height = ceil(width * fit.height / fit.width)
        let canvas = PictureCanvas(frame: CGRect(x: 0, y: 0, width: width, height: height))
        canvas.picture = picture; canvas.step = picture.steps.count; canvas.progress = 1
        let labelItems = (picture.always + picture.steps.flatMap(\.items)).filter { if case .text = $0 { return true }; return false }
        let visible = PictureRenderer.labels(picture, through: picture.steps.count).count
        try check(canvas.labelViews.filter { !$0.isHidden }.count == visible && visible > 0 && visible <= labelItems.count, "\(name): one text view per shown label (\(visible))")
        for dark in [false, true] {
            let overlay = render(canvas, dark: dark)
            guard let drawn = PictureRenderer.image(picture, through: picture.steps.count, width: width, dark: dark) else { throw CheckFailure(description: "reference image") }
            let (mean, changed) = difference(overlay, drawn)
            print("  labels vs drawn \(name) \(dark ? "dark" : "light"): mean \(String(format: "%.3f", mean)), \(changed) px differ")
            if let dir = ProcessInfo.processInfo.environment["SYNCR_R2_DUMP"] {
                try? overlay.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: dir + "/\(name)-\(dark ? "dark" : "light")-overlay.png"))
                try? drawn.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: dir + "/\(name)-\(dark ? "dark" : "light")-drawn.png"))
            }
            try check(mean < 3 && changed < Int(width * CGFloat(height) * 0.02), "\(name) \(dark ? "dark" : "light"): labels land where they were drawn (mean \(String(format: "%.2f", mean)), \(changed) px differ)")
        }
        // One at a time: selecting in one label clears the other.
        let views = canvas.labelViews.filter { !$0.isHidden }.sorted { $0.string < $1.string }
        guard views.count >= 2 else { continue }
        views[0].setSelectedRange(NSRange(location: 0, length: views[0].string.utf16.count)); views[0].onFocus?()
        views[1].setSelectedRange(NSRange(location: 0, length: min(2, views[1].string.utf16.count))); views[1].onFocus?()
        try check(views[0].selectedRange().length == 0 && views[1].selectedRange().length > 0, "\(name): selecting one label clears the other")
        let picked = (views[1].string as NSString).substring(with: views[1].selectedRange())
        try check(!picked.isEmpty && views[1].isSelectable && !views[1].isEditable, "\(name): a label's text can be selected and copied one at a time (\"\(picked)\")")
    }
    // Step playback: a label fades in with the last step and is hidden by a later step's hide list.
    let canvas = PictureCanvas(frame: CGRect(x: 0, y: 0, width: 640, height: ceil(640 * PictureRenderer.bounds(.fixture).height / PictureRenderer.bounds(.fixture).width)))
    canvas.picture = .fixture; canvas.step = 3; canvas.progress = 0.1
    let early = canvas.labelViews.filter { !$0.isHidden && $0.string == "Backtrack" }.count
    canvas.progress = 1
    let late = canvas.labelViews.filter { !$0.isHidden && $0.string == "Backtrack" }.count
    try check(early == 0 && late == 1, "labels of the step being drawn appear with the end of its stroke")
    canvas.step = 1
    try check(canvas.labelViews.filter { !$0.isHidden && $0.string == "Backtrack" }.isEmpty && canvas.labelViews.contains { !$0.isHidden && $0.string == "Q" }, "going back a step removes later labels")
    print("Notebook round 2 checks: OK (formatted text, code blocks, column borders as preferences, ask thread storage, selectable picture labels)")
}
