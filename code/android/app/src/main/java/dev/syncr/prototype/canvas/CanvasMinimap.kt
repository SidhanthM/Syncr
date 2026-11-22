package dev.syncr.prototype.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * The minimap, after xnotes' `Minimap`: a 168 x 120 px panel 14 px from the bottom-right corner (clear of any inset)
 * that maps the union of the content and the viewport, so panning into blank space still leaves the marker meaningful.
 * Pure geometry plus a small cached bitmap of the items (one rectangle each), rebuilt only when the content or the
 * mapped extent changes, never per frame.
 */
internal class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
}

internal class CanvasMinimap {
    /** The panel's pixel rectangle: left, top, right, bottom. */
    fun panel(viewW: Int, viewH: Int, insetRight: Float = 0f, insetBottom: Float = 0f, density: Float = 1f): PxRect {
        val w = WIDTH * density; val h = HEIGHT * density; val m = MARGIN * density
        val r = viewW - insetRight - m
        val b = viewH - insetBottom - m
        return PxRect(r - w, b - h, r, b)
    }

    /** What the panel maps: everything drawn plus where the view is now, never degenerate. */
    fun extent(content: UnitRect?, visible: UnitRect): UnitRect {
        val u = content?.union(visible) ?: visible
        val w = if (u.width > 1e-6) u.width else max(visible.width, 1.0)
        val h = if (u.height > 1e-6) u.height else max(visible.height, 1.0)
        return UnitRect(u.centerX - w / 2, u.centerY - h / 2, u.centerX + w / 2, u.centerY + h / 2)
    }

    /** Panel pixels per canvas unit, leaving [PAD] px inside the panel. */
    fun scaleFor(extent: UnitRect, panel: PxRect, density: Float = 1f): Double {
        if (extent.width <= 0 || extent.height <= 0) return 1.0
        val pad = PAD * density
        val aw = max(panel.width() - 2 * pad, 1f)
        val ah = max(panel.height() - 2 * pad, 1f)
        return min(aw / extent.width, ah / extent.height)
    }

    fun toPanelX(x: Double, extent: UnitRect, panel: PxRect, density: Float = 1f) =
        (panel.centerX() + (x - extent.centerX) * scaleFor(extent, panel, density)).toFloat()

    fun toPanelY(y: Double, extent: UnitRect, panel: PxRect, density: Float = 1f) =
        (panel.centerY() + (y - extent.centerY) * scaleFor(extent, panel, density)).toFloat()

    /** The canvas point a tap at panel pixel ([px], [py]) means, to centre the view on. */
    fun toCanvas(px: Float, py: Float, extent: UnitRect, panel: PxRect, density: Float = 1f): Pair<Double, Double> {
        val s = scaleFor(extent, panel, density)
        if (s <= 0) return extent.centerX to extent.centerY
        return (extent.centerX + (px - panel.centerX()) / s) to (extent.centerY + (py - panel.centerY()) / s)
    }

    private var items: Bitmap? = null
    private var itemsKey: Triple<Long, UnitRect, Int>? = null
    private val backPaint by lazy { Paint().apply { style = Paint.Style.FILL } }
    private val borderPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    private val itemPaint by lazy { Paint().apply { style = Paint.Style.FILL } }
    private val viewPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    private val bitmapPaint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }

    /**
     * Draws the panel. [rects] holds four doubles per item (left, top, right, bottom; NaN for none) and [version] changes
     * whenever they do. The viewport outline is drawn each frame in [accent].
     */
    fun draw(canvas: Canvas, panel: PxRect, extent: UnitRect, rects: DoubleArray, count: Int, version: Long, visible: UnitRect,
        density: Float, back: Int, border: Int, itemColour: Int, accent: Int) {
        backPaint.color = back
        canvas.drawRoundRect(panel.left, panel.top, panel.right, panel.bottom, 6 * density, 6 * density, backPaint)
        val bw = panel.width().toInt().coerceAtLeast(1)
        val bh = panel.height().toInt().coerceAtLeast(1)
        val key = Triple(version, extent, bw * 10_000 + bh)
        val cached = items
        val bitmap = if (cached != null && itemsKey == key) cached else {
            val b = if (cached != null && cached.width == bw && cached.height == bh) cached.also { it.eraseColor(0) } else Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val s = scaleFor(extent, panel, density)
            itemPaint.color = itemColour
            val cx = bw / 2f; val cy = bh / 2f
            for (i in 0 until count) {
                val l = rects[4 * i]
                if (l.isNaN()) continue
                val x0 = cx + ((l - extent.centerX) * s).toFloat(); val y0 = cy + ((rects[4 * i + 1] - extent.centerY) * s).toFloat()
                val x1 = cx + ((rects[4 * i + 2] - extent.centerX) * s).toFloat(); val y1 = cy + ((rects[4 * i + 3] - extent.centerY) * s).toFloat()
                // At least one pixel, so a dot of ink still shows.
                c.drawRect(x0, y0, max(x1, x0 + 1f), max(y1, y0 + 1f), itemPaint)
            }
            items = b
            itemsKey = key
            b
        }
        canvas.drawBitmap(bitmap, panel.left, panel.top, bitmapPaint)
        borderPaint.color = border
        borderPaint.strokeWidth = density
        canvas.drawRoundRect(panel.left, panel.top, panel.right, panel.bottom, 6 * density, 6 * density, borderPaint)
        val vx0 = toPanelX(visible.left, extent, panel, density); val vy0 = toPanelY(visible.top, extent, panel, density)
        val vx1 = toPanelX(visible.right, extent, panel, density); val vy1 = toPanelY(visible.bottom, extent, panel, density)
        viewPaint.color = accent
        viewPaint.strokeWidth = 1.5f * density
        canvas.drawRect(vx0, vy0, vx1, vy1, viewPaint)
    }

    companion object {
        const val WIDTH = 168f
        const val HEIGHT = 120f
        const val MARGIN = 14f
        const val PAD = 8f
    }
}
