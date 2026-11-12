package dev.syncr.prototype.tablet

import kotlin.math.min

/** An axis-aligned rectangle in pad pixels. */
data class PadRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * Tablet mode: where the pad's active area sits. One scale for both axes, letterboxed in the part of the pad
 * [bounds] that is free, in the shape [aspectW] : [aspectH] (the Mac's view, or the pen frame). Pure Kotlin.
 */
class TabletMapper(val bounds: PadRect, aspectW: Double, aspectH: Double) {
    init { require(aspectW > 0 && aspectH > 0 && aspectW.isFinite() && aspectH.isFinite()) }

    val activeArea: PadRect = letterbox(bounds, aspectW, aspectH)

    /** Unclamped (u, v) of a pad pixel across the active area; values outside 0..1 are outside it. */
    fun uv(x: Double, y: Double): Pair<Double, Double> =
        Pair((x - activeArea.left) / activeArea.width, (y - activeArea.top) / activeArea.height)

    companion object {
        /** The largest rectangle of shape aspectW:aspectH centred in [bounds]. */
        fun letterbox(bounds: PadRect, aspectW: Double, aspectH: Double): PadRect {
            val scale = min(bounds.width / aspectW, bounds.height / aspectH)
            val w = aspectW * scale
            val h = aspectH * scale
            return PadRect(bounds.left + (bounds.width - w) / 2, bounds.top + (bounds.height - h) / 2, w, h)
        }
    }
}
