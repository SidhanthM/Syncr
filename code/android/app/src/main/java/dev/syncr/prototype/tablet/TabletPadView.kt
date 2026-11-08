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
import dev.syncr.core.CoordinateSpace
import dev.syncr.prototype.link.CanvasRect
import dev.syncr.prototype.link.FrameMapping
import dev.syncr.prototype.link.StreamMapping
import dev.syncr.prototype.link.StreamPen
import dev.syncr.prototype.link.StreamTarget
import dev.syncr.prototype.link.android.PadPicture

/**
 * The Tablet mode pad (design §8.7, the `tablet-screen` mock): pure black, one thin solid outline round the
 * active area, a short rose trail fading toward its tail, and a one-line caption in the area's bottom-right
 * corner. Nothing else is drawn; settings live in the tool bubble, which sits in the right margin.
 * Pen-only: the stylus drives the pointer; fingers never send pointer data. The only finger input on the pad
 * is a two-finger drag (Actual size: moves the window; Stream in Local mode: moves and, pinched, resizes the pen frame)
 * and a tap on the picture of what the pad covers.
 *
 * Stream in Local mode (rework-v1 §V): the active area is the pen frame, a rectangle of the Mac's page with the pad's
 * shape. The Mac owns it; fingers ask for a new one and the view shows the asked-for frame at once. The picture the Mac
 * sends of that region is drawn under the pen, large or in the corner, and the pen's own strokes are drawn over it at
 * once from the samples, so what is on the pad never waits for the Mac.
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
    /** A finger tap in progress on the caption or on the picture; 0 when there is none. */
    private var tap = 0
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

    // Stream, Local mode.
    private var localWanted = false
    private var target = StreamTarget()
    /** The frame this pad asked for with a gesture, shown until the Mac's answer has had time to arrive. */
    private var askedFrame: CanvasRect? = null
    private var askedUntil = 0L
    private var lastAskMs = 0L
    private var gesturing = false
    private var gestureStart: CanvasRect? = null
    private var gestureX0 = 0f
    private var gestureY0 = 0f
    private var gestureSpan0 = 0f
    /** The frame the stroke in progress began with: a stroke keeps its mapping whatever the Mac scrolls meanwhile. */
    private var strokeFrame: CanvasRect? = null
    private var picture: PadPicture? = null
    private var pictureLarge = true
    private val pictureBounds = RectF()
    private var inkColour = Color.WHITE
    private var inkWidth = 6f

    /** The pen's own strokes in canvas units, drawn over the picture until a picture that contains them arrives. */
    private class LocalStroke(val ordinal: Long) {
        var xy = FloatArray(256)
        var n = 0
        var endedAt = 0L
        fun add(x: Float, y: Float) {
            if (n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
            xy[n] = x; xy[n + 1] = y; n += 2
        }
    }
    private val localStrokes = ArrayDeque<LocalStroke>()
    private var liveStroke: LocalStroke? = null
    private var strokesSent = 0L
    private val strokePath = android.graphics.Path()

    /** Local: fingers asked for this frame (canvas units). */
    var onFrameRequest: (CanvasRect) -> Unit = {}
    /** The pad's usable shape (width over height) is known or changed. */
    var onPadShape: (Double) -> Unit = {}
    /** The picture was tapped: now large (true) or in the corner. */
    var onPictureLarge: (Boolean) -> Unit = {}

    private val frame: CanvasRect? get() {
        if (stream == null || !localWanted) return null
        val asked = askedFrame
        return if (asked != null && (gesturing || SystemClock.uptimeMillis() < askedUntil)) asked else target.frame
    }

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
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    /** Stream: write inside the pen frame (Local) or over the whole view. A stroke in progress is released first. */
    fun setLocal(wanted: Boolean) {
        if (wanted == localWanted) return
        releaseAll()
        localWanted = wanted
        askedFrame = null
        rebuildMapper()
    }

    /** The Mac's target, with its pen frame and visible view. */
    fun setTarget(next: StreamTarget) {
        val shape = target.frame?.let { it.w / it.h }
        target = next
        val now = next.frame?.let { it.w / it.h }
        // The area follows the frame's shape; never change the mapping under a stroke (it keeps its own frame anyway).
        if (shape != now && (shape == null || now == null || kotlin.math.abs(shape - now) > 0.001)) { if (penId == -1) rebuildMapper() }
        invalidate()
    }

    fun setPicture(next: PadPicture?) {
        picture = next
        if (next != null) while (localStrokes.isNotEmpty() && localStrokes.first().endedAt != 0L && localStrokes.first().ordinal <= next.saved) localStrokes.removeFirst()
        else localStrokes.clear()
        invalidate()
    }

    fun setPictureLarge(large: Boolean) { pictureLarge = large; invalidate() }

    /** The pen Stream writes with: its colour on the black pad and its width in canvas units. */
    fun setInk(colour: Int, widthCanvas: Float) { inkColour = colour; inkWidth = widthCanvas }

    /** A new connection: the Mac counts saved strokes from zero again. */
    fun linkReset() { strokesSent = 0; localStrokes.clear(); liveStroke = null; askedFrame = null; invalidate() }

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
        // In Local mode the shape is the pen frame's, which the Mac made the pad's own shape, so the area fills the pad.
        val local = if (stream != null && localWanted) target.frame else null
        val m = if (stream != null) TabletMapper(MappingMode.WHOLE_SCREEN, bounds, dpi,
            if (local != null) MacScreen(local.w, local.h) else MacScreen(StreamMapping.WIDTH, StreamMapping.HEIGHT))
        else TabletMapper(mode, bounds, dpi, screen, requestedOffsetX, requestedOffsetY)
        if (stream != null) onPadShape(bounds.width / bounds.height)
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
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !isPen(e, 0) && tabletCaptionTapAllowed(penId != -1, e.eventTime, lastPenUp)) {
            val hit = when {
                captionBounds.contains(e.x, e.y) -> TAP_CAPTION
                stream != null && picture != null && pictureBounds.contains(e.x, e.y) -> TAP_PICTURE
                else -> 0
            }
            if (hit != 0) { tap = hit; tapX = e.x; tapY = e.y; return true }
        }
        if (tap != 0) {
            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val which = tap
                    tap = 0
                    if (which == TAP_CAPTION && captionBounds.contains(e.x, e.y)) performClick()
                    if (which == TAP_PICTURE && pictureBounds.contains(e.x, e.y)) { pictureLarge = !pictureLarge; onPictureLarge(pictureLarge); invalidate() }
                    return true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tap = 0
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.x - tapX) > tapSlop || kotlin.math.abs(e.y - tapY) > tapSlop) tap = 0
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
                    endFrameGesture()
                } else updateDrag(e, excludeIndex = -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penId != -1) penMove(e)
                else if (dragging) dragMove(e)
                else if (gesturing) frameMove(e)
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
            val whole = if (canceled) { pen.cancel(); false } else pen.up()
            finishLocalStroke(whole)
            penId = -1
            return
        }
        if (!canceled) { lastX = x; lastY = y; emit(x, y, true) }
        emit(lastX, lastY, false)
        penId = -1
    }

    /** Releases the button (if held) and stops any drag. Called on cancel, pause, focus loss, bubble and panel open. */
    fun releaseAll() {
        tap = 0
        if (penId != -1) {
            lastPenUp = SystemClock.uptimeMillis()
            stream?.cancel() ?: emit(lastX, lastY, false)
            finishLocalStroke(whole = false)
            penId = -1
        }
        endDrag()
        endFrameGesture()
    }

    /** The stroke ended: whole, it is numbered as the Mac will count it; otherwise it leaves the pad at once. */
    private fun finishLocalStroke(whole: Boolean) {
        val s = liveStroke ?: return
        liveStroke = null
        strokeFrame = null
        if (whole) { strokesSent++; s.endedAt = SystemClock.uptimeMillis() } else localStrokes.remove(s)
        invalidate()
    }

    // ---- Stream ----

    private fun streamDown(pen: StreamPen, e: MotionEvent, i: Int) {
        val m = mapper ?: return
        val device = e.device
        val f = frame
        strokeFrame = f
        val x = streamX(m, f, e.getX(i)); val y = streamY(m, f, e.getY(i))
        val sent = pen.down(
            x, y, e.getPressure(i),
            e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i), e.eventTime,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
            space = if (f != null) CoordinateSpace.CANVAS else CoordinateSpace.VIEW_RELATIVE,
        )
        if (sent && f != null) liveStroke = LocalStroke(strokesSent + 1).also { it.add(x, y); localStrokes.addLast(it) }
        pen.flush()
    }

    /** Every historical sample, oldest first, then the current one: one batch, one frame. */
    private fun streamMove(pen: StreamPen, e: MotionEvent, i: Int) {
        val m = mapper ?: return
        val f = strokeFrame
        val local = liveStroke
        for (h in 0 until e.historySize) {
            lastX = e.getHistoricalX(i, h); lastY = e.getHistoricalY(i, h)
            addTrail(lastX, lastY, starts = false)
            val x = streamX(m, f, lastX); val y = streamY(m, f, lastY)
            local?.add(x, y)
            pen.move(x, y, e.getHistoricalPressure(i, h),
                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h), e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h),
                e.getHistoricalEventTime(h))
        }
        lastX = e.getX(i); lastY = e.getY(i)
        addTrail(lastX, lastY, starts = false)
        val x = streamX(m, f, lastX); val y = streamY(m, f, lastY)
        local?.add(x, y)
        pen.move(x, y, e.getPressure(i),
            e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i), e.eventTime)
        pen.flush()
    }

    // Pad pixel to what Stream sends: canvas units through the pen frame [f], or view-relative units without one. The
    // same maths as [FrameMapping.toCanvas] and [StreamMapping.toView], without their allocation per sample.
    private fun streamX(m: TabletMapper, f: CanvasRect?, x: Float): Float {
        val a = m.activeArea
        val u = ((x - a.left) / a.width).coerceIn(0.0, 1.0)
        return (if (f != null) f.x + u * f.w else u * StreamMapping.WIDTH).toFloat()
    }

    private fun streamY(m: TabletMapper, f: CanvasRect?, y: Float): Float {
        val a = m.activeArea
        val v = ((y - a.top) / a.height).coerceIn(0.0, 1.0)
        return (if (f != null) f.y + v * f.h else v * StreamMapping.HEIGHT).toFloat()
    }

    // ---- Local mode: two fingers move and resize the pen frame ----

    private fun fingerSpan(e: MotionEvent, excludeIndex: Int, cx: Float, cy: Float): Float {
        var n = 0; var sum = 0f
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || e.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            n++; sum += kotlin.math.hypot(e.getX(i) - cx, e.getY(i) - cy)
        }
        return if (n == 0) 0f else 2 * sum / n
    }

    private fun updateFrameGesture(e: MotionEvent, excludeIndex: Int, n: Int, cx: Float, cy: Float) {
        val start = frame
        if (start == null || !tabletFrameGestureAllowed(true, penId != -1, e.eventTime, lastPenUp, n)) { endFrameGesture(); return }
        // The set of fingers changed: carry on from the frame as it is now.
        gestureStart = start; gestureX0 = cx; gestureY0 = cy
        gestureSpan0 = fingerSpan(e, excludeIndex, cx, cy)
        askedFrame = start
        gesturing = true
    }

    private fun frameMove(e: MotionEvent) {
        val m = mapper ?: return
        val start = gestureStart ?: return
        val (n, cx, cy) = fingerCentroid(e, -1)
        if (n != 2) return
        val span = fingerSpan(e, -1, cx, cy)
        val ratio = if (gestureSpan0 > dp(32f) && span > dp(16f)) (span / gestureSpan0).toDouble() else 1.0
        val next = FrameMapping.gesture(m.activeArea, start, gestureX0.toDouble(), gestureY0.toDouble(), cx.toDouble(), cy.toDouble(), ratio, target.view)
        askedFrame = next
        if (e.eventTime - lastAskMs >= 33) { lastAskMs = e.eventTime; onFrameRequest(next) }
        invalidate()
    }

    private fun endFrameGesture() {
        if (!gesturing) return
        gesturing = false
        gestureStart = null
        // The last position always goes; the asked-for frame stays on the pad until the Mac's answer has had time to arrive.
        askedFrame?.let(onFrameRequest)
        askedUntil = SystemClock.uptimeMillis() + ASKED_HOLD_MS
        postInvalidateDelayed(ASKED_HOLD_MS + 20)
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
        updateFrameGesture(e, excludeIndex, n, cx, cy)
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
        endFrameGesture()
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
                        if (m != null) { val f = frame; pen.hover(streamX(m, f, e.x), streamY(m, f, e.y), e.eventTime) }
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
        if (stream != null) drawPicture(canvas, a)
        canvas.drawRoundRect(a.left.toFloat() + inset, a.top.toFloat() + inset, a.right.toFloat() - inset, a.bottom.toFloat() - inset,
            r, r, outlinePaint)
        if (stream != null) { drawLocalStrokes(canvas, a); drawMap(canvas, a) }
        drawTrail(canvas)
        drawCaption(canvas, a)
    }

    /**
     * The Mac's picture of what the pad covers. Large: under the pen, placed by the canvas region it shows, so a frame
     * that has moved since slides the old picture until the next arrives. Small: a thumbnail in the top-left corner.
     * A tap on the thumbnail, or on the corner mark of the large picture, switches between the two.
     */
    private fun drawPicture(canvas: Canvas, a: PadRect) {
        val p = picture ?: run { pictureBounds.setEmpty(); return }
        val edge = u(8f, 8f, 14f)
        if (pictureLarge) {
            val f = frame
            val dest = if (f != null) RectF(
                FrameMapping.toPadX(a, f, p.region.x), FrameMapping.toPadY(a, f, p.region.y),
                FrameMapping.toPadX(a, f, p.region.right), FrameMapping.toPadY(a, f, p.region.bottom))
            else RectF(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat())
            canvas.save()
            canvas.clipRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat())
            picturePaint.alpha = 215
            canvas.drawBitmap(p.bitmap, null, dest, picturePaint)
            canvas.restore()
            // The corner mark: a small frame within a frame, "make this small".
            val s = dp(18f)
            val x = a.left.toFloat() + edge; val y = a.top.toFloat() + edge
            mapPaint.style = Paint.Style.STROKE; mapPaint.strokeWidth = dp(1.2f); mapPaint.color = Color.argb(150, 255, 255, 255)
            canvas.drawRoundRect(x, y, x + s, y + s * 0.7f, dp(2f), dp(2f), mapPaint)
            canvas.drawRoundRect(x, y, x + s * 0.5f, y + s * 0.35f, dp(1f), dp(1f), mapPaint)
            pictureBounds.set(x - dp(16f), y - dp(16f), x + s + dp(20f), y + s + dp(20f))
        } else {
            val w = (a.width * 0.3).toFloat().coerceIn(dp(120f), dp(260f))
            val h = w * p.bitmap.height / p.bitmap.width.coerceAtLeast(1)
            val x = a.left.toFloat() + edge; val y = a.top.toFloat() + edge
            pictureBounds.set(x, y, x + w, y + h)
            picturePaint.alpha = 235
            canvas.drawBitmap(p.bitmap, null, pictureBounds, picturePaint)
            mapPaint.style = Paint.Style.STROKE; mapPaint.strokeWidth = dp(1f); mapPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawRect(pictureBounds, mapPaint)
        }
    }

    /** The pen's own strokes over the large picture, from its samples; gone once a picture shows them, or after a while. */
    private fun drawLocalStrokes(canvas: Canvas, a: PadRect) {
        val now = SystemClock.uptimeMillis()
        while (localStrokes.isNotEmpty() && localStrokes.first().endedAt != 0L && now - localStrokes.first().endedAt > LOCAL_STROKE_MS) localStrokes.removeFirst()
        val f = frame ?: return
        if (!pictureLarge || localStrokes.isEmpty()) return
        inkPaint.color = inkColour
        inkPaint.strokeWidth = (inkWidth * a.width / f.w).toFloat().coerceAtLeast(dp(1f))
        canvas.save()
        canvas.clipRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat())
        for (s in localStrokes) {
            if (s.n < 2) continue
            strokePath.rewind()
            strokePath.moveTo(FrameMapping.toPadX(a, f, s.xy[0].toDouble()), FrameMapping.toPadY(a, f, s.xy[1].toDouble()))
            var i = 2
            while (i < s.n) { strokePath.lineTo(FrameMapping.toPadX(a, f, s.xy[i].toDouble()), FrameMapping.toPadY(a, f, s.xy[i + 1].toDouble())); i += 2 }
            canvas.drawPath(strokePath, inkPaint)
        }
        canvas.restore()
        if (localStrokes.any { it.endedAt != 0L }) postInvalidateDelayed(500)
    }

    /** Local: where the pen frame sits in the Mac's window, bottom-left. The window is the outline, the frame the fill. */
    private fun drawMap(canvas: Canvas, a: PadRect) {
        val f = frame ?: return
        val view = target.view ?: return
        if (view.w <= 0 || view.h <= 0) return
        val edge = u(8f, 8f, 14f)
        val w = u(40f, 56f, 96f)
        val k = w / view.w.toFloat()
        val h = view.h.toFloat() * k
        val left = a.left.toFloat() + edge; val top = a.bottom.toFloat() - edge - h
        mapPaint.style = Paint.Style.FILL; mapPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(left, top, left + w, top + h, mapPaint)
        mapPaint.style = Paint.Style.STROKE; mapPaint.strokeWidth = dp(1f); mapPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(left, top, left + w, top + h, mapPaint)
        val fl = (left + (f.x - view.x).toFloat() * k).coerceIn(left, left + w); val ft = (top + (f.y - view.y).toFloat() * k).coerceIn(top, top + h)
        val fr = (left + (f.right - view.x).toFloat() * k).coerceIn(left, left + w); val fb = (top + (f.bottom - view.y).toFloat() * k).coerceIn(top, top + h)
        mapPaint.style = Paint.Style.FILL; mapPaint.color = Color.argb(90, 0xd3, 0x99, 0x99)
        canvas.drawRect(fl, ft, fr, fb, mapPaint)
        mapPaint.style = Paint.Style.STROKE; mapPaint.color = ACCENT
        canvas.drawRect(fl, ft, fr, fb, mapPaint)
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
        private const val TAP_CAPTION = 1
        private const val TAP_PICTURE = 2
        /** How long the asked-for frame stays on the pad after a gesture, for the Mac's answer to arrive. */
        private const val ASKED_HOLD_MS = 450L
        /** A stroke of the pen's own that no picture has confirmed is dropped after this long. */
        private const val LOCAL_STROKE_MS = 8_000L
        private val ACCENT = Color.rgb(0xd3, 0x99, 0x99) // --accent
        private val GREY = Color.rgb(0x8f, 0x8e, 0x88) // dark --faint
        private val AMBER = Color.rgb(0xe0, 0xb2, 0x5c) // dark --amber
    }
}
