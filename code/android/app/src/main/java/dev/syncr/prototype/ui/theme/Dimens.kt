package dev.syncr.prototype.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
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
fun Modifier.cardSurface(shape: Shape = Radius.card, color: Color = Syncr.colors.surface1): Modifier {
    val c = Syncr.colors
    return this
        .then(if (c.dark) Modifier else Modifier
            .dropShadow(shape, Shadow(radius = 2.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.03f))
            .dropShadow(shape, Shadow(radius = 30.dp, color = Color.Black, offset = DpOffset(0.dp, 10.dp), alpha = 0.04f)))
        .background(color, shape)
        .border(1.dp, c.dots, shape)
}

/** The two raised levels of the surface ladder. */
enum class Level { TWO, THREE }

/**
 * A raised surface ([Level.TWO]: sheets, the bubble, toolbars; [Level.THREE]: menus, dialogs, toasts). The
 * ladder's colour, a soft shadow (12 or 20 dp blur; 10% or 14% black, 40% in dark mode so a menu still parts from
 * a sheet) and, in dark mode, a 1 dp top-edge highlight (white at 6% or 8%). All of it is drawn when the surface
 * is, never per frame of anything under it.
 */
@Composable
@ReadOnlyComposable
fun Modifier.raised(level: Level, shape: Shape = Radius.card): Modifier {
    val c = Syncr.colors
    val two = level == Level.TWO
    val shadow = Shadow(radius = if (two) 12.dp else 20.dp, color = Color.Black,
        offset = DpOffset(0.dp, if (two) 4.dp else 8.dp),
        alpha = if (c.dark) 0.4f else if (two) 0.10f else 0.14f)
    val highlight = if (c.dark) Color.White.copy(alpha = if (two) 0.06f else 0.08f) else null
    return this
        .dropShadow(shape, shadow)
        .background(if (two) c.surface2 else c.surface3, shape)
        .then(if (highlight == null) Modifier else Modifier.drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = when (outline) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> outline.path
            }
            clipPath(path) { drawRect(highlight, size = Size(size.width, 1.dp.toPx())) }
        })
}

/**
 * Floating chrome over the paper (design `.tb-pill`, `.tb-row`): a raised level-two surface with a stronger edge so
 * it never blends into white paper.
 */
@Composable
@ReadOnlyComposable
fun Modifier.floatingSurface(shape: Shape = Radius.pill): Modifier =
    this.raised(Level.TWO, shape).border(1.dp, Syncr.colors.lineStrong, shape)
