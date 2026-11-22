package dev.syncr.prototype.notes

import kotlin.math.max
import kotlin.math.min

/** A rectangle in canvas units. An edge may be infinite (the legacy page has no bottom). */
data class CanvasRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** The fixed numbers of a note written before pages: one column 4,800 wide, the phone column 1,600 of it. */
object LegacyPage {
    const val WIDTH = 4800f
    const val VIEW_HEIGHT = 3000f
    const val PHONE_COLUMN = 1600f
}

/**
 * Where the paper is, in canvas units, and the rules the viewport, the wet ink and the cards take from it.
 * A note's pages, the legacy single column and the unbounded canvas each answer the same questions, so no
 * caller needs to know the paper's shape. Pure (no Android types).
 */
interface NoteGeometry {
    /** The scale at which the phone column fills the short side of the view. */
    fun fitScale(viewWidth: Int, viewHeight: Int): Float

    /** The scales a pinch or a zoom button may reach. */
    fun scaleRange(fit: Float): ClosedFloatingPointRange<Float>

    /** Keeps the visible bounds on the paper (or does nothing, on an unbounded canvas). */
    fun clamp(m: SheetMapping, viewWidth: Int, viewHeight: Int): SheetMapping

    /** The scroll limits a fling may travel; a bounded paper has max offsets of 0. */
    fun flingBounds(m: SheetMapping, viewWidth: Int, viewHeight: Int): FlingBounds

    /** Whether a canvas point lies on the paper. */
    fun contains(x: Float, y: Float): Boolean

    /** The paper that wet ink starting at a canvas point is clipped to; null means no clip at all. */
    fun inkClip(x: Float, y: Float): CanvasRect?

    /** The sheets of paper to draw, in canvas units. Empty when the canvas has none. */
    val paper: List<CanvasRect>

    /** The extent library cards draw: width and the height of the view they show. */
    val thumbnailWidth: Float
    val thumbnailHeight: Float
}

/**
 * Paper in columns: today's note is one page 4,800 wide with no bottom edge, panned down to the lowest ink
 * plus one screen. Visible bounds stay inside x = 0..4800, y = 0..[lowestInk] + one current screen.
 */
data class PagedGeometry(val lowestInk: Float = 0f) : NoteGeometry {
    override fun fitScale(viewWidth: Int, viewHeight: Int) =
        (min(viewWidth, viewHeight).toFloat() / LegacyPage.PHONE_COLUMN).coerceAtLeast(0.001f)

    override fun scaleRange(fit: Float) = (fit * 0.5f)..max(fit * 6f, 3f)

    override fun clamp(m: SheetMapping, viewWidth: Int, viewHeight: Int): SheetMapping {
        val minScale = viewWidth / LegacyPage.WIDTH
        val scale = max(m.scale, minScale)
        val minX = min(0f, viewWidth - LegacyPage.WIDTH * scale)
        val minY = -max(0f, lowestInk) * scale
        return SheetMapping(scale, m.tx.coerceIn(minX, 0f), m.ty.coerceIn(minY, 0f))
    }

    override fun flingBounds(m: SheetMapping, viewWidth: Int, viewHeight: Int): FlingBounds {
        val minX = minOf(0f, viewWidth - LegacyPage.WIDTH * m.scale)
        val minY = -lowestInk * m.scale
        return FlingBounds(m.tx.toInt(), m.ty.toInt(), minX.toInt(), minY.toInt())
    }

    override fun contains(x: Float, y: Float) = x in 0f..LegacyPage.WIDTH && y >= 0f

    override fun inkClip(x: Float, y: Float): CanvasRect? = column

    override val paper: List<CanvasRect> get() = listOf(column)

    override val thumbnailWidth get() = LegacyPage.WIDTH
    override val thumbnailHeight get() = LegacyPage.VIEW_HEIGHT

    private val column get() = CanvasRect(0f, 0f, LegacyPage.WIDTH, Float.POSITIVE_INFINITY)
}

/** No edges: pan anywhere, no paper to draw, ink clipped by nothing. The canvas editor fills in its own zoom range and fit. */
object CanvasGeometry : NoteGeometry {
    override fun fitScale(viewWidth: Int, viewHeight: Int) = 1f
    override fun scaleRange(fit: Float) = 0.0155f..49.6f // xnotes 2%..6400% in note units
    override fun clamp(m: SheetMapping, viewWidth: Int, viewHeight: Int) = m
    override fun flingBounds(m: SheetMapping, viewWidth: Int, viewHeight: Int) =
        FlingBounds(m.tx.toInt(), m.ty.toInt(), Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
    override fun contains(x: Float, y: Float) = true
    override fun inkClip(x: Float, y: Float): CanvasRect? = null
    override val paper: List<CanvasRect> get() = emptyList()
    override val thumbnailWidth get() = LegacyPage.WIDTH
    override val thumbnailHeight get() = LegacyPage.VIEW_HEIGHT
}
