package dev.syncr.prototype.link

import dev.syncr.prototype.tablet.PadRect
import dev.syncr.prototype.tablet.TabletMapper

/**
 * Tablet Stream (wifi-link-v0 §3.5): the pad maps onto the Mac's view with one scale: view-relative units 0..4800 across and 0..3000 down, letterboxed on the pad. Pure maths, unit tested.
 */
object StreamMapping {
    const val WIDTH = 4800.0
    const val HEIGHT = 3000.0

    /** The part of [bounds] the pad uses: the largest 4800:3000 rectangle, centred. */
    fun area(bounds: PadRect): PadRect = TabletMapper.letterbox(bounds, WIDTH, HEIGHT)

    /** Pad pixel to view-relative units. A point outside [area] clamps to its edge. */
    fun toView(area: PadRect, x: Double, y: Double): Pair<Float, Float> = Pair(
        (((x - area.left) / area.width) * WIDTH).coerceIn(0.0, WIDTH).toFloat(),
        (((y - area.top) / area.height) * HEIGHT).coerceIn(0.0, HEIGHT).toFloat(),
    )

    /** Whether the pad point is inside the active area. */
    fun contains(area: PadRect, x: Double, y: Double) = x >= area.left && x <= area.right && y >= area.top && y <= area.bottom
}

/**
 * Local mode (rework-v1 §V.1): the pad's active area is the pen frame, a rectangle on the Mac's canvas with the pad's
 * shape. The phone maps pad pixels to canvas units itself, with one scale. Pure maths, unit tested.
 */
object FrameMapping {
    const val MIN_WIDTH = 400.0
    const val CANVAS_WIDTH = 4800.0

    /** Pad pixel to canvas units. A point outside [area] clamps to the frame's edge. */
    fun toCanvas(area: PadRect, frame: CanvasRect, x: Double, y: Double): Pair<Float, Float> = Pair(
        (frame.x + ((x - area.left) / area.width).coerceIn(0.0, 1.0) * frame.w).toFloat(),
        (frame.y + ((y - area.top) / area.height).coerceIn(0.0, 1.0) * frame.h).toFloat(),
    )

    fun toPadX(area: PadRect, frame: CanvasRect, canvasX: Double): Float = (area.left + (canvasX - frame.x) / frame.w * area.width).toFloat()
    fun toPadY(area: PadRect, frame: CanvasRect, canvasY: Double): Float = (area.top + (canvasY - frame.y) / frame.h * area.height).toFloat()

    /**
     * Two fingers on the pad, as on a touch screen: the page follows them, and spreading them magnifies it. So a drag
     * moves the frame the other way across the page, and [spanRatio] above 1 (fingers apart) makes the frame smaller.
     * [start] is the frame when the fingers landed with their centre at ([x0], [y0]); the canvas point under that centre
     * stays under the centre now at ([x], [y]). [view] is the Mac's visible canvas, the largest the frame may be.
     */
    fun gesture(area: PadRect, start: CanvasRect, x0: Double, y0: Double, x: Double, y: Double, spanRatio: Double, view: CanvasRect?): CanvasRect {
        val aspect = start.w / start.h
        val most = minOf(CANVAS_WIDTH, view?.let { minOf(it.w, it.h * aspect) } ?: CANVAS_WIDTH)
        val ratio = if (spanRatio.isFinite() && spanRatio > 0) spanRatio else 1.0
        val w = (start.w / ratio).coerceIn(minOf(MIN_WIDTH, most), most)
        val h = w / aspect
        val px = start.x + (x0 - area.left) / area.width * start.w
        val py = start.y + (y0 - area.top) / area.height * start.h
        val left = (px - (x - area.left) / area.width * w).coerceIn(0.0, maxOf(0.0, CANVAS_WIDTH - w))
        val top = maxOf(0.0, py - (y - area.top) / area.height * h)
        return CanvasRect(left, top, w, h)
    }
}
