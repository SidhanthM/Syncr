package dev.syncr.prototype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Syncr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the open bar sits for a bubble centred at (centerX, centerY), all in container pixels. */
data class BarPlacement(val left: Float, val top: Float, val growsRight: Boolean)

/**
 * The bar grows from the bubble toward the side with room for it; when both sides have room it grows toward
 * the middle of the screen, and when neither does it takes the roomier side and is clamped inside [margin].
 * Its rounded end stays concentric with the bubble, so the circle reads as stretching into the pill.
 */
fun barPlacement(centerX: Float, centerY: Float, barWidth: Float, barHeight: Float,
    containerWidth: Float, containerHeight: Float, margin: Float): BarPlacement {
    val cap = barHeight / 2
    val roomRight = containerWidth - margin - (centerX - cap)
    val roomLeft = centerX + cap - margin
    val growsRight = when {
        barWidth <= roomRight && barWidth <= roomLeft -> centerX <= containerWidth / 2
        barWidth <= roomRight -> true
        barWidth <= roomLeft -> false
        else -> roomRight >= roomLeft
    }
    val wanted = if (growsRight) centerX - cap else centerX + cap - barWidth
    val left = wanted.coerceIn(margin, max(margin, containerWidth - margin - barWidth))
    val top = (centerY - cap).coerceIn(margin, max(margin, containerHeight - margin - barHeight))
    return BarPlacement(left, top, growsRight)
}

/**
 * How far an item on the bar has appeared (0..1) at morph [progress]. Items appear once the shape has room,
 * nearest the bubble first. The [anchor] item (the current tool) appears last, as the bubble's own glyph lands
 * on it, so the glyph reads as the bubble's content moving into the bar.
 */
fun itemReveal(progress: Float, order: Int, anchor: Boolean = false): Float {
    val start = if (anchor) 0.62f else 0.3f + 0.05f * order.coerceIn(0, 8)
    val end = minOf(1f, start + 0.35f)
    return ((progress - start) / (end - start)).coerceIn(0f, 1f)
}

internal class MorphGeometry {
    var growsRight = true
    /** The visible part of the shape, in the bar's own coordinates. */
    var clip = Rect.Zero
}

/** Lets bar content stagger in and mark the item the bubble's glyph flies to. */
class BubbleBarScope internal constructor(
    private val progress: () -> Float,
    private val geometry: MorphGeometry,
    private val onAnchor: (LayoutCoordinates) -> Unit,
) {
    /** Fade and scale in item [index] of [count]; [anchorIndex] is the current tool's index, or -1. */
    fun Modifier.morphItem(index: Int, count: Int, anchorIndex: Int = -1): Modifier {
        val anchor = index == anchorIndex
        return (if (anchor) onGloballyPositioned(onAnchor) else this).graphicsLayer {
            val order = when {
                anchorIndex >= 0 -> abs(index - anchorIndex)
                geometry.growsRight -> index
                else -> count - 1 - index
            }
            val t = itemReveal(progress(), order, anchor)
            alpha = t
            val s = 0.72f + 0.28f * t
            scaleX = s; scaleY = s
        }
    }
}

private val Pill = RoundedCornerShape(50)

/**
 * The tool bubble and its bar as one continuous shape (design §8.3). Collapsed it is a 44dp disc with [ring]
 * and [glyph] at [center]; tapping it stretches the disc into a pill holding [bar], anchored at the bubble and
 * growing toward the side with room. The bar's items fade and scale in, staggered, and the bubble's glyph
 * travels to the item marked as the anchor. [options] emerge from the bar's far edge.
 *
 * Fills its parent; [center] is in that parent's pixels. Spring physics with no overshoot, about 300 ms;
 * with reduced motion it is a quick crossfade between the two states.
 */
@Composable
fun MorphingBubble(
    expanded: Boolean,
    center: Offset,
    description: String,
    stateDescription: String,
    onOpen: () -> Unit,
    ring: @Composable () -> Unit,
    glyph: @Composable () -> Unit,
    bar: @Composable BubbleBarScope.() -> Unit,
    modifier: Modifier = Modifier,
    optionsOpen: Boolean = false,
    options: @Composable () -> Unit = {},
    onDrag: ((Offset) -> Unit)? = null,
    onRelease: (Offset) -> Unit = {},
) {
    if (rememberReducedMotion()) {
        Crossfade(expanded, modifier.fillMaxSize(), animationSpec = tween(140), label = "bubble") { open ->
            MorphBody(open, { if (open) 1f else 0f }, center, description, stateDescription, onOpen, ring, glyph, bar,
                optionsOpen, options, onDrag, onRelease, reduced = true)
        }
    } else {
        val progress = remember { Animatable(if (expanded) 1f else 0f) }
        LaunchedEffect(expanded) {
            // Critically damped: settles in about 300 ms and never overshoots past the bar's real size.
            progress.animateTo(if (expanded) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
        }
        MorphBody(expanded, { progress.value }, center, description, stateDescription, onOpen, ring, glyph, bar,
            optionsOpen, options, onDrag, onRelease, reduced = false, modifier = modifier)
    }
}

@Composable
private fun MorphBody(
    expanded: Boolean,
    progressSource: () -> Float,
    center: Offset,
    description: String,
    stateDescription: String,
    onOpen: () -> Unit,
    ring: @Composable () -> Unit,
    glyph: @Composable () -> Unit,
    bar: @Composable BubbleBarScope.() -> Unit,
    optionsOpen: Boolean,
    options: @Composable () -> Unit,
    onDrag: ((Offset) -> Unit)?,
    onRelease: (Offset) -> Unit,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Syncr.colors
    val progress by rememberUpdatedState(progressSource)
    val p = { progress().coerceIn(0f, 1f) }
    val geometry = remember { MorphGeometry() }
    val outer = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var height by remember { mutableIntStateOf(0) }
    val barVisible by remember(expanded) { derivedStateOf { expanded || p() > 0.001f } }
    val scope = remember(geometry) {
        BubbleBarScope({ p() }, geometry) { coords ->
            val o = outer[0]
            if (o != null && o.isAttached && coords.isAttached)
                anchor = o.localPositionOf(coords, Offset(coords.size.width / 2f, coords.size.height / 2f))
        }
    }
    val above = center.y > height / 2f

    Layout(
        contents = listOf(
            // 0: the shape. A disc at rest, the pill when open; same colour as the bar it becomes.
            { Box(Modifier.graphicsLayer { shape = Pill; clip = true; shadowElevation = 6.dp.toPx() * p() }.background(c.chrome)) },
            // 1: bar content, laid out at its final place and revealed by the growing shape.
            {
                if (barVisible) Box(Modifier.drawWithContent {
                    p() // redraw every frame of the morph
                    val r = geometry.clip
                    val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(minOf(r.width, r.height) / 2))) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }) {
                    DisposableEffect(Unit) { onDispose { anchor = null } }
                    scope.bar()
                }
            },
            // 2: save or link ring, fading as the shape stretches.
            { Box(Modifier.size(52.dp).graphicsLayer { alpha = 1f - (p() / 0.3f).coerceIn(0f, 1f) }) { ring() } },
            // 3: the glyph, travelling to the anchor item.
            {
                Box(Modifier.size(25.dp).graphicsLayer {
                    alpha = if (anchor != null) 1f - itemReveal(p(), 0, anchor = true) else 1f - (p() / 0.3f).coerceIn(0f, 1f)
                }, contentAlignment = Alignment.Center) { glyph() }
            },
            // 4: the 64dp touch target while collapsed: tap opens, drag moves.
            {
                if (!expanded) Box(Modifier.size(64.dp).clickable(role = Role.Button, onClick = onOpen)
                    .then(if (onDrag != null) Modifier.floatingDrag(onDrag, onRelease) else Modifier)
                    .semantics { contentDescription = description; this.stateDescription = stateDescription })
            },
            // 5: size and colour options, emerging from the bar's far edge.
            {
                AnimatedVisibility(expanded && optionsOpen,
                    enter = if (reduced) fadeIn(tween(100)) else
                        expandVertically(spring(dampingRatio = 1f, stiffness = 520f), expandFrom = if (above) Alignment.Bottom else Alignment.Top) +
                            fadeIn(tween(160, delayMillis = 40)),
                    exit = if (reduced) fadeOut(tween(80)) else
                        shrinkVertically(spring(dampingRatio = 1f, stiffness = 700f), shrinkTowards = if (above) Alignment.Bottom else Alignment.Top) +
                            fadeOut(tween(90))) { options() }
            },
        ),
        modifier = modifier.fillMaxSize().onPlaced { outer[0] = it }.onSizeChanged { height = it.height },
    ) { slots, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val margin = 8.dp.toPx()
        val disc = 44.dp.toPx()
        val loose = Constraints(maxWidth = max(0, (w - 2 * margin).roundToInt()), maxHeight = constraints.maxHeight)
        val barPlaceable = slots[1].firstOrNull()?.measure(loose)
        val t = p()
        val startL = center.x - disc / 2; val startT = center.y - disc / 2
        val placement = barPlaceable?.let { barPlacement(center.x, center.y, it.width.toFloat(), it.height.toFloat(), w, h, margin) }
        val endL = placement?.left ?: startL
        val endT = placement?.top ?: startT
        val endR = endL + (barPlaceable?.width?.toFloat() ?: disc)
        val endB = endT + (barPlaceable?.height?.toFloat() ?: disc)
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        val l = lerp(startL, endL); val top = lerp(startT, endT)
        val r = lerp(startL + disc, endR); val b = lerp(startT + disc, endB)
        placement?.let { geometry.growsRight = it.growsRight }
        geometry.clip = Rect(l - endL, top - endT, r - endL, b - endT)

        val shape = slots[0].first().measure(Constraints.fixed(max(1, (r - l).roundToInt()), max(1, (b - top).roundToInt())))
        val ringP = slots[2].first().measure(Constraints())
        val glyphP = slots[3].first().measure(Constraints())
        val touch = slots[4].firstOrNull()?.measure(Constraints())
        val optionsP = slots[5].firstOrNull()?.measure(loose)
        val target = anchor ?: center
        // The glyph lands a little early, mid-way through the anchor item's fade-in, so they hand over in place.
        val travel = (t / 0.8f).coerceAtMost(1f)
        val glyphCenter = Offset(center.x + (target.x - center.x) * travel, center.y + (target.y - center.y) * travel)

        layout(constraints.maxWidth, constraints.maxHeight) {
            shape.place(l.roundToInt(), top.roundToInt())
            barPlaceable?.place(endL.roundToInt(), endT.roundToInt())
            ringP.place((center.x - ringP.width / 2f).roundToInt(), (center.y - ringP.height / 2f).roundToInt())
            glyphP.place((glyphCenter.x - glyphP.width / 2f).roundToInt(), (glyphCenter.y - glyphP.height / 2f).roundToInt())
            touch?.place((center.x - touch.width / 2f).roundToInt(), (center.y - touch.height / 2f).roundToInt())
            if (optionsP != null && placement != null && barPlaceable != null) {
                val gap = 8.dp.toPx()
                val ox = (if (placement.growsRight) endL else endR - optionsP.width)
                    .coerceIn(margin, max(margin, w - margin - optionsP.width))
                val oy = if (above) endT - gap - optionsP.height else endB + gap
                optionsP.place(ox.roundToInt(), oy.roundToInt())
            }
        }
    }
}

/** Drag that follows the finger in window space, reporting deltas and a release velocity for a settle. */
@Composable
internal fun Modifier.floatingDrag(onDrag: (Offset) -> Unit, onRelease: (Offset) -> Unit): Modifier {
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val origin = remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin.value = it.positionInWindow() }.pointerInput(Unit) {
        var tracker = VelocityTracker()
        var previous = Offset.Zero
        var lastDelta = Offset.Zero
        fun finish() {
            val v = tracker.calculateVelocity()
            val measured = Offset(v.x, v.y)
            val momentum = if (measured.getDistance() < 1f) lastDelta * 16f else measured
            release(Offset(momentum.x.coerceIn(-2500f, 2500f), momentum.y.coerceIn(-2500f, 2500f)))
        }
        detectDragGestures(onDragStart = { tracker = VelocityTracker(); previous = it + origin.value; lastDelta = Offset.Zero },
            onDragEnd = { finish() }, onDragCancel = { finish() }) { change, _ ->
            // Layout moves under the finger; local dragAmount would repeatedly undo the last move.
            val screen = change.position + origin.value
            tracker.addPosition(change.uptimeMillis, screen)
            lastDelta = screen - previous
            drag(lastDelta)
            previous = screen
            change.consume()
        }
    }
}
