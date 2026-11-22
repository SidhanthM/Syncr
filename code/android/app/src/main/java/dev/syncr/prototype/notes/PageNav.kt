package dev.syncr.prototype.notes

import dev.syncr.core.NoteSnapshot
import kotlin.math.max
import kotlin.math.min

/** The Fit menu's three choices. */
enum class PageFit { WHOLE, WIDTH, HEIGHT }

/** Navigation arithmetic over the page layout. Pure, so each rule is a unit test. */
object PageNav {
    /** The page that fills most of the view: the one with the most visible area, else the nearest to the view's centre. */
    fun currentPage(spots: List<PageSpot>, m: SheetMapping, viewWidth: Int, viewHeight: Int): PageSpot? {
        if (spots.isEmpty()) return null
        val l = -m.tx / m.scale; val t = -m.ty / m.scale
        val r = (viewWidth - m.tx) / m.scale; val b = (viewHeight - m.ty) / m.scale
        fun visible(p: PageSpot) = max(0f, min(p.right, r) - max(p.x, l)) * max(0f, min(p.bottom, b) - max(p.y, t))
        val best = spots.maxByOrNull { visible(it) }!!
        if (visible(best) > 0f) return best
        val cx = (l + r) / 2; val cy = (t + b) / 2
        return spots.minByOrNull { p -> val dx = cx - (p.x + p.width / 2); val dy = cy - (p.y + p.height / 2); dx * dx + dy * dy }
    }

    /** The scale at which [page] fits [fit] in the view with the side and vertical margins. A legacy auto-height page counts its 1,600 column wide. */
    fun fitScale(page: PageSpot, fit: PageFit, viewWidth: Int, viewHeight: Int): Float {
        val w = if (page.autoHeight) min(page.width, LegacyPage.PHONE_COLUMN) else page.width
        val byWidth = viewWidth / (w + 2 * Xn.SIDE_MARGIN)
        val byHeight = viewHeight / (page.height + 2 * Xn.VERTICAL_MARGIN)
        return when (fit) {
            PageFit.WIDTH -> byWidth
            PageFit.HEIGHT -> byHeight
            PageFit.WHOLE -> min(byWidth, byHeight)
        }.coerceIn(Xn.scaleOf(Xn.MIN_ZOOM), Xn.scaleOf(Xn.MAX_ZOOM))
    }

    /** A view of [page] at [scale] with its top-left in view (centred when it is smaller than the view), unclamped. */
    fun showing(page: PageSpot, scale: Float, viewWidth: Int, viewHeight: Int): SheetMapping {
        val w = if (page.autoHeight) min(page.width, LegacyPage.PHONE_COLUMN) else page.width
        val tx = if (w * scale <= viewWidth) (viewWidth - w * scale) / 2 - page.x * scale else (Xn.SIDE_MARGIN - page.x) * scale
        val ty = if (page.height * scale <= viewHeight) (viewHeight - page.height * scale) / 2 - page.y * scale else (Xn.VERTICAL_MARGIN - page.y) * scale
        return SheetMapping(scale, tx, ty)
    }

    /** A view of the whole [row] of pages (one page, a pair): fit to the view's width, centred, unclamped. A legacy page counts its 1,600 column. */
    fun fitRow(row: List<PageSpot>, viewWidth: Int, viewHeight: Int): SheetMapping {
        val left = row.minOf { it.x }; val top = row.minOf { it.y }; val bottom = row.maxOf { it.bottom }
        val right = row.maxOf { if (it.autoHeight) it.x + min(it.width, LegacyPage.PHONE_COLUMN) else it.right }
        val scale = (viewWidth / (right - left + 2 * Xn.SIDE_MARGIN)).coerceIn(Xn.scaleOf(Xn.MIN_ZOOM), Xn.scaleOf(Xn.MAX_ZOOM))
        val tx = if ((right - left) * scale <= viewWidth) (viewWidth - (right - left) * scale) / 2 - left * scale else (Xn.SIDE_MARGIN - left) * scale
        val ty = if ((bottom - top) * scale <= viewHeight) (viewHeight - (bottom - top) * scale) / 2 - top * scale else (Xn.VERTICAL_MARGIN - top) * scale
        return SheetMapping(scale, tx, ty)
    }

    /** Snap to [fit] within 5% of it (zoom limits aside); the page's own fit-width is what a pinch lands on. */
    fun snapToFit(scale: Float, fit: Float): Float = if (kotlin.math.abs(scale / fit - 1f) <= Xn.FIT_SNAP) fit else scale

    /** Where each sheet sits, for [NoteSnapshot]'s sheets: page rects, with a sheet that is no page inside page 1's rect. */
    fun placements(snapshot: NoteSnapshot, spots: List<PageSpot>): Map<String, Placement> {
        if (spots.isEmpty()) return emptyMap()
        val byId = spots.associate { it.sheetId to (it.x to it.y) }
        val first = snapshot.sheets.firstOrNull()?.id?.let(byId::get) ?: spots.first().let { it.x to it.y }
        return snapshot.sheets.associate { it.id to (byId[it.id] ?: first) }
    }

    /** The sheet the text lives on (the document's, else the first page's): its place is the text's origin. */
    fun textSheet(snapshot: NoteSnapshot, spots: List<PageSpot>): String? =
        snapshot.textBlocks.firstOrNull { !it.tombstone && it.role == dev.syncr.core.TextRole.DOCUMENT }?.sheetId
            ?: spots.firstOrNull()?.sheetId ?: snapshot.sheets.firstOrNull()?.id
}
