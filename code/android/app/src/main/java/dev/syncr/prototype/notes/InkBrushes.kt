package dev.syncr.prototype.notes

import android.annotation.SuppressLint
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.SampleChannels
import dev.syncr.prototype.ui.theme.NotePalette
import java.util.concurrent.ConcurrentHashMap
import androidx.ink.brush.Brush as InkBrush
import androidx.ink.brush.BrushFamily as InkFamily

/** A brush's nominal width in canvas units. Highlighters are five times wider. */
fun brushWidth(brush: Brush): Float {
    val width = when (brush.size) {
        BrushSize.SMALL -> 2f
        BrushSize.MEDIUM -> 4f
        BrushSize.LARGE -> 8f
    }
    return if (brush.family == BrushFamily.HIGHLIGHTER) width * 5f else width
}

/** A brush's ARGB colour under the theme. Highlighters are translucent. */
fun inkColour(brush: Brush, dark: Boolean = false): Int {
    val rgb = when (brush.colour) {
        InkColour.INK -> NotePalette.ink(dark)
        InkColour.RED -> NotePalette.red(dark)
        InkColour.GREEN -> NotePalette.green(dark)
        InkColour.BLUE -> NotePalette.blue(dark)
        InkColour.ORANGE -> NotePalette.orange(dark)
    }
    return if (brush.family == BrushFamily.HIGHLIGHTER) (rgb and 0x00ffffff) or (NotePalette.highlighterAlpha shl 24) else rgb
}

/**
 * Pure description of how one stored [Brush] is drawn under the local [InkPreferences]. Sizes and
 * lengths are in page units unless named "sizes" (multiples of [size]). Wet and dry ink, and library
 * thumbnails, all draw from the same style, so a stroke keeps its shape when the pen lifts.
 */
data class StrokeStyle(
    val size: Float,
    val epsilon: Float,
    /** Size multipliers at the lightest and the full end of [InkStyles.pressureCurve]; equal means pressure is ignored. */
    val pressureLow: Float,
    val pressureHigh: Float,
    val pressureDampingSeconds: Float,
    /** Size multiplier once the pen moves at [speedEndSizesPerSecond] or faster; 1 means no thinning. */
    val speedThinning: Float,
    val speedStartSizesPerSecond: Float,
    val speedEndSizesPerSecond: Float,
    /** Size multiplier at the first sample, recovering over [taperStartSizes]; 1 means no taper. */
    val taperStart: Float,
    val taperStartSizes: Float,
    /** Size multiplier at the last sample, over the final [taperEndSizes]; 1 means no taper. */
    val taperEnd: Float,
    val taperEndSizes: Float,
    /** Input smoothing: Ink's sliding-window model. */
    val smoothingWindowMs: Long,
    val upsamplingHz: Int,
    val tipScaleX: Float = 1f,
    val tipScaleY: Float = 1f,
    val cornerRounding: Float = 1f,
    val tipRotationDegrees: Float = 0f,
    /** Translucent ink must not darken where one stroke crosses itself. */
    val discardSelfOverlap: Boolean = false,
) {
    val pressureSensitive get() = pressureHigh > pressureLow

    /** Width at typical writing pressure, ignoring speed and taper. */
    fun nominalWidth(): Float = widthAt(InkStyles.TYPICAL_PRESSURE)

    /** Width at a raw pressure, ignoring damping, speed and taper. */
    fun widthAt(pressure: Float): Float =
        if (!pressureSensitive) size else size * (pressureLow + (pressureHigh - pressureLow) * InkStyles.pressureCurve(pressure))
}

/** Pure mapping from a stored brush and the local preferences to a [StrokeStyle]. */
object InkStyles {
    /**
     * Mesh tolerance in page units. Pages are 1600 units wide and the deepest zoom is about 4 px per
     * unit, so 0.05 units stays under a quarter pixel there; at fit-width it is a few hundredths.
     */
    const val EPSILON = 0.05f

    /** S Pen pressure during ordinary handwriting sits around here. */
    const val TYPICAL_PRESSURE = 0.3f

    /**
     * Gentle ease-out response (CSS-style cubic Bézier control points x1, y1, x2, y2): light touches
     * already give a usable line, typical writing lands mid-range, and the last stretch of force adds
     * width slowly, like a flexing nib.
     */
    val PRESSURE_CURVE = floatArrayOf(0.25f, 0.5f, 0.6f, 1f)

    /** The same curve Ink evaluates, for tests and nominal widths. */
    fun pressureCurve(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        val (x1, y1, x2, y2) = PRESSURE_CURVE.toList()
        fun bezier(a: Float, b: Float, t: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
        var lo = 0f; var hi = 1f
        repeat(40) { val t = (lo + hi) / 2; if (bezier(x1, x2, t) < x) lo = t else hi = t }
        return bezier(y1, y2, (lo + hi) / 2)
    }

    /** 0 → off, 0.5 → 1 (the tuned default), 1 → 1.7. */
    fun pressureGain(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return if (p <= 0.5f) p * 2f else 1f + (p - 0.5f) * 1.4f
    }

    /**
     * Centre-line smoothing window. Every millisecond of window is lag the pen feels, and prediction
     * now hides the rest, so the default is short: 0 → 1 ms (raw; Ink wants a positive window, and
     * 1 ms is below one 180 Hz upsampling step), 0.5 → 8 ms (default), 1 → 24 ms (very smooth).
     * Was 6 / 20 / 40 ms.
     */
    fun smoothingWindowMs(smoothing: Float): Long {
        val s = smoothing.coerceIn(0f, 1f)
        return Math.round(if (s <= 0.5f) 1f + s * 14f else 8f + (s - 0.5f) * 32f).toLong()
    }

    /** Width follows pressure with this lag: 4 ms (raw), 9 ms (default), 14 ms. Was 6 / 18 / 30 ms. */
    fun pressureDampingSeconds(smoothing: Float): Float = 0.004f + 0.010f * smoothing.coerceIn(0f, 1f)

    /** Speed thinning lag: enough to stop width flicker on jittery speed, short enough to follow. Was 30 ms. */
    const val SPEED_DAMPING_SECONDS = 0.018f

    private const val UPSAMPLING_HZ = 180
    private const val SPEED_START_UNITS = 900f
    private const val SPEED_END_UNITS = 4500f

    /** [minSize] lets tiny thumbnails keep hairlines visible. */
    fun style(brush: Brush, prefs: InkPreferences, minSize: Float = 0f): StrokeStyle {
        val size = maxOf(brushWidth(brush), minSize)
        val window = smoothingWindowMs(prefs.smoothing)
        if (brush.family == BrushFamily.HIGHLIGHTER) {
            // Flat translucent chisel held upright: full height on horizontal passes, narrow on vertical ones.
            return StrokeStyle(size, EPSILON, 1f, 1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, window, UPSAMPLING_HZ,
                tipScaleX = 0.4f, tipScaleY = 1f, cornerRounding = 0.3f, discardSelfOverlap = true)
        }
        val look = LOOKS.getValue(prefs.penLook)
        val amount = look.pressure * pressureGain(prefs.pressure)
        val low = (1f - amount).coerceAtLeast(0.12f)
        val high = (1f + amount).coerceAtMost(2f)
        val taper = prefs.taper && look.taperStart < 1f
        return StrokeStyle(
            size = size, epsilon = EPSILON,
            pressureLow = if (amount > 0f) low else 1f, pressureHigh = if (amount > 0f) high else 1f,
            pressureDampingSeconds = pressureDampingSeconds(prefs.smoothing),
            speedThinning = look.speedThinning,
            speedStartSizesPerSecond = SPEED_START_UNITS / size, speedEndSizesPerSecond = SPEED_END_UNITS / size,
            taperStart = if (taper) look.taperStart else 1f,
            taperStartSizes = (look.taperStartUnits + look.taperPerSize * size) / size,
            taperEnd = if (taper) look.taperEnd else 1f,
            taperEndSizes = (look.taperEndUnits + look.taperPerSize * size) / size,
            smoothingWindowMs = window, upsamplingHz = UPSAMPLING_HZ,
        )
    }

    /** Per-look constants. Taper lengths are page units plus a share of the size, so they read at every size. */
    internal class Look(val pressure: Float, val speedThinning: Float, val taperStart: Float, val taperEnd: Float,
        val taperStartUnits: Float, val taperEndUnits: Float, val taperPerSize: Float)

    internal val LOOKS = mapOf(
        PenLook.FOUNTAIN to Look(pressure = 0.6f, speedThinning = 0.82f, taperStart = 0.22f, taperEnd = 0.18f,
            taperStartUnits = 18f, taperEndUnits = 16f, taperPerSize = 2f),
        PenLook.BALLPOINT to Look(pressure = 0.15f, speedThinning = 0.93f, taperStart = 0.6f, taperEnd = 0.6f,
            taperStartUnits = 8f, taperEndUnits = 8f, taperPerSize = 1f),
        PenLook.FINELINER to Look(pressure = 0f, speedThinning = 1f, taperStart = 1f, taperEnd = 1f,
            taperStartUnits = 0f, taperEndUnits = 0f, taperPerSize = 1f),
    )
}

/**
 * Builds and caches AndroidX Ink brush families for [StrokeStyle]s. Thread-safe.
 *
 * Lint reports the custom-brush builders below as RestrictedApi. They are public
 * `@ExperimentalInkCustomBrushApi` API in Ink 1.0.0 (only members such as `nativePointer` are
 * `@RestrictTo`), so the check is a false positive; the opt-in is the real contract.
 */
@SuppressLint("RestrictedApi")
object InkBrushes {
    private val families = ConcurrentHashMap<StrokeStyle, InkFamily>()

    fun brush(brush: Brush, dark: Boolean, prefs: InkPreferences, minSize: Float = 0f): InkBrush {
        val style = InkStyles.style(brush, prefs, minSize)
        return InkBrush.createWithColorIntArgb(family(style), inkColour(brush, dark), style.size, style.epsilon)
    }

    fun family(style: StrokeStyle): InkFamily = families.getOrPut(style) { build(style) }

    /** A disposable rendering cache for canonical samples. Never the source of truth. */
    fun stroke(channels: SampleChannels, brush: InkBrush): Stroke {
        val batch = MutableStrokeInputBatch()
        val input = StrokeInput()
        val seen = hashSetOf<Triple<Float, Float, Long>>()
        channels.x.indices.forEach { i ->
            val t = channels.elapsedMs[i].toLong()
            // Rust retains every measured sample. Ink disallows repeated x/y/time triplets,
            // so deduplicate ONLY this disposable renderer input, never the canonical data.
            if (!seen.add(Triple(channels.x[i], channels.y[i], t))) return@forEach
            // Android orientation starts at up; Ink starts at right and uses [0,2π).
            val orientation = channels.orientation?.get(i)?.let {
                val radians = it - (Math.PI / 2).toFloat()
                val turn = (2 * Math.PI).toFloat()
                val normalized = if (radians < 0) radians + turn else radians
                if (normalized >= turn) 0f else normalized
            }
            input.update(channels.x[i], channels.y[i], t, InputToolType.STYLUS,
                pressure = channels.pressure[i].coerceIn(0f, 1f), tiltRadians = channels.tilt?.get(i) ?: StrokeInput.NO_TILT,
                orientationRadians = orientation ?: StrokeInput.NO_ORIENTATION)
            batch.add(input)
        }
        return Stroke(brush, batch)
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun build(style: StrokeStyle): InkFamily {
        val behaviors = mutableListOf<BrushBehavior>()
        if (style.pressureSensitive) {
            val (x1, y1, x2, y2) = InkStyles.PRESSURE_CURVE.toList()
            val curve = EasingFunction.CubicBezier(x1, y1, x2, y2)
            behaviors += target(style.pressureLow, style.pressureHigh,
                BrushBehavior.ResponseNode(curve, BrushBehavior.DampingNode(BrushBehavior.DampingSource.TIME_IN_SECONDS,
                    style.pressureDampingSeconds, source(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f))))
        }
        if (style.speedThinning < 1f) {
            behaviors += target(1f, style.speedThinning, BrushBehavior.DampingNode(BrushBehavior.DampingSource.TIME_IN_SECONDS, InkStyles.SPEED_DAMPING_SECONDS,
                source(BrushBehavior.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, style.speedStartSizesPerSecond, style.speedEndSizesPerSecond)))
        }
        // Tapers are gated by the distance to the other end, so a tap stays a full round dot and a
        // short tick tapers only a little.
        val traveled = BrushBehavior.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE
        val remaining = BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE
        if (style.taperStart < 1f) {
            behaviors += target(1f, style.taperStart, product(source(traveled, style.taperStartSizes, 0f),
                source(remaining, 0f, style.taperStartSizes * 0.6f)))
        }
        if (style.taperEnd < 1f) {
            behaviors += target(1f, style.taperEnd, product(source(remaining, style.taperEndSizes, 0f),
                source(traveled, 0f, style.taperEndSizes * 0.6f)))
        }
        behaviors += StockBrushes.predictionFadeOutBehavior
        val tip = BrushTip(style.tipScaleX, style.tipScaleY, style.cornerRounding, 0f, 0f, style.tipRotationDegrees, 0f, 0L, behaviors)
        val paint = BrushPaint(emptyList(), emptyList(), if (style.discardSelfOverlap) SelfOverlap.DISCARD else SelfOverlap.ANY)
        return InkFamily(tip, paint, "syncr:${style.hashCode()}", InkFamily.SlidingWindowModel(style.smoothingWindowMs, style.upsamplingHz))
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun source(source: BrushBehavior.Source, start: Float, end: Float) =
        BrushBehavior.SourceNode(source, start, end, BrushBehavior.OutOfRange.CLAMP)

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun product(a: BrushBehavior.ValueNode, b: BrushBehavior.ValueNode) =
        BrushBehavior.BinaryOpNode(BrushBehavior.BinaryOp.PRODUCT, a, b)

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun target(start: Float, end: Float, input: BrushBehavior.ValueNode) =
        BrushBehavior(listOf(BrushBehavior.TargetNode(BrushBehavior.Target.SIZE_MULTIPLIER, start, end, input)))
}
