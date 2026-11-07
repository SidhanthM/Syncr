package dev.syncr.prototype.notes

import kotlin.math.roundToInt

/**
 * Typesetting that reproduces the Mac's note text (`DocumentLayout.swift`, `NoteDetailView.positionText`) in canvas
 * units. Pure: line breaking and glyph widths come from a [Shaper] (the phone's StaticLayout in [TextLayer]; a fixed-width
 * fake in tests), so the vertical geometry, the part that decides whether text lines up with ink, is unit tested.
 *
 * Mac metrics (measured with NSLayoutManager on the system font, see [MacMetrics]): a line is round(ascent) + round(descent)
 * tall, the paragraph's line spacing (a third of the body size) is added under every visual line, and 10 units follow a
 * paragraph. Hidden syntax is a 0.01 unit font; list markers and quote marks keep their width but are drawn clear.
 */
internal object MacMetrics {
    const val BODY = 48f
    const val LINE_SPACING = BODY / 3f
    const val PARAGRAPH_SPACING = 10f
    const val ASCENT = 0.9668f
    const val DESCENT = 0.2109f
    const val HIDDEN = 0.01f
    fun ascent(size: Float) = (size * ASCENT).roundToInt()
    fun lineHeight(size: Float) = ascent(size) + (size * DESCENT).roundToInt()
    fun headingSize(level: Int): Float = (BODY * (listOf(1.5f, 1.3f, 1.15f).getOrNull(level - 1) ?: 1.05f)).roundToInt().toFloat()
    const val TITLE_SIZE = 76f
    const val TEXT_X = 100f
    const val TEXT_Y = 260f
    const val TITLE_Y = 70f
    const val DEFAULT_PAGE_WIDTH = 1600f
    const val MARGIN_GAP = 40f
}

internal enum class Family { SANS, SERIF, MONO }

/** Colour roles; the painter maps them to ARGB for light and dark paper. */
internal enum class Tok { INK, SOFT, MUTED, HAIRLINE, SEPARATOR, CODE_FILL, CLEAR, PURPLE, GREEN, ORANGE, HIGHLIGHT, INLINE_CODE, PAGE, RED, BLUE }

internal data class Attr(
    val size: Float = MacMetrics.BODY,
    val family: Family = Family.SANS,
    val weight: Int = 400,
    val italic: Boolean = false,
    val tok: Tok = Tok.INK,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val background: Tok? = null,
    val kern: Float = 0f,
) {
    /** Takes no room and draws nothing: the Mac's 0.01 unit clear font. */
    val hidden get() = size <= MacMetrics.HIDDEN * 2
}

internal data class Run(val start: Int, val end: Int, val attr: Attr)
internal data class Seg(val start: Int, val end: Int, val x: Float)
internal data class ShapedLine(val start: Int, val end: Int, val segments: List<Seg>)

internal interface Shaper {
    /** Wraps [text] (no newline) styled by [runs] to [width]; always returns at least one line. */
    fun shape(text: String, runs: List<Run>, width: Float): List<ShapedLine>
}

internal sealed interface Prim {
    val top: Float
    val bottom: Float

    data class Text(val text: String, val x: Float, val baseline: Float, val attr: Attr) : Prim {
        override val top get() = baseline - attr.size
        override val bottom get() = baseline + attr.size * 0.3f
    }

    data class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val tok: Tok, val alpha: Float = 1f) : Prim {
        override val top get() = y
        override val bottom get() = y + h
    }

    data class Outline(val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val tok: Tok, val width: Float = 2f) : Prim {
        override val top get() = y
        override val bottom get() = y + h
    }

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val tok: Tok, val dash: List<Float> = emptyList()) : Prim {
        override val top get() = minOf(y1, y2) - width
        override val bottom get() = maxOf(y1, y2) + width
    }

    /** A companion picture, drawn by the painter from the core's display list. */
    data class Picture(val key: String, val x: Float, val y: Float, val w: Float, val h: Float) : Prim {
        override val top get() = y
        override val bottom get() = y + h
    }
}

/** One wrapped line of the document, in canvas units. [top] and [used] bound the fragment without paragraph spacing. */
internal data class VLine(val start: Int, val end: Int, val top: Float, val used: Float, val logical: Int)

internal class DocLayout(val prims: List<Prim>, val vlines: List<VLine>, val logicalBottoms: List<Float>, val bottom: Float) {
    /** The canvas y of the visual line holding the UTF-16 [offset], or null for an empty document. */
    fun yOf(offset: Int): Float? = (vlines.firstOrNull { offset >= it.start && offset <= it.end } ?: vlines.lastOrNull())?.top
}

internal class Typeset(private val shaper: Shaper) {
    /**
     * [heights] gives drawing-embed lines (`![[syncr-drawing:ID]]`) their reserved height; [gaps] adds space after the
     * paragraph at a logical line index (answers placed under their passage).
     */
    fun document(text: String, x: Float, y: Float, width: Float, heights: Map<String, Float> = emptyMap(), gaps: Map<Int, Float> = emptyMap()): DocLayout {
        val prims = ArrayList<Prim>()
        val vlines = ArrayList<VLine>()
        val bottoms = ArrayList<Float>()
        val lines = TextMarkdown.lines(text)
        var cursor = y
        var codeBlock: FloatArray? = null // top, bottom of the current code background
        fun flushCode() {
            val b = codeBlock ?: return
            prims += Prim.Fill(x - 24, b[0], width + 48, b[1] - b[0], 14f, Tok.CODE_FILL, 0.12f)
            codeBlock = null
        }
        for ((index, line) in lines.withIndex()) {
            val raw = line.text.trim()
            val embedId = if (raw.startsWith("![[syncr-drawing:") && raw.endsWith("]]")) raw.drop(17).dropLast(2) else null
            val embedHeight = embedId?.let { heights[it] }
            val attrs = attributes(line)
            val runs = compress(attrs)
            val shaped = if (embedHeight != null) listOf(ShapedLine(0, 0, emptyList())) else shaper.shape(line.text, runs, width)
            var firstFragment: FloatArray? = null
            var lastFragment: FloatArray? = null
            for ((k, sl) in shaped.withIndex()) {
                val lastOfLine = k == shaped.lastIndex
                // The tallest font among the line's glyphs; the line break counts only when nothing else is on the line.
                var size = 0f
                for (r in runs) if (r.end > sl.start && r.start < maxOf(sl.end, sl.start + 1)) size = maxOf(size, r.attr.size)
                if (runs.isEmpty()) size = MacMetrics.BODY
                val height = embedHeight ?: MacMetrics.lineHeight(size).toFloat()
                val spacing = if (embedHeight != null) 0f else MacMetrics.LINE_SPACING
                val paragraph = if (lastOfLine && embedHeight == null) MacMetrics.PARAGRAPH_SPACING + (gaps[index] ?: 0f) else 0f
                val top = cursor
                val baseline = top + (if (embedHeight != null) 0 else MacMetrics.ascent(size))
                for (seg in sl.segments) {
                    val attr = runs.firstOrNull { it.start <= seg.start && seg.start < it.end }?.attr ?: continue
                    if (attr.hidden || attr.tok == Tok.CLEAR || seg.end <= seg.start) continue
                    prims += Prim.Text(line.text.substring(seg.start, seg.end), x + seg.x, baseline, attr)
                }
                val fragmentHeight = height + spacing + paragraph
                val fragment = floatArrayOf(top, top + fragmentHeight)
                if (k == 0) firstFragment = fragment
                lastFragment = fragment
                vlines += VLine(line.start + sl.start, line.start + sl.end, top, top + height + spacing, index)
                cursor += fragmentHeight
            }
            val first = firstFragment!!
            decorate(line, prims, x, width, first[0], first[1] - first[0])
            when (line.kind) {
                is MdKind.Code, is MdKind.Fence -> {
                    val b = codeBlock
                    val union = floatArrayOf(minOf(first[0], lastFragment!![0]), maxOf(first[1], lastFragment[1]))
                    val merged = if (b == null) union else floatArrayOf(minOf(b[0], union[0]), maxOf(b[1], union[1]))
                    codeBlock = merged
                    if (line.kind is MdKind.Fence && !(merged[0] == union[0] && merged[1] == union[1])) flushCode()
                }
                else -> flushCode()
            }
            bottoms += (vlines.last().used)
        }
        flushCode()
        return DocLayout(prims, vlines, bottoms, cursor)
    }

    private fun decorate(line: MdLine, prims: MutableList<Prim>, x: Float, width: Float, top: Float, fragment: Float) {
        val indent = line.text.takeWhile { it == ' ' || it == '\t' }.length * 12f
        fun label(text: String, size: Float, dx: Float) {
            prims += Prim.Text(text, x + dx, top + MacMetrics.ascent(size), Attr(size = size, tok = Tok.MUTED))
        }
        when (val k = line.kind) {
            is MdKind.Fence -> if (k.language.isNotEmpty()) {
                // Right-aligned by the painter's measure: x is the right edge, flagged by a negative size sign.
                prims += Prim.Text(k.language, x + width, top + MacMetrics.ascent(30f), Attr(size = 30f, tok = Tok.MUTED, kern = RIGHT_ALIGNED))
            }
            MdKind.Bullet -> label("•", MacMetrics.BODY, indent)
            is MdKind.Numbered -> label("${k.n}.", MacMetrics.BODY, indent)
            MdKind.Quote -> prims += Prim.Fill(x, top + 4, 5f, fragment - 8, 0f, Tok.SEPARATOR)
            is MdKind.Task -> {
                prims += Prim.Outline(x + indent, top + 10, 34f, 34f, 6f, Tok.MUTED, 1.5f)
                if (k.checked) prims += Prim.Text("✓", x + indent + 5, top + 10 - 2 + MacMetrics.ascent(30f), Attr(size = 30f, weight = 500, tok = Tok.INK))
            }
            is MdKind.Rule -> prims += Prim.Line(x, top + fragment / 2, x + width, top + fragment / 2, 3f, Tok.SEPARATOR,
                when (k.marker) { "---" -> emptyList(); "..." -> listOf(3f, 10f); else -> listOf(24f, 15f) })
            else -> Unit
        }
    }

    /** The Mac's `attributed(...)` for one line with no caret: syntax hidden, prefixes kept or hidden as it does. */
    fun attributes(line: MdLine): Array<Attr> {
        val n = line.text.length
        val a = Array(n) { Attr() }
        fun set(range: IntRange, f: (Attr) -> Attr) {
            for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(n - 1)) a[i] = f(a[i])
        }
        fun rel(range: IntRange) = (range.first - line.start)..(range.last - line.start)
        val hide = { r: IntRange -> set(r) { it.copy(size = MacMetrics.HIDDEN, tok = Tok.CLEAR) } }
        val whole = 0 until n
        val prefix = 0 until line.prefixLength
        when (val k = line.kind) {
            is MdKind.Heading -> {
                val size = MacMetrics.headingSize(k.level)
                set(whole) { it.copy(size = size, weight = 500, kern = -0.02f) }
                hide(prefix)
            }
            MdKind.Quote -> { set(whole) { it.copy(tok = Tok.SOFT) }; set(prefix) { it.copy(tok = Tok.CLEAR) } }
            is MdKind.Task -> set(prefix) { it.copy(size = 16f, family = Family.MONO, tok = Tok.CLEAR) }
            MdKind.Bullet, is MdKind.Numbered -> set(prefix) { it.copy(tok = Tok.CLEAR) }
            is MdKind.Fence -> { set(whole) { it.copy(size = 34f, family = Family.MONO, tok = Tok.MUTED) }; hide(whole) }
            is MdKind.Code -> {
                set(whole) { it.copy(size = 42f, family = Family.MONO) }
                colourCode(line.text, k.language, a)
            }
            is MdKind.Rule -> set(whole) { it.copy(tok = Tok.CLEAR) }
            else -> Unit
        }
        for (span in line.spans) {
            val r = (span.start - line.start) until (span.end - line.start)
            when (span.kind) {
                SpanKind.BOLD -> set(r) { it.copy(weight = 700) }
                SpanKind.ITALIC -> set(r) { it.copy(italic = true) }
                SpanKind.STRIKE -> set(r) { it.copy(strike = true) }
                SpanKind.HIGHLIGHT -> set(r) { it.copy(background = Tok.HIGHLIGHT) }
                SpanKind.CODE -> set(r) { it.copy(size = 44f, family = Family.MONO, background = Tok.INLINE_CODE) }
                SpanKind.LINK, SpanKind.WIKI, SpanKind.TAG -> set(r) { it.copy(tok = Tok.INK, underline = true) }
            }
            span.syntax.forEach { hide(rel(it)) }
        }
        return a
    }

    private val keywords = Regex("\\b(let|var|func|fn|class|struct|enum|if|else|return|for|while|in|import|from|def|const|true|false|null|nil|pub|use|val|fun)\\b")
    private val strings = Regex("\"[^\"]*\"|'[^']*'")
    private val numbers = Regex("\\b[0-9]+(?:\\.[0-9]+)?\\b")
    private val comments = Regex("//.*$|#.*$")
    private val languages = setOf("swift", "rust", "rs", "python", "py", "javascript", "js", "typescript", "ts", "json", "sh", "bash", "kotlin")

    private fun colourCode(text: String, language: String, a: Array<Attr>) {
        if (language.lowercase() !in languages) return
        for ((regex, tok) in listOf(keywords to Tok.PURPLE, strings to Tok.GREEN, numbers to Tok.ORANGE, comments to Tok.MUTED)) {
            for (m in regex.findAll(text)) for (i in m.range) a[i] = a[i].copy(tok = tok)
        }
    }

    fun compress(attrs: Array<Attr>): List<Run> {
        val runs = ArrayList<Run>()
        var start = 0
        for (i in 1..attrs.size) {
            if (i == attrs.size || attrs[i] != attrs[start]) {
                if (i > start) runs += Run(start, i, attrs[start])
                start = i
            }
        }
        return runs
    }

}

/** Marks a label (in [Attr.kern]) whose x is its right edge. */
internal const val RIGHT_ALIGNED = 9999f
