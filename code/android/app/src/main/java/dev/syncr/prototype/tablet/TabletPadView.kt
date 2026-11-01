package dev.syncr.prototype.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.syncr.prototype.link.StreamMapping
import dev.syncr.prototype.link.StreamPen

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
    var onCaptionClick: (() -> Unit)? = null
    private val captionBounds = RectF()
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var captionTap = false
    private var tapX = 0f
    private var tapY = 0f
    private var link = LinkState.NOT_CONNECTED
    private var trailMs = TabletSettings.TRAIL_DEFAULT_MS
    var inputEnabled = true
        set(value) { if (!value) releaseAll(); field = value }

    private var mapper: TabletMapper? = null

    /** Stream link: pen strokes go to the Mac as view-relative preview and a stroke-commit; null is the Bluetooth Pointer. */
    private var stream: StreamPen? = null

    // Pen stroke.
    private var penId = -1
    private var lastPenUp = -1000L
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
    private val captionPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255) // white at 55%
        typeface = Typeface.DEFAULT
    }

    fun configure(mode: MappingMode, screen: MacScreen, offsetXMm: Double, offsetYMm: Double) {
        if (mode != this.mode) releaseAll()
        this.mode = mode; this.screen = screen
        requestedOffsetX = offsetXMm; requestedOffsetY = offsetYMm
        rebuildMapper()
    }

    /** Switches between Stream (non-null) and the Bluetooth Pointer (null). A stroke in progress is released first. */
    fun setStream(pen: StreamPen?) {
        if (pen === stream) return
        releaseAll()
        stream = pen
        rebuildMapper()
    }

    /** The caption's words and the status dot's state. [description] is the spoken version. */
    fun setCaption(text: String, state: LinkState, description: String) {
        caption = "$text  ⇄"; link = state
        contentDescription = "$description Tap the caption to switch Stream · Wi-Fi and Pointer · Bluetooth."
        isClickable = true
        invalidate()
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
        if (stream != null) releaseAll() // Never change the pad mapping in a Stream stroke.
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
        val top = if (stream != null) maxOf(edge, dp(40f)) else edge // Room for Stream's destination line.
        val bounds = PadRect(side.toDouble(), top.toDouble(), (width - 2 * side).toDouble(), (height - top - edge).toDouble())
        if (bounds.width <= 0 || bounds.height <= 0) return
        // Stream maps the pad onto the Mac's 4800 x 3000 view with the Whole-screen maths: one scale, letterboxed.
        val m = if (stream != null) TabletMapper(MappingMode.WHOLE_SCREEN, bounds, dpi, MacScreen(StreamMapping.WIDTH, StreamMapping.HEIGHT))
        else TabletMapper(mode, bounds, dpi, screen, requestedOffsetX, requestedOffsetY)
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
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !isPen(e, 0) &&
            tabletCaptionTapAllowed(penId != -1, e.eventTime, lastPenUp) && captionBounds.contains(e.x, e.y)) {
            captionTap = true; tapX = e.x; tapY = e.y
            return true
        }
        if (captionTap) {
            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> { captionTap = false; if (captionBounds.contains(e.x, e.y)) performClick(); return true }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> captionTap = false
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.x - tapX) > tapSlop || kotlin.math.abs(e.y - tapY) > tapSlop) captionTap = false
                    return true
                }
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (isPen(e, i)) {
                    if (penId == -1) {
                        // Stream only: deliver pen samples as they are measured, not once per display frame.
                        // Pointer keeps frame batching: Bluetooth HID cannot carry more reports than that.
                        if (stream != null) requestUnbufferedDispatch(e)
                        penDown(e.getPointerId(i), e.getX(i), e.getY(i), e, i)
                    } else if (stream != null) releaseAll() // A second pen cancels, never completes the first stroke.
                    dragging = false
                } else updateDrag(e, excludeIndex = -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penId != -1) penMove(e)
                else if (dragging) dragMove(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) == penId) { if (stream != null && !isCanceled(e)) penMove(e); penUp(e.getX(i), e.getY(i), canceled = isCanceled(e)) }
                else updateDrag(e, excludeIndex = i)
            }
            MotionEvent.ACTION_UP -> {
                if (e.getPointerId(0) == penId) { if (stream != null && !isCanceled(e)) penMove(e); penUp(e.x, e.y, canceled = isCanceled(e)) }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
            }
        }
        return true
    }

    private fun isCanceled(e: MotionEvent) = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0

    private fun penDown(id: Int, x: Float, y: Float, e: MotionEvent, index: Int) {
        penId = id; lastX = x; lastY = y
        hoverX = Float.NaN
        addTrail(x, y, starts = true)
        val pen = stream
        if (pen != null) { streamDown(pen, e, index); return }
        // Position without clicking first, so the host does not draw a line from the old cursor position.
        emit(x, y, false)
        emit(x, y, true)
    }

    private fun penMove(e: MotionEvent) {
        val i = e.findPointerIndex(penId)
        if (i < 0) { releaseAll(); return }
        val pen = stream
        if (pen != null) { streamMove(pen, e, i); return }
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
        lastPenUp = SystemClock.uptimeMillis()
        val pen = stream
        if (pen != null) {
            if (canceled) pen.cancel() else pen.up()
            penId = -1
            return
        }
        if (!canceled) { lastX = x; lastY = y; emit(x, y, true) }
        emit(lastX, lastY, false)
        penId = -1
    }

    /** Releases the button (if held) and stops any drag. Called on cancel, pause, focus loss, bubble and panel open. */
    fun releaseAll() {
        captionTap = false
        if (penId != -1) {
            lastPenUp = SystemClock.uptimeMillis()
            stream?.cancel() ?: emit(lastX, lastY, false)
            penId = -1
        }
        endDrag()
    }

    // ---- Stream ----

    private fun streamDown(pen: StreamPen, e: MotionEvent, i: Int) {
        val m = mapper ?: return
        val device = e.device
        pen.down(
            viewX(m, e.getX(i)), viewY(m, e.getY(i)), e.getPressure(i),
            e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i), e.eventTime,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
        )
        pen.flush()
    }

    /** Every historical sample, oldest first, then the current one: one batch, one frame. */
    private fun streamMove(pen: StreamPen, e: MotionEvent, i: Int) {
        val m = mapper ?: return
        for (h in 0 until e.historySize) {
            lastX = e.getHistoricalX(i, h); lastY = e.getHistoricalY(i, h)
            addTrail(lastX, lastY, starts = false)
            pen.move(viewX(m, lastX), viewY(m, lastY), e.getHistoricalPressure(i, h),
                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h), e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h),
                e.getHistoricalEventTime(h))
        }
        lastX = e.getX(i); lastY = e.getY(i)
        addTrail(lastX, lastY, starts = false)
        pen.move(viewX(m, lastX), viewY(m, lastY), e.getPressure(i),
            e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i), e.eventTime)
        pen.flush()
    }

    private fun viewX(m: TabletMapper, x: Float) = StreamMapping.toView(m.activeArea, x.toDouble(), 0.0).first
    private fun viewY(m: TabletMapper, y: Float) = StreamMapping.toView(m.activeArea, 0.0, y.toDouble()).second

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
        dragging = tabletWindowDragAllowed(stream != null, penId != -1, mode, n)
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
                    val pen = stream
                    val m = mapper
                    if (pen != null) {
                        if (m != null) pen.hover(viewX(m, e.x), viewY(m, e.y), e.eventTime)
                        invalidate()
                        return true
                    }
                    emit(e.x, e.y, false) // Hover moves the cursor without clicking.
                    invalidate()
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> { hoverX = Float.NaN; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onCaptionClick?.invoke()
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
        val visible = android.text.TextUtils.ellipsize(caption, captionPaint,
            (a.width.toFloat() - 2 * pad - dp(16f)).coerceAtLeast(1f), android.text.TextUtils.TruncateAt.MIDDLE).toString()
        val textLeft = a.right.toFloat() - pad - captionPaint.measureText(visible)
        canvas.drawText(visible, textLeft, baseline, captionPaint)
        captionBounds.set(textLeft - dp(16f), baseline - dp(36f), a.right.toFloat(), baseline + dp(12f))
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
        private val ACCENT = Color.rgb(0xd3, 0x99, 0x99) // --accent
        private val GREY = Color.rgb(0x8f, 0x8e, 0x88) // dark --faint
        private val AMBER = Color.rgb(0xe0, 0xb2, 0x5c) // dark --amber
    }
}
