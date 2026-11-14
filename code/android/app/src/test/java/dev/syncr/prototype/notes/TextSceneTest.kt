package dev.syncr.prototype.notes

import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed-width shaper: every glyph is [em] of its size wide; greedy word wrap, trailing spaces stay on their line. */
internal class FakeShaper(private val em: Float = 1400f / 60f / 48f) : Shaper {
    override fun shape(text: String, runs: List<Run>, width: Float): List<ShapedLine> {
        if (text.isEmpty()) return listOf(ShapedLine(0, 0, emptyList()))
        fun w(i: Int) = (runs.firstOrNull { it.start <= i && i < it.end }?.attr?.size ?: MacMetrics.BODY) * em
        val breaks = ArrayList<Int>()
        var lineStart = 0; var x = 0f; var lastSpace = -1; var i = 0
        while (i < text.length) {
            val cw = w(i)
            if (text[i] != ' ' && x + cw > width + 0.01f) {
                val cut = if (lastSpace > lineStart) lastSpace + 1 else i
                breaks += cut; lineStart = cut; x = 0f; lastSpace = -1; i = cut; continue
            }
            if (text[i] == ' ') lastSpace = i
            x += cw; i++
        }
        val ends = breaks + text.length
        var s = 0
        return ends.map { e ->
            val segs = ArrayList<Seg>(); var sx = 0f
            for (r in runs) { val a = maxOf(r.start, s); val b = minOf(r.end, e); if (b > a) { segs += Seg(a, b, sx); sx += (b - a) * r.attr.size * em } }
            ShapedLine(s, e, segs).also { s = e }
        }
    }
}

class TextSceneTest {
    private val shaper = FakeShaper()
    private val typeset = Typeset(shaper)
    private fun tops(text: String, width: Float = 1400f) = typeset.document(text, 100f, 260f, width).vlines.map { it.top - 260f }

    private fun assertTops(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.01f) }
    }

    // Expected values below were measured with NSLayoutManager (macOS system font, 48 unit body, line spacing 16, paragraph spacing 10).
    @Test fun bodyLinesAndBlankLinesStepLikeTheMac() = assertTops(listOf(0f, 82f, 164f, 246f), tops("a\nb\n\nc"))

    @Test fun headingsAreTallerAndTheLineAfterFollows() {
        assertTops(listOf(0f, 111f), tops("# T\nb"))
        assertTops(listOf(0f, 99f), tops("## T\nb"))
        assertTops(listOf(0f, 91f), tops("### T\nb"))
    }

    @Test fun hiddenFenceLinesCollapseAndCodeUsesItsOwnFont() =
        assertTops(listOf(0f, 82f, 108f, 184f, 260f, 286f, 397f), tops("a\n```swift\nlet x = 1\nlet y = 2\n```\n# H\nz"))

    @Test fun wrappedParagraphStepsBySeventyTwo() {
        val text = "word ".repeat(80).trimEnd()
        val t = tops(text)
        assertEquals(7, t.size)
        assertTops((0 until 7).map { it * 72f }, t)
    }

    @Test fun lineHeightsRoundAscentAndDescentSeparately() {
        assertEquals(56, MacMetrics.lineHeight(48f)); assertEquals(85, MacMetrics.lineHeight(72f))
        assertEquals(73, MacMetrics.lineHeight(62f)); assertEquals(65, MacMetrics.lineHeight(55f))
        assertEquals(50, MacMetrics.lineHeight(42f)); assertEquals(40, MacMetrics.lineHeight(34f))
        assertEquals(listOf(72f, 62f, 55f, 50f, 50f), (1..5).map { MacMetrics.headingSize(it) })
    }

    @Test fun markdownKindsAndHiddenSyntax() {
        val lines = TextMarkdown.lines("# Title\n- item\n1. one\n- [x] done\n> q\n---\nplain **b** and `c` [[W]] #tag")
        assertEquals(listOf<MdKind>(MdKind.Heading(1), MdKind.Bullet, MdKind.Numbered(1), MdKind.Task(true), MdKind.Quote, MdKind.Rule("---"), MdKind.Plain), lines.map { it.kind })
        assertEquals(setOf(SpanKind.BOLD, SpanKind.CODE, SpanKind.WIKI, SpanKind.TAG), lines.last().spans.map { it.kind }.toSet())
        val attrs = typeset.attributes(lines[0])
        assertTrue(attrs[0].hidden && attrs[1].hidden) // "# " is hidden
        assertEquals(72f, attrs[2].size)
        val bullet = typeset.attributes(lines[1])
        assertEquals(Tok.CLEAR, bullet[0].tok); assertFalse(bullet[0].hidden) // kept width, drawn clear
    }

    @Test fun fencesCarryTheirLanguageAndFrontMatterIsPlain() {
        val lines = TextMarkdown.lines("---\ntitle: x\n---\n```kotlin\nval a = 1\n```")
        assertEquals(listOf<MdKind>(MdKind.Plain, MdKind.Plain, MdKind.Plain, MdKind.Fence("kotlin"), MdKind.Code("kotlin"), MdKind.Fence("")), lines.map { it.kind })
    }

    @Test fun bytesMapToUtf16() {
        val o = Utf16Offsets("é😀x")
        assertEquals(0, o.at(0u)); assertEquals(1, o.at(2u)); assertEquals(3, o.at(6u)); assertEquals(4, o.at(7u))
    }

    // ---- scene ----

    private val head = EntityHead("h", null)
    private fun block(text: String, width: Float = 1600f, role: TextRole = TextRole.DOCUMENT, x: Float = 0f, y: Float = 0f) =
        TextBlockView("tb", "sheet", "a", head, role, x, y, IDENTITY, width, text, TextStyle(BrushSize.MEDIUM, InkColour.INK), false)
    private fun item(id: String, body: CompanionBody, tombstone: Boolean = false) = CompanionItemView(id, "companion", head, tombstone, body)
    private fun note(blocks: List<TextBlockView>, items: List<CompanionItemView> = emptyList()) = NoteSnapshot(
        noteId = "n", title = "Two sum", notebook = null, metadataHead = head, structureHead = head,
        sheets = listOf(SheetView("sheet", "c", 0f, 0f)), strokes = emptyList(), localSequence = 1u, cursor = EventCursor("s", 1u),
        undoTarget = null, redoTarget = null, undoDependencies = emptyList(), redoDependencies = emptyList(),
        textBlocks = blocks, companionItems = items)
    private fun scene(n: NoteSnapshot, pictures: (String) -> PictureDisplayList? = { null }) = NoteTextSceneBuilder(shaper, pictures).build(n)
    private fun texts(s: NoteTextScene) = s.prims.filterIsInstance<Prim.Text>()

    @Test fun documentTextSitsAtTheMacsDefaultOrigin() {
        val s = scene(note(listOf(block("hello world"))))
        val body = texts(s).first { it.text == "hello world" }
        assertEquals(100f, body.x, 0.01f)
        assertEquals(260f + MacMetrics.ascent(48f), body.baseline, 0.01f)
        val title = texts(s).first { it.text == "Two sum" }
        assertEquals(100f, title.x, 0.01f); assertEquals(76f, title.attr.size, 0f)
    }

    @Test fun titleBandReadsAsATitleWithAQuietLineAndOneSalmonRule() {
        val s = NoteTextSceneBuilder(shaper) { null }.build(note(listOf(block("hello"))), title = TitleDisplay("Today, 23:49", "Inbox · Edited Today, 23:49"))
        val title = texts(s).first { it.text == "Today, 23:49" }
        assertEquals(76f, title.attr.size, 0f); assertTrue(title.attr.kern < 0f)
        val meta = texts(s).first { it.text == "INBOX · EDITED TODAY, 23:49" }
        assertEquals(Tok.MUTED, meta.attr.tok); assertTrue(meta.attr.kern > 0f); assertTrue(meta.baseline > title.baseline)
        val rule = s.prims.filterIsInstance<Prim.Fill>().single { it.tok == Tok.ACCENT }
        assertTrue("a body line of air under the rule", MacMetrics.TEXT_Y - rule.bottom >= 64f)
        // The body keeps the Mac's geometry whatever the title looks like.
        assertEquals(260f + MacMetrics.ascent(48f), texts(s).first { it.text == "hello" }.baseline, 0.01f)
    }

    @Test fun aTitleTooLongForTheColumnEndsInAnEllipsis() {
        val long = "word ".repeat(80).trim()
        val shown = texts(NoteTextSceneBuilder(shaper) { null }.build(note(listOf(block("x"))), title = TitleDisplay(long, ""))).first { it.attr.size == 76f }
        assertTrue(shown.text.endsWith("…") && shown.text.length < long.length)
    }

    @Test fun noteWithoutTextOrCompanionDrawsNothing() = assertTrue(scene(note(emptyList())).prims.isEmpty())

    @Test fun deletedTextIsNotDrawn() {
        val gone = block("secret").copy(tombstone = true)
        assertTrue(scene(note(listOf(gone))).prims.isEmpty())
    }

    @Test fun marginNoteSitsBesideItsPassageInTheMargin() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 10u, 14u, false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.EXPLANATION, "Think of a hash map.", "staff", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block("aaaa\nbbbb\ncccc\ndddd")), items))
        val card = texts(s).first { it.text.startsWith("Think") }
        assertEquals(1600f + 40f + 56f, card.x, 0.01f)
        // The passage starts on the third line: 260 + 2 * 82.
        val dot = s.prims.filterIsInstance<Prim.Fill>().first { it.w == 20f && it.h == 20f }
        assertEquals(260f + 164f + 26f, dot.y, 0.01f)
    }

    @Test fun dismissedMarginNotesAndTombstonedItemsAreSkipped() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 2u, false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.FLAG, "dismissed one", "r", MarginState.DISMISSED, emptyList())),
            item("m2", CompanionBody.MarginNote("anchor", MarginKind.FLAG, "deleted one", "r", MarginState.OPEN, emptyList()), tombstone = true),
        )
        assertTrue(texts(scene(note(listOf(block("ab")), items))).none { it.text.contains("one") })
    }

    @Test fun anAsksRootNoteIsNotShownAsAMarginCard() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 1u, false))),
            item("root", CompanionBody.MarginNote("anchor", MarginKind.QUESTION, "Question", "You", MarginState.OPEN, emptyList())),
            item("q", CompanionBody.ThreadMessage("root", 0u, MessageWho.YOU, "Why?", emptyList())),
        )
        val t = texts(scene(note(listOf(block("x")), items)))
        assertTrue(t.none { it.text == "Question" }); assertTrue(t.any { it.text == "Why?" })
    }

    @Test fun answerThreadSitsUnderItsPassageAndPushesLaterTextDown() {
        val plain = scene(note(listOf(block("question\nlater"))))
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 8u, false))),
            item("q", CompanionBody.ThreadMessage("anchor", 0u, MessageWho.YOU, "How?", emptyList())),
            item("a", CompanionBody.ThreadMessage("anchor", 1u, MessageWho.COMPANION, "Use a map.\n\n```python\nseen = {}\n```", emptyList())),
        )
        val s = scene(note(listOf(block("question\nlater")), items))
        val later = { sc: NoteTextScene -> texts(sc).first { it.text == "later" }.baseline }
        val answer = texts(s).first { it.text == "Use a map." }
        val code = texts(s).first { it.text == "seen = {}" }
        assertTrue(answer.baseline > texts(s).first { it.text == "question" }.baseline)
        assertTrue(code.attr.family == Family.MONO && code.baseline > answer.baseline)
        assertTrue(later(s) > answer.baseline && later(s) > code.baseline) // the next paragraph is below the answer
        assertTrue(later(s) > later(plain) + 100f)
        assertTrue(texts(s).any { it.text == "You" } && texts(s).any { it.text == "Companion" })
    }

    @Test fun listsAndInlineCodeInAnAnswerKeepTheirShape() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 1u, false))),
            item("a", CompanionBody.ThreadMessage("anchor", 1u, MessageWho.COMPANION, "- first\n- second uses `dict`\n1. one", emptyList())),
        )
        val t = texts(scene(note(listOf(block("x")), items)))
        assertEquals(2, t.count { it.text == "•" })
        assertTrue(t.any { it.text == "1." })
        val inline = t.first { it.text == "dict" }
        assertEquals(Family.MONO, inline.attr.family); assertEquals(Tok.INLINE_CODE, inline.attr.background)
    }

    @Test fun anchorsUseUtf8OffsetsForNonAsciiText() {
        val text = "é😀 first\nsecond"
        val start = text.toByteArray().size - "second".length
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", start.toUInt(), (start + 6).toUInt(), false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.QUESTION, "why", "r", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block(text)), items))
        val dot = s.prims.filterIsInstance<Prim.Fill>().first { it.w == 20f && it.h == 20f }
        assertEquals(260f + 82f + 26f, dot.y, 0.01f)
    }

    private val picture = PictureDisplayList(
        width = 320f, height = 190f,
        always = listOf(PictureItem.Text("hi", 160f, 20f, "middle", "alphabetic", "font-voice", 16f, "ink", IDENTITY, PictureBounds(140f, 5f, 180f, 25f))),
        steps = listOf(PictureStep(1u, "A box.", emptyList(), emptyList(),
            listOf(PictureItem.Polyline(listOf(PicturePoint(30f, 50f), PicturePoint(290f, 50f), PicturePoint(290f, 145f), PicturePoint(30f, 145f)), true, "voice", 1.1f, "none", PictureBounds(29f, 49f, 291f, 146f))))))

    @Test fun pictureFitsLikeTheMacsCard() {
        // Bounds: points widened by stroke width, core bounds, 8 units slack: x 20.9..299.1, y -3..154.1.
        assertEquals(1100f * 157.1f / 278.2f + 200f, PictureGeometry.fittedHeight(picture), 0.01f)
    }

    @Test fun pictureWithAnEmbedLineHoldsItsRoomInTheText() {
        val items = listOf(item("p", CompanionBody.Picture("anchor", "Box", "<svg/>")))
        val s = scene(note(listOf(block("intro\n![[syncr-drawing:p]]\nafter")), items)) { picture }
        val place = s.prims.filterIsInstance<Prim.Picture>().single()
        assertEquals(170f, place.x, 0f)
        assertEquals(260f + 82f, place.y, 0.01f)
        val after = texts(s).first { it.text == "after" }
        assertEquals(260f + 82f + PictureGeometry.fittedHeight(picture) + MacMetrics.ascent(48f), after.baseline, 0.5f)
    }

    @Test fun pictureWithoutAnEmbedWaitsInTheMargin() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 5u, false))),
            item("p", CompanionBody.Picture("anchor", "Box", "<svg/>")),
        )
        val place = scene(note(listOf(block("intro\nafter")), items)) { picture }.prims.filterIsInstance<Prim.Picture>().single()
        assertEquals(1640f, place.x, 0f); assertEquals(1000f, place.w, 0f)
    }

    @Test fun freeTextSitsAtItsOriginWithTheEditorsInset() {
        val free = block("floating", width = 600f, role = TextRole.FREE, x = 2000f, y = 500f).copy(id = "free")
        val t = texts(scene(note(listOf(free)))).single()
        assertEquals(2000f, t.x, 0.01f); assertEquals(545f + MacMetrics.ascent(48f), t.baseline, 0.01f)
    }

    @Test fun narrowerPageMovesTheMarginAndWrapsEarlier() {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 1u, false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.FLAG, "note", "r", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block("x", width = 1200f)), items))
        assertEquals(1200f + 40f + 56f, texts(s).first { it.text == "note" }.x, 0.01f)
    }
}
