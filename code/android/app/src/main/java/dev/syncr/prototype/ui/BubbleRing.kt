package dev.syncr.prototype.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/** The ring is drawn around the disc with a small gap (design `.tdot`: `outline-offset: 3px`). */
internal val BubbleRingSize = 54.dp

/**
 * The four ring states (design "Connection, saving and recovery"), each readable without colour or motion:
 * [ACCENT] solid (connected); [ACCENT_DOTTED] dotted and slowly pulsing (work pending); [GREY] with a gap at the
 * top (not connected: saved on this phone only); [ATTENTION] amber with a "!" badge.
 */
enum class RingKind { ACCENT, ACCENT_DOTTED, GREY, ATTENTION }

/**
 * A note's save state as a ring. There is no Mac link in this milestone, so "saved" is the design's grey
 * "Not connected. Notes are saved on the phone" ring; solid accent is reserved for a confirmed Mac.
 */
fun SaveRing.kind() = when (this) {
    SaveRing.SAVED_ON_PHONE -> RingKind.GREY
    SaveRing.SAVING -> RingKind.ACCENT_DOTTED
    SaveRing.ATTENTION -> RingKind.ATTENTION
}

/** The bubble's ring, plus an optional ink-colour pip under the glyph. The disc itself is the morph shape. */
@Composable
fun BubbleRing(kind: RingKind, pip: Color? = null) {
    val c = Syncr.colors
    // The design's pulse: 2.4 s a cycle, 6 s with reduced motion. The dots carry the meaning either way.
    val pulse = if (kind == RingKind.ACCENT_DOTTED) {
        val half = if (Syncr.reducedMotion) 3000 else 1200
        rememberInfiniteTransition(label = "saving").animateFloat(1f, 0.25f,
            Motion.pulse(half), label = "pulse").value
    } else 1f
    Canvas(Modifier.size(BubbleRingSize)) {
        val ringWidth = 2.dp.toPx()
        val r = size.minDimension / 2 - ringWidth / 2 - 1.dp.toPx()
        when (kind) {
            RingKind.ACCENT -> drawCircle(c.accent, r, style = Stroke(ringWidth))
            RingKind.ACCENT_DOTTED -> drawCircle(c.accent, r, alpha = pulse, style = Stroke(ringWidth * 1.25f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx()))))
            RingKind.GREY -> drawArc(c.faint, -60f, 300f, useCenter = false, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                style = Stroke(ringWidth, cap = StrokeCap.Round))
            RingKind.ATTENTION -> {
                drawCircle(c.attention, r, style = Stroke(ringWidth * 1.25f))
                val badge = Offset(size.width - 7.dp.toPx(), 7.dp.toPx())
                drawCircle(c.surface2, 7.dp.toPx(), badge)
                drawCircle(c.attention, 6.dp.toPx(), badge)
                drawLine(c.surface2, badge - Offset(0f, 3.dp.toPx()), badge + Offset(0f, 0.6f.dp.toPx()), 1.6f.dp.toPx(), StrokeCap.Round)
                drawCircle(c.surface2, 0.9f.dp.toPx(), badge + Offset(0f, 2.7f.dp.toPx()))
            }
        }
        if (pip != null) {
            val at = Offset(center.x, size.height - 12.dp.toPx())
            drawCircle(c.surface2, 3.6f.dp.toPx(), at)
            drawCircle(pip, 2.6f.dp.toPx(), at)
        }
    }
}
