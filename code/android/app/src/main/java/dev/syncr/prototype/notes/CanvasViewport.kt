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

    var geometry: NoteGeometry = PagedGeometry()
        private set

    var width = 0
        private set

    var height = 0
        private set

    val fitScale: Float get() = geometry.fitScale(width, height)

    /** A newly opened note starts from its retained viewport, if any, at the left edge. */
    fun reset(retained: SheetMapping?) {
        mapping = retained?.copy(tx = 0f)
    }

    /** New paper: re-clamp, or place the default view once the size is known. */
    fun setGeometry(next: NoteGeometry) {
        geometry = next
        if (width > 0 && height > 0) mapping = mapping?.let(::clamp) ?: Viewport.defaultView(geometry, width, height)
    }

    /** Rotation and width changes keep the zoom but return to the left edge; a change of height alone (the keyboard) keeps the place. */
    fun resize(newWidth: Int, newHeight: Int, oldWidth: Int) {
        width = newWidth
        height = newHeight
        mapping = Viewport.resized(geometry, mapping?.takeIf { oldWidth > 0 }, newWidth, newHeight, keepLeft = newWidth == oldWidth)
    }

    /** Back to the phone column at the fit scale, keeping the top canvas row. */
    fun fitColumn() {
        mapping = Viewport.fitColumn(geometry, mapping, width, height)
    }

    /** Jumps to [m] (a page, a fit), kept on the paper. */
    fun setMapping(m: SheetMapping) { mapping = clamp(m) }

    /** Zooms to [scale] about a screen point, without the pinch scale limits. */
    fun zoomTo(scale: Float, focusX: Float, focusY: Float) {
        val m = mapping ?: return
        mapping = clamp(m.navigate(0f, 0f, scale / m.scale, focusX, focusY))
    }

    /** Zooms to [scale] with the sheet's left edge [tx] pixels in, keeping the sheet point at height [anchorSheetY] where it is on screen (the text column fills the width). */
    fun fitText(scale: Float, tx: Float, anchorSheetY: Float, anchorScreenY: Float) {
        mapping = clamp(SheetMapping(scale, tx, anchorScreenY - anchorSheetY * scale))
    }

    /**
     * How far (px) the finger has dragged past the end of the paper without the paper following: [pullX] is positive toward
     * the end (the next page), negative toward the start; [pullY] is positive past the bottom. Only a pan with no zoom adds to
     * them, and dragging back takes them off first. They feed the pull-to-add and the page flip, and are drawn as a stretch.
     */
    var pullX = 0f
        private set
    var pullY = 0f
        private set

    fun setPull(x: Float, y: Float) { pullX = x; pullY = y }

    /** Pan by ([dx], [dy]) and zoom by [factor] about a screen point, within the geometry's scale range. */
    fun navigate(dx: Float, dy: Float, factor: Float, focusX: Float, focusY: Float, track: Boolean = false) {
        val m = mapping ?: return
        var mx = dx; var my = dy
        if (factor == 1f && track) {
            // Dragging back from a pull first takes the pull off.
            if (pullX > 0f && mx > 0f) { val a = minOf(pullX, mx); pullX -= a; mx -= a }
            if (pullX < 0f && mx < 0f) { val a = minOf(-pullX, -mx); pullX += a; mx += a }
            if (pullY > 0f && my > 0f) { val a = minOf(pullY, my); pullY -= a; my -= a }
        }
        val next = (m.scale * factor).coerceIn(geometry.scaleRange(fitScale))
        val moved = m.navigate(mx, my, next / m.scale, focusX, focusY)
        val clamped = clamp(moved)
        if (factor == 1f && track) {
            // What the paper refused to move is the finger's pull past its end.
            pullX -= moved.tx - clamped.tx
            if (moved.ty < clamped.ty) pullY += clamped.ty - moved.ty
        }
        mapping = clamped
    }

    /** Pinch end: snap to fit-width (and, on a legacy column, 100%) when close. Pages snap to fit-width within 5%. */
    fun snapAfterPinch(focusX: Float, focusY: Float) {
        val m = mapping ?: return
        val paged = (geometry as? PagedGeometry)?.spots?.isNotEmpty() == true
        val target = if (paged) PageNav.snapToFit(m.scale, fitScale) else Viewport.snapScale(m.scale, fitScale)
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

    /** Where a fling starts and how far it may travel (a bounded paper's max offsets are both 0). */
    fun flingBounds(): FlingBounds? {
        val m = mapping ?: return null
        return geometry.flingBounds(m, width, height)
    }

    /** Whether a screen point lies on the canvas. */
    fun contains(x: Float, y: Float): Boolean {
        val m = mapping ?: return false
        val (cx, cy) = m.toSheet(x, y)
        return geometry.contains(cx, cy)
    }

    private fun clamp(m: SheetMapping) = geometry.clamp(m, width, height)
}

data class FlingBounds(val startX: Int, val startY: Int, val minX: Int, val minY: Int, val maxX: Int = 0, val maxY: Int = 0)

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
            viewport.navigate(x - previous.first, y - previous.second, factor, previous.first, previous.second, track = true)
        }
        focus = x to y
        span = spread
    }
}
