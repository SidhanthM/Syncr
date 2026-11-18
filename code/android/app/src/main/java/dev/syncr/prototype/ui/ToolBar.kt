package dev.syncr.prototype.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import dev.syncr.core.PenLook
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.floatingSurface

fun InkColour.label() = name.lowercase().replaceFirstChar { it.uppercase() }
fun BrushSize.label() = name.lowercase().replaceFirstChar { it.uppercase() }
fun toolName(brush: Brush, eraser: Boolean, lasso: Boolean = false, typing: Boolean = false) = when {
    typing -> "Type"
    lasso -> "Lasso"
    eraser -> "Eraser"
    brush.family == BrushFamily.HIGHLIGHTER -> "Highlighter"
    else -> "Pen"
}

/** The bubble's spoken label: tool, colour and size, then save state. */
fun bubbleDescription(brush: Brush, eraser: Boolean, lasso: Boolean, status: SaveStatus, typing: Boolean = false) =
    "${toolName(brush, eraser, lasso, typing)}${if (eraser || lasso || typing) "" else ", ${brush.colour.label()}, ${brush.size.label()}"}. " +
        "${status.headline}. Show tools."

private fun toolIcon(brush: Brush, eraser: Boolean, lasso: Boolean, typing: Boolean): ImageVector = when {
    typing -> SyncrIcons.Type
    lasso -> SyncrIcons.Lasso
    eraser -> SyncrIcons.Eraser
    brush.family == BrushFamily.HIGHLIGHTER -> SyncrIcons.Highlighter
    else -> SyncrIcons.Pen
}

/** The current tool's glyph in the bubble (never the ink colour); it travels into the bar when the bubble opens. */
@Composable
fun ToolGlyph(brush: Brush, eraser: Boolean, lasso: Boolean, typing: Boolean = false) {
    SyncrIcon(toolIcon(brush, eraser, lasso, typing), Syncr.colors.ink, size = 22.dp)
}

/** A plain pen glyph, used by Tablet mode's bubble. */
@Composable
fun PenGlyph() = SyncrIcon(SyncrIcons.Pen, Syncr.colors.ink, size = 22.dp)

/**
 * One bar button (design `.tb-btn`): 48dp target, and when selected the soft accent fill with an accent ring
 * (`.tb-btn.on`). Disabled buttons use the faint colour and say why in their label.
 */
@Composable
private fun BarItem(icon: ImageVector, description: String, modifier: Modifier, selected: Boolean = false, width: Dp = TouchTarget,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Syncr.colors
    // The selected tool's disc grows and fades in (and out) as the selection moves; no box at rest, never a square flash.
    val on by animateFloatAsState(if (selected) 1f else 0f, if (Syncr.reducedMotion) snap() else Motion.quick<Float>(), label = "tool disc")
    Box(modifier.size(width, TouchTarget).padding(3.dp)
        .drawBehind {
            if (on <= 0.01f) return@drawBehind
            val radius = size.minDimension / 2
            val grow = 0.7f + 0.3f * on
            val w = size.width * grow
            val h = size.height * grow
            val at = Offset((size.width - w) / 2, (size.height - h) / 2)
            val ring = 1.5.dp.toPx()
            drawRoundRect(c.accentSoft.copy(alpha = c.accentSoft.alpha * on), at, Size(w, h), CornerRadius(radius * grow))
            drawRoundRect(c.accent.copy(alpha = on), at + Offset(ring / 2, ring / 2), Size(w - ring, h - ring),
                CornerRadius(radius * grow - ring / 2), style = Stroke(ring))
        }
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = onLongClick?.let { "Sizes and colours" },
            onLongClick = onLongClick, onClick = onClick)
        .semantics { contentDescription = description; if (selected) stateDescription = "Selected" },
        contentAlignment = Alignment.Center) {
        SyncrIcon(icon, if (enabled) c.ink else c.faint, size = 22.dp)
    }
}

/** A 1dp rule between groups (design `.tb-sep`). */
@Composable
private fun BarSeparator(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = Space.xs).size(1.dp, 24.dp).background(Syncr.colors.lineStrong))
}

/**
 * The note's bar (design `.tb-pill`): Pen, Highlighter, Eraser, Lasso, a grip to move it, then Undo, Redo, Fit
 * and More. Drawn inside [MorphingBubble]'s shape, so it has no background of its own.
 */
@Composable
fun BubbleBarScope.NoteToolBar(brush: Brush, eraser: Boolean, typing: Boolean, canUndo: Boolean, canRedo: Boolean,
    onPen: () -> Unit, onHighlighter: () -> Unit, onEraser: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit,
    onMore: () -> Unit, lasso: Boolean, onLasso: () -> Unit, onTyping: () -> Unit,
    onPenOptions: () -> Unit, onHighlighterOptions: () -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit, onRelease: (androidx.compose.ui.geometry.Offset) -> Unit) {
    val c = Syncr.colors
    val pen = !typing && !eraser && !lasso && brush.family == BrushFamily.PEN
    val highlighter = !typing && !eraser && !lasso && brush.family == BrushFamily.HIGHLIGHTER
    val active = when { typing -> 4; lasso -> 3; eraser -> 2; highlighter -> 1; else -> 0 }
    val n = 10
    val w = BarLayout.itemWidth(LocalConfiguration.current.screenWidthDp.toFloat()).dp
    Row(Modifier.padding(horizontal = BarLayout.PAD.dp, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        BarItem(SyncrIcons.Pen, if (pen) "Pen. Tap for sizes and colours" else "Pen", Modifier.morphItem(0, n, active), pen, w,
            onLongClick = onPenOptions, onClick = onPen)
        BarItem(SyncrIcons.Highlighter, if (highlighter) "Highlighter. Tap for sizes and colours" else "Highlighter",
            Modifier.morphItem(1, n, active), highlighter, w, onLongClick = onHighlighterOptions, onClick = onHighlighter)
        BarItem(SyncrIcons.Eraser, "Eraser, removes whole strokes", Modifier.morphItem(2, n, active), eraser && !lasso && !typing, w,
            onClick = onEraser)
        BarItem(SyncrIcons.Lasso, "Lasso: circle strokes with the pen to select them", Modifier.morphItem(3, n, active), lasso && !typing, w,
            onClick = onLasso)
        BarItem(SyncrIcons.Type, "Type: tap the paper to type", Modifier.morphItem(4, n, active), typing, w, onClick = onTyping)
        Box(Modifier.morphItem(5, n, active).size(BarLayout.GRIP.dp, TouchTarget).floatingDrag(onDrag, onRelease)
            .semantics { contentDescription = "Drag to move the tool bar" }, contentAlignment = Alignment.Center) {
            SyncrIcon(SyncrIcons.Grip, c.faint, size = 18.dp)
        }
        Box(Modifier.morphItem(6, n, active).size(BarLayout.SEPARATOR.dp, 24.dp).background(Syncr.colors.lineStrong))
        BarItem(SyncrIcons.Undo, "Undo", Modifier.morphItem(7, n, active), width = w, enabled = canUndo, onClick = onUndo)
        BarItem(SyncrIcons.Redo, "Redo", Modifier.morphItem(8, n, active), width = w, enabled = canRedo, onClick = onRedo)
        BarItem(SyncrIcons.More, "More: note, storage and modes", Modifier.morphItem(9, n, active), width = w, onClick = onMore)
    }
}

/** A text item for a bar (Tablet mode), 48dp tall. Disabled items stay readable and say why. */
@Composable
fun BubbleBarScope.BubbleChip(text: String, index: Int, count: Int, enabled: Boolean = true, description: String? = null,
    onClick: () -> Unit = {}) {
    val c = Syncr.colors
    Box(Modifier.morphItem(index, count).heightIn(min = TouchTarget).padding(vertical = 3.dp)
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (description != null) contentDescription = description; if (!enabled) disabled() }
        .padding(horizontal = Space.m), contentAlignment = Alignment.Center) {
        Text(text, style = Syncr.type.label, color = if (enabled) c.ink else c.muted, maxLines = 1)
    }
}

/**
 * Three sizes and five colours for the active tool (design `.tb-row`). No sliders, no picker. The selected size
 * gets the soft accent fill and ring; the selected colour a card-coloured gap and an accent ring.
 */
@Composable
fun ToolOptions(brush: Brush, onSize: (BrushSize) -> Unit, onColour: (InkColour) -> Unit, modifier: Modifier = Modifier,
    penLook: PenLook? = null, onPenLook: (PenLook) -> Unit = {}) {
    val c = Syncr.colors
    Column(modifier.floatingSurface(if (penLook != null) Radius.card else Radius.pill)) {
    // The pen's look (a display preference of this phone) sits above the sizes and colours.
    if (penLook != null) Row(Modifier.selectableGroup().horizontalScroll(rememberScrollState()).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically) {
        PenLook.entries.forEach { look ->
            Chip(look.name.lowercase().replaceFirstChar { it.uppercase() }, penLook == look, description = "${look.name.lowercase().replaceFirstChar { it.uppercase() }} pen look") { onPenLook(look) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            BrushSize.entries.forEach { option ->
                val selected = brush.size == option
                OptionCell(selected, "${option.label()} size", onClick = { onSize(option) }) {
                    Canvas(Modifier.size(30.dp)) {
                        if (selected) {
                            drawCircle(c.accentSoft)
                            drawCircle(c.accent, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        }
                        val r = when (option) { BrushSize.SMALL -> 2.5f; BrushSize.MEDIUM -> 4.5f; BrushSize.LARGE -> 7f }
                        drawCircle(c.ink, r.dp.toPx())
                    }
                }
            }
        }
        BarSeparator()
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            InkColour.entries.forEach { colour ->
                val selected = brush.colour == colour
                OptionCell(selected, colour.label(), onClick = { onColour(colour) }) {
                    ColourDot(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), c.dark)), selected)
                }
            }
        }
    }
    }
}

@Composable
private fun OptionCell(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(TouchTarget).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { content() }
}

/** An ink colour swatch (design `.cdot`); selected: `0 0 0 2px card, 0 0 0 3.5px accent`. */
@Composable
fun ColourDot(colour: Color, selected: Boolean, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Canvas(modifier.size(30.dp)) {
        val r = 11.dp.toPx()
        if (selected) {
            drawCircle(c.accent, r + 3.5.dp.toPx())
            drawCircle(c.surface2, r + 2.dp.toPx())
        }
        drawCircle(colour, r, center = Offset(size.width / 2, size.height / 2))
    }
}
