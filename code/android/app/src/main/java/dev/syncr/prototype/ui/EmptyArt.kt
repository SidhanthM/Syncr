package dev.syncr.prototype.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/** The portion of each stroke of a drawing that is on paper at [progress] (0 to 1): the strokes are drawn one after another by length. */
internal fun drawnLengths(lengths: List<Float>, progress: Float): List<Float> {
    val total = lengths.sum()
    if (total <= 0f) return lengths.map { 0f }
    var left = progress.coerceIn(0f, 1f) * total
    return lengths.map { len -> val used = minOf(len, left).coerceAtLeast(0f); left -= used; used }
}

/**
 * A small hand-drawn picture for an empty place ([EmptyArtData]): drawn on once when it appears, ink-black lines with a
 * faint second pass in the muted tone, then still. Reduced motion shows it finished. Decorative: the words beside it say it.
 */
@Composable
fun EmptyArt(strokes: List<ArtStroke>, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    val reduced = Syncr.reducedMotion
    val paths = remember(strokes) { strokes.map { PathParser().parsePathString(it.d).toPath() } }
    val progress = remember(strokes) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(strokes, reduced) { if (reduced) progress.snapTo(1f) else progress.animateTo(1f, Motion.drawOn()) }
    Canvas(modifier.clearAndSetSemantics { }) {
        val scale = size.width / EmptyArtData.WIDTH
        val measures = paths.map { PathMeasure().apply { setPath(it, false) } }
        val lengths = measures.map { it.length }
        val drawn = drawnLengths(lengths, progress.value)
        strokes.forEachIndexed { i, stroke ->
            if (drawn[i] <= 0f) return@forEachIndexed
            val piece = Path()
            measures[i].getSegment(0f, drawn[i], piece, true)
            withTransform({ scale(scale, scale, Offset.Zero) }) {
                drawPath(piece, if (stroke.faint) c.muted.copy(alpha = 0.55f) else c.ink,
                    style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
