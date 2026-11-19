package dev.syncr.prototype.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import kotlin.math.hypot
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.Oklch
import dev.syncr.prototype.notes.OklchInk
import dev.syncr.prototype.notes.toArgb as oklchArgb
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.stipple.Stipple
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.floatingSurface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The picker's geometry, pure so it can be tested. It does no colour maths of its own: every conversion, the gamut edge
 * and the snap come from the core ([OklchInk], [toArgb]), the same ones a stroke is drawn with.
 */
object PickerMath {
    /** The most chroma the field shows (sRGB's own maximum is below this in every hue). */
    const val MAX_CHROMA = OklchInk.MAX_CHROMA
    /** The ring's colour: one lightness and chroma that every hue can reach. */
    const val RING_L = 0.72f
    const val RING_C = 0.1f

    fun maxChroma(l: Float, h: Float): Float = OklchInk.maxChroma(l, h)
    fun inGamut(l: Float, c: Float, h: Float): Boolean = OklchInk.inGamut(Oklch(l, c, h))

    /** The picked point snapped to the gamut: chroma is reduced at the same lightness and hue, nothing else moves. */
    fun snap(l: Float, c: Float, h: Float): Oklch =
        OklchInk.snapToGamut(Oklch(l.coerceIn(0f, 1f), c.coerceIn(0f, MAX_CHROMA), ((h % 360f) + 360f) % 360f))

    fun argb(l: Float, c: Float, h: Float): Int = snap(l, c, h).oklchArgb()

    /** The hue (degrees) of a touch at [dx], [dy] from the ring's centre, 0 at the right, turning clockwise on screen. */
    fun hueAt(dx: Float, dy: Float): Float = ((atan2(dy, dx) * 180 / PI).toFloat() + 360f) % 360f

    /** Lightness and chroma for a touch at fractions [x] (chroma, 0 left) and [y] (0 top = light) of the field. */
    fun fieldPoint(x: Float, y: Float): Pair<Float, Float> = (1f - y.coerceIn(0f, 1f)) to x.coerceIn(0f, 1f) * MAX_CHROMA
}

/** Channel-wise mix of two opaque ARGB colours: [t] of [b], the rest [a]. */
private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t + .5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * The field for [h] at device resolution ([size] px square, [dp] px per dp): the in-gamut colour smoothly blended from a coarse
 * grid of core conversions (chroma held at the gamut edge so every sample is a real colour), the gamut edge a soft, very slightly
 * wavering line, and the out-of-gamut side a fine faint stipple of 0.75 dp dots on the paper, never a clipped colour.
 * The core is asked once per grid point and once per pixel row; the caller caches the bitmap per hue.
 */
private suspend fun fieldImage(h: Float, size: Int, dp: Float, paper: Int, dotColour: Int): ImageBitmap {
    val g = 32
    val grid = Array(g + 1) { r ->
        val l = 1f - r.toFloat() / g
        val edge = OklchInk.maxChroma(l, h)
        IntArray(g + 1) { k -> Oklch(l, minOf(PickerMath.MAX_CHROMA * k / g, edge), h).oklchArgb() }
    }
    val px = IntArray(size * size)
    val cell = 3.6f * dp
    val radius = 0.75f * dp
    val pixelChroma = PickerMath.MAX_CHROMA / size
    for (y in 0 until size) {
        if (y % 16 == 0) currentCoroutineContext().ensureActive()
        val l = 1f - (y + .5f) / size
        // A hair of hand-drawn waver along the edge, the same on every draw.
        val edge = OklchInk.maxChroma(l, h) + 0.004f * sin(y * 0.05f + h * 0.1f) + 0.0025f * sin(y * 0.14f + 1.7f)
        val gy = ((1f - l) * g).coerceIn(0f, g - 0.001f); val r0 = gy.toInt(); val fy = gy - r0
        val row = (y / cell).toInt()
        for (x in 0 until size) {
            val chroma = (x + .5f) / size * PickerMath.MAX_CHROMA
            val inside = ((edge - chroma) / (pixelChroma * 1.6f) + .5f).coerceIn(0f, 1f)
            val gx = ((x + .5f) / size * g).coerceIn(0f, g - 0.001f); val k0 = gx.toInt(); val fx = gx - k0
            val colour = if (inside > 0f) mix(mix(grid[r0][k0], grid[r0][k0 + 1], fx), mix(grid[r0 + 1][k0], grid[r0 + 1][k0 + 1], fx), fy) else 0
            var out = paper
            if (inside < 1f) {
                val cx = ((x / cell).toInt() + .5f + if (row % 2 == 1) .5f else 0f) * cell
                val cy = (row + .5f) * cell
                val d = hypot(x + .5f - cx, y + .5f - cy)
                out = mix(paper, dotColour, (radius + .5f - d).coerceIn(0f, 1f) * .42f)
            }
            px[y * size + x] = if (inside >= 1f) colour else if (inside <= 0f) out else mix(out, colour, inside)
        }
    }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The OKLCH picker: a hue ring around a lightness-by-chroma field for that hue. The field's out-of-gamut part is a quiet
 * dither; a touch there snaps to the gamut boundary by reducing chroma. Five named inks stay as one-tap presets, with the
 * recent custom inks after them. Nothing loops; it dissolves in and out with the house stipple, in one 240 ms pass.
 *
 * [current] is the ink being edited, [onChange] reports every move (a preview), [onCommit] a finished choice
 * (lifting the finger, a recent ink), [onNamed] a preset, [onClose] after the closing dissolve has run.
 */
@Composable
fun InkPicker(current: Oklch, recents: List<Oklch>, named: InkColour?, onChange: (Oklch) -> Unit, onCommit: (Oklch) -> Unit,
    onNamed: (InkColour) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, closeRequest: Int = 0) {
    val c = Syncr.colors
    val reduced = Syncr.reducedMotion
    val presence = remember { Animatable(if (reduced) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val closed by rememberUpdatedState(onClose)
    LaunchedEffect(reduced) { if (reduced) presence.snapTo(1f) else presence.animateTo(1f, Motion.pickerIn()) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { if (!reduced) presence.animateTo(0f, Motion.pickerOut()); closed() }
    }
    LaunchedEffect(closeRequest) { if (closeRequest > 0) close() }
    // Gesture handlers retain this state holder, not a replaced holder from a previous committed colour.
    var ink by remember { mutableStateOf(current) }
    var gestureStart by remember { mutableStateOf(current) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(current) { ink = current }
    fun beginDrag() { gestureStart = ink; dragging = true }
    fun finishDrag() { dragging = false; commit(ink) }
    fun cancelDrag() { dragging = false; ink = gestureStart; change(ink) }
    fun adjust(l: Float = ink.l, chroma: Float = ink.c, hue: Float = ink.h): Boolean {
        ink = PickerMath.snap(l, chroma, hue); commit(ink); return true
    }
    // The field is built off the main thread; the old one stays on screen until the new hue's is ready.
    var field by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    val hueKey = (ink.h / 2f).toInt()
    val paper = c.surface2.toArgb(); val dots = c.lineStrong.toArgb()
    val density = LocalDensity.current.density
    val fieldPx = (100 * density).toInt()
    LaunchedEffect(hueKey, c.dark, fieldPx) { field = hueKey to withContext(Dispatchers.Default) { fieldImage(hueKey * 2f, fieldPx, density, paper, dots) } }
    val ringColours = remember { IntArray(90) { PickerMath.argb(PickerMath.RING_L, PickerMath.RING_C, it * 4f) } }
    Column(modifier.width(264.dp).dissolve { presence.value }.floatingSurface(Radius.card).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ringDp = 168.dp
        Box(Modifier.size(ringDp), contentAlignment = Alignment.Center) {
            val ringWidth = 16.dp
            Canvas(Modifier.size(ringDp).semantics {
                contentDescription = "Hue ring"
                progressBarRangeInfo = ProgressBarRangeInfo(ink.h, 0f..360f)
                setProgress { adjust(hue = it) }
            }.pointerInput(Unit) {
                fun at(p: Offset) {
                    val h = PickerMath.hueAt(p.x - size.width / 2f, p.y - size.height / 2f)
                    val (l, cc, hh) = PickerMath.snap(ink.l, ink.c, h)
                    ink = Oklch(l, cc, hh); change(ink)
                }
                detectDragGestures(onDragStart = { beginDrag(); at(it) }, onDragEnd = { finishDrag() },
                    onDragCancel = { cancelDrag() }) { event, _ -> event.consume(); at(event.position) }
            }.pointerInput(Unit) { detectTapGestures { p ->
                val h = PickerMath.hueAt(p.x - size.width / 2f, p.y - size.height / 2f)
                val (l, cc, hh) = PickerMath.snap(ink.l, ink.c, h); ink = Oklch(l, cc, hh); commit(ink)
            } }) {
                val stroke = ringWidth.toPx()
                val rad = size.minDimension / 2 - stroke / 2
                val steps = 90
                // Short arcs of one stroked ring, so neighbouring colours meet without gaps.
                for (i in 0 until steps) {
                    val hue = i * 360f / steps
                    drawArc(Color(ringColours[i]), hue - 0.2f, 360f / steps + 0.5f, false,
                        Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                }
                val middle = Offset(size.width / 2, size.height / 2)
                drawCircle(c.lineStrong.copy(alpha = c.lineStrong.alpha * .6f), rad + stroke / 2, middle, style = Stroke(.75.dp.toPx()))
                drawCircle(c.lineStrong.copy(alpha = c.lineStrong.alpha * .6f), rad - stroke / 2, middle, style = Stroke(.75.dp.toPx()))
                val a = ink.h * PI / 180
                val centre = Offset(size.width / 2 + (rad * cos(a)).toFloat(), size.height / 2 + (rad * sin(a)).toFloat())
                drawCircle(c.surface2, stroke / 2 + 1.5.dp.toPx(), centre)
                drawCircle(Color(PickerMath.argb(PickerMath.RING_L, PickerMath.RING_C, ink.h)), stroke / 2 - 1.dp.toPx(), centre)
                drawCircle(c.ink.copy(alpha = .55f), stroke / 2 + 1.5.dp.toPx(), centre, style = Stroke(.75.dp.toPx()))
            }
            val inner = 100.dp
            Canvas(Modifier.size(inner).testTag(if (field?.first == hueKey) "ink-field-ready" else "ink-field-loading").semantics {
                contentDescription = "Lightness and chroma"
                stateDescription = "Lightness ${"%.2f".format(ink.l)}, chroma ${"%.2f".format(ink.c)}"
                customActions = listOf(
                    CustomAccessibilityAction("Increase lightness") { adjust(l = ink.l + .05f) },
                    CustomAccessibilityAction("Decrease lightness") { adjust(l = ink.l - .05f) },
                    CustomAccessibilityAction("Increase chroma") { adjust(chroma = ink.c + .02f) },
                    CustomAccessibilityAction("Decrease chroma") { adjust(chroma = ink.c - .02f) },
                )
            }.pointerInput(Unit) {
                fun at(p: Offset) {
                    val (l, cc) = PickerMath.fieldPoint(p.x / size.width, p.y / size.height)
                    val (sl, sc, sh) = PickerMath.snap(l, cc, ink.h)
                    ink = Oklch(sl, sc, sh); change(ink)
                }
                detectDragGestures(onDragStart = { beginDrag(); at(it) }, onDragEnd = { finishDrag() },
                    onDragCancel = { cancelDrag() }) { event, _ -> event.consume(); at(event.position) }
            }.pointerInput(Unit) { detectTapGestures { p ->
                val (l, cc) = PickerMath.fieldPoint(p.x / size.width, p.y / size.height)
                val (sl, sc, sh) = PickerMath.snap(l, cc, ink.h); ink = Oklch(sl, sc, sh); commit(ink)
            } }) {
                field?.let { drawImage(it.second, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) }
                drawRect(c.lineStrong.copy(alpha = c.lineStrong.alpha * .7f), style = Stroke(.75.dp.toPx()))
                val at = Offset(ink.c / PickerMath.MAX_CHROMA * size.width, (1f - ink.l) * size.height)
                drawCircle(c.surface2, 8.dp.toPx(), at); drawCircle(Color(PickerMath.argb(ink.l, ink.c, ink.h)), 5.5.dp.toPx(), at)
                drawCircle(c.ink.copy(alpha = .55f), 8.dp.toPx(), at, style = Stroke(.75.dp.toPx()))
            }
        }
        val preset = named?.takeUnless { dragging }
        val swatch = preset?.let { Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, it), c.dark)) }
            ?: Color(PickerMath.argb(ink.l, ink.c, ink.h))
        var showValues by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(36.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { showValues = !showValues }) }
                .semantics { contentDescription = "Current ink"; onLongClick("Show or hide the values") { showValues = !showValues; true } }) {
                // The current ink is a ring, as in the tray and on the bubble.
                drawCircle(swatch, size.minDimension / 2 - 4.dp.toPx(), style = Stroke(3.dp.toPx()))
            }
            if (showValues) Text(preset?.label() ?: "L ${"%.2f".format(ink.l)}  C ${"%.2f".format(ink.c)}  H ${ink.h.toInt()}", style = Syncr.type.label, color = c.muted)
            Box(Modifier.size(TouchTarget).selectable(false, role = Role.Button, onClick = { close() }).semantics { contentDescription = "Done" },
                contentAlignment = Alignment.Center) { SyncrIcon(SyncrIcons.Check, c.ink, size = 22.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            InkColour.entries.forEach { colour ->
                Box(Modifier.size(TouchTarget).selectable(named == colour, role = Role.RadioButton, onClick = { onNamed(colour) })
                    .semantics { contentDescription = colour.label() }, contentAlignment = Alignment.Center) {
                    ColourDot(Color(inkColour(Brush(BrushFamily.PEN, BrushSize.MEDIUM, colour), c.dark)), named == colour)
                }
            }
        }
        if (recents.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            recents.take(8).forEach { r ->
                Box(Modifier.size(TouchTarget).selectable(false, role = Role.Button, onClick = { ink = r; commit(r) })
                    .semantics { contentDescription = "Recent ink, lightness ${"%.2f".format(r.l)}, hue ${r.h.toInt()}" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) { drawCircle(Color(PickerMath.argb(r.l, r.c, r.h)), size.minDimension / 2 - 1.dp.toPx()) }
                }
            }
        }
    }
}
