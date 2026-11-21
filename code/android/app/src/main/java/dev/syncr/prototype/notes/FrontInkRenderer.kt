// Adapted from xnotes (MIT, Copyright (c) 2026 Shardul Vikram Singh), app/src/main/java/com/xnotes/gl/GlWetPadInk.kt
package dev.syncr.prototype.notes

import android.opengl.GLES30
import android.util.Log
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import androidx.ink.brush.Brush as InkBrush

/** Pen samples in a batch, in view pixels and Ink's conventions, with the event times they came from. */
internal class SampleBatch(val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val pressure = FloatArray(count)
    val tilt = FloatArray(count)
    val orientation = FloatArray(count)
    val eventMs = LongArray(count)
}

/**
 * Every GL and Ink object of the pad, owned by the render thread (the pad's thread posts to it, nothing here
 * reads the model).
 *
 * The stroke under the pen is an [InProgressStroke] built with the same brush, events and canvas transform as
 * the bridge's, so it has the dry geometry. It is drawn from its outlines (the mesh buffers' layout is not
 * public in ink 1.0.0) by stencil-then-cover with non-zero winding, into a multisampled framebuffer the size
 * of the damage, then resolved with identical rectangles and copied into the front buffer. Each present
 * rebuilds only the pixels the new and the previous tail can reach: clear them, redraw every outline that
 * touches them. Nothing accumulates, so a retracting tail is not a special case.
 */
internal class FrontInkRenderer {
    /** One closed outline in view pixels, with its box. */
    private class Poly(var xy: FloatArray = FloatArray(64), var n: Int = 0) {
        var minX = 0f
        var minY = 0f
        var maxX = 0f
        var maxY = 0f

        fun copy() = Poly(xy.copyOf(n * 2), n).also { it.minX = minX; it.minY = minY; it.maxX = maxX; it.maxY = maxY }
        fun touches(r: PxRect) = maxX > r.left && minX < r.right && maxY > r.top && minY < r.bottom
    }

    private class Coat(val color: FloatArray = FloatArray(4)) {
        val polys = ArrayList<Poly>()
        var used = 0
    }

    // Held (finished, still on the pad) coats, drawn under the live stroke.
    private val held = ArrayList<Coat>()

    // The live stroke.
    private var stroke: InProgressStroke? = null
    private val live = ArrayList<Coat>()
    private var coatCount = 0
    private val batch = MutableStrokeInputBatch()
    private val noPredicted = MutableStrokeInputBatch()
    private val input = StrokeInput()
    private val vec = MutableVec()
    private val region = BoxAccumulator()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var halfWidthPx = 0f
    private var colorArgb = 0
    private var clip = PxRect(0, 0, 0, 0)
    private var firstMs = -1L
    private var lastMs = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastT = -1L
    private var lastTail: PxBox? = null
    private var liveBox: PxBox? = null
    private var needsPresent = false
    private var samples = 4

    private var program = 0
    private var uXf = -1
    private var uColor = -1
    private var msFbo = 0
    private var msColor = 0
    private var msStencil = 0
    private var resFbo = 0
    private var resColor = 0
    private var fboW = 0
    private var fboH = 0
    private var fboSamples = 0
    private var fan: FloatBuffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()

    val isLive: Boolean get() = stroke != null
    val hasHeld: Boolean get() = held.isNotEmpty()

    /** Why the last present drew nothing, for the log. */
    var why = ""
        private set

    /** A new context: every GL name is gone. */
    fun onContextCreated(): Boolean {
        program = 0; msFbo = 0; msColor = 0; msStencil = 0; resFbo = 0; resColor = 0; fboW = 0; fboH = 0
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX) ?: return false
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT) ?: return false
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            Log.w(TAG, "program link failed: ${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            return false
        }
        program = p
        uXf = GLES30.glGetUniformLocation(p, "uXf")
        uColor = GLES30.glGetUniformLocation(p, "uColor")
        return true
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return null
        }
        return s
    }

    /**
     * Pen-down. [mapping] is the one the stroke is recorded under (canvas units to view pixels); [clipRect] is
     * the canvas area, the only part of the surface ink may reach. A joining stroke keeps every held outline.
     */
    fun beginStroke(brush: InkBrush, mapping: SheetMapping, clipRect: PxRect, msaa: Int, join: Boolean) {
        if (!join) held.clear()
        val s = stroke ?: InProgressStroke().also { stroke = it }
        s.start(brush)
        scale = mapping.scale
        tx = mapping.tx
        ty = mapping.ty
        colorArgb = brush.colorIntArgb
        halfWidthPx = brush.size * scale / 2f
        clip = clipRect
        samples = msaa
        firstMs = -1L
        lastX = Float.NaN
        lastY = Float.NaN
        lastT = -1L
        lastTail = null
        liveBox = null
        coatCount = 0
        needsPresent = false
        region.reset()
    }

    /** Feeds a batch to the live stroke, in canvas units, oldest first. Repeated x/y/time triplets are skipped as dry ink skips them. */
    fun addSamples(b: SampleBatch) {
        val s = stroke ?: return
        for (i in 0 until b.count) {
            if (firstMs < 0) firstMs = b.eventMs[i]
            val t = (b.eventMs[i] - firstMs).coerceAtLeast(lastT.coerceAtLeast(0L))
            val cx = (b.x[i] - tx) / scale
            val cy = (b.y[i] - ty) / scale
            if (cx == lastX && cy == lastY && t == lastT) continue
            lastX = cx; lastY = cy; lastT = t
            input.update(
                cx, cy, t, InputToolType.STYLUS,
                pressure = b.pressure[i].coerceIn(0f, 1f), tiltRadians = b.tilt[i], orientationRadians = b.orientation[i],
            )
            batch.clear()
            batch.add(input)
            try {
                s.enqueueInputs(batch, noPredicted)
                lastMs = t
                needsPresent = true
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "sample refused: ${e.message}")
            }
        }
    }

    /** Pen-up: closes the input and shapes it once more. [present] then draws the final tail. */
    fun finishInput() {
        val s = stroke ?: return
        if (!s.isInputFinished()) s.finishInput()
        needsPresent = true
    }

    /**
     * Draws what changed since the last present into the bound front buffer and says whether it wrote anything.
     * The caller flushes.
     */
    fun present(surfaceW: Int, surfaceH: Int): Boolean {
        val s = stroke ?: run { why = "no stroke"; return false }
        if (program == 0) { why = "no program"; return false }
        if (!needsPresent) { why = "nothing new"; return false }
        needsPresent = false
        s.updateShape(lastMs)
        extractLive(s)
        region.reset()
        s.populateUpdatedRegion(region)
        s.resetUpdatedRegion()
        val tail = if (region.isEmpty()) null else region.box?.let {
            PxBox(it.xMin * scale + tx, it.yMin * scale + ty, it.xMax * scale + tx, it.yMax * scale + ty)
        }
        val bounds = PxRect(clip.left.coerceAtLeast(0), clip.top.coerceAtLeast(0), clip.right.coerceAtMost(surfaceW), clip.bottom.coerceAtMost(surfaceH))
        val damage = FrontInkDamage.rect(lastTail, tail, halfWidthPx, bounds)
        lastTail = tail
        if (damage == null) { why = "no damage"; return false }
        drawDamage(damage, surfaceW, surfaceH, withLive = true)
        liveBox = FrontInkDamage.union(liveBox, PxBox(damage.left.toFloat(), damage.top.toFloat(), damage.right.toFloat(), damage.bottom.toFloat()))
        return true
    }

    /** The pen-up outlines become held coats (still on the pad, redrawn under later strokes). Returns the finished stroke. */
    fun settleStroke(): Stroke? {
        val s = stroke ?: return null
        val done = try { s.toImmutable() } catch (e: RuntimeException) { Log.w(TAG, "toImmutable failed", e); null }
        for (c in 0 until coatCount) {
            val coat = live[c]
            val frozen = Coat(coat.color.copyOf())
            for (i in 0 until coat.used) frozen.polys.add(coat.polys[i].copy())
            held.add(frozen)
        }
        endLive()
        return done
    }

    /** Throws the live stroke away and repaints what it covered, from the held outlines alone. Returns whether it drew. */
    fun cancelStroke(surfaceW: Int, surfaceH: Int): Boolean {
        val box = FrontInkDamage.union(liveBox, lastTail)
        endLive()
        val bounds = PxRect(clip.left.coerceAtLeast(0), clip.top.coerceAtLeast(0), clip.right.coerceAtMost(surfaceW), clip.bottom.coerceAtMost(surfaceH))
        val damage = FrontInkDamage.rect(null, box, halfWidthPx, bounds) ?: return false
        drawDamage(damage, surfaceW, surfaceH, withLive = false)
        return true
    }

    private fun endLive() {
        stroke?.clear()
        coatCount = 0
        lastTail = null
        liveBox = null
        needsPresent = false
    }

    /** Forgets every held outline (the pad is being wiped). */
    fun forgetHeld() {
        held.clear()
    }

    /** Wipes the whole surface. */
    fun clearSurface() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    // ---- outlines ----

    private fun extractLive(s: InProgressStroke) {
        coatCount = s.getBrushCoatCount()
        while (live.size < coatCount) live.add(Coat())
        val a = (colorArgb ushr 24 and 0xff) / 255f
        for (c in 0 until coatCount) {
            val coat = live[c]
            coat.color[0] = (colorArgb ushr 16 and 0xff) / 255f * a
            coat.color[1] = (colorArgb ushr 8 and 0xff) / 255f * a
            coat.color[2] = (colorArgb and 0xff) / 255f * a
            coat.color[3] = a
            val outlines = s.getOutlineCount(c)
            coat.used = outlines
            while (coat.polys.size < outlines) coat.polys.add(Poly())
            for (o in 0 until outlines) {
                val poly = coat.polys[o]
                val n = s.getOutlineVertexCount(c, o)
                if (poly.xy.size < n * 2) poly.xy = FloatArray(n * 2 + 64)
                poly.n = n
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (v in 0 until n) {
                    s.populateOutlinePosition(c, o, v, vec)
                    val px = vec.x * scale + tx
                    val py = vec.y * scale + ty
                    poly.xy[v * 2] = px
                    poly.xy[v * 2 + 1] = py
                    if (px < minX) minX = px; if (px > maxX) maxX = px
                    if (py < minY) minY = py; if (py > maxY) maxY = py
                }
                poly.minX = minX; poly.minY = minY; poly.maxX = maxX; poly.maxY = maxY
            }
        }
    }

    // ---- drawing ----

    private fun drawDamage(d: PxRect, surfaceW: Int, surfaceH: Int, withLive: Boolean) {
        val dw = d.width
        val dh = d.height
        if (!ensureTargets(dw, dh)) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
        GLES30.glViewport(0, 0, dw, dh)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glStencilMask(0xff)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearStencil(0)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(uXf, 2f / dw, -2f / dh, -1f - 2f * d.left / dw, 1f + 2f * d.top / dh)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        for (coat in held) drawCoat(coat.color, coat.polys, coat.polys.size, d)
        if (withLive) for (c in 0 until coatCount) drawCoat(live[c].color, live[c].polys, live[c].used, d)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisableVertexAttribArray(0)

        // Out of a multisampled read GLES3 allows one shape of blit: identical rectangles. The resolve lands at
        // the twin's origin, and the single-sampled twin goes to wherever on the surface the damage is. The
        // surface counts up from the bottom and the damage counts down from the top.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resFbo)
        GLES30.glBlitFramebuffer(0, 0, dw, dh, 0, 0, dw, dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glInvalidateFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 1, INVALIDATE_COLOR, 0)
        val bottom = surfaceH - d.bottom
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, resFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, dw, dh, d.left, bottom, d.left + dw, bottom + dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
    }

    /** One coat: every outline that touches the damage into the stencil (non-zero winding), then one cover. */
    private fun drawCoat(color: FloatArray, polys: List<Poly>, count: Int, d: PxRect) {
        var any = false
        for (i in 0 until count) if (polys[i].n >= 3 && polys[i].touches(d)) { any = true; break }
        if (!any) return
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xff)
        GLES30.glStencilOpSeparate(GLES30.GL_FRONT, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INCR_WRAP)
        GLES30.glStencilOpSeparate(GLES30.GL_BACK, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_DECR_WRAP)
        for (i in 0 until count) {
            val p = polys[i]
            if (p.n < 3 || !p.touches(d)) continue
            putFan(p.xy, p.n)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, fan)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, p.n)
        }
        GLES30.glColorMask(true, true, true, true)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Premultiplied: the layer is translucent and the compositor reads it that way.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glStencilFunc(GLES30.GL_NOTEQUAL, 0, 0xff)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_ZERO)
        GLES30.glUniform4f(uColor, color[0], color[1], color[2], color[3])
        val quad = floatArrayOf(d.left.toFloat(), d.top.toFloat(), d.right.toFloat(), d.top.toFloat(), d.left.toFloat(), d.bottom.toFloat(), d.right.toFloat(), d.bottom.toFloat())
        putFan(quad, 4, strip = true)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, fan)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun putFan(xy: FloatArray, n: Int, @Suppress("UNUSED_PARAMETER") strip: Boolean = false) {
        if (fan.capacity() < n * 2) {
            fan = ByteBuffer.allocateDirect(n * 2 * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        fan.clear()
        fan.put(xy, 0, n * 2)
        fan.position(0)
    }

    /** The multisampled target (colour + stencil) and its single-sampled twin, grown to hold [w] x [h]. */
    private fun ensureTargets(w: Int, h: Int): Boolean {
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        val want = if (samples < 2) 0 else minOf(samples, max[0].coerceAtLeast(0))
        if (msFbo != 0 && fboW >= w && fboH >= h && fboSamples == want) return true
        releaseTargets()
        val nw = (w + 63) / 64 * 64
        val nh = (h + 63) / 64 * 64
        val rb = IntArray(3)
        GLES30.glGenRenderbuffers(3, rb, 0)
        msColor = rb[0]; msStencil = rb[1]; resColor = rb[2]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msColor)
        if (want >= 2) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, want, GLES30.GL_RGBA8, nw, nh)
        else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, nw, nh)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msStencil)
        if (want >= 2) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, want, GLES30.GL_DEPTH24_STENCIL8, nw, nh)
        else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, nw, nh)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, resColor)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, nw, nh)
        val fb = IntArray(2)
        GLES30.glGenFramebuffers(2, fb, 0)
        msFbo = fb[0]; resFbo = fb[1]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msColor)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, msStencil)
        val a = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, resColor)
        val b = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (a != GLES30.GL_FRAMEBUFFER_COMPLETE || b != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "targets incomplete: ms=0x%x res=0x%x at %dx%d x%d".format(a, b, nw, nh, want))
            releaseTargets()
            why = "incomplete framebuffer"
            return false
        }
        fboW = nw; fboH = nh; fboSamples = want
        return true
    }

    private fun releaseTargets() {
        if (msFbo != 0) GLES30.glDeleteFramebuffers(2, intArrayOf(msFbo, resFbo), 0)
        if (msColor != 0) GLES30.glDeleteRenderbuffers(3, intArrayOf(msColor, msStencil, resColor), 0)
        msFbo = 0; resFbo = 0; msColor = 0; msStencil = 0; resColor = 0; fboW = 0; fboH = 0; fboSamples = 0
    }

    private companion object {
        const val TAG = "SyncrFrontInk"
        val INVALIDATE_COLOR = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
        const val VERTEX = """#version 300 es
in vec2 aPos;
uniform vec4 uXf;
void main() { gl_Position = vec4(aPos.x * uXf.x + uXf.z, aPos.y * uXf.y + uXf.w, 0.0, 1.0); }
"""
        const val FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 o;
void main() { o = uColor; }
"""
    }
}
