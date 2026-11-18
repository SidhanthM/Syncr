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
import dev.syncr.core.strokeStyleMinSize
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
 * Pure description of how one stored [Brush] is drawn. Sizes and lengths are in page units unless named
 * "sizes" (multiples of [size]). Wet and dry ink, library thumbnails and the Mac all draw from the same
 * style, which the core derives from the brush alone, so a stroke keeps its shape when the pen lifts and
 * never changes with a later setting.
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

/** The mapping from a stored brush to a [StrokeStyle] is the core's (`default_stroke_style`); this adds the Ink-side constants. */
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

    /** Speed thinning lag: enough to stop width flicker on jittery speed, short enough to follow. Was 30 ms. */
    const val SPEED_DAMPING_SECONDS = 0.018f

    private val styles = ConcurrentHashMap<Pair<Brush, Float>, StrokeStyle>()

    /** The style a stored [brush] is drawn with. [minSize] lets tiny thumbnails keep hairlines visible. */
    fun style(brush: Brush, minSize: Float = 0f): StrokeStyle {
        if (styles.size > MAX_CACHED_STYLES) styles.clear()
        return styles.getOrPut(brush.copy() to minSize) {
            val core = strokeStyleMinSize(brush, minSize)
            StrokeStyle(
                size = core.size, epsilon = core.epsilon,
                pressureLow = core.pressureLow, pressureHigh = core.pressureHigh,
                pressureDampingSeconds = core.pressureDampingSeconds, speedThinning = core.speedThinning,
                speedStartSizesPerSecond = core.speedStartSizesPerSecond, speedEndSizesPerSecond = core.speedEndSizesPerSecond,
                taperStart = core.taperStart, taperStartSizes = core.taperStartSizes,
                taperEnd = core.taperEnd, taperEndSizes = core.taperEndSizes,
                smoothingWindowMs = core.smoothingWindowMs.toLong(), upsamplingHz = core.upsamplingHz.toInt(),
                tipScaleX = core.tipScaleX, tipScaleY = core.tipScaleY, cornerRounding = core.cornerRounding,
                tipRotationDegrees = core.tipRotationDegrees, discardSelfOverlap = core.discardSelfOverlap,
            )
        }
    }

    private const val MAX_CACHED_STYLES = 512
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

    fun brush(brush: Brush, dark: Boolean, minSize: Float = 0f): InkBrush {
        val style = InkStyles.style(brush, minSize)
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
