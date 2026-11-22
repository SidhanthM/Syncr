package dev.syncr.prototype.editor

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import dev.syncr.prototype.notes.Attr
import dev.syncr.prototype.notes.CodeHighlight
import dev.syncr.prototype.notes.Family
import dev.syncr.prototype.notes.LineSpan
import dev.syncr.prototype.notes.MacMetrics
import dev.syncr.prototype.notes.MdKind
import dev.syncr.prototype.notes.MdLine
import dev.syncr.prototype.notes.Run
import dev.syncr.prototype.notes.TextMarkdown
import dev.syncr.prototype.notes.TextPainter
import dev.syncr.prototype.notes.Tok
import dev.syncr.prototype.notes.Typeset
import dev.syncr.prototype.notes.configure
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Extra room the reading layer gives lines of the document: space after a paragraph (answers) and picture embed lines. */
internal data class EditorExtras(val gaps: Map<Int, Float> = emptyMap(), val embeds: Map<Int, Float> = emptyMap())

/** Which lines show their Markdown syntax: the ones the selection touches, and the fences of a block holding the caret (§5.1). */
internal object Reveal {
    fun lines(text: String, start: Int, end: Int, lines: List<MdLine> = TextMarkdown.lines(text)): Set<Int> {
        val lo = minOf(start, end); val hi = maxOf(start, end)
        val shown = HashSet<Int>()
        for ((i, line) in lines.withIndex()) {
            val touched = if (lo == hi) line.start <= lo && lo <= line.end else line.end >= lo && line.start <= hi && !(line.start == hi && hi > lo)
            if (touched) shown += i
        }
        // Fenced blocks as (opening line, closing line or the last line).
        var open = -1
        for ((i, line) in lines.withIndex()) {
            if (line.kind !is MdKind.Fence) continue
            if (open < 0) open = i
            else {
                if ((open..i).any { it in shown }) { shown += open; shown += i }
                open = -1
            }
        }
        if (open >= 0 && (open until lines.size).any { it in shown }) shown += open
        return shown
    }
}

/** The height of one visual line of a paragraph, in canvas units: what [ParaHeight] and the reading layer both use. */
internal object EditorMetrics {
    /** [ascent] above the baseline and [descent] below it, the paragraph's spacing included on its last line. */
    data class Box(val ascent: Int, val descent: Int)

    fun box(size: Float, lastOfParagraph: Boolean, gap: Float = 0f): Box {
        val extra = MacMetrics.LINE_SPACING + if (lastOfParagraph) MacMetrics.PARAGRAPH_SPACING + gap else 0f
        return Box(MacMetrics.ascent(size), (size * MacMetrics.DESCENT).roundToInt() + extra.roundToInt())
    }

    /** The tallest font among the glyphs of [from] until [to] of a line; the whole line's body size when it has none. */
    fun sizeOf(runs: List<Run>, from: Int, to: Int): Float {
        if (runs.isEmpty()) return MacMetrics.BODY
        var size = 0f
        for (r in runs) if (r.end > from && r.start < maxOf(to, from + 1)) size = maxOf(size, r.attr.size)
        return size
    }
}

private class RunStyle(val attr: Attr, val painter: TextPainter) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = configure(p, attr)
    override fun updateDrawState(p: TextPaint) {
        configure(p, attr)
        p.color = painter.colour(attr.tok)
        p.isStrikeThruText = attr.strike
        p.isUnderlineText = attr.underline
        attr.background?.let { p.bgColor = painter.colour(it) }
    }
}

/** Gives each visual line of a paragraph the height the reading layer gives it (`Typeset.document`). */
private class ParaHeight(val runs: List<Run>, val length: Int, val gap: Float, val embed: Float?) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (embed != null) { fm.ascent = 0; fm.top = 0; fm.descent = embed.roundToInt(); fm.bottom = fm.descent; return }
        val origin = (text as Spanned).getSpanStart(this)
        val from = (start - origin).coerceAtLeast(0); val to = (end - origin).coerceAtMost(length)
        val box = EditorMetrics.box(EditorMetrics.sizeOf(runs, from, to), to >= length, gap)
        fm.ascent = -box.ascent; fm.top = -box.ascent; fm.descent = box.descent; fm.bottom = box.descent
    }
}

/** What the reading layer draws in place of list, task and quote syntax and rules, drawn over the same characters. */
private class Mark(val kind: Kind, val painter: TextPainter, val width: Float, val rule: String = "", val checked: Boolean = false) : ReplacementSpan() {
    enum class Kind { BULLET, TASK, QUOTE, RULE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        ceil(paint.measureText(text, start, end)).toInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val baseTop = y - MacMetrics.ascent(MacMetrics.BODY).toFloat()
        ink.reset(); ink.isAntiAlias = true
        when (kind) {
            Kind.BULLET -> { ink.textSize = MacMetrics.BODY; ink.color = painter.colour(Tok.MUTED); canvas.drawText("•", x, y.toFloat(), ink) }
            Kind.TASK -> {
                ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.5f; ink.color = painter.colour(Tok.MUTED)
                canvas.drawRoundRect(x, baseTop + 10, x + 34, baseTop + 44, 6f, 6f, ink)
                if (checked) {
                    ink.reset(); ink.isAntiAlias = true; ink.textSize = 30f; ink.isFakeBoldText = true; ink.color = painter.colour(Tok.INK)
                    canvas.drawText("✓", x + 5, baseTop + 8 + MacMetrics.ascent(30f), ink)
                }
            }
            Kind.QUOTE -> { ink.color = painter.colour(Tok.SEPARATOR); canvas.drawRect(x, top + 4f, x + 5f, bottom - 4f, ink) }
            Kind.RULE -> {
                ink.style = Paint.Style.STROKE; ink.strokeWidth = 3f; ink.color = painter.colour(Tok.SEPARATOR)
                when (rule) { "..." -> ink.pathEffect = DashPathEffect(floatArrayOf(3f, 10f), 0f); "---" -> Unit; else -> ink.pathEffect = DashPathEffect(floatArrayOf(24f, 15f), 0f) }
                val mid = (top + bottom) / 2f
                canvas.drawLine(x, mid, x + width, mid, ink)
            }
        }
    }
}

/**
 * Styles the editing field to look like the rendered note (§5.1, §5.3): the reading layer's fonts, sizes and colours
 * from `Typeset.attributes` (the same Markdown classification), the same line boxes, and the same decorations in place of
 * list, task and quote syntax. Syntax shows only on [Reveal]ed lines. Spans are kept per line and re-set only for lines
 * that changed, so typing touches the line being typed in.
 */
internal class EditorStyler(dark: Boolean, private val columnWidth: Float) {
    private class Applied(val key: Int, val spans: List<Any>)

    private val painter = TextPainter(dark, emptyMap())
    /** Measures with the reading layer's own line breaking, so a caret line can keep the lines the rendered note has. */
    private val typeset = Typeset(dev.syncr.prototype.notes.AndroidShaper())
    private val applied = ArrayList<Applied>()
    var extras = EditorExtras()
    /** Called on the main thread when a large code block's colours have arrived and the text still asks for them. */
    var onCodeReady: (() -> Unit)? = null

    fun forget(edit: Editable) { applied.forEach { a -> a.spans.forEach(edit::removeSpan) }; applied.clear() }

    fun style(edit: Editable, selStart: Int, selEnd: Int) {
        val text = edit.toString()
        val lines = TextMarkdown.lines(text)
        val shown = Reveal.lines(text, selStart, selEnd, lines)
        val code = CodeHighlight.perLine(text, lines, onCodeReady)
        for ((i, line) in lines.withIndex()) {
            val show = i in shown
            val gap = extras.gaps[i]; val embed = extras.embeds[i]
            val spans = code[i].orEmpty()
            val key = listOf(line.text, show, line.kind, gap, embed, i == lines.lastIndex, spans).hashCode()
            val old = applied.getOrNull(i)
            if (old != null && old.key == key && edit.getSpanStart(old.spans.first()) == line.start) continue
            old?.spans?.forEach(edit::removeSpan)
            val fresh = Applied(key, build(edit, line, show, gap ?: 0f, embed, spans))
            if (i < applied.size) applied[i] = fresh else applied += fresh
        }
        while (applied.size > lines.size) applied.removeAt(applied.lastIndex).spans.forEach(edit::removeSpan)
    }

    private fun build(edit: Editable, line: MdLine, show: Boolean, gap: Float, embed: Float?, code: List<LineSpan>): List<Any> {
        val out = ArrayList<Any>()
        val attrs = if (embed != null) Array(line.text.length) { Attr(tok = if (show) Tok.MUTED else Tok.CLEAR) } else typeset.attributesFitting(line, show, code, columnWidth)
        // Off the caret a number stays as typed, faint, where the reading layer draws its label over it.
        if (!show && embed == null && line.kind is MdKind.Numbered) for (i in 0 until minOf(line.prefixLength, attrs.size)) attrs[i] = attrs[i].copy(tok = Tok.MUTED)
        val runs = typeset.compress(attrs)
        val paragraphEnd = minOf(edit.length, line.end + 1)
        val empty = paragraphEnd == line.start
        val para = ParaHeight(runs, line.text.length, gap, embed)
        edit.setSpan(para, line.start, paragraphEnd, if (empty) Spanned.SPAN_INCLUSIVE_INCLUSIVE else Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out += para
        for (r in runs) {
            val span = RunStyle(r.attr, painter)
            edit.setSpan(span, line.start + r.start, line.start + r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out += span
        }
        if (!show && embed == null) for (mark in marks(line)) {
            edit.setSpan(mark.first, line.start + mark.second.first, line.start + mark.second.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out += mark.first
        }
        return out
    }

    /** The reading layer's stand-ins for a line's syntax, with the character range each covers. */
    private fun marks(line: MdLine): List<Pair<Mark, IntRange>> {
        val indent = line.text.takeWhile { it == ' ' || it == '\t' }.length
        val prefix = indent until line.prefixLength
        if (line.text.isEmpty()) return emptyList()
        return when (val k = line.kind) {
            MdKind.Bullet -> listOf(Mark(Mark.Kind.BULLET, painter, columnWidth) to prefix)
            is MdKind.Task -> listOf(Mark(Mark.Kind.TASK, painter, columnWidth, checked = k.checked) to prefix)
            MdKind.Quote -> listOf(Mark(Mark.Kind.QUOTE, painter, columnWidth) to prefix)
            is MdKind.Rule -> listOf(Mark(Mark.Kind.RULE, painter, columnWidth, rule = k.marker) to (0 until line.text.length))
            else -> emptyList()
        }.filter { !it.second.isEmpty() }
    }
}
