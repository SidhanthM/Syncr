package dev.syncr.prototype.notes

/**
 * One visual line of the note's text column, in canvas units. [end] is where a caret goes for "the end of that line":
 * the line's last character, or for a line the column wraps, just before the space it broke at (a caret after the
 * space would draw at the start of the next line). [bottom] is where the line's own claim on the page ends.
 */
internal data class TextLine(val start: Int, val end: Int, val top: Float, val bottom: Float)

/** Where a tap on paper wants the caret. */
internal sealed interface CaretWish {
    /** Exactly this character offset: the end of the line that was tapped beside. */
    data class At(val offset: Int) : CaretWish
    /** On a new empty line after the last one; an empty last line is reused. */
    data object EndOfDocumentNewLine : CaretWish
    data object StartOfDocument : CaretWish
}

/** What a finger tap on the paper does. */
internal sealed interface TapAction {
    /** Inside the palm window after a pen lift: nothing. */
    data object Ignored : TapAction
    /** The tap only dismisses a selection or an open bar or menu (the next tap types). */
    data object Dismiss : TapAction
    /** A rendered task box: flip it, as before. */
    data class Task(val block: String, val line: Int) : TapAction
    /** Companion content opens, and text under the finger types with the caret where it landed, as before. */
    data class Content(val target: ContentTarget) : TapAction
    /** Empty paper types: the caret goes where the wish says. */
    data class Type(val wish: CaretWish) : TapAction
    /** A stroke under the finger, or a tool that does not type (lasso, eraser): the tap goes to the selection as before. */
    data object Selection : TapAction
}

/** What the tap router needs to know about the tap, read when the finger came down and lifted. */
internal data class TapContext(
    /** The finger came down inside the palm window after a pen lift. */
    val palm: Boolean,
    /** A selection existed when the finger came down. */
    val selection: Boolean,
    /** The tool bar, its options or a menu was open when the finger came down (a touch folds them as it lands). */
    val chrome: Boolean,
    val task: Pair<String, Int>?,
    val content: ContentTarget?,
    val onStroke: Boolean,
    /** The Type tool is active, or the tool is one a finger tap may type with (not Lasso or Eraser). */
    val typingAllowed: Boolean,
    val lines: List<TextLine>,
)

/**
 * The single place that decides what a finger tap on paper does (mobile polish §3.1). Pure, so every row of the table
 * and every precedence rule is a unit test. The double-tap wait and the keyboard are the canvas's business.
 */
internal object TapRouter {
    fun route(y: Float, c: TapContext): TapAction {
        if (c.palm) return TapAction.Ignored
        c.task?.let { return TapAction.Task(it.first, it.second) }
        val content = c.content
        // Companion content opens as before, even over a selection or an open bar.
        if (content != null && content.response) return TapAction.Content(content)
        if (c.selection || c.chrome) return TapAction.Dismiss
        if (c.onStroke) return TapAction.Selection
        if (!c.typingAllowed) return TapAction.Selection
        if (content != null) return TapAction.Content(content)
        return TapAction.Type(wish(y, c.lines))
    }

    /** Beside a line: its end. Below the last line: a new last line. Above the first: the start. */
    fun wish(y: Float, lines: List<TextLine>): CaretWish {
        if (lines.isEmpty()) return CaretWish.StartOfDocument
        if (y < lines.first().top) return CaretWish.StartOfDocument
        if (y >= lines.last().bottom) return CaretWish.EndOfDocumentNewLine
        // A gap between lines belongs to the line above it.
        val line = lines.lastOrNull { it.top <= y } ?: lines.first()
        return CaretWish.At(line.end)
    }

    /**
     * The text and caret a [wish] starts typing with, for a document of [text]. A new line is added only when the last
     * line has words; the pair's second part says that the newline is the tap's own, not the writer's.
     */
    fun place(wish: CaretWish, text: String): Triple<String, Int, Boolean> = when (wish) {
        is CaretWish.At -> Triple(text, wish.offset.coerceIn(0, text.length), false)
        CaretWish.StartOfDocument -> Triple(text, 0, false)
        CaretWish.EndOfDocumentNewLine ->
            if (text.isEmpty() || text.endsWith("\n")) Triple(text, text.length, false) else Triple(text + "\n", text.length + 1, true)
    }
}

/**
 * Whether a finger tap that has lifted must wait for a possible second tap before it types: only when double-tap to
 * fit is on and the Type tool is not active. The wait is the system's double-tap timeout and never longer.
 */
internal fun typingWaitMs(doubleTapFit: Boolean, typingTool: Boolean, systemDoubleTapMs: Long): Long =
    if (doubleTapFit && !typingTool) systemDoubleTapMs else 0L
