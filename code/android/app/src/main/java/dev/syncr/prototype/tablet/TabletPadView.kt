package dev.syncr.prototype.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The Tablet mode pad (design §8.7, the `tablet-screen` mock): pure black, one thin solid outline round the
 * active area, a short rose trail fading toward its tail, and a one-line caption in the area's bottom-right
 * corner. Nothing else is drawn; settings live in the tool bubble, which sits in the right margin.
 * Pen-only: the stylus drives the pointer; fingers never send pointer data. The only finger input on the pad
 * is a two-finger drag (Actual size: moves the window).
 *
 * [send] receives HID coordinates (0..32767) and the primary-button state.
 */
@SuppressLint("ViewConstructor")
class TabletPadView(
    context: Context,
    private val send: (x: Int, y: Int, pressed: Boolean) -> Unit,
    private val onWindowMoved: (offsetXMm: Double, offsetYMm: Double) -> Unit,
    private val onMapperChanged: (TabletMapper) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Configuration, set by the activity.
    private var mode = MappingMode.ACTUAL_SIZE
    private var screen = MacScreen.DEFAULT
    private var dpi = PhoneDpi(resources.displayMetrics.xdpi.toDouble(), resources.displayMetrics.ydpi.toDouble())
    private var requestedOffsetX = 0.0
    private var requestedOffsetY = 0.0
    private var caption = ""
    private var link = LinkState.NOT_CONNECTED
    private var trailMs = TabletSettings.TRAIL_DEFAULT_MS
    var inputEnabled = true
        set(value) { if (!value) releaseAll(); field = value }

    private var mapper: TabletMapper? = null

    // Pen stroke.
    private var penId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var hoverX = Float.NaN
    private var hoverY = Float.NaN

    // Two-finger window drag.
    private var dragging = false
    private var dragCx = 0f
    private var dragCy = 0f

    // Fading trail: x, y, time (uptime ms), stroke-start flag.
    private val trail = ArrayDeque<TrailPoint>()
    private class TrailPoint(val x: Float, val y: Float, val t: Long, val starts: Boolean)

    // Sizes follow the mock's 528 × 238 screen, one unit = height / 238, kept within comfortable limits.
    private var unit = dp(1.7f)
    private fun u(v: Float, min: Float, max: Float) = (v * unit).coerceIn(dp(min), dp(max))

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(41, 255, 255, 255) // white at 16%
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = ACCENT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255) // white at 55%
        typeface = Typeface.DEFAULT
    }

    fun configure(mode: MappingMode, screen: MacScreen, offsetXMm: Double, offsetYMm: Double) {
        if (mode != this.mode) releaseAll()
        this.mode = mode; this.screen = screen
        requestedOffsetX = offsetXMm; requestedOffsetY = offsetYMm
        rebuildMapper()
    }

    /** The caption's words and the status dot's state. [description] is the spoken version. */
    fun setCaption(text: String, state: LinkState, description: String) {
        caption = text; link = state; contentDescription = description; invalidate()
    }

    fun setTrailMs(ms: Long) { trailMs = ms }

    /** Actual size: puts the window in the middle of the Mac screen. */
    fun recentre() {
        val m = mapper ?: return
        if (m.mode != MappingMode.ACTUAL_SIZE) return
        val moved = m.withOffset(m.maxOffsetXMm / 2, m.maxOffsetYMm / 2)
        requestedOffsetX = moved.offsetXMm; requestedOffsetY = moved.offsetYMm
        mapper = moved
        persistWindow()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val metrics = resources.displayMetrics
        dpi = PhoneDpi(metrics.xdpi.toDouble(), metrics.ydpi.toDouble())
        rebuildMapper()
    }

    private fun rebuildMapper() {
        if (width == 0 || height == 0) return
        unit = height / 238f
        // Mock: 12 of 238 above and below; at least the bubble's zone on each side so the area stays centred
        // and the bubble docks in the right margin, outside it.
        val edge = 12f * unit
        val side = maxOf(edge, dp(BUBBLE_ZONE_DP))
        val bounds = PadRect(side.toDouble(), edge.toDouble(), (width - 2 * side).toDouble(), (height - 2 * edge).toDouble())
        if (bounds.width <= 0 || bounds.height <= 0) return
        val m = TabletMapper(mode, bounds, dpi, screen, requestedOffsetX, requestedOffsetY)
        mapper = m
        onMapperChanged(m)
        invalidate()
    }

    // ---- input ----

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun emit(x: Float, y: Float, pressed: Boolean) {
        val m = mapper ?: return
        val p = m.toHid(x.toDouble(), y.toDouble())
        send(p.x, p.y, pressed)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (isPen(e, i)) {
                    if (penId == -1) penDown(e.getPointerId(i), e.getX(i), e.getY(i))
                    dragging = false
                } else updateDrag(e, excludeIndex = -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penId != -1) penMove(e)
                else if (dragging) dragMove(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) == penId) penUp(e.getX(i), e.getY(i), canceled = isCanceled(e))
                else updateDrag(e, excludeIndex = i)
            }
            MotionEvent.ACTION_UP -> {
                if (e.getPointerId(0) == penId) penUp(e.x, e.y, canceled = isCanceled(e))
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
            }
        }
        return true
    }

    private fun isCanceled(e: MotionEvent) = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0

    private fun penDown(id: Int, x: Float, y: Float) {
        penId = id; lastX = x; lastY = y
        hoverX = Float.NaN
        addTrail(x, y, starts = true)
        // Position without clicking first, so the host does not draw a line from the old cursor position.
        emit(x, y, false)
        emit(x, y, true)
    }

    private fun penMove(e: MotionEvent) {
        val i = e.findPointerIndex(penId)
        if (i < 0) return
        // Every historical sample, oldest first, then the current one.
        for (h in 0 until e.historySize) {
            lastX = e.getHistoricalX(i, h); lastY = e.getHistoricalY(i, h)
            addTrail(lastX, lastY, starts = false)
            emit(lastX, lastY, true)
        }
        lastX = e.getX(i); lastY = e.getY(i)
        addTrail(lastX, lastY, starts = false)
        emit(lastX, lastY, true)
    }

    /** Pen lifted: the final point with the button held, then the release. A canceled pointer skips the final point. */
    private fun penUp(x: Float, y: Float, canceled: Boolean) {
        if (!canceled) { lastX = x; lastY = y; emit(x, y, true) }
        emit(lastX, lastY, false)
        penId = -1
    }

    /** Releases the button (if held) and stops any drag. Called on cancel, pause, focus loss, bubble and panel open. */
    fun releaseAll() {
        if (penId != -1) {
            emit(lastX, lastY, false)
            penId = -1
        }
        endDrag()
    }

    private fun fingerCentroid(e: MotionEvent, excludeIndex: Int): Triple<Int, Float, Float> {
        var n = 0; var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || e.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            n++; sx += e.getX(i); sy += e.getY(i)
        }
        return if (n == 0) Triple(0, 0f, 0f) else Triple(n, sx / n, sy / n)
    }

    /** Starts, re-anchors or ends a window drag whenever the set of fingers changes. */
    private fun updateDrag(e: MotionEvent, excludeIndex: Int) {
        val (n, cx, cy) = fingerCentroid(e, excludeIndex)
        val wasDragging = dragging
        dragging = n == 2 && penId == -1 && mode == MappingMode.ACTUAL_SIZE
        if (dragging) { dragCx = cx; dragCy = cy }
        else if (wasDragging) persistWindow()
    }

    private fun dragMove(e: MotionEvent) {
        val m = mapper ?: return
        val (n, cx, cy) = fingerCentroid(e, -1)
        if (n != 2) return
        val moved = m.movedBy((cx - dragCx).toDouble(), (cy - dragCy).toDouble())
        dragCx = cx; dragCy = cy
        requestedOffsetX = moved.offsetXMm; requestedOffsetY = moved.offsetYMm
        mapper = moved
        invalidate()
    }

    private fun endDrag() {
        if (dragging) { dragging = false; persistWindow() }
    }

    private fun persistWindow() {
        val m = mapper ?: return
        onWindowMoved(m.offsetXMm, m.offsetYMm)
        onMapperChanged(m)
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!inputEnabled || !isPen(e, 0)) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (penId == -1) {
                    hoverX = e.x; hoverY = e.y
                    emit(e.x, e.y, false) // Hover moves the cursor without clicking.
                    invalidate()
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> { hoverX = Float.NaN; invalidate() }
        }
        return true
    }

    // ---- drawing ----

    private fun addTrail(x: Float, y: Float, starts: Boolean) {
        trail.addLast(TrailPoint(x, y, SystemClock.uptimeMillis(), starts))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val m = mapper ?: return
        val a = m.activeArea
        val r = u(10f, 8f, 16f)
        val inset = outlinePaint.strokeWidth / 2
        canvas.drawRoundRect(a.left.toFloat() + inset, a.top.toFloat() + inset, a.right.toFloat() - inset, a.bottom.toFloat() - inset,
            r, r, outlinePaint)
        drawTrail(canvas)
        drawCaption(canvas, a)
    }

    /** The trail fades from transparent at its tail to the accent at the tip (`url(#trail-fade)`), then expires. */
    private fun drawTrail(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        while (trail.isNotEmpty() && now - trail.first().t > trailMs) trail.removeFirst()
        trailPaint.strokeWidth = u(3f, 2f, 4f)
        val n = trail.size
        var prev: TrailPoint? = null
        trail.forEachIndexed { i, p ->
            val q = prev
            if (q != null && !p.starts) {
                val along = i.toFloat() / (n - 1).coerceAtLeast(1)
                val age = 1f - (now - p.t).toFloat() / trailMs
                trailPaint.alpha = (255 * (along * age.coerceIn(0f, 1f))).toInt()
                canvas.drawLine(q.x, q.y, p.x, p.y, trailPaint)
            }
            prev = p
        }
        dotPaint.alpha = 255
        if (penId != -1) canvas.drawCircle(lastX, lastY, u(4f, 3f, 6f), dotPaint)
        else if (!hoverX.isNaN()) { dotPaint.alpha = 150; canvas.drawCircle(hoverX, hoverY, u(4f, 3f, 6f), dotPaint); dotPaint.alpha = 255 }
        if (trail.isNotEmpty()) postInvalidateOnAnimation()
    }

    /**
     * One line inside the area's bottom-right corner: a status dot, then e.g. "Mac · Pointer · Bluetooth".
     * The dot is readable without colour: filled = connected, hollow = not connected, filled amber with the
     * problem named in the words = needs attention.
     */
    private fun drawCaption(canvas: Canvas, a: PadRect) {
        captionPaint.textSize = u(8.5f, 10f, 13f)
        val pad = u(8f, 8f, 14f)
        val baseline = a.bottom.toFloat() - pad
        val textLeft = a.right.toFloat() - pad - captionPaint.measureText(caption)
        canvas.drawText(caption, textLeft, baseline, captionPaint)
        val dot = u(3f, 2.5f, 4f)
        val cx = textLeft - dot - u(5f, 5f, 8f)
        val cy = baseline - captionPaint.textSize * 0.34f
        when (link) {
            LinkState.CONNECTED -> { statusPaint.style = Paint.Style.FILL; statusPaint.color = ACCENT }
            LinkState.NOT_CONNECTED -> { statusPaint.style = Paint.Style.STROKE; statusPaint.strokeWidth = dp(1.2f); statusPaint.color = GREY }
            LinkState.ATTENTION -> { statusPaint.style = Paint.Style.FILL; statusPaint.color = AMBER }
        }
        canvas.drawCircle(cx, cy, dot, statusPaint)
    }

    companion object {
        /** Room kept free on each side of the active area; the tool bubble docks in the right one. */
        const val BUBBLE_ZONE_DP = 88f
        private val ACCENT = Color.rgb(0xd3, 0x99, 0x99)
        private val GREY = Color.rgb(0x75, 0x71, 0x7a)
        private val AMBER = Color.rgb(0xe3, 0xa6, 0x3c)
    }
}
