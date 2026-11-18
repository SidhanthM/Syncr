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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.stipple.Stipple
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/**
 * The tools as hand-drawn heads (generated art, `PenArtData`). [spoken] is the content description the controls keep.
 * [inkLook] marks the heads that carry a remembered ink on their tip.
 */
enum class Head(val art: PenArt, val spoken: String, val inkLook: Boolean) {
    FOUNTAIN(PenArtData.FOUNTAIN, "Fountain pen", true),
    BALLPOINT(PenArtData.BALLPOINT, "Ballpoint pen", true),
    FINELINER(PenArtData.FINELINER, "Fineliner pen", true),
    HIGHLIGHTER(PenArtData.HIGHLIGHTER, "Highlighter", true),
    ERASER(PenArtData.ERASER, "Eraser, removes whole strokes", false),
    LASSO(PenArtData.LASSO, "Lasso: circle strokes with the pen to select them", false),
    CARET(PenArtData.CARET, "Type: tap the paper to type", false),
}

/** The draw-on, as pure schedules the drawing code (and the tests) ask for. */
object PenDrawOn {
    /** One draw-on: the line art, then the ink. */
    const val MS = Motion.PEN_DRAW_MS
    /** The share of the draw-on spent drawing lines; the ink dissolves in over the rest. */
    private const val LINES_SHARE = 0.62f

    /** How much of each stroke is drawn at [progress] (0..1): strokes are drawn one after another, by length. */
    fun strokeFractions(art: PenArt, progress: Float): FloatArray {
        val total = art.strokes.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(0.001f)
        val reached = (progress / LINES_SHARE).coerceIn(0f, 1f) * total
        var start = 0f
        return FloatArray(art.strokes.size) { i ->
            val len = art.strokes[i].length
            val f = ((reached - start) / len.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            start += len
            f
        }
    }

    /** How far the ink has dissolved in at [progress]: 0 until the lines are mostly down, 1 at the end. */
    fun inkProgress(progress: Float): Float = ((progress - LINES_SHARE * 0.6f) / (1f - LINES_SHARE * 0.6f)).coerceIn(0f, 1f)

    /** Whether the stipple cell shows ink at [ink] progress. */
    fun cellShown(col: Int, row: Int, ink: Float): Boolean = ink >= 1f || Stipple.threshold(col, row) < ink
}

private fun cubicPath(curve: FloatArray, close: Boolean = false): Path = Path().apply {
    moveTo(curve[0], curve[1])
    var i = 2
    while (i + 5 < curve.size) {
        cubicTo(curve[i], curve[i + 1], curve[i + 2], curve[i + 3], curve[i + 4], curve[i + 5]); i += 6
    }
    if (close) close()
}

private class HeadPaths(val strokes: List<Path>, val ink: List<Path>)

/**
 * A hand-drawn head, tip down, [height] tall. The line art is [line]; the ink-bearing part is filled with [ink] when the
 * head has one. When the selected head or [playKey] changes the outline is drawn on once, 260 ms ease-out, and the ink
 * dissolves in through the stipple at the end; with animations off it simply appears. Nothing moves at rest.
 * [freeze] (debug builds) holds the draw-on part-way, for screenshots.
 */
@Composable
fun PenHead(head: Head, ink: Color?, modifier: Modifier = Modifier, height: Dp = 32.dp, line: Color = Syncr.colors.ink,
    playKey: Any? = null, freeze: Float? = null) {
    val reduced = Syncr.reducedMotion
    val paths = remember(head) { HeadPaths(head.art.strokes.map { cubicPath(it.curve) }, head.art.ink.map { cubicPath(it, close = true) }) }
    val progress = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(head, playKey, reduced) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (reduced || playKey == null) progress.snapTo(1f)
        else { progress.snapTo(0f); progress.animateTo(1f, Motion.penDraw()) }
    }
    val cell = with(LocalDensity.current) { Motion.STIPPLE_CELL_DP.dp.toPx() }
    val width = height * (PenArtData.WIDTH / PenArtData.HEIGHT)
    val measure = remember { PathMeasure() }
    val piece = remember { Path() }
    Canvas(modifier.size(width, height)) {
        val p = if (reduced) 1f else freeze ?: progress.value
        val k = size.height / PenArtData.HEIGHT
        val inkColour = ink
        if (inkColour != null && head.inkLook) {
            val t = PenDrawOn.inkProgress(p)
            if (t > 0f) paths.ink.forEach { shape -> drawInk(shape, inkColour, t, k, cell) }
        }
        scale(k, Offset.Zero) {
            val fractions = PenDrawOn.strokeFractions(head.art, p)
            head.art.strokes.forEachIndexed { i, s ->
                val f = fractions[i]
                if (f <= 0f) return@forEachIndexed
                val style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (f >= 1f) drawPath(paths.strokes[i], line, style = style)
                else {
                    measure.setPath(paths.strokes[i], false)
                    piece.rewind()
                    measure.getSegment(0f, measure.length * f, piece, true)
                    drawPath(piece, line, style = style)
                }
            }
        }
    }
}

/** The ink part: solid at the end, a dissolve of stipple cells (the house Bayer pattern) on the way there. */
private fun DrawScope.drawInk(shape: Path, colour: Color, t: Float, k: Float, cell: Float) {
    val scaled = Path().apply { addPath(shape); transform(androidx.compose.ui.graphics.Matrix().apply { scale(k, k) }) }
    if (t >= 1f) { drawPath(scaled, colour); return }
    val box: Rect = scaled.getBounds()
    val dot = cell * 0.62f
    clipPath(scaled) {
        val c0 = (box.left / cell).toInt(); val c1 = (box.right / cell).toInt() + 1
        val r0 = (box.top / cell).toInt(); val r1 = (box.bottom / cell).toInt() + 1
        for (r in r0..r1) for (c in c0..c1) if (PenDrawOn.cellShown(c, r, t)) {
            drawCircle(colour, dot, Offset((c + 0.5f) * cell, (r + 0.5f) * cell))
        }
    }
}
