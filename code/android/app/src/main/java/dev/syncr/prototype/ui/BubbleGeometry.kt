package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.abs
import kotlin.math.max

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
