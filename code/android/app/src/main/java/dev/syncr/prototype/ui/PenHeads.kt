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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/**
 * The tools as straight, monochrome line-art heads (generated art, `PenArtData`). [spoken] is the content description the
 * controls keep. The ink is shown by the bubble's ring and in the tray, never on the head.
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
    /** One draw-on of the line art. */
    const val MS = Motion.PEN_DRAW_MS

    /** How much of each stroke is drawn at [progress] (0..1): strokes are drawn one after another, by length. */
    fun strokeFractions(art: PenArt, progress: Float): FloatArray {
        val total = art.strokes.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(0.001f)
        val reached = progress.coerceIn(0f, 1f) * total
        var start = 0f
        return FloatArray(art.strokes.size) { i ->
            val len = art.strokes[i].length
            val f = ((reached - start) / len.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            start += len
            f
        }
    }
}

private fun cubicPath(curve: FloatArray): Path = Path().apply {
    moveTo(curve[0], curve[1])
    var i = 2
    while (i + 5 < curve.size) {
        cubicTo(curve[i], curve[i + 1], curve[i + 2], curve[i + 3], curve[i + 4], curve[i + 5]); i += 6
    }
}

/**
 * A line-art head, tip down, [height] tall, in [line]. When the selected head or [playKey] changes it is drawn on once,
 * 260 ms ease-out; with animations off it simply appears. Nothing moves at rest.
 * [freeze] (debug builds) holds the draw-on part-way, for screenshots.
 */
@Composable
fun PenHead(head: Head, modifier: Modifier = Modifier, height: Dp = 32.dp, line: Color = Syncr.colors.ink,
    playKey: Any? = null, freeze: Float? = null) {
    val reduced = Syncr.reducedMotion
    val paths = remember(head) { head.art.strokes.map { cubicPath(it.curve) } }
    val progress = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(head, playKey, reduced) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (reduced || playKey == null) progress.snapTo(1f)
        else { progress.snapTo(0f); progress.animateTo(1f, Motion.penDraw()) }
    }
    val width = height * (PenArtData.WIDTH / PenArtData.HEIGHT)
    val measure = remember { PathMeasure() }
    val piece = remember { Path() }
    Canvas(modifier.size(width, height)) {
        val p = if (reduced) 1f else freeze ?: progress.value
        val k = size.height / PenArtData.HEIGHT
        scale(k, Offset.Zero) {
            val fractions = PenDrawOn.strokeFractions(head.art, p)
            head.art.strokes.forEachIndexed { i, s ->
                val f = fractions[i]
                if (f <= 0f) return@forEachIndexed
                val style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (f >= 1f) drawPath(paths[i], line, style = style)
                else {
                    measure.setPath(paths[i], false)
                    piece.rewind()
                    measure.getSegment(0f, measure.length * f, piece, true)
                    drawPath(piece, line, style = style)
                }
            }
        }
    }
}
