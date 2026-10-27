package dev.syncr.prototype.link

import dev.syncr.prototype.tablet.PadRect
import dev.syncr.prototype.tablet.TabletMapping

/**
 * Tablet Stream (wifi-link-v0 §3.5): the pad maps onto the Mac's view with one scale, as the Whole-screen pointer
 * mapping does: view-relative units 0..4800 across and 0..3000 down, letterboxed on the pad. Pure maths, unit tested.
 */
object StreamMapping {
    const val WIDTH = 4800.0
    const val HEIGHT = 3000.0

    /** The part of [bounds] the pad uses: the largest 4800:3000 rectangle, centred. */
    fun area(bounds: PadRect): PadRect = TabletMapping.letterbox(bounds, WIDTH, HEIGHT)

    /** Pad pixel to view-relative units. A point outside [area] clamps to its edge, like the pointer's HID clamp. */
    fun toView(area: PadRect, x: Double, y: Double): Pair<Float, Float> = Pair(
        (((x - area.left) / area.width) * WIDTH).coerceIn(0.0, WIDTH).toFloat(),
        (((y - area.top) / area.height) * HEIGHT).coerceIn(0.0, HEIGHT).toFloat(),
    )

    /** Whether the pad point is inside the active area. */
    fun contains(area: PadRect, x: Double, y: Double) = x >= area.left && x <= area.right && y >= area.top && y <= area.bottom
}
