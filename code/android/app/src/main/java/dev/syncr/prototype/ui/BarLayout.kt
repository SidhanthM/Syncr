package dev.syncr.prototype.ui

/**
 * The note's tool bar sized to the screen (pure, so it is unit-tested). Eight buttons (Pen, Highlighter, Eraser, Lasso,
 * Type, Undo, Redo, More), a grip and a hairline share the width left after a 16 dp gutter each side: buttons are 48 dp
 * wide when there is room and narrower (never under [MIN_ITEM]) when there is not, so nothing can lie off screen.
 * Fit lives in the More sheet; a double-tap fits too.
 */
internal object BarLayout {
    const val GUTTER = 16f
    const val PAD = 6f
    const val GRIP = 20f
    const val SEPARATOR = 1f
    const val ITEMS = 8
    const val MAX_ITEM = 48f
    const val MIN_ITEM = 34f

    /** The width of each button, in dp, for a screen [screenDp] wide. */
    fun itemWidth(screenDp: Float): Float =
        ((screenDp - 2 * GUTTER - 2 * PAD - GRIP - SEPARATOR) / ITEMS).coerceIn(MIN_ITEM, MAX_ITEM)

    /** Every item's left and right edge, in dp from the bar's left edge, in order: five tools, grip, rule, Undo, Redo, More. */
    fun edges(screenDp: Float): List<ClosedFloatingPointRange<Float>> {
        val w = itemWidth(screenDp)
        var x = PAD
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        fun add(width: Float) { out += x..(x + width); x += width }
        repeat(5) { add(w) }
        add(GRIP); add(SEPARATOR)
        repeat(3) { add(w) }
        return out
    }

    /** The whole bar's width, in dp. */
    fun width(screenDp: Float): Float = edges(screenDp).last().endInclusive + PAD
}
