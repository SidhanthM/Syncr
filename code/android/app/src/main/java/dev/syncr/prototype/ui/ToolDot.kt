package dev.syncr.prototype.ui

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush as PaintBrush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.ui.theme.Syncr

fun InkColour.label() = name.lowercase().replaceFirstChar { it.uppercase() }
fun BrushSize.label() = name.lowercase().replaceFirstChar { it.uppercase() }
fun toolName(brush: Brush, eraser: Boolean, lasso: Boolean = false) = when {
    lasso -> "Lasso"
    eraser -> "Eraser"
    brush.family == BrushFamily.HIGHLIGHTER -> "Highlighter"
    else -> "Pen"
}

/** "Remove animations" sets the animator scale to 0; honour it as reduced motion. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Ring kinds for the bubble (design §1, "four states"), each readable without colour or motion:
 * [ACCENT] solid thin; [ACCENT_DOTTED] dotted (pending); [GREY] thin with a gap at the top (not connected);
 * [ATTENTION] heavy with a "!" notch.
 */
enum class RingKind { ACCENT, ACCENT_DOTTED, GREY, ATTENTION }

fun SaveRing.kind() = when (this) {
    SaveRing.SAVED_ON_PHONE -> RingKind.ACCENT
    SaveRing.SAVING -> RingKind.ACCENT_DOTTED
    SaveRing.ATTENTION -> RingKind.ATTENTION
}

/** The bubble's ring (52dp), plus an optional ink-colour pip under the glyph. The disc itself is the morph shape. */
@Composable
fun BubbleRing(kind: RingKind, pip: Color? = null) {
    val c = Syncr.colors
    val reduced = rememberReducedMotion()
    val pulse = if (kind == RingKind.ACCENT_DOTTED && !reduced) {
        rememberInfiniteTransition(label = "saving").animateFloat(0.35f, 1f,
            infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    val salmon = PaintBrush.sweepGradient(listOf(c.accent.copy(alpha = 0.7f), c.accent, c.accent.copy(alpha = 0.85f), c.accent.copy(alpha = 0.7f)))
    Canvas(Modifier.size(52.dp)) {
        val ringWidth = (if (kind == RingKind.ATTENTION) 4.dp else 2.5.dp).toPx()
        val r = size.minDimension / 2 - ringWidth / 2 - 1.dp.toPx()
        when (kind) {
            RingKind.ACCENT -> drawCircle(salmon, r, style = Stroke(ringWidth))
            RingKind.ACCENT_DOTTED -> drawCircle(salmon, r, alpha = pulse, style = Stroke(ringWidth, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx()))))
            RingKind.GREY -> drawArc(c.ringSaved, -60f, 300f, useCenter = false, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                style = Stroke(ringWidth, cap = StrokeCap.Round))
            RingKind.ATTENTION -> {
                drawCircle(c.attention, r, style = Stroke(ringWidth))
                drawCircle(c.attention, 6.dp.toPx(), Offset(size.width - 6.dp.toPx(), 6.dp.toPx()))
                drawLine(c.chrome, Offset(size.width - 6.dp.toPx(), 3.dp.toPx()), Offset(size.width - 6.dp.toPx(), 6.5f.dp.toPx()),
                    1.6f.dp.toPx(), StrokeCap.Round)
                drawCircle(c.chrome, 0.9f.dp.toPx(), Offset(size.width - 6.dp.toPx(), 8.6f.dp.toPx()))
            }
        }
        if (pip != null) drawCircle(pip, 2.6f.dp.toPx(), Offset(center.x, size.height - 11.dp.toPx()))
    }
}

/** The current tool's glyph in the bubble's foreground (never the ink colour); it travels into the bar. */
@Composable
fun ToolGlyph(brush: Brush, eraser: Boolean, lasso: Boolean) {
    val c = Syncr.colors
    val glyph = when {
        lasso -> Glyph.LASSO
        eraser -> Glyph.ERASER
        brush.family == BrushFamily.HIGHLIGHTER -> Glyph.HIGHLIGHTER
        else -> Glyph.PEN
    }
    Canvas(Modifier.size(25.dp)) { glyph(glyph, c.ink) }
}

/** A plain pen glyph, used by Tablet mode's bubble. */
@Composable
fun PenGlyph() {
    val c = Syncr.colors
    Canvas(Modifier.size(25.dp)) { glyph(Glyph.PEN, c.ink) }
}

/** The bubble's spoken label: tool, colour and size, then save state. */
fun bubbleDescription(brush: Brush, eraser: Boolean, lasso: Boolean, status: SaveStatus) =
    "${toolName(brush, eraser, lasso)}${if (eraser || lasso) "" else ", ${brush.colour.label()}, ${brush.size.label()}"}. " +
        "${status.headline}. Show tools."

private enum class Glyph { PEN, HIGHLIGHTER, ERASER, LASSO, UNDO, REDO, FIT, MORE }

// Same 24×24 outlines as the design mockup; retain labels in semantics, not on the pill.
private val penOutline = PathParser().parsePathString("M4 20l1.2-4.4L15.8 5a1.9 1.9 0 0 1 2.7 0l.5.5a1.9 1.9 0 0 1 0 2.7L8.4 18.8z M14 7l3 3").toPath()
private val highlighterOutline = PathParser().parsePathString("M9 15l7.5-7.5 3 3L12 18z M9 15l-2 2 1.5 1.5h3.5 M4 21h9").toPath()
private val eraserOutline = PathParser().parsePathString("M8.5 19L4.7 15.2a1.5 1.5 0 0 1 0-2.1l8.4-8.4a1.5 1.5 0 0 1 2.1 0l4.1 4.1a1.5 1.5 0 0 1 0 2.1L11.5 19z M8 10l6 6 M8.5 19H20").toPath()
private val lassoOutline = PathParser().parsePathString("M12 4.5c4.7 0 8 2.1 8 5s-3.3 5-8 5-8-2.1-8-5 3.3-5 8-5z M7.5 13.5c-1.2 1.5-.8 3.3 1 3.6 1.6.3 1.9 1.9.6 3.4").toPath()
private val undoOutline = PathParser().parsePathString("M9 14 4 9l5-5 M4 9h10a6 6 0 0 1 0 12h-2").toPath()
private val redoOutline = PathParser().parsePathString("M15 14l5-5-5-5 M20 9H10a6 6 0 0 0 0 12h2").toPath()

private fun DrawScope.glyph(kind: Glyph, colour: Color) {
    val w = 2.dp.toPx(); val s = size.minDimension; val o = Offset((size.width - s) / 2, (size.height - s) / 2)
    fun p(x: Float, y: Float) = o + Offset(x * s, y * s)
    when (kind) {
        Glyph.PEN, Glyph.HIGHLIGHTER, Glyph.ERASER, Glyph.LASSO -> {
            val outline = when (kind) {
                Glyph.PEN -> penOutline
                Glyph.HIGHLIGHTER -> highlighterOutline
                Glyph.ERASER -> eraserOutline
                else -> lassoOutline
            }
            withTransform({ scale(s / 24f, s / 24f, pivot = Offset.Zero) }) {
                drawPath(outline, colour, style = Stroke(1.8f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    pathEffect = if (kind == Glyph.LASSO) PathEffect.dashPathEffect(floatArrayOf(2.6f, 2.2f)) else null))
            }
        }
        Glyph.UNDO, Glyph.REDO -> withTransform({ scale(s / 24f, s / 24f, pivot = Offset.Zero) }) {
            drawPath(if (kind == Glyph.UNDO) undoOutline else redoOutline, colour,
                style = Stroke(1.8f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        Glyph.FIT -> listOf(p(.2f, .2f) to (1 to 1), p(.8f, .2f) to (-1 to 1), p(.2f, .8f) to (1 to -1), p(.8f, .8f) to (-1 to -1))
            .forEach { (corner, dir) ->
                drawLine(colour, corner, corner + Offset(dir.first * s * .2f, 0f), w, StrokeCap.Round)
                drawLine(colour, corner, corner + Offset(0f, dir.second * s * .2f), w, StrokeCap.Round)
            }
        Glyph.MORE -> listOf(.25f, .5f, .75f).forEach { drawCircle(colour, 1.6f.dp.toPx(), p(it, .5f)) }
    }
}

@Composable
private fun BarItem(glyph: Glyph, description: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Syncr.colors
    val tint = if (enabled) c.ink else c.ink.copy(alpha = 0.35f)
    Box(modifier.size(48.dp).padding(3.dp)
        .background(if (selected) c.accent.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = onLongClick?.let { "Sizes and colours" },
            onLongClick = onLongClick, onClick = onClick)
        .semantics { contentDescription = description; if (selected) this.selected = true },
        contentAlignment = Alignment.Center) {
        if (selected) Canvas(Modifier.fillMaxSize()) {
            drawCircle(c.accent, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
        }
        Canvas(Modifier.size(24.dp)) { glyph(glyph, tint) }
    }
}

/**
 * The note's bar (design §8.3): Pen, Highlighter, Eraser, Lasso; Undo, Redo and Fit are always here.
 * Drawn inside [MorphingBubble]'s shape, so it has no background of its own.
 */
@Composable
fun BubbleBarScope.NoteToolBar(brush: Brush, eraser: Boolean, eraserEnabled: Boolean, canUndo: Boolean, canRedo: Boolean,
    onPen: () -> Unit, onHighlighter: () -> Unit, onEraser: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit,
    onFit: () -> Unit, onMore: () -> Unit, lasso: Boolean, onLasso: () -> Unit,
    onPenOptions: () -> Unit, onHighlighterOptions: () -> Unit,
    onDrag: (Offset) -> Unit, onRelease: (Offset) -> Unit) {
    val c = Syncr.colors
    val pen = !eraser && !lasso && brush.family == BrushFamily.PEN
    val highlighter = !eraser && !lasso && brush.family == BrushFamily.HIGHLIGHTER
    val active = when { lasso -> 3; eraser -> 2; highlighter -> 1; else -> 0 }
    val n = 10
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BarItem(Glyph.PEN, if (pen) "Pen, selected. Tap for sizes and colours" else "Pen", Modifier.morphItem(0, n, active), pen,
            onLongClick = onPenOptions, onClick = onPen)
        BarItem(Glyph.HIGHLIGHTER, if (highlighter) "Highlighter, selected. Tap for sizes and colours" else "Highlighter",
            Modifier.morphItem(1, n, active), highlighter, onLongClick = onHighlighterOptions, onClick = onHighlighter)
        BarItem(Glyph.ERASER, if (eraserEnabled) "Eraser, removes whole strokes" else "Eraser, unavailable until changes are saved",
            Modifier.morphItem(2, n, active), eraser && !lasso, eraserEnabled, onClick = onEraser)
        BarItem(Glyph.LASSO, "Lasso: draw around strokes with the pen to select them", Modifier.morphItem(3, n, active), lasso,
            onClick = onLasso)
        Box(Modifier.morphItem(4, n, active).size(32.dp, 48.dp).floatingDrag(onDrag, onRelease)
            .semantics { contentDescription = "Drag toolbar to move it" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                for (x in listOf(5f, 12f)) for (y in listOf(4f, 9f, 14f))
                    drawCircle(c.muted, 1.3.dp.toPx(), Offset(x.dp.toPx(), y.dp.toPx()))
            }
        }
        Box(Modifier.morphItem(5, n, active).padding(horizontal = 4.dp).size(1.dp, 24.dp)) { VerticalDots() }
        BarItem(Glyph.UNDO, "Undo", Modifier.morphItem(6, n, active), enabled = canUndo, onClick = onUndo)
        BarItem(Glyph.REDO, "Redo", Modifier.morphItem(7, n, active), enabled = canRedo, onClick = onRedo)
        BarItem(Glyph.FIT, "Fit a phone column to screen", Modifier.morphItem(8, n, active), onClick = onFit)
        BarItem(Glyph.MORE, "More: storage, note and modes", Modifier.morphItem(9, n, active), onClick = onMore)
    }
}

/** A text item for a bar (Tablet mode), 48dp tall. Disabled items stay readable and say why. */
@Composable
fun BubbleBarScope.BubbleChip(text: String, index: Int, count: Int, enabled: Boolean = true, description: String? = null,
    onClick: () -> Unit = {}) {
    val c = Syncr.colors
    Box(Modifier.morphItem(index, count).heightIn(min = 48.dp).padding(vertical = 3.dp)
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (description != null) contentDescription = description; if (!enabled) disabled() }
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Syncr.type.label, color = if (enabled) c.ink else c.muted, maxLines = 1)
    }
}

@Composable
private fun VerticalDots() {
    val line = Syncr.colors.ink.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxSize()) {
        drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
    }
}

/** Three sizes and five colours for the active tool. No sliders. */
@Composable
fun ToolOptions(brush: Brush, onSize: (BrushSize) -> Unit, onColour: (InkColour) -> Unit, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.shadow(6.dp, RoundedCornerShape(50)).background(c.chrome, RoundedCornerShape(50))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BrushSize.entries.forEach { option ->
            val selected = brush.size == option
            Box(Modifier.size(48.dp).clickable(role = Role.RadioButton) { onSize(option) }
                .semantics { contentDescription = "${option.label()} size"; this.selected = selected }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(32.dp)) {
                    if (selected) drawCircle(c.accent, size.minDimension / 2 - 1.5f.dp.toPx(), style = Stroke(2.dp.toPx()))
                    val d = when (option) { BrushSize.SMALL -> 2.5f; BrushSize.MEDIUM -> 4.5f; BrushSize.LARGE -> 7.5f }
                    drawCircle(c.ink.copy(alpha = if (selected) 1f else 0.6f), d.dp.toPx())
                }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(2.dp, 28.dp)) { VerticalDots() }
        InkColour.entries.forEach { colour ->
            val selected = brush.colour == colour
            Box(Modifier.size(48.dp).clickable(role = Role.RadioButton) { onColour(colour) }
                .semantics { contentDescription = colour.label(); this.selected = selected }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(32.dp)) {
                    if (selected) drawCircle(c.accent, size.minDimension / 2 - 1.5f.dp.toPx(), style = Stroke(2.dp.toPx()))
                    drawCircle(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), c.dark)), 10.dp.toPx())
                }
            }
        }
    }
}
