package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Syncr
import kotlin.math.max
import kotlin.math.roundToInt

/** The resting disc's diameter (design `.tdot`, 36px at mock scale). */
internal val BubbleDisc = 44.dp
/** The collapsed bubble's touch target: larger than the disc, so it is easy to hit with a thumb. */
private val BubbleTarget = 64.dp

/**
 * The tool bubble and its bar as one continuous shape (design "Canvas, resting" and "Canvas, tool bar open").
 * Collapsed it is a 44dp disc with [ring] and [glyph] at [center]; tapping it stretches the disc into a pill
 * holding [bar], anchored at the bubble and growing toward the side with room. The bar's items fade and scale in,
 * staggered, and the bubble's glyph travels to the item marked as the anchor. [options] emerge from the bar's
 * far edge.
 *
 * The shape is the card colour with a strong edge and a lifted shadow, so it stays high-contrast on paper in
 * either theme. Fills its parent; [center] is in that parent's pixels. A critically damped spring, about 300 ms;
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
    if (Syncr.reducedMotion) {
        Crossfade(expanded, modifier.fillMaxSize(), animationSpec = Motion.fade(true), label = "bubble") { open ->
            MorphBody(open, { if (open) 1f else 0f }, center, description, stateDescription, onOpen, ring, glyph, bar,
                optionsOpen, options, onDrag, onRelease, reduced = true)
        }
    } else {
        val progress = remember { Animatable(if (expanded) 1f else 0f) }
        LaunchedEffect(expanded) { progress.animateTo(if (expanded) 1f else 0f, Motion.shape()) }
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
            // 0: the shape. A disc at rest, the pill when open; the same surface as the bar it becomes.
            {
                Box(Modifier.graphicsLayer { shape = Radius.pill; clip = true; shadowElevation = (4f + 4f * p()) * density }
                    .background(c.surface2).border(1.dp, c.lineStrong, Radius.pill)
                    .drawBehind { if (c.dark) drawRect(Color.White.copy(alpha = 0.06f), size = Size(size.width, 1.dp.toPx())) })
            },
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
            { Box(Modifier.size(BubbleRingSize).graphicsLayer { alpha = 1f - (p() / 0.3f).coerceIn(0f, 1f) }) { ring() } },
            // 3: the glyph, travelling to the anchor item.
            {
                Box(Modifier.size(24.dp).graphicsLayer {
                    alpha = if (anchor != null) 1f - itemReveal(p(), 0, anchor = true) else 1f - (p() / 0.3f).coerceIn(0f, 1f)
                }, contentAlignment = Alignment.Center) { glyph() }
            },
            // 4: the touch target while collapsed: tap opens, drag moves.
            {
                if (!expanded) Box(Modifier.size(BubbleTarget).clickable(role = Role.Button, onClick = onOpen)
                    .then(if (onDrag != null) Modifier.floatingDrag(onDrag, onRelease) else Modifier)
                    .semantics { contentDescription = description; this.stateDescription = stateDescription })
            },
            // 5: size and colour options, emerging from the bar's far edge.
            {
                AnimatedVisibility(expanded && optionsOpen,
                    enter = if (reduced) Motion.fadeInOnly(true) else
                        expandVertically(Motion.shape<IntSize>(), expandFrom = if (above) Alignment.Bottom else Alignment.Top) +
                            Motion.fadeInOnly(false),
                    exit = if (reduced) Motion.fadeOutOnly(true) else
                        shrinkVertically(Motion.quick<IntSize>(), shrinkTowards = if (above) Alignment.Bottom else Alignment.Top) +
                            Motion.fadeOutOnly(false)) { options() }
            },
        ),
        modifier = modifier.fillMaxSize().onPlaced { outer[0] = it }.onSizeChanged { height = it.height },
    ) { slots, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val margin = BarLayout.GUTTER.dp.toPx()
        val disc = BubbleDisc.toPx()
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
            if (optionsP != null && placement != null) {
                val gap = 8.dp.toPx()
                val ox = (if (placement.growsRight) endL else endR - optionsP.width)
                    .coerceIn(margin, max(margin, w - margin - optionsP.width))
                val oy = if (above) endT - gap - optionsP.height else endB + gap
                optionsP.place(ox.roundToInt(), oy.roundToInt())
            }
        }
    }
}
