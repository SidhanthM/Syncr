package dev.syncr.prototype.link

/** Pointer extension v1 is negotiated independently of note-sync/pen link versions. */
data class PointerStatus(val version: Int = 0, val ready: Boolean = false, val message: String = "Update the Mac app to use Trackpad.")

/**
 * Relative logical-point motion; buttons is the current left-button state, not a toggle.
 * [scrollEnd] closes a two-finger scroll (the Mac sends its "ended" phase); it travels as an optional `se` key.
 */
data class PointerInput(val dx: Double = 0.0, val dy: Double = 0.0, val sx: Double = 0.0, val sy: Double = 0.0,
    val buttons: Int = 0, val click: Int = 0, val scrollEnd: Boolean = false) {
    fun valid() = listOf(dx, dy, sx, sy).all { it.isFinite() && kotlin.math.abs(it) <= 2048 } && buttons in 0..1 && click in 0..3
}
