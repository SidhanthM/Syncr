package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PictureDisplayList
import dev.syncr.core.PictureItem
import dev.syncr.core.pictureDisplayList
import dev.syncr.prototype.ui.theme.PaperPalette
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The note's text and the companion's comments, drawn under the ink (read-only). Everything slow happens on one
 * background thread: [show] only compares a cheap fingerprint of the text and companion entities and, when it changed,
 * queues a job that lays the scene out and records it into banded [Picture]s. The pen's path never waits: [draw] replays
 * the finished bands that are on screen, and a new scene is swapped in with one volatile write, then asks for a redraw.
 */
internal class TextLayer(private val invalidate: () -> Unit, private val onExtent: (Float) -> Unit) {
    private class Band(val top: Float, val bottom: Float, val picture: Picture)
    private class Done(val key: Long, val dark: Boolean, val bands: List<Band>, val bottom: Float, val hits: List<ContentHit> = emptyList())

    @Volatile private var done: Done? = null
    private var queuedKey = 0L
    private var queuedDark = false
    private var queuedNote: String? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    /** Called from [NoteCanvasView.update] on the main thread; does no layout. */
    fun show(note: NoteSnapshot?, dark: Boolean) {
        if (note == null) { clear(); return }
        val key = fingerprint(note)
        if (note.noteId == queuedNote && key == queuedKey && dark == queuedDark) return
        if (note.noteId != queuedNote) { done = null; onExtent(0f) }
        queuedNote = note.noteId; queuedKey = key; queuedDark = dark
        val mine = ++generation
        executor.execute {
            val result = try { render(note, key, dark) } catch (_: Throwable) { Done(key, dark, emptyList(), 0f) }
            main.post { if (mine == generation) { done = result; onExtent(result.bottom); invalidate() } }
        }
    }

    fun hit(x: Float, y: Float): ContentTarget? = done?.takeIf { it.key == queuedKey }?.hits
        ?.asReversed()?.firstOrNull { it.contains(x, y) }?.target

    fun bounds(id: String): RectF? {
        val hits = done?.hits?.filter { it.target.id == id && !it.target.response }.orEmpty()
        if (hits.isEmpty()) return null
        return RectF(hits.minOf { it.x }, hits.minOf { it.y }, hits.maxOf { it.x + it.width }, hits.maxOf { it.y + it.height })
    }

    fun clear() {
        generation++
        queuedNote = null; queuedKey = 0L
        done = null
        onExtent(0f)
    }

    /** Draws the bands that intersect [view] (canvas units); the canvas is already in canvas space. */
    fun draw(canvas: Canvas, view: CullRect) {
        val current = done ?: return
        for (band in current.bands) {
            if (band.bottom < view.top || band.top > view.bottom) continue
            canvas.save()
            canvas.translate(0f, band.top)
            canvas.drawPicture(band.picture)
            canvas.restore()
        }
    }

    private fun fingerprint(note: NoteSnapshot): Long {
        var h = 1125899906842597L
        fun mix(s: String) { h = 31 * h + s.hashCode() }
        mix(note.noteId); mix(note.title)
        for (b in note.textBlocks) { mix(b.id); mix(b.head.operationId); h = 31 * h + (if (b.tombstone) 1 else 0) }
        for (c in note.companionItems) { mix(c.id); mix(c.head.operationId); h = 31 * h + (if (c.tombstone) 1 else 0) }
        note.companionSettings?.let { mix(it.head.operationId) }
        return h
    }

    private fun render(note: NoteSnapshot, key: Long, dark: Boolean): Done {
        val shaper = AndroidShaper()
        val cache = HashMap<String, PictureDisplayList?>()
        val scene = NoteTextSceneBuilder(shaper) { svg -> cache.getOrPut(svg) { try { pictureDisplayList(svg) } catch (_: Exception) { null } } }.build(note)
        val painter = TextPainter(dark, scene.pictures)
        val bands = ArrayList<Band>()
        var top = 0f
        while (top < scene.bottom) {
            val bottom = top + BAND
            val inBand = scene.prims.filter { it.bottom >= top && it.top < bottom }
            if (inBand.isNotEmpty()) {
                val picture = Picture()
                val canvas = picture.beginRecording(CanvasLayout.WIDTH.toInt(), BAND.toInt())
                canvas.translate(0f, -top)
                for (p in inBand) painter.draw(canvas, p)
                picture.endRecording()
                bands += Band(top, bottom, picture)
            }
            top = bottom
        }
        return Done(key, dark, bands, scene.bottom, scene.hits)
    }

    private companion object {
        const val BAND = 1200f
        val executor = Executors.newSingleThreadExecutor(ThreadFactory { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "note-text").apply { isDaemon = true }
        })
    }
}

/** Applies an [Attr] to a paint; shared by measuring (line breaking) and drawing so both see the same glyphs. */
internal fun configure(p: Paint, a: Attr) {
    p.textSize = a.size
    val style = when { a.weight >= 700 && a.italic -> Typeface.BOLD_ITALIC; a.weight >= 700 -> Typeface.BOLD; a.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
    p.typeface = when (a.family) {
        Family.MONO -> Typeface.create(Typeface.MONOSPACE, style)
        Family.SERIF -> Typeface.create(Typeface.SERIF, style)
        Family.SANS -> if (a.weight in 500..699) Typeface.create("sans-serif-medium", if (a.italic) Typeface.ITALIC else Typeface.NORMAL) else Typeface.create(Typeface.DEFAULT, style)
    }
    p.letterSpacing = if (a.kern == RIGHT_ALIGNED) 0f else a.kern
}

private class RunSpan(val attr: Attr) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = configure(p, attr)
    override fun updateDrawState(p: TextPaint) = configure(p, attr)
}

/** Line breaking by StaticLayout (greedy word wrap, like NSLayoutManager); x positions by measuring each run. */
internal class AndroidShaper : Shaper {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun shape(text: String, runs: List<Run>, width: Float): List<ShapedLine> {
        if (text.isEmpty()) return listOf(ShapedLine(0, 0, emptyList()))
        val spannable = SpannableString(text)
        for (r in runs) spannable.setSpan(RunSpan(r.attr), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val layout = StaticLayout.Builder.obtain(spannable, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false).build()
        return List(layout.lineCount) { i ->
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i).let { if (it > start && text[it - 1] == '\n') it - 1 else it }
            var x = 0f
            val segments = ArrayList<Seg>()
            for (r in runs) {
                val a = maxOf(r.start, start); val b = minOf(r.end, end)
                if (b <= a) continue
                configure(measure, r.attr)
                segments += Seg(a, b, x)
                x += measure.measureText(text, a, b)
            }
            ShapedLine(start, end, segments)
        }
    }
}

/** Draws [Prim]s with Android paints. Colours follow the Mac's tokens (`code/shared/mac-theme.json`). */
internal class TextPainter(private val dark: Boolean, private val pictures: Map<String, PictureDisplayList>) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun colour(t: Tok): Int = when (t) {
        Tok.INK -> if (dark) 0xffe9e6df else 0xff1c1b19
        Tok.SOFT -> if (dark) 0xffb9b5ac else 0xff4a4741
        Tok.MUTED -> if (dark) 0xff85827a else 0xff7d7a72
        Tok.HAIRLINE -> alpha(colour(Tok.INK), 0x17)
        Tok.SEPARATOR -> alpha(colour(Tok.INK), 0x40)
        Tok.CODE_FILL -> colour(Tok.INK).toLong() and 0xffffffffL
        Tok.INLINE_CODE -> alpha(colour(Tok.INK), 0x1a)
        Tok.HIGHLIGHT -> 0x40ffcc00L
        Tok.CLEAR -> 0L
        Tok.PURPLE -> if (dark) 0xffbf8bff else 0xff8944ab
        Tok.GREEN -> if (dark) 0xff6cc58a else 0xff2f8f4e
        Tok.ORANGE -> if (dark) 0xffe0a458 else 0xffc77c1e
        Tok.RED -> if (dark) 0xffff6b60 else 0xffc43c32
        Tok.BLUE -> if (dark) 0xff6aa3ff else 0xff2f6fd0
        Tok.PAGE -> PaperPalette.paper(dark).toLong() and 0xffffffffL
    }.toInt()

    private fun alpha(c: Int, a: Int): Long = (c.toLong() and 0x00ffffffL) or (a.toLong() shl 24)

    fun draw(canvas: Canvas, p: Prim) {
        when (p) {
            is Prim.Text -> text(canvas, p)
            is Prim.Fill -> {
                paint.reset(); paint.isAntiAlias = true
                paint.color = colour(p.tok)
                if (p.alpha < 1f) paint.alpha = (p.alpha * 255).toInt()
                canvas.drawRoundRect(RectF(p.x, p.y, p.x + p.w, p.y + p.h), p.radius, p.radius, paint)
            }
            is Prim.Outline -> {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE; paint.strokeWidth = p.width; paint.color = colour(p.tok)
                canvas.drawRoundRect(RectF(p.x, p.y, p.x + p.w, p.y + p.h), p.radius, p.radius, paint)
            }
            is Prim.Line -> {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE; paint.strokeWidth = p.width; paint.color = colour(p.tok)
                if (p.dash.size >= 2) paint.pathEffect = android.graphics.DashPathEffect(p.dash.toFloatArray(), 0f)
                canvas.drawLine(p.x1, p.y1, p.x2, p.y2, paint)
            }
            is Prim.Picture -> pictures[p.key]?.let { picture(canvas, it, p) }
        }
    }

    private fun text(canvas: Canvas, p: Prim.Text) {
        val a = p.attr
        paint.reset(); paint.isAntiAlias = true
        configure(paint, a)
        paint.color = colour(a.tok)
        paint.isStrikeThruText = a.strike
        paint.isUnderlineText = a.underline
        var x = p.x
        if (a.kern == RIGHT_ALIGNED) x -= paint.measureText(p.text)
        a.background?.let { bg ->
            val w = paint.measureText(p.text)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour(bg) }
            canvas.drawRect(x, p.baseline + paint.ascent(), x + w, p.baseline + paint.descent(), fill)
        }
        canvas.drawText(p.text, x, p.baseline, paint)
    }

    private fun token(t: String): Tok = when (t.replace("var(--", "").replace(")", "")) {
        "soft", "voice" -> Tok.SOFT
        "muted" -> Tok.MUTED
        "hairline" -> Tok.HAIRLINE
        "wash" -> Tok.INLINE_CODE
        "page" -> Tok.PAGE
        "none" -> Tok.CLEAR
        else -> Tok.INK
    }

    /** The Mac's `PictureRenderer.draw` through the last step, with plain (not hand-wobbled) lines. */
    private fun picture(canvas: Canvas, list: PictureDisplayList, at: Prim.Picture) {
        val b = PictureGeometry.bounds(list)
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        val scale = minOf(at.w / maxOf(1f, bw), at.h / maxOf(1f, bh))
        canvas.save()
        canvas.translate(at.x + (at.w - bw * scale) / 2, at.y)
        canvas.scale(scale, scale)
        canvas.translate(-b[0], -b[1])
        val hidden = HashSet<UInt>(); val dimmed = HashSet<UInt>()
        for (s in list.steps) { hidden += s.hide; dimmed += s.dim }
        items(canvas, list.always)
        for ((index, step) in list.steps.withIndex()) {
            if ((index + 1).toUInt() in hidden) continue
            val layer = canvas.saveLayerAlpha(null, if ((index + 1).toUInt() in dimmed) 56 else 255)
            items(canvas, step.items)
            canvas.restoreToCount(layer)
        }
        canvas.restore()
    }

    private fun items(canvas: Canvas, items: List<PictureItem>) {
        for (item in items) if (item is PictureItem.Polyline) {
            val path = Path()
            item.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
            if (item.closed) path.close()
            if (token(item.fill) != Tok.CLEAR) {
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = colour(token(item.fill))
                canvas.drawPath(path, paint)
            }
            if (token(item.stroke) != Tok.CLEAR) {
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = item.strokeWidth
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = colour(token(item.stroke))
                canvas.drawPath(path, paint)
            }
        }
        // Labels after the lines, each with a page-coloured outline first so one never covers another's glyphs.
        for (halo in listOf(true, false)) for (item in items) if (item is PictureItem.Text) label(canvas, item, halo)
    }

    private fun label(canvas: Canvas, t: PictureItem.Text, halo: Boolean) {
        val shift = t.size * when (t.baseline) {
            "middle" -> 0.26f; "central" -> 0.378f; "hanging" -> 0.705f; "text-before-edge" -> 0.9668f; "text-after-edge" -> -0.2109f; else -> 0f
        }
        val attr = Attr(size = t.size, family = if (t.font.contains("sans")) Family.SANS else Family.SERIF)
        paint.reset(); paint.isAntiAlias = true
        configure(paint, attr)
        val width = paint.measureText(t.text)
        val dx = when (t.anchor) { "middle" -> -width / 2; "end" -> -width; else -> 0f }
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.concat(Matrix().apply { setValues(floatArrayOf(t.transform.a.toFloat(), t.transform.c.toFloat(), 0f, t.transform.b.toFloat(), t.transform.d.toFloat(), 0f, 0f, 0f, 1f)) })
        if (halo) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = t.size * 0.16f; paint.color = colour(Tok.PAGE)
        } else {
            paint.style = Paint.Style.FILL; paint.color = colour(token(t.fill))
        }
        canvas.drawText(t.text, dx, shift, paint)
        canvas.restore()
    }
}
