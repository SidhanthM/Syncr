package dev.syncr.prototype.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The tools as feathered, monochrome heads: a few thin tapered strands per edge, filled (generated art, `PenArtData`).
 * [spoken] is the content description the controls keep. The ink is shown by the bubble's ring and in the tray, never on the head.
 */
enum class Head(val art: PenArt, val spoken: String) {
    FOUNTAIN(PenArtData.FOUNTAIN, "Fountain pen"),
    BALLPOINT(PenArtData.BALLPOINT, "Ballpoint pen"),
    FINELINER(PenArtData.FINELINER, "Fineliner pen"),
    HIGHLIGHTER(PenArtData.HIGHLIGHTER, "Highlighter"),
    ERASER(PenArtData.ERASER, "Eraser, removes whole strokes"),
    LASSO(PenArtData.LASSO, "Lasso: circle strokes with the pen to select them"),
    CARET(PenArtData.CARET, "Type: tap the paper to type"),
}

/** The draw-on, as a pure schedule the drawing code (and the tests) ask for. */
object PenDrawOn {
    /** One draw-on of the art. */
    const val MS = Motion.PEN_DRAW_MS

    /** How much of each strand is drawn at [progress] (0..1): strands are drawn one after another, by length. */
    fun strokeFractions(art: PenArt, progress: Float): FloatArray {
        val total = art.strands.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(0.001f)
        val reached = progress.coerceIn(0f, 1f) * total
        var start = 0f
        return FloatArray(art.strands.size) { i ->
            val len = art.strands[i].length
            val f = ((reached - start) / len.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            start += len
            f
        }
    }
}

/**
 * A strand's filled outline as plain numbers: left side going forward, right side coming back (tangent at a point = direction
 * from the previous to the next point, clamped at the ends). Built once; a partial strand is a prefix of its points.
 */
class PenOutline(strand: PenStrand) {
    val count = strand.count
    /** x, y pairs of the left and right edge, point by point. */
    val left = FloatArray(count * 2)
    val right = FloatArray(count * 2)

    init {
        val v = strand.xyh
        for (i in 0 until count) {
            val a = maxOf(i - 1, 0); val c = minOf(i + 1, count - 1)
            var dx = v[c * 3] - v[a * 3]; var dy = v[c * 3 + 1] - v[a * 3 + 1]
            val d = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().let { if (it == 0f) 1f else it }
            dx /= d; dy /= d
            val x = v[i * 3]; val y = v[i * 3 + 1]; val h = v[i * 3 + 2]
            left[i * 2] = x - dy * h; left[i * 2 + 1] = y + dx * h
            right[i * 2] = x + dy * h; right[i * 2 + 1] = y - dx * h
        }
    }

    /** The polygon of the first [fraction] of the strand as x, y pairs (left forward, right back); the cut point is interpolated. */
    fun polygon(fraction: Float): FloatArray {
        val pos = fraction.coerceIn(0f, 1f) * (count - 1)
        val last = minOf(pos.toInt(), count - 1)
        val t = if (last >= count - 1) 0f else pos - last
        val kept = last + 1 + if (t > 0f) 1 else 0
        fun at(src: FloatArray, k: Int, axis: Int) =
            if (k <= last) src[k * 2 + axis] else src[last * 2 + axis] + (src[(last + 1) * 2 + axis] - src[last * 2 + axis]) * t
        val out = FloatArray(kept * 4)
        for (k in 0 until kept) {
            out[k * 2] = at(left, k, 0); out[k * 2 + 1] = at(left, k, 1)
            val r = (2 * kept - 1 - k) * 2
            out[r] = at(right, k, 0); out[r + 1] = at(right, k, 1)
        }
        return out
    }
}

/** Decodes the generator's base64 strings (format in `generate-pens.py`). Runs once per glyph, never on a pen path. */
internal object PenArtCodec {
    const val POS_UNIT = 1024f
    const val LEN_UNIT = 256f

    fun decode(data: String): List<PenStrand> {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(data)).order(ByteOrder.LITTLE_ENDIAN)
        return List(b.short.toInt() and 0xFFFF) {
            val n = b.short.toInt() and 0xFFFF
            val length = (b.short.toInt() and 0xFFFF) / LEN_UNIT
            PenStrand(length, FloatArray(n * 3) { b.short / POS_UNIT })
        }
    }

    /** The sum of every quantised x, y and half-width: what the generator wrote next to the string. */
    fun checksum(art: PenArt): Long = art.strands.sumOf { s -> s.xyh.sumOf { Math.round(it * POS_UNIT).toLong() } }
}

private fun polygonPath(poly: FloatArray, into: Path = Path()): Path = into.apply {
    rewind()
    moveTo(poly[0], poly[1])
    var i = 2
    while (i < poly.size) { lineTo(poly[i], poly[i + 1]); i += 2 }
    close()
}

/** The filled shapes of an art, cached: whole strands as paths built once, a partial one rebuilt into a single scratch path. */
private class PenShapes(art: PenArt) {
    val outlines = art.strands.map { PenOutline(it) }
    private val whole = arrayOfNulls<Path>(outlines.size)
    private val scratch = Path()

    fun of(i: Int, fraction: Float): Path =
        if (fraction >= 1f) whole[i] ?: polygonPath(outlines[i].polygon(1f)).also { whole[i] = it }
        else polygonPath(outlines[i].polygon(fraction), scratch)
}

/**
 * A feathered head, [size] square, in [line]. When the selected head or [playKey] changes it is drawn on once, 260 ms
 * ease-out, strand by strand; with animations off it simply appears. Nothing moves at rest.
 * [freeze] (debug builds) holds the draw-on part-way, for screenshots.
 */
@Composable
fun PenHead(head: Head, modifier: Modifier = Modifier, size: Dp = 32.dp, line: Color = Syncr.colors.ink,
    playKey: Any? = null, freeze: Float? = null) {
    val reduced = Syncr.reducedMotion
    val shapes = remember(head) { PenShapes(head.art) }
    val progress = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(head, playKey, reduced) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (reduced || playKey == null) progress.snapTo(1f)
        else { progress.snapTo(0f); progress.animateTo(1f, Motion.penDraw()) }
    }
    Canvas(modifier.size(size)) {
        val p = if (reduced) 1f else freeze ?: progress.value
        scale(this.size.height / PenArtData.HEIGHT, Offset.Zero) {
            val fractions = PenDrawOn.strokeFractions(head.art, p)
            fractions.forEachIndexed { i, f -> if (f > 0f) drawPath(shapes.of(i, f), line) }
        }
    }
}

/** A bar action (undo, redo, fit, more, grip) drawn filled and still, [size] square, in [tint]. */
@Composable
fun PenAction(art: PenArt, tint: Color, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val shapes = remember(art) { PenShapes(art) }
    Canvas(modifier.size(size)) {
        scale(this.size.height / PenArtData.HEIGHT, Offset.Zero) {
            for (i in shapes.outlines.indices) drawPath(shapes.of(i, 1f), tint)
        }
    }
}
