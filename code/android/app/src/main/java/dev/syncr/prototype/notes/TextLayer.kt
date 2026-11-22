package dev.syncr.prototype.notes

import android.graphics.Bitmap
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
import android.os.SystemClock
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
import dev.syncr.prototype.stipple.PageChange
import dev.syncr.prototype.stipple.Stipple
import dev.syncr.prototype.ui.theme.CodeColours
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.PaperPalette
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The note's text and the companion's comments, drawn under the ink (read-only). Everything slow happens on one
 * background thread: [show] only compares a cheap fingerprint of the text and companion entities and, when it changed,
 * asks for a job that lays the scene out and records it into banded [Picture]s. The pen's path never waits: [draw] replays
 * the finished bands that are on screen, and a new scene is swapped in with one volatile write, then asks for a redraw.
 *
 * Latest only (latency-v1 T.4): at most one job runs and at most one newer request waits, replaced by each later one. A job
 * checks whether it is still the newest before each expensive stage and gives up if not; the scene on screen stays until the
 * newest one is ready, so a burst of edits costs one layout in flight plus one, not one per edit.
 */
internal class TextLayer(private val invalidate: () -> Unit, private val onExtent: (Float) -> Unit) {
    private class Band(val top: Float, val bottom: Float, val picture: Picture, val left: Float = 0f, val right: Float = Float.POSITIVE_INFINITY)
    private class Done(val key: Long, val dark: Boolean, val bands: List<Band>, val bottom: Float, val hits: List<ContentHit> = emptyList(),
        val hidden: String? = null, val scene: NoteTextScene = NoteTextScene.EMPTY, val noteId: String? = null,
        /** What differs from the scene on screen before it (items that came or went), to animate; null when nothing did. */
        val change: Change? = null)

    /** Paper-coloured stipple over an item's [box], from full (step 0) to none (the last step). */
    private class Cover(val box: ItemBox, val frames: List<Bitmap>)

    /** Companion items that came and went between two scenes: [old] is the page they left, drawn while they dissolve out. */
    private class Change(val plan: PageChange.Plan, val old: List<Band>, val leaving: List<Cover>, val arriving: List<Cover>)

    private class Running(val change: Change, val timeline: PageChange.Timeline, val start: Long)

    @Volatile private var done: Done? = null
    private var queuedKey = 0L
    private var queuedDark = false
    private var queuedHide: String? = null
    private var queuedNote: String? = null
    @Volatile private var generation = 0
    private class Request(val note: NoteSnapshot, val key: Long, val dark: Boolean, val hide: String?, val title: TitleDisplay?, val cell: Float, val mine: Int,
        val offsets: Map<String, Placement>, val canvas: Boolean = false, val paper: Int? = null)
    private val latest = LatestWork<Request>(executor, ::process)
    /** Jobs that started and jobs that gave up halfway, for the tests. */
    @Volatile var rendered = 0; @Volatile var abandoned = 0
    private val main = Handler(Looper.getMainLooper())
    /** The change animating on screen, if any (main thread only). */
    private var running: Running? = null
    private val coverPaint = Paint()

    /** The unbounded canvas: free text and cards anywhere, recorded in tiles instead of bands the width of a page. */
    var canvasMode = false

    /** Counts scenes swapped in, so caches built from [boxes] know when to rebuild. */
    @Volatile var version = 0
        private set

    /** Every text block and card box of the scene on screen (canvas units), for the minimap and fit all. */
    internal fun boxes(): List<ContentHit> = done?.hits.orEmpty()

    /** True while a pen is down or has just been: nothing starts to animate on the page then, it lands finished. */
    var penBusy: () -> Boolean = { false }
    /** The system's "Remove animations": no dissolve. */
    var reduced = false
    /** A stipple cell in canvas units (about 3 dp at the page's zoom). */
    var cellUnits = 10f
    /** The paper colour of the page the text sits on (a page can have its own), which the covers of a dissolve match; null: the house paper. */
    var paper: Int? = null

    /** Called from [NoteCanvasView.update] on the main thread; does no layout. */
    internal fun show(note: NoteSnapshot?, dark: Boolean, hide: String? = null, title: TitleDisplay? = null, offsets: Map<String, Placement> = emptyMap()) {
        if (note == null) { clear(); return }
        val key = (fingerprint(note) * 31 + (title?.hashCode() ?: 0)) * 31 + offsets.hashCode()
        if (note.noteId == queuedNote && key == queuedKey && dark == queuedDark && hide == queuedHide) return
        if (note.noteId != queuedNote) { done = null; onExtent(0f) }
        queuedNote = note.noteId; queuedKey = key; queuedDark = dark; queuedHide = hide
        val mine = ++generation
        latest.submit(Request(note, key, dark, hide, title, cellUnits, mine, offsets, canvasMode, paper))
    }

    private fun process(r: Request) {
        if (r.mine != generation) return
        val before = done
        val result = try { render(r, before) } catch (_: Throwable) { Done(r.key, r.dark, emptyList(), 0f) }
        if (result == null) { abandoned++; return }
        rendered++
        main.post {
            if (r.mine == generation) {
                done = result; version++; onExtent(result.bottom)
                // Items that came or went on a note already on screen dissolve and the text glides, unless the pen is busy or
                // animations are off: then the new page simply lands finished. A newer scene ends what was running.
                running = null
                val change = result.change
                if (change != null && !reduced && (Stipple.freeze != null || !penBusy())) {
                    val timeline = PageChange.Timeline(change.leaving.isNotEmpty(), change.plan.glides, change.arriving.isNotEmpty())
                    running = Running(change, timeline, SystemClock.uptimeMillis())
                }
                invalidate()
            }
        }
    }

    /** Whether the scene on screen leaves out the words of block [id], because the field above shows them. */
    fun hides(id: String?): Boolean = id != null && done?.hidden == id

    /** The document's own extra room and the text it was laid out for, from the scene on screen. */
    fun extras(): Triple<Map<Int, Float>, Map<Int, Float>, String>? = done?.scene?.let { Triple(it.gaps, it.embeds, it.documentText) }

    /** The task box at a point of the page, if the scene on screen has one there: its block and line. */
    fun task(x: Float, y: Float): Pair<String, Int>? = done?.takeIf { it.key == queuedKey }?.scene?.tasks?.firstOrNull { it.contains(x, y) }?.let { it.blockId to it.line }

    /** The document's visual lines in the scene on screen, empty until one is built. */
    internal fun lines(): List<TextLine> = done?.takeIf { it.key == queuedKey }?.scene?.lines.orEmpty()

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
        done = null; running = null
        onExtent(0f)
    }

    /** Draws the bands that intersect [view] (canvas units); the canvas is already in canvas space. */
    fun draw(canvas: Canvas, view: CullRect) {
        val current = done ?: return
        val run = running
        if (run == null) { drawBands(canvas, view, current.bands); return }
        val frozen = Stipple.freeze
        // A pen going down ends the animation where it stands: the page is drawn finished.
        if (frozen == null && penBusy()) { running = null; drawBands(canvas, view, current.bands); return }
        val step = if (frozen != null) run.timeline.frozen(frozen) else run.timeline.at(SystemClock.uptimeMillis() - run.start)
        val change = run.change
        when (step.phase) {
            // Items leaving dissolve out over the old page, text still where it was.
            PageChange.Phase.LEAVE -> {
                drawBands(canvas, view, change.old)
                covers(canvas, change.leaving, Stipple.frame(step.progress, leaving = true))
            }
            // Then room opens or closes: the text below the change glides to where it belongs, arriving items still hidden.
            PageChange.Phase.GLIDE -> {
                val offset = -change.plan.delta * (1f - Motion.EASE_OUT.transform(step.progress))
                drawBands(canvas, view, current.bands, to = minOf(change.plan.fromY, change.plan.fromY - change.plan.delta))
                covers(canvas, change.arriving, 0)
                drawBands(canvas, view, current.bands, from = change.plan.fromY, dy = offset)
            }
            PageChange.Phase.ARRIVE -> {
                drawBands(canvas, view, current.bands)
                covers(canvas, change.arriving, Stipple.frame(step.progress))
            }
            PageChange.Phase.DONE -> { running = null; drawBands(canvas, view, current.bands) }
        }
    }

    /** The bands in [bands] between [from] and [to] (canvas y), moved down by [dy]; the rest are not drawn. */
    private fun drawBands(canvas: Canvas, view: CullRect, bands: List<Band>, from: Float = NO_LIMIT_TOP, to: Float = NO_LIMIT_BOTTOM, dy: Float = 0f) {
        val limited = from > NO_LIMIT_TOP || to < NO_LIMIT_BOTTOM
        for (band in bands) {
            if (band.bottom + dy < view.top || band.top + dy > view.bottom || band.right < view.left || band.left > view.right) continue
            if (band.bottom < from || band.top >= to) continue
            canvas.save()
            canvas.translate(0f, dy)
            // A page band is as wide as the page and has no right edge of its own (infinity): clip to a finite one.
            if (limited) canvas.clipRect(band.left - 1f, from, if (band.right.isInfinite()) NO_LIMIT_BOTTOM else band.right + 1f, to)
            canvas.translate(band.left, band.top)
            canvas.drawPicture(band.picture)
            canvas.restore()
        }
    }

    private fun covers(canvas: Canvas, covers: List<Cover>, step: Int) {
        if (step >= Motion.STIPPLE_FRAMES) return
        for (c in covers) canvas.drawBitmap(c.frames[step.coerceIn(0, c.frames.lastIndex)], null, RectF(c.box.x, c.box.y, c.box.x + c.box.w, c.box.y + c.box.h), coverPaint)
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

    /** True while a change is still animating, so the view must draw again ([draw] steps it and ends it). */
    fun drawDissolves(@Suppress("UNUSED_PARAMETER") canvas: Canvas): Boolean = running != null

    /** Paper-coloured covers of [box], from hiding everything to hiding nothing, one cell about 3 dp. */
    private fun cover(box: ItemBox, paper: Int, cell: Float): Cover {
        val columns = Stipple.columns(box.w, cell); val rows = Stipple.rows(box.h, cell)
        return Cover(box, (0..Motion.STIPPLE_FRAMES).map { k ->
            val shown = Stipple.mask(columns, rows, k)
            Bitmap.createBitmap(IntArray(columns * rows) { if (shown[it]) 0 else paper }, columns, rows, Bitmap.Config.ARGB_8888)
        })
    }

    /** What items came and went between [before] and [scene] on the same note, with the covers to animate them. */
    private fun change(note: String, scene: NoteTextScene, before: Done?, dark: Boolean, cell: Float, pageColour: Int?): Change? {
        if (before == null || before.noteId != note || before.scene === NoteTextScene.EMPTY) return null
        val plan = PageChange.plan(before.scene.items.keys, scene.items.keys, before.scene.documentText, scene.documentText, before.scene.lines, scene.lines) ?: return null
        val paper = pageColour ?: PaperPalette.paper(dark)
        fun covers(ids: Set<String>, from: Map<String, ItemBox>) = ids.mapNotNull { id -> from[id]?.takeIf { it.w >= 1f && it.h >= 1f }?.let { cover(it, paper, cell) } }
        val leaving = covers(plan.leaving, before.scene.items)
        val arriving = covers(plan.arriving, scene.items)
        if (leaving.isEmpty() && arriving.isEmpty()) return null
        return Change(plan, before.bands, leaving, arriving)
    }

    /** The scene for [r], or null when a newer request came in before it was done (nothing is published then). */
    private fun render(r: Request, before: Done?): Done? {
        val note = r.note; val key = r.key; val dark = r.dark; val hide = r.hide; val cell = r.cell; val title = r.title
        fun stale() = r.mine != generation || latest.newerWaiting
        if (stale()) return null
        val shaper = AndroidShaper()
        val cache = HashMap<String, PictureDisplayList?>()
        val builder = NoteTextSceneBuilder(shaper) { svg -> cache.getOrPut(svg) { try { pictureDisplayList(svg) } catch (_: Exception) { null } } }
        val scene = if (r.canvas) builder.buildCanvas(note, hide) else builder.build(note, hide, title, r.offsets)
        if (stale()) return null
        val painter = TextPainter(dark, scene.pictures)
        if (r.canvas) {
            val tiles = tiled(scene, painter) { stale() } ?: return null
            return Done(key, dark, tiles, scene.bottom, scene.hits, hide, scene, note.noteId, change(note.noteId, scene, before, dark, cell, r.paper))
        }
        val bands = ArrayList<Band>()
        var top = 0f
        while (top < scene.bottom) {
            if (stale()) return null
            val bottom = top + BAND
            val inBand = scene.prims.filter { it.bottom >= top && it.top < bottom }
            if (inBand.isNotEmpty()) {
                val picture = Picture()
                val canvas = picture.beginRecording(LegacyPage.WIDTH.toInt(), BAND.toInt())
                canvas.translate(0f, -top)
                for (p in inBand) painter.draw(canvas, p)
                picture.endRecording()
                bands += Band(top, bottom, picture)
            }
            top = bottom
        }
        if (stale()) return null
        return Done(key, dark, bands, scene.bottom, scene.hits, hide, scene, note.noteId, change(note.noteId, scene, before, dark, cell, r.paper))
    }

    /**
     * Canvas tiles: primitives filed by their left edge and top into 2,400 x 1,200 unit tiles (sparse: only tiles with
     * something in them exist), each recorded with room to spill right so a wide line is not cut at the tile edge.
     */
    private fun tiled(scene: NoteTextScene, painter: TextPainter, stale: () -> Boolean): List<Band>? {
        val tiles = LinkedHashMap<Long, MutableList<Prim>>()
        for (p in scene.prims) {
            val cx = Math.floor((p.left / TILE_W).toDouble()).toLong()
            val r0 = Math.floor((p.top / BAND).toDouble()).toLong()
            val r1 = Math.floor((p.bottom / BAND).toDouble()).toLong()
            for (cy in r0..r1) tiles.getOrPut((cx shl 32) or (cy and 0xFFFFFFFFL)) { ArrayList() } += p
        }
        val out = ArrayList<Band>()
        for ((key, prims) in tiles) {
            if (stale()) return null
            val cx = (key shr 32).toInt(); val cy = key.toInt()
            val left = cx * TILE_W; val top = cy * BAND
            val picture = Picture()
            val canvas = picture.beginRecording((TILE_W + SPILL).toInt(), BAND.toInt())
            canvas.translate(-left, -top)
            for (p in prims) painter.draw(canvas, p)
            picture.endRecording()
            out += Band(top, top + BAND, picture, left, left + TILE_W + SPILL)
        }
        return out
    }

    private companion object {
        const val TILE_W = 2400f
        const val SPILL = 2000f
        const val BAND = 1200f
        const val NO_LIMIT_TOP = -1e7f
        const val NO_LIMIT_BOTTOM = 1e7f
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
        Tok.GREEN -> if (dark) 0xff6cc58a else 0xff2f8f4e
        Tok.ORANGE -> if (dark) 0xffe0a458 else 0xffc77c1e
        Tok.RED -> if (dark) 0xffff6b60 else 0xffc43c32
        Tok.BLUE -> if (dark) 0xff6aa3ff else 0xff2f6fd0
        Tok.PAGE -> PaperPalette.paper(dark).toLong() and 0xffffffffL
        Tok.ACCENT -> 0xffd39999
        Tok.CODE_KEYWORD -> CodeColours.keyword(dark).toLong()
        Tok.CODE_STRING -> CodeColours.string(dark).toLong()
        Tok.CODE_COMMENT -> CodeColours.comment(dark).toLong()
        Tok.CODE_NUMBER -> CodeColours.number(dark).toLong()
        Tok.CODE_FUNCTION -> CodeColours.function(dark).toLong()
        Tok.CODE_TYPE -> CodeColours.type(dark).toLong()
        Tok.CODE_ATTRIBUTE -> CodeColours.attribute(dark).toLong()
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
