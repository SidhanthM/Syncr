package dev.syncr.prototype.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a press fill looks like for an element of this size: a disc for a square-ish one, else a rounded rectangle. */
internal data class PressFill(val circle: Boolean, val radius: Float)

/** Square-ish elements (icon buttons) get a disc; rows, cards and chips a fill rounded to their own corners, at most [maxRadius]. */
internal fun pressFill(width: Float, height: Float, maxRadius: Float): PressFill {
    val short = minOf(width, height)
    val long = maxOf(width, height)
    return if (short > 0f && long / short < 1.25f) PressFill(true, short / 2f) else PressFill(false, minOf(short / 2f, maxRadius))
}

/**
 * The one press indication: a soft fill in [color] that fades in under a finger and out when it lifts, drawn only
 * inside the element's own bounds as a disc or a rounded rectangle, never Compose's square ripple. With reduced
 * motion the fade is short and the same.
 */
internal class SyncrIndication(private val color: Color, private val reduced: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color, reduced)
    override fun equals(other: Any?) = other is SyncrIndication && other.color == color && other.reduced == reduced
    override fun hashCode() = 31 * color.hashCode() + reduced.hashCode()
}

private class PressNode(private val source: InteractionSource, private val color: Color, private val reduced: Boolean) :
    Modifier.Node(), DrawModifierNode {
    private val fill = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val down = HashSet<PressInteraction.Press>()
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> down += event
                    is PressInteraction.Release -> down -= event.press
                    is PressInteraction.Cancel -> down -= event.press
                    else -> return@collect
                }
                val pressed = down.isNotEmpty()
                launch { fill.animateTo(if (pressed) 1f else 0f, Motion.press(pressed, reduced)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = fill.value
        if (a <= 0f) return
        val shape = pressFill(size.width, size.height, 16.dp.toPx())
        if (shape.circle) drawCircle(color.copy(alpha = color.alpha * a), radius = shape.radius, center = center)
        else drawRoundRect(color.copy(alpha = color.alpha * a), topLeft = Offset.Zero, size = size, cornerRadius = CornerRadius(shape.radius))
    }

}
