package dev.syncr.prototype.tablet

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tablet mode, Pointer link: pad pixels to HID absolute coordinates (design §8.7).
 *
 * The rule is one scale for both directions, always. Pure Kotlin, no Android types, so it is unit-tested.
 */
enum class MappingMode { ACTUAL_SIZE, WHOLE_SCREEN }

/** The Mac screen's physical size. Default is the built-in M2 MacBook Air display. */
data class MacScreen(val widthMm: Double, val heightMm: Double) {
    init { require(widthMm > 0 && heightMm > 0 && widthMm.isFinite() && heightMm.isFinite()) }
    companion object { val DEFAULT = MacScreen(290.0, 189.0) }
}

/** The phone's reported physical pixels per inch (DisplayMetrics.xdpi / ydpi). */
data class PhoneDpi(val x: Double, val y: Double) {
    init { require(x > 0 && y > 0 && x.isFinite() && y.isFinite()) }
}

/** An axis-aligned rectangle in pad pixels. */
data class PadRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

/** A HID absolute position as actually sent, each axis 0..32767. */
data class HidPoint(val x: Int, val y: Int)

object TabletMapping {
    const val HID_MAX = 32767
    private const val MM_PER_INCH = 25.4

    /** sent = round(clamp(u, 0, 1) * 32767). */
    fun hid(u: Double): Int = (u.coerceIn(0.0, 1.0) * HID_MAX).roundToInt()

    fun pxToMm(px: Double, dpi: Double): Double = px * MM_PER_INCH / dpi
    fun mmToPx(mm: Double, dpi: Double): Double = mm * dpi / MM_PER_INCH

    /** The largest rectangle of shape aspectW:aspectH centred in [bounds]. */
    fun letterbox(bounds: PadRect, aspectW: Double, aspectH: Double): PadRect {
        val scale = min(bounds.width / aspectW, bounds.height / aspectH)
        val w = aspectW * scale
        val h = aspectH * scale
        return PadRect(bounds.left + (bounds.width - w) / 2, bounds.top + (bounds.height - h) / 2, w, h)
    }

    /**
     * Actual size: the pad is a 1:1 window onto the Mac screen. If the pad is physically larger than the
     * screen on an axis (not the case for a phone and a laptop), the window is cut to the screen's size there,
     * centred, so every millimetre of the active area still maps to a millimetre of screen.
     */
    fun actualSizeArea(bounds: PadRect, screen: MacScreen, dpi: PhoneDpi): PadRect {
        val w = min(bounds.width, mmToPx(screen.widthMm, dpi.x))
        val h = min(bounds.height, mmToPx(screen.heightMm, dpi.y))
        return PadRect(bounds.left + (bounds.width - w) / 2, bounds.top + (bounds.height - h) / 2, w, h)
    }
}

/**
 * One configured mapping. [bounds] is the part of the pad available for the active area;
 * [requestedOffsetXMm]/[requestedOffsetYMm] are the actual-size window's top-left corner on the Mac screen,
 * clamped into [offsetXMm]/[offsetYMm] so the window stays on screen.
 */
class TabletMapper(
    val mode: MappingMode,
    val bounds: PadRect,
    val dpi: PhoneDpi,
    val screen: MacScreen,
    requestedOffsetXMm: Double = 0.0,
    requestedOffsetYMm: Double = 0.0,
) {
    val activeArea: PadRect = when (mode) {
        MappingMode.ACTUAL_SIZE -> TabletMapping.actualSizeArea(bounds, screen, dpi)
        MappingMode.WHOLE_SCREEN -> TabletMapping.letterbox(bounds, screen.widthMm, screen.heightMm)
    }

    /** Size of the actual-size window on the Mac screen, in mm. */
    val windowWidthMm: Double = TabletMapping.pxToMm(activeArea.width, dpi.x)
    val windowHeightMm: Double = TabletMapping.pxToMm(activeArea.height, dpi.y)

    val maxOffsetXMm: Double = max(0.0, screen.widthMm - windowWidthMm)
    val maxOffsetYMm: Double = max(0.0, screen.heightMm - windowHeightMm)
    val offsetXMm: Double = requestedOffsetXMm.finiteOr0().coerceIn(0.0, maxOffsetXMm)
    val offsetYMm: Double = requestedOffsetYMm.finiteOr0().coerceIn(0.0, maxOffsetYMm)

    /** Unclamped (u, v); values outside 0..1 mean the pen is outside the active area. */
    fun uv(x: Double, y: Double): Pair<Double, Double> {
        val px = x - activeArea.left
        val py = y - activeArea.top
        return when (mode) {
            MappingMode.ACTUAL_SIZE -> Pair(
                (offsetXMm + TabletMapping.pxToMm(px, dpi.x)) / screen.widthMm,
                (offsetYMm + TabletMapping.pxToMm(py, dpi.y)) / screen.heightMm,
            )
            MappingMode.WHOLE_SCREEN -> Pair(px / activeArea.width, py / activeArea.height)
        }
    }

    fun toHid(x: Double, y: Double): HidPoint {
        val (u, v) = uv(x, y)
        return HidPoint(TabletMapping.hid(u), TabletMapping.hid(v))
    }

    /** The window moved by a finger drag of (dxPx, dyPx) pad pixels: it follows the fingers 1:1. */
    fun movedBy(dxPx: Double, dyPx: Double): TabletMapper = withOffset(
        offsetXMm + TabletMapping.pxToMm(dxPx, dpi.x),
        offsetYMm + TabletMapping.pxToMm(dyPx, dpi.y),
    )

    fun withOffset(xMm: Double, yMm: Double) = TabletMapper(mode, bounds, dpi, screen, xMm, yMm)

    private fun Double.finiteOr0() = if (isFinite()) this else 0.0
}
