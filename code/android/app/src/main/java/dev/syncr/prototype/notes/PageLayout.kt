package dev.syncr.prototype.notes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A note's pages stacked vertically in "document" units: page i spans x ∈ [0, pageWidth] and
 * y ∈ [top(i), top(i) + pageHeight], with [gap] units between pages. Sizes always come from the
 * snapshot (pageWidth/pageHeight), never hardcoded. A [SheetMapping] maps document units to screen
 * pixels; [pageMapping] derives the mapping for one page's own units (the sample coordinate space).
 */
data class PageLayout(val pageWidth: Float, val pageHeight: Float, val count: Int, val gap: Float = DEFAULT_GAP) {
    init { require(pageWidth > 0 && pageHeight > 0 && count >= 0) }
    fun top(index: Int) = index * (pageHeight + gap)
    val height get() = if (count == 0) 0f else count * pageHeight + (count - 1) * gap

    /** Page under a document point, or null in a gap/outside. Pen-down there is ignored. */
    fun pageAt(x: Float, y: Float): Int? {
        if (count == 0 || x < 0 || x > pageWidth || y < 0) return null
        val index = (y / (pageHeight + gap)).toInt()
        if (index >= count) return null
        return if (y - top(index) <= pageHeight) index else null
    }
    fun pageMapping(view: SheetMapping, index: Int) = SheetMapping(view.scale, view.tx, view.ty + top(index) * view.scale)
    /** Pages intersecting the screen rows [0, viewHeight]. Empty when none is visible. */
    fun visible(view: SheetMapping, viewHeight: Int): IntRange {
        if (count == 0) return IntRange.EMPTY
        val first = (0 until count).firstOrNull { view.ty + (top(it) + pageHeight) * view.scale >= 0 } ?: return IntRange.EMPTY
        val last = (count - 1 downTo 0).firstOrNull { view.ty + top(it) * view.scale <= viewHeight } ?: return IntRange.EMPTY
        return if (last < first) IntRange.EMPTY else first..last
    }
    /** 1-based page shown at the middle of the screen (the nearest page when the middle is a gap). */
    fun current(view: SheetMapping, viewHeight: Int): Int {
        if (count == 0) return 0
        val middle = (viewHeight / 2f - view.ty) / view.scale
        val index = (0 until count).minBy { i ->
            val top = top(i); val bottom = top + pageHeight
            if (middle in top..bottom) 0f else min(abs(middle - top), abs(middle - bottom))
        }
        return index + 1
    }
    companion object { const val DEFAULT_GAP = 48f }
}

/** Viewport rules: fit-width scale, clamping and pinch snapping. Pixels in, pixels out. */
object Viewport {
    /** Scale at which a page fills the width minus [gutter] on each side. */
    fun fitScale(layout: PageLayout, viewWidth: Int, gutter: Float) =
        ((viewWidth - 2 * gutter) / layout.pageWidth).coerceAtLeast(0.001f)

    /** Fit the width, keeping the document row now at the middle of the screen in place. */
    fun fitWidth(layout: PageLayout, current: SheetMapping?, viewWidth: Int, viewHeight: Int, gutter: Float, top: Float): SheetMapping {
        val scale = fitScale(layout, viewWidth, gutter)
        val ty = if (current == null) top else {
            val middle = (viewHeight / 2f - current.ty) / current.scale
            viewHeight / 2f - middle * scale
        }
        return clamp(SheetMapping(scale, gutter, ty), layout, viewWidth, viewHeight, gutter, top, 0f)
    }

    /** Zoom limits relative to fit-width. */
    fun scaleRange(fit: Float) = (fit * 0.5f)..max(fit * 6f, 3f)

    /**
     * Keeps the pages reachable: horizontally centred when narrower than the screen, otherwise
     * edge-clamped with a gutter; vertically from [top] above the first page to [bottom] extra
     * pixels below the last (room for the "Add page" affordance).
     */
    fun clamp(m: SheetMapping, layout: PageLayout, viewWidth: Int, viewHeight: Int, gutter: Float, top: Float, bottom: Float): SheetMapping {
        val w = layout.pageWidth * m.scale
        val tx = if (w + 2 * gutter <= viewWidth) (viewWidth - w) / 2 else m.tx.coerceIn(viewWidth - w - gutter, gutter)
        val h = layout.height * m.scale
        val minTy = min(top, viewHeight - h - bottom)
        return SheetMapping(m.scale, tx, m.ty.coerceIn(minTy, top))
    }

    /** At pinch end, snap to fit-width or to 100% (one note unit per pixel) when close to either. */
    fun snapScale(scale: Float, fit: Float, tolerance: Float = 0.08f): Float = when {
        abs(scale / fit - 1f) <= tolerance -> fit
        abs(scale - 1f) <= tolerance -> 1f
        else -> scale
    }

    /** Double-tap on empty paper: fit the width, or from fit go to 100% around the tap. */
    fun isFit(scale: Float, fit: Float) = abs(scale / fit - 1f) <= 0.01f
}

/** Auto-add (owner decision 6): a committed stroke on the LAST page reaching below 75% of its height. */
fun shouldAutoAddPage(pageIndex: Int, pageCount: Int, strokeMaxY: Float, pageHeight: Float, addPending: Boolean,
    threshold: Float = 0.75f): Boolean =
    !addPending && pageCount > 0 && pageIndex == pageCount - 1 && strokeMaxY >= threshold * pageHeight
