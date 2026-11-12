package dev.syncr.prototype.notes

import kotlin.math.hypot

/**
 * The canvas-to-screen mapping and every navigation that changes it, applying the [Viewport] rules.
 * Pure (no Android types): the view feeds it sizes and gestures and draws with [mapping].
 */
internal class CanvasViewport {
    /** Canvas units to view pixels; null until the view has a size or a retained viewport. */
    var mapping: SheetMapping? = null
        private set

    var layout = CanvasLayout()
        private set

    var width = 0
        private set

    var height = 0
        private set

    val fitScale: Float get() = Viewport.fitScale(width, height)

    /** A newly opened note starts from its retained viewport, if any, at the left edge. */
    fun reset(retained: SheetMapping?) {
        mapping = retained?.copy(tx = 0f)
    }

    /** New ink extent: re-clamp, or place the default view once the size is known. */
    fun setLayout(next: CanvasLayout) {
        layout = next
        if (width > 0 && height > 0) mapping = mapping?.let(::clamp) ?: Viewport.defaultView(layout, width, height)
    }

    /** Rotation and width changes keep the zoom but return to the left edge; a change of height alone (the keyboard) keeps the place. */
    fun resize(newWidth: Int, newHeight: Int, oldWidth: Int) {
        width = newWidth
        height = newHeight
        mapping = Viewport.resized(layout, mapping?.takeIf { oldWidth > 0 }, newWidth, newHeight, keepLeft = newWidth == oldWidth)
    }

    /** Back to the phone column at the fit scale, keeping the top canvas row. */
    fun fitColumn() {
        mapping = Viewport.fitColumn(layout, mapping, width, height)
    }

    /** Zooms to [scale] about a screen point, without the pinch scale limits. */
    fun zoomTo(scale: Float, focusX: Float, focusY: Float) {
        val m = mapping ?: return
        mapping = clamp(m.navigate(0f, 0f, scale / m.scale, focusX, focusY))
    }

    /** Zooms to [scale] with the sheet's left edge [tx] pixels in, keeping the sheet point at height [anchorSheetY] where it is on screen (the text column fills the width). */
    fun fitText(scale: Float, tx: Float, anchorSheetY: Float, anchorScreenY: Float) {
        mapping = clamp(SheetMapping(scale, tx, anchorScreenY - anchorSheetY * scale))
    }

    /** Pan by ([dx], [dy]) and zoom by [factor] about a screen point, within [Viewport.scaleRange]. */
    fun navigate(dx: Float, dy: Float, factor: Float, focusX: Float, focusY: Float) {
        val m = mapping ?: return
        val next = (m.scale * factor).coerceIn(Viewport.scaleRange(fitScale))
        mapping = clamp(m.navigate(dx, dy, next / m.scale, focusX, focusY))
    }

    /** Pinch end: snap to fit-width or 100% when close to either. */
    fun snapAfterPinch(focusX: Float, focusY: Float) {
        val m = mapping ?: return
        val target = Viewport.snapScale(m.scale, fitScale)
        if (target != m.scale) zoomTo(target, focusX, focusY)
    }

    /** Double-tap at fit zooms in to at least 100%. Returns false when not at fit, so the caller fits instead. */
    fun zoomInFromFit(x: Float, y: Float): Boolean {
        val m = mapping ?: return false
        val fit = fitScale
        if (!Viewport.isFit(m.scale, fit)) return false
        zoomTo(maxOf(1f, fit * 2f), x, y)
        return true
    }

    /** One fling animation step. */
    fun scrollTo(tx: Float, ty: Float) {
        mapping?.let { mapping = clamp(it.copy(tx = tx, ty = ty)) }
    }

    /** Where a fling starts and how far it may travel (the max offsets are both 0). */
    fun flingBounds(): FlingBounds? {
        val m = mapping ?: return null
        val minX = minOf(0f, width - CanvasLayout.WIDTH * m.scale)
        val minY = -layout.lowestInk * m.scale
        return FlingBounds(m.tx.toInt(), m.ty.toInt(), minX.toInt(), minY.toInt())
    }

    /** Whether a screen point lies on the canvas. */
    fun contains(x: Float, y: Float): Boolean {
        val m = mapping ?: return false
        val (cx, cy) = m.toSheet(x, y)
        return layout.contains(cx, cy)
    }

    private fun clamp(m: SheetMapping) = Viewport.clamp(m, layout, width, height)
}

internal data class FlingBounds(val startX: Int, val startY: Int, val minX: Int, val minY: Int)

/**
 * Finger pan and pinch: each move pans by the change in the pointers' centroid and zooms by the
 * change in their spread, about the previous centroid. Forgetting the focus whenever a pointer goes
 * down or up makes the next move re-anchor instead of jumping.
 */
internal class PanZoomTracker {
    /** The previous centroid, or null when the next move only re-anchors. */
    var focus: Pt? = null
        private set

    private var span = 0f

    /** A second finger touched during this sequence: lifting snaps the zoom. */
    var pinched = false
        private set

    /** First finger down. */
    fun start() {
        focus = null
        pinched = false
    }

    fun secondPointerDown() {
        pinched = true
    }

    /** Any pointer down or up, a pen contact or a cancel. */
    fun reanchor() {
        focus = null
    }

    /**
     * [xs] and [ys] are the current pointer positions. A single finger still within tap slop
     * ([withinTapSlop]) only moves the anchor, so a tap never pans.
     */
    fun move(xs: FloatArray, ys: FloatArray, withinTapSlop: Boolean, viewport: CanvasViewport) {
        val x = xs.average().toFloat()
        val y = ys.average().toFloat()
        val spread = if (xs.size >= 2) hypot(xs[0] - xs[1], ys[0] - ys[1]) else 0f
        val previous = focus
        if (!(xs.size == 1 && withinTapSlop) && previous != null) {
            val factor = if (spread > 0 && span > 0) spread / span else 1f
            viewport.navigate(x - previous.first, y - previous.second, factor, previous.first, previous.second)
        }
        focus = x to y
        span = spread
    }
}
