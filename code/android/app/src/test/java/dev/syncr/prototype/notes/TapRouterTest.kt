package dev.syncr.prototype.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mobile polish §3.1: every row of the tap table and precedence rules 1 to 5. */
class TapRouterTest {
    // Two lines: "hello" (0..5) at y 100..180 and "world" (6..11) at y 180..260; the last line claims only its own height.
    private val lines = listOf(TextLine(0, 5, 100f, 180f), TextLine(6, 11, 180f, 260f))
    private val text = "hello\nworld"
    private fun ctx(palm: Boolean = false, selection: Boolean = false, chrome: Boolean = false, task: Pair<String, Int>? = null,
        content: ContentTarget? = null, onStroke: Boolean = false, typing: Boolean = true, lines: List<TextLine> = this.lines) =
        TapContext(palm, selection, chrome, task, content, onStroke, typing, lines)

    // ---- the table ----
    @Test fun tapOnTextKeepsTheCaretWhereItLanded() {
        val t = ContentTarget("doc", false)
        assertEquals(TapAction.Content(t), TapRouter.route(120f, ctx(content = t)))
    }
    @Test fun emptyPaperBesideALineGoesToThatLinesEnd() {
        assertEquals(TapAction.Type(CaretWish.At(5)), TapRouter.route(130f, ctx()))
        assertEquals(TapAction.Type(CaretWish.At(11)), TapRouter.route(200f, ctx()))
    }
    @Test fun aGapBetweenLinesBelongsToTheLineAbove() {
        val gap = listOf(TextLine(0, 5, 100f, 150f), TextLine(6, 11, 190f, 260f))
        assertEquals(TapAction.Type(CaretWish.At(5)), TapRouter.route(170f, ctx(lines = gap)))
    }
    @Test fun belowTheLastLineIsTheEndOnANewLine() {
        assertEquals(TapAction.Type(CaretWish.EndOfDocumentNewLine), TapRouter.route(900f, ctx()))
    }
    @Test fun aboveTheFirstLineIsTheStart() {
        assertEquals(TapAction.Type(CaretWish.StartOfDocument), TapRouter.route(20f, ctx()))
    }
    @Test fun anEmptyDocumentStartsAtZero() {
        assertEquals(TapAction.Type(CaretWish.StartOfDocument), TapRouter.route(500f, ctx(lines = emptyList())))
    }

    // ---- where the caret lands, and the newline ----
    @Test fun endOfDocumentAddsALineOnlyWhenTheLastLineHasWords() {
        assertEquals(Triple("hello\nworld\n", 12, true), TapRouter.place(CaretWish.EndOfDocumentNewLine, text))
        assertEquals(Triple("hello\n", 6, false), TapRouter.place(CaretWish.EndOfDocumentNewLine, "hello\n"))
        assertEquals(Triple("", 0, false), TapRouter.place(CaretWish.EndOfDocumentNewLine, ""))
    }
    @Test fun lineEndAndStartPlaceTheCaretWithoutChangingText() {
        assertEquals(Triple(text, 5, false), TapRouter.place(CaretWish.At(5), text))
        assertEquals(Triple(text, 11, false), TapRouter.place(CaretWish.At(99), text))
        assertEquals(Triple(text, 0, false), TapRouter.place(CaretWish.StartOfDocument, text))
    }

    // ---- precedence ----
    @Test fun rule1aSelectionOnlyDismisses() {
        assertEquals(TapAction.Dismiss, TapRouter.route(130f, ctx(selection = true)))
        assertEquals(TapAction.Dismiss, TapRouter.route(130f, ctx(selection = true, content = ContentTarget("doc", false))))
    }
    @Test fun rule1bAnOpenBarOrMenuOnlyDismisses() {
        assertEquals(TapAction.Dismiss, TapRouter.route(900f, ctx(chrome = true)))
    }
    @Test fun rule1theNextTapTypes() {
        assertEquals(TapAction.Type(CaretWish.EndOfDocumentNewLine), TapRouter.route(900f, ctx(selection = false, chrome = false)))
    }
    @Test fun rule2PalmWindowIgnoresFingers() {
        assertEquals(TapAction.Ignored, TapRouter.route(130f, ctx(palm = true)))
        assertEquals(TapAction.Ignored, TapRouter.route(130f, ctx(palm = true, task = "doc" to 1)))
    }
    @Test fun rule3CompanionContentOpensAndTaskBoxesFlip() {
        val answer = ContentTarget("root", true)
        assertEquals(TapAction.Content(answer), TapRouter.route(130f, ctx(content = answer)))
        assertEquals(TapAction.Content(answer), TapRouter.route(130f, ctx(content = answer, selection = true, chrome = true)))
        assertEquals(TapAction.Task("doc", 2), TapRouter.route(130f, ctx(task = "doc" to 2, selection = true)))
    }
    @Test fun aStrokeOrANonTypingToolGoesToTheSelectionAsBefore() {
        assertEquals(TapAction.Selection, TapRouter.route(130f, ctx(onStroke = true)))
        assertEquals(TapAction.Selection, TapRouter.route(130f, ctx(typing = false)))
    }

    // ---- rule 5: the double-tap wait ----
    @Test fun rule5WaitsForTheSystemDoubleTapWindowOnlyWhenFitIsOnAndNotTyping() {
        assertEquals(300L, typingWaitMs(true, false, 300L))
        assertEquals(0L, typingWaitMs(false, false, 300L))
        assertEquals(0L, typingWaitMs(true, true, 300L)) // the Type tool, or the pen put away: at once
        assertEquals(400L, typingWaitMs(true, false, 400L)) // never more than the system's own timeout
    }
}
