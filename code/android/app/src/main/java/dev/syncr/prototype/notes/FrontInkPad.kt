// Adapted from xnotes (MIT, Copyright (c) 2026 Shardul Vikram Singh), app/src/main/java/com/xnotes/gl/GlWetPad.kt
package dev.syncr.prototype.notes

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.brush.Brush as InkBrush

/**
 * The second wet-ink path: a [SurfaceView] stacked over the canvas, switched to `EGL_SINGLE_BUFFER`
 * (`EGL_KHR_mutable_render_buffer`) so GL commands land in the buffer the panel is scanning out and a
 * `glFlush` is the whole publish step: no buffer queue, no fence to SurfaceFlinger. It exists only behind
 * [InkPreferences.frontBufferInk]; [WetInkBridge] stays the default and the fallback.
 *
 * Threads: the main thread posts samples and reads nothing back except through [Handler] posts; the render
 * thread owns every EGL/GL/Ink object ([FrontInkRenderer]) and never reads the model.
 *
 * Handover (contract F2): see [FrontInkHandover]. At pen-up the stroke stays on the pad and the dry path
 * draws it underneath; once the dry layer is allowed to draw it, the pad hides at the vsync after the canvas
 * frame is committed and is wiped a few refreshes later. Prediction is off here (v0).
 */
internal class FrontInkPad(
    context: Context,
    /** Hands the finished stroke to dry ink as the bridge does, with the theme it was drawn under. */
    private val onFinished: (InkPreview, Boolean, Stroke) -> Unit,
    private val invalidate: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, WetInk {

    private class Live(val pointerId: Int, val hasTilt: Boolean, val hasOrientation: Boolean, val dark: Boolean)
    private class Waiting(val preview: InkPreview, val dark: Boolean, var stroke: Stroke? = null)

    private val handover = FrontInkHandover()
    private val mainHandler = Handler(context.mainLooper)
    private val renderer = FrontInkRenderer()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var layer: SurfaceControl? = null

    @Volatile
    private var target: Surface? = null

    /** The display rotation [layer] was opened under; a pad left through a rotation is not turned back. */
    private var turn = -1

    private var active: Live? = null
    private val waiting = HashMap<String, Waiting>()
    private var padView: PadView? = null

    @Volatile
    private var refreshMs = DEFAULT_REFRESH_MS

    /** Surface and context exist and the buffer is single: a stroke may start. */
    @Volatile
    private var ready = false

    @Volatile
    private var surfaceW = 0

    @Volatile
    private var surfaceH = 0

    // ---- render thread only ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var single = false
    private var presentPosted = false

    /** Whether the setting is on. Off is no pad at all: the view goes away, and with it the surface and its thread. */
    var settingOn = false
        set(on) {
            if (field == on) return
            field = on
            visibility = if (on && available != false) VISIBLE else GONE
        }

    init {
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        visibility = GONE
    }

    // ---- WetInk ----

    override val view: android.view.View get() = this
    override val isAttached: Boolean get() = ready
    override val isDrawing: Boolean get() = active != null
    override fun isFinishing(previewId: String): Boolean = handover.isFinishing(previewId)
    override fun eagerInit() = Unit

    /** Whether a stroke on [mapping] may take the pad now (the pad is up, the same size, not rotated, not letting go). */
    fun canStart(mapping: SheetMapping, width: Int, height: Int): Boolean {
        if (!settingOn || !ready || available == false || rotation != 0f) return false
        if (width != surfaceW || height != surfaceH || turned()) return false
        return handover.canStart(PadView(mapping, width, height))
    }

    /** Waiting strokes are handed to dry ink now if the view is no longer the one they were drawn through. */
    fun viewMayHaveChanged(mapping: SheetMapping, width: Int, height: Int) {
        if (handover.viewChanged(PadView(mapping, width, height))) release()
    }

    /** A finger touch (pan, zoom, selection) is about to move the canvas: whatever waits goes to dry ink first. */
    fun forceHandover() {
        if (handover.force()) release()
    }

    override fun start(event: MotionEvent, pointerId: Int, frozen: SheetMapping, width: Int, height: Int, brush: InkBrush, dark: Boolean, prefs: InkPreferences) {
        val pv = PadView(frozen, width, height)
        val join = handover.penDown(pv)
        if (join == PadPen.BUSY) return
        padView = pv
        val device = event.device
        active = Live(
            pointerId,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
            dark = dark,
        )
        val hz = display?.refreshRate ?: 0f
        refreshMs = if (hz > 1f) (1000f / hz).toLong().coerceIn(4L, 40L) else DEFAULT_REFRESH_MS
        val clip = canvasClip(frozen, width, height)
        val fresh = join == PadPen.FRESH
        post {
            renderer.beginStroke(brush, frozen, clip, SAMPLES, join = !fresh)
            if (fresh) {
                // Shown before there is ink: the compositor latches this at the next refresh.
                setAutoRefresh(true)
                setLayerVisible(true)
            }
        }
        offer(batchOf(event, pointerId, withCurrent = true))
    }

    override fun add(event: MotionEvent) {
        val live = active ?: return
        offer(batchOf(event, live.pointerId, withCurrent = true))
    }

    override fun finish(event: MotionEvent, preview: InkPreview?) {
        val live = active ?: return
        active = null
        if (preview == null || !ready) {
            handover.penCancelled()
            post { cancelOnRender() }
            if (preview != null) invalidate() // the pad is gone: dry ink builds the stroke itself
            return
        }
        handover.penUp(preview.id)
        waiting[preview.id] = Waiting(preview, live.dark)
        val batch = batchOf(event, live.pointerId, withCurrent = true)
        val id = preview.id
        post {
            collect(batch)
            renderer.finishInput()
            presentNow()
            val stroke = renderer.settleStroke()
            mainHandler.post { strokeReady(id, stroke) }
        }
    }

    override fun cancel() {
        if (active == null) return
        active = null
        handover.penCancelled()
        post { cancelOnRender() }
    }

    /** A different note, or leaving the window: nothing on the pad is wanted any more. */
    override fun clear() {
        cancel()
        waiting.clear()
        handover.abandon()
        post {
            renderer.forgetHeld()
            if (ready) {
                renderer.clearSurface()
                GLES30.glFlush()
                setAutoRefresh(false)
            }
            setLayerVisible(false)
        }
    }

    // ---- handover ----

    private fun strokeReady(id: String, stroke: Stroke?) {
        val w = waiting[id] ?: return
        w.stroke = stroke
        if (handover.strokeReady(id)) armIdle()
    }

    private fun armIdle() {
        val gen = handover.generation
        mainHandler.postDelayed({ if (handover.idleElapsed(gen)) release() }, IDLE_MS)
    }

    /** The waiting strokes go to the dry layer now; the pad is wiped once the canvas frame carrying them is committed. */
    private fun release() {
        val ids = handover.releasingIds()
        val gen = handover.generation
        for (id in ids) {
            val w = waiting.remove(id) ?: continue
            w.stroke?.let { onFinished(w.preview, w.dark, it) }
        }
        invalidate()
        afterFrameCommit {
            // One refresh after the canvas's frame: hide by transaction (latched like a buffer, so both layers
            // change in the same composite), then wipe the pixels once nothing is scanning them out.
            Choreographer.getInstance().postFrameCallback {
                if (gen == handover.generation) setLayerVisible(false)
            }
            mainHandler.postDelayed({
                if (gen != handover.generation) return@postDelayed
                post {
                    renderer.forgetHeld()
                    if (ready) {
                        renderer.clearSurface()
                        GLES30.glFlush()
                        setAutoRefresh(false)
                    }
                    mainHandler.post { handover.cleared(gen) }
                }
            }, refreshMs * WIPE_REFRESHES)
        }
    }

    /** Runs [block] on the main thread once the next frame has been committed (two animation hops below API 29). */
    private fun afterFrameCommit(block: () -> Unit) {
        var done = false
        val once = Runnable { if (!done) { done = true; block() } }
        if (Build.VERSION.SDK_INT >= 29 && isAttachedToWindow) {
            viewTreeObserver.registerFrameCommitCallback { mainHandler.post(once) }
        } else {
            postOnAnimation { postOnAnimation(once) }
        }
        mainHandler.postDelayed(once, COMMIT_TIMEOUT_MS)
    }

    // ---- main thread: samples ----

    private fun batchOf(event: MotionEvent, pointerId: Int, withCurrent: Boolean): SampleBatch {
        val index = event.findPointerIndex(pointerId).coerceAtLeast(0)
        val live = active
        val hasTilt = live?.hasTilt ?: false
        val hasOrientation = live?.hasOrientation ?: false
        val h = event.historySize
        val b = SampleBatch(h + if (withCurrent) 1 else 0)
        for (i in 0 until b.count) {
            val hist = if (i < h) i else -1
            val hs = hist >= 0
            b.x[i] = if (hs) event.getHistoricalX(index, hist) else event.getX(index)
            b.y[i] = if (hs) event.getHistoricalY(index, hist) else event.getY(index)
            b.pressure[i] = if (hs) event.getHistoricalPressure(index, hist) else event.getPressure(index)
            b.tilt[i] = if (!hasTilt) StrokeInput.NO_TILT
            else if (hs) event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, hist) else event.getAxisValue(MotionEvent.AXIS_TILT, index)
            b.orientation[i] = if (!hasOrientation) StrokeInput.NO_ORIENTATION
            else inkOrientation(if (hs) event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, hist) else event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index))
            b.eventMs[i] = if (hs) event.getHistoricalEventTime(hist) else event.eventTime
        }
        return b
    }

    private fun offer(batch: SampleBatch) {
        post {
            collect(batch)
            if (!presentPosted) {
                presentPosted = true
                handler?.post { presentNow() }
            }
        }
    }

    // ---- render thread: work ----

    private fun collect(b: SampleBatch) {
        renderer.addSamples(b)
    }

    /** One present: draw what changed and flush. */
    private fun presentNow() {
        presentPosted = false
        if (!ready) return
        try {
            if (renderer.present(surfaceW, surfaceH)) GLES30.glFlush()
        } catch (e: Throwable) {
            fail("present: $e")
        }
    }

    private fun cancelOnRender() {
        if (!ready) return
        if (renderer.cancelStroke(surfaceW, surfaceH)) GLES30.glFlush()
    }

    private fun post(work: () -> Unit) {
        handler?.post {
            try {
                work()
            } catch (e: Throwable) {
                fail("render: $e")
            }
        }
    }

    // ---- surface lifecycle ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (available == false) return
        val t = HandlerThread("syncr-front-ink").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        val frame = holder.surfaceFrame
        val surface = openLayer(parentLayer(), frame.width(), frame.height()) ?: holder.surface
        target = surface
        turn = display?.rotation ?: -1
        handler?.post { createEgl(surface) }
        setLayerVisible(false)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        handler?.post { if (ready && (width != surfaceW || height != surfaceH)) rebuild(width, height, null) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        surfaceLost()
        val t = thread ?: return closeLayer()
        handler?.post { destroyEgl() }
        thread = null
        handler = null
        t.quitSafely()
        t.join(500)
        closeLayer()
    }

    /** Every pixel on the pad is gone: whatever waited goes to dry ink at once, whatever was under the pen is dropped. */
    private fun surfaceLost() {
        val live = active
        active = null
        if (live != null) handover.penCancelled()
        handover.force()
        for ((_, w) in waiting) w.stroke?.let { onFinished(w.preview, w.dark, it) }
        waiting.clear()
        handover.abandon()
        invalidate()
    }

    private fun openLayer(parent: SurfaceControl?, w: Int, h: Int): Surface? {
        if (Build.VERSION.SDK_INT < 31 || parent == null) return null
        val child = SurfaceControl.Builder()
            .setName("syncr-front-ink")
            .setParent(parent)
            .setBufferSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
            .setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            .build()
        SurfaceControl.Transaction().use { it.setLayer(child, 1).apply() }
        layer = child
        return Surface(child)
    }

    private fun parentLayer(): SurfaceControl? =
        if (Build.VERSION.SDK_INT >= 31) surfaceControl?.takeIf { it.isValid } else null

    /** Whether the display has turned since [layer] was opened; asked at pen-down, in which case the layer is reopened. */
    private fun turned(): Boolean {
        val now = display?.rotation ?: return false
        if (now == turn) return false
        turn = now
        val parent = parentLayer()
        val w = width
        val h = height
        handler?.post { rebuild(w, h, parent) }
        return true
    }

    private fun closeLayer() {
        val surface = target
        target = null
        if (Build.VERSION.SDK_INT < 31) return
        val child = layer ?: return
        layer = null
        surface?.release()
        SurfaceControl.Transaction().use { it.reparent(child, null).apply() }
        child.release()
    }

    private fun setLayerVisible(visible: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val control = (layer ?: surfaceControl)?.takeIf { it.isValid } ?: return
        SurfaceControl.Transaction().use { t ->
            t.setVisibility(control, visible)
            t.apply()
        }
    }

    // ---- render thread: EGL ----

    private fun createEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return unavailable("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return unavailable("eglInitialize failed")
        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        val mutable = "EGL_KHR_mutable_render_buffer" in extensions
        val autoRefresh = "EGL_ANDROID_front_buffer_auto_refresh" in extensions
        Log.d(TAG, "egl extensions: $extensions")
        val config = chooseConfig()
        if (!mutable) return unavailable("EGL_KHR_mutable_render_buffer missing (autoRefresh=$autoRefresh, mutable config=${config != null})")
        if (config == null) return unavailable("no config with EGL_MUTABLE_RENDER_BUFFER_BIT_KHR")
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return unavailable("eglCreateContext ${EGL14.eglGetError()}")
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return unavailable("eglCreateWindowSurface ${EGL14.eglGetError()}")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return unavailable("eglMakeCurrent ${EGL14.eglGetError()}")
        if (!renderer.onContextCreated()) return unavailable("shader setup failed")
        if (!EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER)) {
            return unavailable("eglSurfaceAttrib single buffer ${EGL14.eglGetError()}")
        }
        // Before the transition swap, not after: set afterwards the compositor never looks at the layer again.
        if (autoRefresh) EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val mode = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL_RENDER_BUFFER, mode, 0)
        single = mode[0] == EGL_SINGLE_BUFFER
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        if (!single) return unavailable("asked EGL_SINGLE_BUFFER, eglQuerySurface(EGL_RENDER_BUFFER) says 0x%x".format(mode[0]))
        if (autoRefresh) EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, 0)
        surfaceW = w[0]
        surfaceH = h[0]
        available = true
        ready = true
        Log.i(TAG, "probe ok: front buffer on ${if (layer != null) "own layer" else "the SurfaceView"}, ${surfaceW}x$surfaceH, " +
            "mutable_render_buffer=$mutable auto_refresh=$autoRefresh renderBuffer=SINGLE ${GLES30.glGetString(GLES30.GL_RENDERER)}")
    }

    private fun chooseConfig(): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL_MUTABLE_RENDER_BUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val count = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(8)
        return if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, configs.size, count, 0) && count[0] > 0) configs[0] else null
    }

    private fun setAutoRefresh(on: Boolean) {
        if (eglSurface == EGL14.EGL_NO_SURFACE || !single) return
        EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, if (on) 1 else 0)
    }

    /** A single buffered surface never swaps, so a resize or turn builds it again. */
    private fun rebuild(w: Int, h: Int, parent: SurfaceControl?) {
        if (!ready) return
        var surface = target ?: return
        ready = false
        mainHandler.post { surfaceLost() }
        destroyEgl()
        val fresh = parent != null && parent.isValid && layer != null
        if (fresh) {
            closeLayer()
            surface = openLayer(parent, w, h) ?: return unavailable("no layer to reopen")
            target = surface
        } else if (Build.VERSION.SDK_INT >= 31) {
            layer?.takeIf { it.isValid }?.let { child -> SurfaceControl.Transaction().use { it.setBufferSize(child, w, h).apply() } }
        }
        createEgl(surface)
        setLayerVisible(false)
    }

    private fun destroyEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        surfaceW = 0
        surfaceH = 0
        single = false
        ready = false
    }

    /** The probe or a present failed: the pad is off for the process, one log line says why, and the view goes away. */
    private fun unavailable(why: String) {
        ready = false
        if (available != false) Log.w(TAG, "pad unavailable: $why")
        available = false
        mainHandler.post { visibility = GONE; surfaceLost() }
    }

    private fun fail(what: String) = unavailable(what)

    companion object {
        /** Log tag for the probe and failures. */
        const val TAG = "SyncrFrontInk"

        /** null until the first probe has run; false for the rest of the process after any failure. */
        @Volatile
        var available: Boolean? = null
            private set

        const val EGL_RENDER_BUFFER = 0x3086
        const val EGL_SINGLE_BUFFER = 0x3085
        const val EGL_FRONT_BUFFER_AUTO_REFRESH = 0x314C
        const val EGL_MUTABLE_RENDER_BUFFER_BIT = 0x1000
        const val EGL_OPENGL_ES3_BIT = 0x0040

        private const val SAMPLES = 4
        private const val DEFAULT_REFRESH_MS = 17L

        /** The pad waits this long after the last pen-up before it lets go of its strokes. */
        private const val IDLE_MS = 250L
        private const val WIPE_REFRESHES = 8L
        private const val COMMIT_TIMEOUT_MS = 300L

        /** Android orientation (0 = up, radians) to Ink's (0 = right, [0, 2 pi)), as dry ink converts it. */
        fun inkOrientation(androidRadians: Float): Float {
            val radians = androidRadians - (Math.PI / 2).toFloat()
            val turn = (2 * Math.PI).toFloat()
            val normalized = if (radians < 0) radians + turn else radians
            return if (normalized >= turn) 0f else normalized
        }

        /** The canvas area: left and right edges and the top clip ink; there is no bottom edge. */
        fun canvasClip(frozen: SheetMapping, width: Int, height: Int): PxRect {
            val right = frozen.tx + CanvasLayout.WIDTH * frozen.scale
            return PxRect(
                Math.round(frozen.tx).coerceIn(0, width), Math.round(frozen.ty).coerceIn(0, height),
                Math.round(right).coerceIn(0, width), height,
            )
        }
    }
}
