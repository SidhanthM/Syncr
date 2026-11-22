package dev.syncr.prototype.notes

import dev.syncr.core.NoteKind
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PageExtent
import dev.syncr.core.PageMode
import dev.syncr.core.PageRect
import dev.syncr.core.PageView
import dev.syncr.core.layoutPages

/** xnotes px (150 dpi) to note units, and back: 1600 units are 1240.157 px, so A4 portrait is exactly 1600 wide. */
object Xn {
    const val UNITS = 1600f / 1240.157f
    const val GAP = 20.6f // 16 xnotes px between pages
    const val VERTICAL_MARGIN = 62f // 48 xnotes px above the first row and below the last in scroll mode
    const val SIDE_MARGIN = 20.6f
    /** Zoom percentages are xnotes percentages: 100% is one xnotes px per device px. */
    fun scaleOf(zoom: Float) = zoom / UNITS
    fun zoomOf(scale: Float) = scale * UNITS
    const val MIN_ZOOM = 0.12f
    const val MAX_ZOOM = 16f
    const val ZOOM_STEP = 1.25f
    const val FIT_SNAP = 0.05f
}

/** Single, Double (pairs 1-2, 3-4) or Cover (1 alone, then 2-3, 4-5). A per-note view setting stored on this device. */
enum class PageArrangement { SINGLE, DOUBLE, COVER }

/** How a note's pages are laid out for reading on this device: [mode] and vertical scroll versus paginated [flip]. */
data class PageViewSettings(val mode: PageArrangement = PageArrangement.SINGLE, val flip: Boolean = false)

internal fun PageArrangement.core() = when (this) {
    PageArrangement.SINGLE -> PageMode.SINGLE
    PageArrangement.DOUBLE -> PageMode.DOUBLE
    PageArrangement.COVER -> PageMode.COVER
}

/** One live page at its place in canvas units. [row] is its row (a flip strip column in paginated mode). */
data class PageSpot(val sheetId: String, val index: Int, val row: Int, val x: Float, val y: Float, val width: Float, val height: Float,
    val autoHeight: Boolean, val resolved: PageView) {
    val right get() = x + width
    val bottom get() = y + height
    val rect get() = CanvasRect(x, y, right, bottom)
    fun contains(px: Float, py: Float) = px in x..right && py in y..bottom
}

/**
 * The note's live pages placed by the core's `layout_pages`. A legacy single-page note (one auto-height page, or no page list)
 * gives an empty list: it keeps today's open-ended column. Pure but for the core call, which tests replace via [layout].
 */
object PageSpots {
    /** The legacy page keeps today's 1,600 phone column for placing others in Single mode; its paper is still 4,800 wide. */
    private const val LEGACY_PLACING_WIDTH = 1600f

    /** Space between neighbouring rows of a paginated strip: at least 100 dp of desk, in units at the phone's fit scale. */
    const val FLIP_GAP = 400f

    fun isLegacy(snapshot: NoteSnapshot): Boolean {
        if (snapshot.kind == NoteKind.CANVAS) return false
        val live = snapshot.pages.filter { !it.tombstone }
        return live.isEmpty() || (live.size == 1 && live[0].autoHeight)
    }

    fun of(snapshot: NoteSnapshot, settings: PageViewSettings,
        layout: (List<PageExtent>, PageMode, Float) -> List<PageRect> = { pages, mode, gap -> layoutPages(pages, mode, gap) }): List<PageSpot> {
        if (snapshot.kind == NoteKind.CANVAS || isLegacy(snapshot)) return emptyList()
        val live = snapshot.pages.filter { !it.tombstone }
        val byId = live.associateBy { it.sheetId }
        val single = settings.mode == PageArrangement.SINGLE
        val extents = live.map { PageExtent(it.sheetId, if (it.autoHeight && single) LEGACY_PLACING_WIDTH else it.width, it.height) }
        val rects = layout(extents, settings.mode.core(), Xn.GAP)
        val placed = if (settings.flip) strip(rects) else rects
        return placed.mapIndexed { i, r ->
            val page = byId.getValue(r.sheetId)
            PageSpot(r.sheetId, i, r.row.toInt(), r.x, r.y, page.width, page.height, page.autoHeight, page)
        }
    }

    /** Paginated mode: the same rows side by side, each starting at the top, with room between them. */
    private fun strip(rects: List<PageRect>): List<PageRect> {
        val rows = rects.groupBy { it.row }.toSortedMap()
        var cursor = 0f
        val out = ArrayList<PageRect>()
        for ((_, row) in rows) {
            val left = row.minOf { it.x }
            val top = row.minOf { it.y }
            val right = row.maxOf { it.x + it.width }
            for (r in row) out += r.copy(x = r.x - left + cursor, y = r.y - top)
            cursor += (right - left) + FLIP_GAP
        }
        val order = rects.map { it.sheetId }
        return out.sortedBy { order.indexOf(it.sheetId) }
    }

    /** The row index of each page, for flipping a whole row at once. */
    fun rows(spots: List<PageSpot>): List<List<PageSpot>> = spots.groupBy { it.row }.toSortedMap().values.toList()
}
