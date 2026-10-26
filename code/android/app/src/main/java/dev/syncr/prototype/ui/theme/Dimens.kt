package dev.syncr.prototype.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** The spacing scale: 4, 8, 12, 16, 24 (and 32 for page-level gaps). Nothing in between. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Side margin of the library and sheets. */
    val page = 20.dp
}

/** The radius scale. [card] is the design's `--r-card`. */
object Radius {
    val s = RoundedCornerShape(8.dp)
    val m = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val pill: Shape = CircleShape
}

/** Minimum touch target for every control. */
val TouchTarget = 48.dp

/**
 * A resting card (design `.lib-thumb`, `.sx-card`): the card colour, a hairline edge and the design's soft
 * two-layer `--shadow`. In dark mode the shadow would vanish, so the edge does the work.
 */
@Composable
@ReadOnlyComposable
fun Modifier.cardSurface(shape: Shape = Radius.card, color: Color = Syncr.colors.chrome): Modifier {
    val c = Syncr.colors
    return this
        .then(if (c.dark) Modifier else Modifier
            .dropShadow(shape, Shadow(radius = 2.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.03f))
            .dropShadow(shape, Shadow(radius = 30.dp, color = Color.Black, offset = DpOffset(0.dp, 10.dp), alpha = 0.04f)))
        .background(color, shape)
        .border(1.dp, c.dots, shape)
}

/**
 * Floating chrome over the paper (design `.tb-pill`, `.tb-row`): card colour, a stronger edge so it never blends
 * into white paper, and a lifted shadow (`0 6px 18px rgba(0,0,0,.08)`).
 */
@Composable
@ReadOnlyComposable
fun Modifier.floatingSurface(shape: Shape = Radius.pill): Modifier {
    val c = Syncr.colors
    return this
        .dropShadow(shape, Shadow(radius = 18.dp, color = Color.Black, offset = DpOffset(0.dp, 6.dp), alpha = if (c.dark) 0.5f else 0.1f))
        .background(c.chrome, shape)
        .border(1.dp, c.lineStrong, shape)
}
