package dev.syncr.prototype.editor

import dev.syncr.prototype.notes.FakeShaper
import dev.syncr.prototype.notes.TextMarkdown
import dev.syncr.prototype.notes.Typeset
import org.junit.Assert.*
import org.junit.Test

/** Contract §5.3 "Styled": the field and the rendered note break lines the same way and start at the same place. */
class EditorLayoutTest {
    private val fixture = listOf(
        "# A heading for the page",
        "",
        "A paragraph of plain words that is long enough to wrap onto a second line when the column is only so wide, with a bit left.",
        "Another paragraph with **bold words** and *italic words* and `code` and ==marks== in it, long enough to wrap to a second line of text here.",
        "- first item",
        "- second item with ~~struck~~ words",
        "    - nested item",
        "1. one",
        "2. two",
        "3. three",
        "- [ ] open task",
        "- [x] done task",
        "> a quote of some words",
        "> and its second line",
        "## A smaller heading",
        "Text with a [link](x.io) and a [[Wiki note]] inside of it, then #tag and more words to make it wrap, ok.",
        "---",
        "```swift",
        "let x = 1",
        "let y = \"two\"",
        "```",
        "### Third level",
        "Short.",
        "",
        "...",
        "- - -",
        "Last paragraph: a very long run of words that goes past one line and then continues onto another one without any markers at all in it.",
        "- last bullet",
        "1. numbered again",
        "End.",
    ).joinToString("\n")

    private fun layout(shown: Set<Int>) = Typeset(FakeShaper()).document(fixture, 100f, 260f, 1400f, shown = shown)

    @Test fun thirtyLines() = assertEquals(30, fixture.split("\n").size)

    @Test fun eachParagraphKeepsItsLineCountInBothStates() {
        val reading = layout(emptySet()).vlines.groupBy { it.logical }.mapValues { it.value.size }
        val lines = TextMarkdown.lines(fixture)
        for (k in lines.indices) {
            val offset = lines[k].start + lines[k].text.length / 2
            val shown = Reveal.lines(fixture, offset, offset, lines)
            val editing = layout(shown)
            val counts = editing.vlines.groupBy { it.logical }.mapValues { it.value.size }
            assertEquals("caret on line $k", reading, counts)
        }
    }

    @Test fun theFirstLineStartsWhereTheRenderedNoteDoes() {
        val reading = layout(emptySet()).vlines.first().top
        val lines = TextMarkdown.lines(fixture)
        for (k in lines.indices) {
            val shown = Reveal.lines(fixture, lines[k].start, lines[k].start, lines)
            assertEquals(reading, layout(shown).vlines.first().top, 2f)
        }
    }

    @Test fun aLineIsTheSameHeightWithItsSyntaxShownOrHidden() {
        val lines = TextMarkdown.lines(fixture)
        val reading = layout(emptySet()).vlines
        for (k in lines.indices) {
            val kind = lines[k].kind
            // Fences have no height while hidden, as in the rendered note: they are the one exception.
            if (kind is dev.syncr.prototype.notes.MdKind.Fence) continue
            val shown = layout(setOf(k)).vlines
            val a = reading.filter { it.logical == k }.sumOf { (it.used - it.top).toDouble() }
            val b = shown.filter { it.logical == k }.sumOf { (it.used - it.top).toDouble() }
            assertEquals("line $k", a, b, 0.5)
        }
    }

    @Test fun theFieldsLineBoxesAreTheRenderedNotes() {
        val vlines = layout(emptySet()).vlines
        val lines = TextMarkdown.lines(fixture)
        val typeset = Typeset(FakeShaper())
        for ((i, v) in vlines.withIndex()) {
            val next = vlines.getOrNull(i + 1) ?: continue
            val line = lines[v.logical]
            val runs = typeset.compress(typeset.attributes(line))
            val last = next.logical != v.logical
            val from = v.start - line.start; val to = v.end - line.start
            val box = EditorMetrics.box(EditorMetrics.sizeOf(runs, from, to), last)
            assertEquals("visual line $i", (next.top - v.top).toDouble(), (box.ascent + box.descent).toDouble(), 1.0)
        }
    }

    @Test fun revealFollowsTheSelectionAndFences() {
        val lines = TextMarkdown.lines(fixture)
        assertEquals(setOf(0), Reveal.lines(fixture, 3, 3, lines))
        // A caret inside the code shows both fences.
        val code = lines.indexOfFirst { it.text == "let x = 1" }
        val inside = Reveal.lines(fixture, lines[code].start + 2, lines[code].start + 2, lines)
        assertTrue(lines.indexOfFirst { it.text == "```swift" } in inside)
        assertTrue(lines.indexOfFirst { it.text == "```" } in inside)
        // A selection touches every line it covers, and not the line it ends at the start of.
        val a = lines[4].start + 1; val b = lines[6].start
        assertEquals(setOf(4, 5), Reveal.lines(fixture, a, b, lines))
    }

    /** §5.1: showing a link's syntax on the caret line never breaks that line anywhere else than the rendered note does. */
    private val longLink = "A paragraph of plain words with a [link](https://example.com/a/very/long/target/path/that/goes/on/and/on/and/on/for/a/while/longer) in it."
    private fun lineCount(text: String, shown: Set<Int>) = Typeset(FakeShaper()).document(text, 100f, 260f, 1400f, shown = shown).vlines.size

    @Test fun aLongLinkTargetDoesNotReflowTheCaretLine() {
        val lines = TextMarkdown.lines(longLink)
        val reading = lineCount(longLink, emptySet())
        // Unfitted, showing the target does add lines (so this test is about something).
        val unfitted = Typeset(FakeShaper()).let { t -> t.compress(t.attributes(lines[0], true)) }
        assertTrue(FakeShaper().shape(lines[0].text, unfitted, 1400f).size > reading)
        for (caret in listOf(0, 5, 36, longLink.length / 2, longLink.length)) {
            assertEquals("caret at $caret", reading, lineCount(longLink, Reveal.lines(longLink, caret, caret, lines)))
        }
    }

    @Test fun aLongWikiTargetDoesNotReflowEither() {
        val text = "Words before [[A note with a really quite long title that goes on and on and on for a good while|short]] and words after it to fill the line out."
        val lines = TextMarkdown.lines(text)
        assertEquals(lineCount(text, emptySet()), lineCount(text, Reveal.lines(text, 3, 3, lines)))
    }

    @Test fun aShortLinkStillShowsItsSyntaxOnTheCaretLine() {
        val text = "See [a link](x.io) here."
        val t = Typeset(FakeShaper())
        val line = TextMarkdown.lines(text)[0]
        val attrs = t.attributesFitting(line, true, emptyList(), 1400f)
        // The brackets are faint and sized as the text, not hidden.
        assertEquals(dev.syncr.prototype.notes.Tok.MUTED, attrs[text.indexOf('[')].tok)
        assertEquals(dev.syncr.prototype.notes.Tok.MUTED, attrs[text.indexOf("(x")].tok)
        assertFalse(attrs[text.indexOf("(x")].hidden)
    }

    @Test fun aLinkTargetStaysHiddenOnlyWhenShowingItWouldReflow() {
        val t = Typeset(FakeShaper())
        val line = TextMarkdown.lines(longLink)[0]
        val attrs = t.attributesFitting(line, true, emptyList(), 1400f)
        assertTrue(attrs[longLink.indexOf("(https")].hidden)
        // The words and the opening bracket are as on any other caret line.
        assertEquals(dev.syncr.prototype.notes.Tok.INK, attrs[longLink.indexOf("plain")].tok)
    }
}
