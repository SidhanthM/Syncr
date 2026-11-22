package dev.syncr.prototype.notes

import kotlin.math.abs
import kotlin.math.max

/** What letting go of a page in paginated mode does. */
enum class Flip { STAY, NEXT, PREVIOUS, ADD }

/**
 * When a drag past the edge of a page flips to its neighbour (xnotes' `PaginatedFlipPolicy`). Pure: the view feeds it how far
 * the page was pulled past its edge and how fast the finger was moving when it lifted.
 */
object FlipPolicy {
    /** Distance past the edge that flips: 9% of the view or 75 dp, whichever is larger; 1.2x between 125% and 250% zoom. */
    fun pullPx(viewWidthPx: Float, density: Float, zoomPercent: Float): Float {
        val base = max(75f * density, 0.09f * viewWidthPx)
        return if (zoomPercent in 125f..250f) base * 1.2f else base
    }

    /** Past the last page, adding one needs 22% of the view. */
    fun addPx(viewWidthPx: Float) = 0.22f * viewWidthPx

    /**
     * [pull] is how far (px) the page was dragged past its edge: positive toward the next page, negative toward the previous.
     * [velocity] is the finger's velocity at lift (px/s, same sign), [ageMs] how long ago the last movement was. A flick of
     * at least 850 dp/s needs only 55% of the pull; a reversal of at least 170 dp/s vetoes the flip; motion older than 180 ms
     * is no flick. [last] is whether the page is the last: pulling past it adds a page.
     */
    fun decide(pull: Float, velocity: Float, ageMs: Long, last: Boolean, viewWidthPx: Float, density: Float, zoomPercent: Float): Flip {
        if (pull == 0f) return Flip.STAY
        val recent = ageMs <= 180L
        val reversal = recent && abs(velocity) >= 170f * density && (velocity > 0f) != (pull > 0f)
        if (reversal) return Flip.STAY
        val needed = pullPx(viewWidthPx, density, zoomPercent)
        val flick = recent && abs(velocity) >= 850f * density && (velocity > 0f) == (pull > 0f)
        val enough = abs(pull) >= if (flick) needed * 0.55f else needed
        if (!enough) return Flip.STAY
        if (pull < 0f) return Flip.PREVIOUS
        return if (last) (if (abs(pull) >= addPx(viewWidthPx)) Flip.ADD else Flip.STAY) else Flip.NEXT
    }

    /** The vertical pull past the end of the document: rubber-banded by half, up to 320 px; 250 px (after the resistance) adds a page. */
    const val RESIST = 0.5f
    const val MAX_STRETCH = 320f
    const val TRIGGER = 250f

    fun stretch(rawPull: Float) = (rawPull * RESIST).coerceIn(0f, MAX_STRETCH)
    fun armed(rawPull: Float) = stretch(rawPull) >= TRIGGER
}
