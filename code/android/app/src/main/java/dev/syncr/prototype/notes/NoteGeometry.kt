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
 * Paper in pages. With no [spots] it is today's note: one column 4,800 wide with no bottom edge, panned down to the lowest
 * ink plus one screen (visible bounds stay inside x = 0..4800, y = 0..[lowestInk] + one current screen). With [spots] it is
 * the note's live pages as `layout_pages` placed them: the visible bounds stay on the pages plus a margin, and a document
 * smaller than the view is centred.
 */
data class PagedGeometry(val lowestInk: Float = 0f, val spots: List<PageSpot> = emptyList(), val focusRow: Int? = null) : NoteGeometry {
    private val legacy get() = spots.isEmpty()
    /** What the view may pan over: every page, or in paginated mode only the row being read. */
    private val region = focusRow?.let { r -> spots.filter { it.row == r } }?.takeIf { it.isNotEmpty() } ?: spots
    private val left = region.minOfOrNull { it.x } ?: 0f
    private val top = region.minOfOrNull { it.y } ?: 0f
    private val right = region.maxOfOrNull { it.right } ?: LegacyPage.WIDTH
    private val bottom = region.maxOfOrNull { it.bottom } ?: 0f

    /** What fit-width fits: the widest row, but a legacy auto-height page counts as its 1,600 phone column. */
    private fun fitWidth(): Float {
        val rows = PageSpots.rows(spots)
        return rows.maxOf { row ->
            row.maxOf { if (it.autoHeight) it.x + minOf(it.width, LegacyPage.PHONE_COLUMN) else it.right } - row.minOf { it.x }
        }
    }

    override fun fitScale(viewWidth: Int, viewHeight: Int) =
        if (legacy) (min(viewWidth, viewHeight).toFloat() / LegacyPage.PHONE_COLUMN).coerceAtLeast(0.001f)
        else (viewWidth / (fitWidth() + 2 * Xn.SIDE_MARGIN)).coerceAtLeast(0.001f)

    override fun scaleRange(fit: Float) =
        if (legacy) (fit * 0.5f)..max(fit * 6f, 3f) else Xn.scaleOf(Xn.MIN_ZOOM)..Xn.scaleOf(Xn.MAX_ZOOM)

    /** min and max offsets for [scale]: x then y. A document smaller than the view has one offset (centred). */
    private fun limits(scale: Float, viewWidth: Int, viewHeight: Int): FloatArray {
        val minX: Float; val maxX: Float; val minY: Float; val maxY: Float
        val width = (right - left + 2 * Xn.SIDE_MARGIN) * scale
        if (width <= viewWidth) { minX = (viewWidth - (right + left) * scale) / 2; maxX = minX }
        else { maxX = (Xn.SIDE_MARGIN - left) * scale; minX = viewWidth - (right + Xn.SIDE_MARGIN) * scale }
        val height = (bottom - top + 2 * Xn.VERTICAL_MARGIN) * scale
        if (height <= viewHeight) { minY = (viewHeight - (bottom + top) * scale) / 2; maxY = minY }
        else { maxY = (Xn.VERTICAL_MARGIN - top) * scale; minY = viewHeight - (bottom + Xn.VERTICAL_MARGIN) * scale }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    override fun clamp(m: SheetMapping, viewWidth: Int, viewHeight: Int): SheetMapping {
        if (legacy) {
            val minScale = viewWidth / LegacyPage.WIDTH
            val scale = max(m.scale, minScale)
            val minX = min(0f, viewWidth - LegacyPage.WIDTH * scale)
            val minY = -max(0f, lowestInk) * scale
            return SheetMapping(scale, m.tx.coerceIn(minX, 0f), m.ty.coerceIn(minY, 0f))
        }
        val range = scaleRange(0f)
        val scale = m.scale.coerceIn(range.start, range.endInclusive)
        val l = limits(scale, viewWidth, viewHeight)
        return SheetMapping(scale, m.tx.coerceIn(l[0], l[1]), m.ty.coerceIn(l[2], l[3]))
    }

    override fun flingBounds(m: SheetMapping, viewWidth: Int, viewHeight: Int): FlingBounds {
        if (legacy) {
            val minX = minOf(0f, viewWidth - LegacyPage.WIDTH * m.scale)
            val minY = -lowestInk * m.scale
            return FlingBounds(m.tx.toInt(), m.ty.toInt(), minX.toInt(), minY.toInt())
        }
        val l = limits(m.scale, viewWidth, viewHeight)
        return FlingBounds(m.tx.toInt(), m.ty.toInt(), l[0].toInt(), l[2].toInt(), l[1].toInt(), l[3].toInt())
    }

    override fun contains(x: Float, y: Float) = if (legacy) x in 0f..LegacyPage.WIDTH && y >= 0f else spots.any { it.contains(x, y) }

    override fun inkClip(x: Float, y: Float): CanvasRect? = if (legacy) column else spots.firstOrNull { it.contains(x, y) }?.rect

    override val paper: List<CanvasRect> get() = if (legacy) listOf(column) else spots.map { it.rect }

    /** The page a canvas point is on; null on the desk, or always on a legacy column (which is not a page list). */
    fun pageAt(x: Float, y: Float): PageSpot? = spots.firstOrNull { it.contains(x, y) }

    override val thumbnailWidth get() = spots.firstOrNull()?.width ?: LegacyPage.WIDTH
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
