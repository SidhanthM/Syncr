// Adapted from xnotes (MIT, Copyright (c) 2026 Shardul Vikram Singh), app/src/main/java/com/xnotes/canvas/FrontInk.kt
package dev.syncr.prototype.notes

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The pure logic of the front-buffer pad, kept free of GL, Android and the model so it can be unit
 * tested: which path a stroke takes, which pixels a present must rebuild, when the pad lets go of
 * its strokes, and the input-to-flush statistics.
 */

enum class WetPath { BRIDGE, PAD }

/**
 * The path one stroke takes. The pad draws only upright, solid-colour ink (highlighters and anything
 * translucent stay on the bridge), only when the setting is on, the probe passed, and the pad is not in the
 * middle of letting go of earlier strokes. Everything else is exactly today's path.
 */
fun chooseWetPath(settingOn: Boolean, padAvailable: Boolean, upright: Boolean, solidColour: Boolean, padCanTake: Boolean): WetPath =
    if (settingOn && padAvailable && upright && solidColour && padCanTake) WetPath.PAD else WetPath.BRIDGE

/** A box in view pixels, y down. */
data class PxBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isEmpty: Boolean get() = !(right > left && bottom > top)
}

/** A whole-pixel box in view pixels, y down; right and bottom are exclusive. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersects(o: PxBox) = o.left < right && o.right > left && o.top < bottom && o.bottom > top
}

/** What a present must rebuild. */
object FrontInkDamage {
    /** Pixels of antialiasing fringe beyond the geometry. */
    const val AA_PX = 2f

    /**
     * The pixels the stroke's new tail ([current]) and its previous tail ([previous], which may have moved or
     * retracted) can reach, grown by the brush's half-width and the antialiasing fringe, cut to [clip]. Null
     * when nothing is left to draw.
     */
    fun rect(previous: PxBox?, current: PxBox?, halfWidthPx: Float, clip: PxRect): PxRect? {
        val a = previous?.takeUnless { it.isEmpty }
        val b = current?.takeUnless { it.isEmpty }
        if (a == null && b == null) return null
        val l = min(a?.left ?: Float.MAX_VALUE, b?.left ?: Float.MAX_VALUE)
        val t = min(a?.top ?: Float.MAX_VALUE, b?.top ?: Float.MAX_VALUE)
        val r = max(a?.right ?: -Float.MAX_VALUE, b?.right ?: -Float.MAX_VALUE)
        val bt = max(a?.bottom ?: -Float.MAX_VALUE, b?.bottom ?: -Float.MAX_VALUE)
        val grow = halfWidthPx.coerceAtLeast(0f) + AA_PX
        val out = PxRect(
            max(floor(l - grow).toInt(), clip.left), max(floor(t - grow).toInt(), clip.top),
            min(ceil(r + grow).toInt(), clip.right), min(ceil(bt + grow).toInt(), clip.bottom),
        )
        return out.takeUnless { it.isEmpty }
    }

    /** Union of two boxes where either may be absent. */
    fun union(a: PxBox?, b: PxBox?): PxBox? = when {
        a == null || a.isEmpty -> b
        b == null || b.isEmpty -> a
        else -> PxBox(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }
}

/** The view a pad stroke is painted through. Strokes join only when this is exactly equal. */
data class PadView(val mapping: SheetMapping, val width: Int, val height: Int)

enum class PadPen { FRESH, JOINED, BUSY }

/**
 * When the pad lets go of its strokes (design: contract F2).
 *
 * A finished stroke stays on the pad while the dry path draws it underneath. A pen-down while strokes wait
 * joins the pad: nothing is wiped. Once the pad has been idle (no pen down, every waiting stroke's dry ink
 * ready) the owner calls [idleElapsed]; that moves to RELEASING, where the dry layer is allowed to draw the
 * strokes ([isFinishing] turns false) and the owner waits for the canvas frame carrying them to be committed
 * before calling [cleared], which empties the pad. A pan, zoom or note switch calls [force] first. While
 * RELEASING no stroke may take the pad ([canTake] is false): it goes down the old path.
 *
 * Main thread only.
 */
class FrontInkHandover {
    enum class Phase { IDLE, DOWN, WAITING, RELEASING }

    var phase = Phase.IDLE
        private set

    /** Waiting strokes by preview id, and whether each one's dry ink is ready to be adopted. */
    private val held = LinkedHashMap<String, Boolean>()
    private var view: PadView? = null

    /** Incremented by everything that outdates a release in flight, so a stale commit cannot clear the pad. */
    var generation = 0
        private set

    val canTake: Boolean get() = phase != Phase.RELEASING
    val waitingCount: Int get() = held.size
    val holdsStrokes: Boolean get() = held.isNotEmpty()
    val heldView: PadView? get() = if (held.isEmpty()) null else view

    /** The dry layer skips a stroke until the pad starts letting go of it. */
    fun isFinishing(previewId: String): Boolean = phase != Phase.RELEASING && held.containsKey(previewId)

    /** Whether a stroke on [now] may take the pad: not mid-release, not a second pen, and the same view as any waiting stroke. */
    fun canStart(now: PadView): Boolean = (phase == Phase.IDLE || phase == Phase.WAITING) && (held.isEmpty() || view == now)

    /** Pen-down on the pad. BUSY means the stroke must take the old path; the owner should [force] if the view moved. */
    fun penDown(now: PadView): PadPen {
        if (!canStart(now)) return PadPen.BUSY
        val joined = held.isNotEmpty()
        view = now
        phase = Phase.DOWN
        generation++ // a join cancels any idle wait or release not yet started
        return if (joined) PadPen.JOINED else PadPen.FRESH
    }

    /** The view changed (pan, zoom, resize). True when waiting strokes must be handed over now. */
    fun viewChanged(now: PadView): Boolean = held.isNotEmpty() && phase != Phase.DOWN && view != now && force()

    /** The pen lifted on a staged stroke. */
    fun penUp(previewId: String) {
        if (phase != Phase.DOWN) return
        held[previewId] = false
        phase = Phase.WAITING
    }

    /** The stroke under the pen was thrown away. */
    fun penCancelled() {
        if (phase != Phase.DOWN) return
        phase = if (held.isEmpty()) Phase.IDLE else Phase.WAITING
    }

    /** The dry ink for [previewId] is built. True when that makes the pad ready to release. */
    fun strokeReady(previewId: String): Boolean {
        if (held[previewId] == null) return false
        held[previewId] = true
        return phase == Phase.WAITING && held.values.all { it }
    }

    /** The idle wait is over. True when the owner must now hand the strokes to the dry layer. */
    fun idleElapsed(generationAtArm: Int): Boolean {
        if (generationAtArm != generation || phase != Phase.WAITING || held.isEmpty() || !held.values.all { it }) return false
        phase = Phase.RELEASING
        return true
    }

    /** Hand over now (pan, zoom, note switch, surface lost). True when there was something to hand over. */
    fun force(): Boolean {
        if (held.isEmpty() || phase == Phase.RELEASING) return false
        if (phase == Phase.DOWN) return false
        generation++
        phase = Phase.RELEASING
        return true
    }

    fun releasingIds(): List<String> = if (phase == Phase.RELEASING) held.keys.toList() else emptyList()

    /** The canvas frame carrying the released strokes is committed: the pad may be cleared. */
    fun cleared(expected: Int): Boolean {
        if (phase != Phase.RELEASING || expected != generation) return false
        held.clear()
        phase = Phase.IDLE
        generation++
        return true
    }

    /** The pad's pixels are gone already (surface lost, note switch, leaving the window). */
    fun abandon() {
        held.clear()
        phase = Phase.IDLE
        generation++
    }
}

/**
 * Input-to-flush latencies (not input-to-photon): the time from a pen sample's event to the `glFlush`
 * that put it into the front buffer. [record] returns the log line every [WINDOW] samples.
 */
class FlushLatencyStats(private val window: Int = WINDOW) {
    private val ms = FloatArray(window)
    private var n = 0

    fun record(nanos: Long): String? {
        ms[n++] = max(nanos, 0L) / 1_000_000f
        if (n < window) return null
        val sorted = ms.copyOf().also { it.sort() }
        val line = String.format(
            java.util.Locale.ROOT, "p50=%.1f p95=%.1f max=%.1f n=%d",
            sorted[(window * 50 / 100).coerceAtMost(window - 1)],
            sorted[(window * 95 / 100).coerceAtMost(window - 1)],
            sorted[window - 1], window,
        )
        n = 0
        return line
    }

    companion object {
        const val WINDOW = 200
    }
}
