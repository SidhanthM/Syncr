package dev.syncr.prototype.tablet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.syncr.prototype.link.PointerInput

/** Local contact feedback never waits for Wi-Fi and is never saved as note ink. */
class TrackpadView(context: Context) : View(context) {
    var onInput: (PointerInput) -> Unit = {}
    var onArea: (PadRect) -> Unit = {}
    var enabledForInput = false
        set(value) { if (!value) releaseAll(); field = value }
    var trailMs = TabletSettings.TRAIL_DEFAULT_MS
    private val response = TrackpadResponse()
    var tuning: TrackpadTuning
        get() = response.tuning
        set(value) { response.tuning = value }
    private var inputTime = 0L
    private val density = resources.displayMetrics.density
    private val gesture = TrackpadGesture(ViewConfiguration.get(context).scaledTouchSlop / density,
        ViewConfiguration.getDoubleTapTimeout().toLong()) { onInput(response.apply(it, inputTime)) }
    private data class Point(val x: Float, val y: Float, val t: Long)
    private class Trail(val id: Int) { val points = ArrayDeque<Point>(); var down = true }
    private val trails = ArrayDeque<Trail>()
    private val active = mutableMapOf<Int, Trail>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2.5f * density }
    private val heartbeat = object : Runnable {
        override fun run() { if (active.isNotEmpty() && enabledForInput) { gesture.heartbeat(); postDelayed(this, 150) } }
    }

    init {
        contentDescription = "Mac trackpad. One finger moves; tap clicks; two fingers scroll or tap to right-click. Double tap and hold to drag."
        isFocusable = true
    }

    private fun contacts(e: MotionEvent, except: Int = -1) = (0 until e.pointerCount).filter { it != except }.map {
        PadContact(e.getPointerId(it), e.getX(it) / density, e.getY(it) / density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForInput) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        val now = event.eventTime
        inputTime = now
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                response.reset(now)
                val id = event.getPointerId(event.actionIndex)
                val trail = Trail(id)
                trails += trail; active[id] = trail
                while (trails.size > 24) trails.removeFirst()
                record(event, now)
                gesture.down(contacts(event), now)
                removeCallbacks(heartbeat); postDelayed(heartbeat, 150)
            }
            MotionEvent.ACTION_MOVE -> {
                record(event, now)
                gesture.move(contacts(event))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                record(event, now)
                gesture.move(contacts(event))
                gesture.up(contacts(event, event.actionIndex), now)
                response.reset(now)
                active.remove(event.getPointerId(event.actionIndex))?.down = false
                if (active.isEmpty()) removeCallbacks(heartbeat)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun record(e: MotionEvent, time: Long) {
        for (i in 0 until e.pointerCount) {
            val trail = active[e.getPointerId(i)] ?: continue
            trail.points += Point(e.getX(i), e.getY(i), time)
            while (trail.points.size > 128) trail.points.removeFirst()
        }
    }

    fun releaseAll() {
        inputTime = SystemClock.uptimeMillis()
        gesture.cancel(); response.reset(); removeCallbacks(heartbeat)
        trails.clear(); active.clear(); invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        releaseAll()
        onArea(PadRect(0.0, 0.0, w.toDouble(), h.toDouble()))
    }
    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val now = SystemClock.uptimeMillis()
        val iterator = trails.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next()
            while (trail.points.size > 1 && now - trail.points.first().t > trailMs) trail.points.removeFirst()
            if (!trail.down && (trail.points.lastOrNull()?.let { now - it.t > trailMs } != false)) { iterator.remove(); continue }
            val colour = CONTACT_COLOURS[Math.floorMod(trail.id, CONTACT_COLOURS.size)]
            var before: Point? = null
            for (point in trail.points) {
                val alpha = ((1f - (now - point.t).toFloat() / trailMs).coerceIn(0f, 1f) * 200).toInt()
                paint.color = colour; paint.alpha = alpha
                before?.let { canvas.drawLine(it.x, it.y, point.x, point.y, paint) }
                before = point
            }
            trail.points.lastOrNull()?.let {
                paint.color = colour
                paint.alpha = if (trail.down) 230 else ((1f - (now - it.t).toFloat() / trailMs).coerceIn(0f, 1f) * 230).toInt()
                canvas.drawCircle(it.x, it.y, 7f * density, paint)
            }
        }
        if (trails.isNotEmpty()) postInvalidateOnAnimation()
    }

    private companion object {
        val CONTACT_COLOURS = intArrayOf(0xffd39999.toInt(), 0xffa8bfd0.toInt(), 0xffb8c7a3.toInt(), 0xffcfb18c.toInt(), 0xffb8a7c7.toInt())
    }
}
