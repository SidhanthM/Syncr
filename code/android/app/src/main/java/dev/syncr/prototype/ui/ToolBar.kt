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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import dev.syncr.core.PenLook
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
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
import dev.syncr.core.Oklch
import dev.syncr.prototype.notes.InkStyles
import dev.syncr.prototype.notes.brushWidth
import dev.syncr.prototype.notes.strokeArgb
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
    "${toolName(brush, eraser, lasso, typing)}${if (eraser || lasso || typing) "" else ", ${if (brush.customInk != null) "Custom ink" else brush.colour.label()}, ${brush.size.label()}"}. " +
        "${status.headline}. Show tools."

/** The current tool's line-art head; it travels into the open bar. */
@Composable
fun ToolGlyph(brush: Brush, eraser: Boolean, lasso: Boolean, typing: Boolean = false) {
    val head = when {
        typing -> Head.CARET
        lasso -> Head.LASSO
        eraser -> Head.ERASER
        brush.family == BrushFamily.HIGHLIGHTER -> Head.HIGHLIGHTER
        else -> Head.valueOf((brush.look ?: PenLook.FOUNTAIN).name)
    }
    PenHead(head, size = 34.dp)
}

/** The tablet's current head uses the same art as the note chooser. */
@Composable
fun PenGlyph(head: Head = Head.FOUNTAIN) = PenHead(head, size = 34.dp)

/**
 * One bar button (design `.tb-btn`): 48dp target, and when selected the soft accent fill with an accent ring
 * (`.tb-btn.on`). Disabled buttons use the faint colour and say why in their label.
 */
@Composable
private fun BarItem(icon: ImageVector?, description: String, modifier: Modifier, selected: Boolean = false, width: Dp = TouchTarget,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null, head: Head? = null, art: PenArt? = null, onClick: () -> Unit) {
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
        if (head != null) PenHead(head, size = 32.dp, line = if (enabled) c.ink else c.faint,
            playKey = if (selected) true else null, freeze = if (selected) StippleDebug.freeze else null)
        else if (art != null) PenAction(art, if (enabled) c.ink else c.faint, size = 26.dp)
        else if (icon != null) SyncrIcon(icon, if (enabled) c.ink else c.faint, size = 22.dp)
    }
}

/**
 * The note's bar (design `.tb-pill`): Pen, Highlighter, Eraser, Lasso, a grip to move it, then Undo, Redo, Fit
 * and More. Drawn inside [MorphingBubble]'s shape, so it has no background of its own.
 */
@Composable
fun BubbleBarScope.NoteToolBar(brush: Brush, eraser: Boolean, typing: Boolean, canUndo: Boolean, canRedo: Boolean,
    penHead: Head,
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
        BarItem(null, if (pen) "${penHead.spoken}. Tap for sizes and colours" else penHead.spoken, Modifier.morphItem(0, n, active), pen, w,
            onLongClick = onPenOptions, head = penHead, onClick = onPen)
        BarItem(null, if (highlighter) "Highlighter. Tap for sizes and colours" else "Highlighter",
            Modifier.morphItem(1, n, active), highlighter, w, onLongClick = onHighlighterOptions, head = Head.HIGHLIGHTER,
            onClick = onHighlighter)
        BarItem(null, Head.ERASER.spoken, Modifier.morphItem(2, n, active), eraser && !lasso && !typing, w, head = Head.ERASER, onClick = onEraser)
        BarItem(null, Head.LASSO.spoken, Modifier.morphItem(3, n, active), lasso && !typing, w, head = Head.LASSO, onClick = onLasso)
        BarItem(null, Head.CARET.spoken, Modifier.morphItem(4, n, active), typing, w, head = Head.CARET, onClick = onTyping)
        Box(Modifier.morphItem(5, n, active).size(BarLayout.GRIP.dp, TouchTarget).floatingDrag(onDrag, onRelease)
            .semantics { contentDescription = "Drag to move the tool bar" }, contentAlignment = Alignment.Center) {
            PenAction(PenArtData.GRIP, c.faint, size = 22.dp)
        }
        Box(Modifier.morphItem(6, n, active).size(BarLayout.SEPARATOR.dp, 24.dp).background(Syncr.colors.lineStrong))
        BarItem(null, "Undo", Modifier.morphItem(7, n, active), width = w, enabled = canUndo, art = PenArtData.UNDO, onClick = onUndo)
        BarItem(null, "Redo", Modifier.morphItem(8, n, active), width = w, enabled = canRedo, art = PenArtData.REDO, onClick = onRedo)
        BarItem(null, "More: note, storage and modes", Modifier.morphItem(9, n, active), width = w, art = PenArtData.MORE, onClick = onMore)
    }
}

/** A pen head as an item of a bar (Tablet mode's chooser): 48dp, the chosen one in the soft disc, drawn on once when chosen. */
@Composable
fun BubbleBarScope.BubbleHead(head: Head, index: Int, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Syncr.colors
    Box(Modifier.morphItem(index, count).size(TouchTarget).padding(3.dp)
        .drawBehind {
            if (!selected) return@drawBehind
            val r = CornerRadius(size.minDimension / 2)
            drawRoundRect(c.accentSoft, Offset.Zero, size, r)
            drawRoundRect(c.accent, Offset(.75.dp.toPx(), .75.dp.toPx()), Size(size.width - 1.5.dp.toPx(), size.height - 1.5.dp.toPx()),
                CornerRadius(size.minDimension / 2 - .75.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = head.spoken },
        contentAlignment = Alignment.Center) {
        PenHead(head, size = 36.dp, playKey = if (selected) true else null, freeze = if (selected) StippleDebug.freeze else null)
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

/** The soft selected disc (the same one the bar's selected tool wears), a circle as wide as the cell is tall. */
@Composable
private fun Modifier.trayDisc(selected: Boolean): Modifier {
    val c = Syncr.colors
    val on by animateFloatAsState(if (selected) 1f else 0f, if (Syncr.reducedMotion) snap() else Motion.quick<Float>(), label = "tray disc")
    return drawBehind {
        if (on <= 0.01f) return@drawBehind
        val d = size.minDimension * (0.7f + 0.3f * on)
        val ring = 1.5.dp.toPx()
        drawCircle(c.accentSoft.copy(alpha = c.accentSoft.alpha * on), d / 2)
        drawCircle(c.accent.copy(alpha = on), d / 2 - ring / 2, style = Stroke(ring))
    }
}

/** A short sample of a pen's line: width from the pen's own stroke style, tapered like its stroke where it tapers. */
internal object SampleStroke {
    /** The sample's length, in dp. */
    const val LENGTH_DP = 28f
    private const val DP_PER_UNIT = 0.35f
    private const val MIN_DP = 1.25f
    private const val MAX_DP = 14f

    /** The width of the sample in dp: the brush's nominal stroke width on a page, scaled to a hand-sized line. */
    fun widthDp(nominalUnits: Float): Float = (nominalUnits * DP_PER_UNIT).coerceIn(MIN_DP, MAX_DP)

    /** The width multiplier at [t] (0..1 along the line) for a stroke that starts at [taperStart] and ends at [taperEnd] times its width. */
    fun taper(t: Float, taperStart: Float, taperEnd: Float): Float {
        val edge = 0.22f
        val a = if (t < edge) taperStart + (1f - taperStart) * (t / edge) else 1f
        val b = if (t > 1f - edge) taperEnd + (1f - taperEnd) * ((1f - t) / edge) else 1f
        return minOf(a, b)
    }
}

@Composable
private fun SampleLine(brush: Brush, brushSize: BrushSize, colour: Color, modifier: Modifier = Modifier) {
    val sized = remember(brush, brushSize) { brush.copy(size = brushSize) }
    val style = remember(sized) { runCatching { InkStyles.style(sized) }.getOrNull() }
    val nominal = style?.nominalWidth() ?: brushWidth(sized)
    val width = SampleStroke.widthDp(nominal)
    val startK = style?.taperStart ?: 1f
    val endK = style?.taperEnd ?: 1f
    Canvas(modifier.size(SampleStroke.LENGTH_DP.dp + 4.dp, 24.dp)) {
        val len = SampleStroke.LENGTH_DP.dp.toPx()
        val x0 = (size.width - len) / 2
        val amp = 3.dp.toPx()
        val cy = size.height / 2
        val path = Path().apply {
            moveTo(x0, cy + amp * 0.6f)
            cubicTo(x0 + len * 0.3f, cy - amp * 1.4f, x0 + len * 0.7f, cy + amp * 1.4f, x0 + len, cy - amp * 0.6f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val steps = 24
        val whole = width.dp.toPx()
        val piece = Path()
        for (i in 0 until steps) {
            val a = i / steps.toFloat(); val b = (i + 1) / steps.toFloat()
            piece.rewind()
            measure.getSegment(measure.length * a, measure.length * b, piece, true)
            val w = whole * SampleStroke.taper((a + b) / 2, startK, endK)
            drawPath(piece, colour, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** What the ink row can offer: a recent OKLCH ink or a named preset, with how it reads aloud. */
private class InkChoice(val colour: Color, val description: String, val apply: () -> Unit)

/**
 * The active tool's tray, as wide as the bar and never scrolling. Row 1: the pen heads (and the highlighter while it is the
 * tool), spread evenly, the chosen one in the soft disc. Row 2: three sample strokes in the real pen, size and ink. Row 3:
 * the current ink as a ring (the bubble's ring), up to five swatches that fit (recent OKLCH inks, then the named presets),
 * and the spectrum, which opens the picker for everything else.
 */
@Composable
fun ToolOptions(brush: Brush, onSize: (BrushSize) -> Unit, onColour: (InkColour) -> Unit, modifier: Modifier = Modifier,
    penLook: PenLook = PenLook.FOUNTAIN, onPenLook: (PenLook) -> Unit = {}, recentInks: List<Oklch> = emptyList(),
    onCustomInk: (Oklch) -> Unit = {}, onOpenPicker: () -> Unit = {}) {
    val c = Syncr.colors
    val highlighter = brush.family == BrushFamily.HIGHLIGHTER
    val current = Color(inkColour(brush, c.dark)).compositeOver(c.surface2)
    // The ring swatch matches the bubble's ring (a highlighter's is stronger than its stroke); the samples show the stroke itself.
    val ringColour = if (highlighter) Color(inkColour(brush, c.dark)).copy(alpha = 0.9f).compositeOver(c.surface2) else current
    val screen = LocalConfiguration.current.screenWidthDp.toFloat()
    Column(modifier.width(BarLayout.width(screen).dp).floatingSurface(Radius.card).padding(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        // Row 1: the heads, evenly spread.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PenLook.entries.forEach { look ->
                val head = Head.valueOf(look.name)
                val selected = !highlighter && penLook == look
                HeadCell(head, selected, onLongPress = { onPenLook(look); onOpenPicker() }) { onPenLook(look) }
            }
            if (highlighter) HeadCell(Head.HIGHLIGHTER, true, onLongPress = onOpenPicker) {}
        }
        // Row 2: size, shown as the line the pen will make.
        Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            BrushSize.entries.forEach { option ->
                val selected = brush.size == option
                Box(Modifier.weight(1f).height(TouchTarget).selectable(selected, role = Role.RadioButton, onClick = { onSize(option) })
                    .semantics { contentDescription = "${option.label()} size" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(TouchTarget).trayDisc(selected), contentAlignment = Alignment.Center) { SampleLine(brush, option, current) }
                }
            }
        }
        // Row 3: ink.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val room = (maxWidth / INK_CELL).toInt()
            val count = (room - 2).coerceIn(0, MAX_SWATCHES)
            val choices = remember(recentInks, brush.colour, brush.customInk, c.dark, count) {
                val recent = recentInks.filter { it != brush.customInk }.map { oklch ->
                    InkChoice(Color(oklch.strokeArgb(c.dark)), "Ink: ${oklch.inkName()}, recent") { onCustomInk(oklch) }
                }
                val presets = InkColour.entries.filter { brush.customInk != null || it != brush.colour }.map { colour ->
                    InkChoice(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), c.dark)), "Ink: ${colour.inkName()}, preset") { onColour(colour) }
                }
                (recent + presets).take(count)
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                val now = brush.customInk?.inkName() ?: brush.colour.inkName()
                Box(Modifier.weight(1f).height(TouchTarget).semantics { contentDescription = "Ink: $now, current"; stateDescription = "Selected" },
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(30.dp)) { drawCircle(ringColour, 12.dp.toPx(), style = Stroke(3.dp.toPx())) }
                }
                choices.forEach { choice ->
                    Box(Modifier.weight(1f).height(TouchTarget).selectable(false, role = Role.RadioButton, onClick = choice.apply)
                        .semantics { contentDescription = choice.description }, contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(30.dp)) { drawCircle(choice.colour, 11.dp.toPx()) }
                    }
                }
                Box(Modifier.weight(1f).height(TouchTarget).selectable(false, role = Role.Button, onClick = onOpenPicker)
                    .semantics { contentDescription = "Choose any ink" }, contentAlignment = Alignment.Center) { SpectrumDot() }
            }
        }
    }
}

private val INK_CELL = 44.dp
private const val MAX_SWATCHES = 5

/** One head in the tray: a 56dp target, the chosen head in the soft disc (drawn on once when chosen). */
@Composable
private fun HeadCell(head: Head, selected: Boolean, onLongPress: () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(TouchTarget + 8.dp).padding(Space.xs).trayDisc(selected)
        .combinedClickable(role = Role.RadioButton, onLongClickLabel = "Choose any ink", onLongClick = onLongPress, onClick = onClick)
        .semantics { contentDescription = if (selected) "${head.spoken}, selected" else head.spoken },
        contentAlignment = Alignment.Center) {
        PenHead(head, size = 42.dp, playKey = if (selected) true else null, freeze = if (selected) StippleDebug.freeze else null)
    }
}

/** The way into the picker: a small ring of colours, drawn still. */
@Composable
private fun SpectrumDot() {
    val colours = remember { List(12) { Color(PickerMath.argb(PickerMath.RING_L, PickerMath.RING_C, it * 30f)) } }
    Canvas(Modifier.size(30.dp)) {
        val r = 11.dp.toPx(); val w = 5.dp.toPx()
        for (i in 0 until 12) drawArc(colours[i], i * 30f - 1f, 32f, false,
            Offset(size.width / 2 - r + w / 2, size.height / 2 - r + w / 2), Size(2 * r - w, 2 * r - w), style = Stroke(w))
    }
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
