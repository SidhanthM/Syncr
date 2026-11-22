package dev.syncr.prototype.canvas

import dev.syncr.core.Waypoint
import dev.syncr.prototype.notes.SheetMapping
import kotlin.math.max
import kotlin.math.min

/** xnotes px to Syncr note units (contract "Units"): A4 portrait width is exactly 1600 units. */
internal const val XN = (1600.0 / 1240.157).toFloat()

/** A rectangle in canvas units. */
internal data class UnitRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun union(o: UnitRect) = UnitRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun outset(d: Double) = UnitRect(left - d, top - d, right + d, bottom + d)
    fun intersects(o: UnitRect) = right >= o.left && left <= o.right && bottom >= o.top && top <= o.bottom
}

/**
 * The window onto the unbounded canvas, after xnotes' `CanvasViewport`: a zoom plus the canvas point at the view's
 * top-left, with no clamp on where it goes. Zoom is the xnotes zoom (1.0 = 100% = one xnotes px per device px), so the
 * device scale is `zoom / XN` pixels per note unit. State is Double so a canvas panned a million units out still
 * lines up; [mapping] hands the view the usual float [SheetMapping]. Pure: no Android types.
 */
internal class InfiniteViewport {
    var widthPx = 0
    var heightPx = 0

    var zoom = 1.0
        private set

    /** Canvas unit at the view's left and top edges. */
    var scrollX = 0.0
        private set
    var scrollY = 0.0
        private set

    val scale get() = zoom / XN

    val sized get() = widthPx > 0 && heightPx > 0

    fun mapping() = SheetMapping(scale.toFloat(), (-scrollX * scale).toFloat(), (-scrollY * scale).toFloat())

    /** Adopts a float mapping, to compute a fit, a step or a jump from where the view is now. */
    fun from(m: SheetMapping, w: Int, h: Int) {
        widthPx = w; heightPx = h
        zoom = (m.scale.toDouble() * XN).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollX = -m.tx.toDouble() / scale
        scrollY = -m.ty.toDouble() / scale
    }

    /** Restores a mapping, for the retained view. */
    fun set(zoom: Double, scrollX: Double, scrollY: Double) {
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        this.scrollX = scrollX
        this.scrollY = scrollY
    }

    val centerX get() = scrollX + widthPx / 2.0 / scale
    val centerY get() = scrollY + heightPx / 2.0 / scale

    /** The canvas rectangle on screen. */
    fun visible() = UnitRect(scrollX, scrollY, scrollX + widthPx / scale, scrollY + heightPx / scale)

    fun toCanvasX(x: Float) = scrollX + x / scale
    fun toCanvasY(y: Float) = scrollY + y / scale

    /** Pans by a finger delta in pixels, then zooms by [factor] about the screen point ([fx], [fy]). */
    fun navigate(dx: Float, dy: Float, factor: Float, fx: Float, fy: Float) {
        if (factor != 1f) zoomAround(fx, fy, zoom * factor)
        scrollX -= dx / scale
        scrollY -= dy / scale
    }

    /** Zooms to [target] keeping the canvas point under ([fx], [fy]) where it is. Returns the zoom reached. */
    fun zoomAround(fx: Float, fy: Float, target: Double): Double {
        val ax = toCanvasX(fx)
        val ay = toCanvasY(fy)
        zoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollX = ax - fx / scale
        scrollY = ay - fy / scale
        return zoom
    }

    /** A zoom button: one [STEP] in or out about the centre. */
    fun step(inward: Boolean) {
        zoomAround(widthPx / 2f, heightPx / 2f, if (inward) zoom * STEP else zoom / STEP)
    }

    fun centerOn(cx: Double, cy: Double) {
        scrollX = cx - widthPx / 2.0 / scale
        scrollY = cy - heightPx / 2.0 / scale
    }

    /**
     * "Fit all": the content plus [FIT_PAD_XN] xnotes px each side, centred. An empty canvas goes to 100% at the origin,
     * and a degenerate box (one dot) keeps the current zoom.
     */
    fun fitAll(content: UnitRect?) {
        if (!sized) return
        if (content == null) {
            zoom = 1.0
            centerOn(0.0, 0.0)
            return
        }
        val box = content.outset(FIT_PAD_XN * XN.toDouble())
        // zoom = scale * XN; scale is the fraction of the pixels the box needs.
        val s = min(widthPx / box.width, heightPx / box.height)
        zoom = (s * XN).coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerOn(box.centerX, box.centerY)
    }

    fun toWaypoint(name: String) = Waypoint(name, centerX.toFloat(), centerY.toFloat(), zoom.toFloat())

    fun apply(w: Waypoint) {
        zoom = w.zoom.toDouble().coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerOn(w.cx.toDouble(), w.cy.toDouble())
    }

    /** A size change keeps the centre where it was. */
    fun resize(w: Int, h: Int) {
        if (sized) {
            val cx = centerX
            val cy = centerY
            widthPx = w; heightPx = h
            centerOn(cx, cy)
        } else {
            widthPx = w; heightPx = h
        }
    }

    /** Pinch end: within 5% of a round zoom (100%) snaps to it; nothing else snaps on a canvas. */
    fun snapAfterPinch(fx: Float, fy: Float) {
        if (kotlin.math.abs(zoom - 1.0) <= 0.03) zoomAround(fx, fy, 1.0)
    }

    companion object {
        const val MIN_ZOOM = 0.02
        const val MAX_ZOOM = 64.0
        const val STEP = 1.25
        const val FIT_PAD_XN = 48.0
    }
}
