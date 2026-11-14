package dev.syncr.prototype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.syncr.prototype.ui.theme.Level
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.raised
import kotlin.math.roundToInt

/* The app's own controls (mobile polish C.2): drawn from the colour ladder, 48dp targets, Motion timing. */

private val SwitchTrackWidth = 46.dp
private val SwitchTrackHeight = 28.dp
private val SwitchThumb = 20.dp

/**
 * A pill switch: an ink track when on, a hairline outline when off, a surface thumb that slides on [Motion.quick].
 * It only draws; the row that holds it owns the toggling (and the Switch role).
 */
@Composable
fun SyncrSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    val inset = (SwitchTrackHeight - SwitchThumb) / 2
    val x by animateDpAsState(if (checked) SwitchTrackWidth - SwitchThumb - inset else inset, Motion.quick(Syncr.reducedMotion), label = "thumb")
    Box(modifier.size(SwitchTrackWidth, SwitchTrackHeight).clip(CircleShape)
        .then(if (checked) Modifier.background(c.ink) else Modifier.border(1.5.dp, c.lineStrong, CircleShape))) {
        Box(Modifier.offset(x, inset).size(SwitchThumb)
            .dropShadow(CircleShape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = if (c.dark) 0.5f else 0.2f))
            .background(c.surface1, CircleShape)
            .then(if (checked) Modifier else Modifier.border(1.dp, c.lineStrong, CircleShape)))
    }
}

/**
 * A 0..1 slider: a 4 dp track, an ink fill to the value and a 20 dp raised thumb. The whole 48 dp row takes the
 * touch; [onFinished] runs once the finger lifts. [label] and [state] are spoken, and the slider can be nudged by
 * accessibility actions.
 */
@Composable
fun SyncrSlider(value: Float, onChange: (Float) -> Unit, label: String, state: String, modifier: Modifier = Modifier,
    onFinished: (() -> Unit)? = null) {
    val c = Syncr.colors
    val v = value.coerceIn(0f, 1f)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val density = LocalDensity.current
    val thumb = with(density) { SwitchThumb.toPx() }
    Box(modifier.fillMaxWidth().height(TouchTarget)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val span = (size.width - thumb).coerceAtLeast(1f)
                fun at(x: Float) = ((x - thumb / 2) / span).coerceIn(0f, 1f)
                change(at(down.position.x))
                drag(down.id) { move -> change(at(move.position.x)); move.consume() }
                finished?.invoke()
            }
        }
        .semantics {
            contentDescription = label; stateDescription = state
            progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
            setProgress { target -> change(target.coerceIn(0f, 1f)); finished?.invoke(); true }
        }
        .drawBehindSlider(v, c.ink, c.lineStrong, c.surface3, c.lineStrong, c.dark, thumb, with(density) { 4.dp.toPx() }))
}

private fun Modifier.drawBehindSlider(v: Float, fill: Color, rail: Color, thumbColour: Color, edge: Color, dark: Boolean,
    thumb: Float, track: Float): Modifier = this.drawBehind {
    val cy = size.height / 2
    val left = thumb / 2
    val right = size.width - thumb / 2
    val x = left + (right - left) * v
    drawRoundRect(rail, Offset(left, cy - track / 2), Size(right - left, track), CornerRadius(track / 2))
    drawRoundRect(fill, Offset(left, cy - track / 2), Size((x - left).coerceAtLeast(0f), track), CornerRadius(track / 2))
    // The thumb's shadow, then the thumb and its edge.
    drawCircle(Color.Black.copy(alpha = if (dark) 0.5f else 0.18f), thumb / 2 + 1.5f, Offset(x, cy + 2f))
    drawCircle(thumbColour, thumb / 2, Offset(x, cy))
    drawCircle(edge, thumb / 2, Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
}

/**
 * A text field: [surface1] ground with a hairline, an ink 1 dp line on focus, the app's type and an accent caret.
 * [label] sits above the field in the label style; [error] replaces [hint] under it.
 */
@Composable
fun SyncrTextField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, placeholder: String? = null, hint: String? = null, error: String? = null, enabled: Boolean = true,
    singleLine: Boolean = false, minLines: Int = 1, textStyle: TextStyle = Syncr.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    val c = Syncr.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = Radius.m
    Column(modifier) {
        if (label != null) Label(label, Modifier.padding(bottom = Space.xs))
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = TouchTarget)
            .background(c.surface1, shape)
            .border(1.dp, if (error != null) c.destructive else if (focused) c.ink else c.dots, shape)
            .padding(horizontal = Space.m, vertical = Space.m),
            enabled = enabled, singleLine = singleLine, minLines = minLines,
            textStyle = textStyle.copy(color = if (enabled) c.ink else c.muted),
            cursorBrush = SolidColor(c.accent), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            interactionSource = source,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = c.faint)
                    inner()
                }
            })
        val note = error ?: hint
        if (note != null) Text(note, style = Syncr.type.caption, color = if (error != null) c.destructive else c.muted,
            modifier = Modifier.padding(top = Space.xs))
    }
}

/** [SyncrTextField] over a plain string. */
@Composable
fun SyncrTextField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    placeholder: String? = null, hint: String? = null, error: String? = null, enabled: Boolean = true, singleLine: Boolean = false,
    minLines: Int = 1, textStyle: TextStyle = Syncr.type.body, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default) {
    // Keep the selection and composition across recompositions; only a changed string resets them.
    var held by remember { androidx.compose.runtime.mutableStateOf(TextFieldValue(value)) }
    if (held.text != value) held = TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    SyncrTextField(held, { held = it; if (it.text != value) onChange(it.text) }, modifier, label, placeholder, hint, error, enabled,
        singleLine, minLines, textStyle, keyboardOptions, keyboardActions)
}

/** Opens below its anchor, flips above when it would leave the window, and stays inside the window's sides. */
private class MenuPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - gap) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(gap)
        return IntOffset(x, y)
    }
}

/**
 * A popover menu on [surface3]: it opens under the composable it sits in, with the slash menu's row style
 * ([MenuItem]), and fades on [Motion.MENU_MS]. It stays composed while it fades out.
 */
@Composable
fun SyncrMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduced = Syncr.reducedMotion
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    if (state.currentState || state.targetState) {
        Popup(MenuPosition(gap), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(visibleState = state, enter = Motion.menuIn(reduced), exit = Motion.menuOut(reduced)) {
                Column(modifier.padding(8.dp).sizeIn(minWidth = 200.dp, maxWidth = 280.dp).raised(Level.THREE, Radius.m)
                    .padding(vertical = Space.xs).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** One row of a [SyncrMenu]: an optional icon, a title, 48 dp tall; destructive rows read in the destructive colour. */
@Composable
fun MenuItem(title: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, tone: ActionTone = ActionTone.DEFAULT) {
    val c = Syncr.colors
    val colour = when {
        !enabled -> c.faint
        tone == ActionTone.DESTRUCTIVE -> c.destructive
        else -> c.ink
    }
    Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(Radius.s).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        if (icon != null) SyncrIcon(icon, colour, size = 20.dp)
        Text(title, style = Syncr.type.body, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
