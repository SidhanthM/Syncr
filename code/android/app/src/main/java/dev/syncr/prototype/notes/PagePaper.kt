package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.syncr.core.PagePattern
import dev.syncr.core.ResolvedPageStyle
import dev.syncr.core.Rgba
import dev.syncr.prototype.ui.theme.PaperPalette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** An [Rgba] as an ARGB int. */
internal fun Rgba.argb(): Int = (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()

/** Draws one page's paper, its pattern and its hairline edge, in screen space. Reuses its paints and rect: nothing allocates per frame. */
internal class PagePaper {
    private val fill = Paint()
    private val edge = Paint().apply { style = Paint.Style.STROKE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screen = RectF()
    private var lines = FloatArray(256)

    /**
     * [page] is the page in canvas units under [m]; [style] the resolved style (null for a plain sheet). A page whose
     * bottom is infinite runs to the bottom of the view. [density] widens the hairline.
     */
    fun draw(canvas: Canvas, m: SheetMapping, page: CanvasRect, style: ResolvedPageStyle?, dark: Boolean, viewWidth: Int, viewHeight: Int, density: Float,
        edges: Boolean = true) {
        screen.set(m.tx + page.left * m.scale, m.ty + page.top * m.scale, m.tx + page.right * m.scale,
            if (page.bottom.isInfinite()) viewHeight.toFloat() else m.ty + page.bottom * m.scale)
        fill.color = style?.paper?.argb() ?: PaperPalette.paper(dark)
        canvas.drawRect(screen, fill)
        if (style != null && style.pattern != PagePattern.NONE) pattern(canvas, m, page, style, viewWidth, viewHeight)
        edge.color = PaperPalette.hairline(dark)
        edge.strokeWidth = 1f * density
        if (edges) canvas.drawRect(screen, edge)
        else {
            canvas.drawLine(screen.left, screen.top, screen.left, screen.bottom, edge)
            canvas.drawLine(screen.right, screen.top, screen.right, screen.bottom, edge)
        }
    }

    private fun pattern(canvas: Canvas, m: SheetMapping, page: CanvasRect, style: ResolvedPageStyle, viewWidth: Int, viewHeight: Int) {
        val period = style.spacing * m.scale
        if (period < MIN_PERIOD_PX) return // too dense to be anything but noise
        ink.color = style.patternColour.argb()
        val l = max(screen.left, 0f); val t = max(screen.top, 0f)
        val r = minOf(screen.right, viewWidth.toFloat()); val b = minOf(screen.bottom, viewHeight.toFloat())
        if (r <= l || b <= t) return
        canvas.save()
        canvas.clipRect(l, t, r, b)
        // Ruling 1.5 xnotes px and dots 2.0 px radius, in units, never thinner than a device pixel.
        val width = max(1f, RULING_UNITS * m.scale)
        // First line index visible below the page top (the first ruling sits one spacing down).
        val firstRow = max(1, ceil((t - screen.top) / period).toInt())
        val lastRow = floor((b - screen.top) / period).toInt()
        val firstCol = max(1, ceil((l - screen.left) / period).toInt())
        val lastCol = floor((r - screen.left) / period).toInt()
        when (style.pattern) {
            PagePattern.LINES, PagePattern.GRID -> {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = width
                val rows = max(0, lastRow - firstRow + 1)
                val cols = if (style.pattern == PagePattern.GRID) max(0, lastCol - firstCol + 1) else 0
                ensure((rows + cols) * 4)
                var n = 0
                for (i in 0 until rows) {
                    val y = screen.top + (firstRow + i) * period
                    lines[n++] = l; lines[n++] = y; lines[n++] = r; lines[n++] = y
                }
                for (i in 0 until cols) {
                    val x = screen.left + (firstCol + i) * period
                    lines[n++] = x; lines[n++] = t; lines[n++] = x; lines[n++] = b
                }
                if (n > 0) canvas.drawLines(lines, 0, n, ink)
            }
            PagePattern.DOTS -> {
                if (period < MIN_DOT_PERIOD_PX) {
                    canvas.restore()
                    return
                }
                ink.style = Paint.Style.STROKE
                ink.strokeCap = Paint.Cap.ROUND
                ink.strokeWidth = max(1.5f, 2 * DOT_UNITS * m.scale)
                var n = 0
                ensure(CHUNK)
                for (row in firstRow..lastRow) {
                    val y = screen.top + row * period
                    for (col in firstCol..lastCol) {
                        lines[n++] = screen.left + col * period; lines[n++] = y
                        if (n == CHUNK) { canvas.drawPoints(lines, 0, n, ink); n = 0 }
                    }
                }
                if (n > 0) canvas.drawPoints(lines, 0, n, ink)
                ink.strokeCap = Paint.Cap.BUTT
            }
            PagePattern.NONE -> Unit
        }
        canvas.restore()
    }

    private fun ensure(size: Int) { if (lines.size < size) lines = FloatArray(size + size / 2) }

    private companion object {
        const val MIN_PERIOD_PX = 4f
        const val MIN_DOT_PERIOD_PX = 8f
        const val CHUNK = 8192
        const val RULING_UNITS = 1.5f * Xn.UNITS
        const val DOT_UNITS = 2.0f * Xn.UNITS
    }
}
