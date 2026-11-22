package dev.syncr.prototype.stipple

import dev.syncr.prototype.notes.TextLine
import dev.syncr.prototype.ui.theme.Motion
import kotlin.math.abs

/**
 * What changed on the page when a new scene replaces the one on screen (editor-v2 §8, the phone's part R): which companion
 * items left, which arrived, and how far the text below them moves. Pure; [TextLayer] animates it. Items leaving dissolve
 * out (the stipple run backwards) while the old page still shows, then the text glides to its new place, then items
 * arriving dissolve in: room opens before content arrives, and nothing jumps.
 */
internal object PageChange {
    /** [fromY] is where, in the new scene, the text that moves starts; [delta] is how far it moved (negative: up). */
    class Plan(val leaving: Set<String>, val arriving: Set<String>, val fromY: Float, val delta: Float) {
        val glides get() = delta != 0f
    }

    /**
     * The plan from the scene on screen to the new one, or null when no item came or went. The text only glides when it is
     * the same text (an edit on the page moves lines for its own reasons) and every line after the first that moved moved
     * by the same amount (so a single shift describes it).
     */
    fun plan(beforeItems: Set<String>, afterItems: Set<String>, beforeText: String, afterText: String,
        beforeLines: List<TextLine>, afterLines: List<TextLine>): Plan? {
        val leaving = beforeItems - afterItems
        val arriving = afterItems - beforeItems
        if (leaving.isEmpty() && arriving.isEmpty()) return null
        var fromY = 0f; var delta = 0f
        if (beforeText == afterText && beforeLines.size == afterLines.size) {
            val first = afterLines.indices.firstOrNull { abs(afterLines[it].top - beforeLines[it].top) > 0.5f }
            if (first != null) {
                val d = afterLines[first].top - beforeLines[first].top
                if ((first until afterLines.size).all { abs(afterLines[it].top - beforeLines[it].top - d) <= 0.5f }) { fromY = afterLines[first].top; delta = d }
            }
        }
        return Plan(leaving, arriving, fromY, delta)
    }

    enum class Phase { LEAVE, GLIDE, ARRIVE, DONE }

    /** [progress] is 0..1 within [phase]. */
    data class Step(val phase: Phase, val progress: Float)

    /** The three phases in order, each present only when it has something to do. Durations come from [Motion]. */
    class Timeline(leaves: Boolean, glides: Boolean, arrives: Boolean) {
        val leaveMs = if (leaves) Motion.LEAVE_MS else 0
        val glideMs = if (glides) Motion.SLOW_MS else 0
        val arriveMs = if (arrives) Motion.REVEAL_MS else 0
        val totalMs get() = leaveMs + glideMs + arriveMs

        fun at(elapsedMs: Long): Step {
            val t = elapsedMs.coerceAtLeast(0)
            if (t < leaveMs) return Step(Phase.LEAVE, t.toFloat() / leaveMs)
            if (t < leaveMs + glideMs) return Step(Phase.GLIDE, (t - leaveMs).toFloat() / glideMs)
            if (t < totalMs) return Step(Phase.ARRIVE, (t - leaveMs - glideMs).toFloat() / arriveMs)
            return Step(Phase.DONE, 1f)
        }

        /** The phase a frozen debug dissolve ([Stipple.freeze]) shows: the leaving one when there is one, else the arriving one. */
        fun frozen(progress: Float): Step =
            if (leaveMs > 0) Step(Phase.LEAVE, progress) else if (arriveMs > 0) Step(Phase.ARRIVE, progress) else Step(Phase.GLIDE, 1f)
    }
}
