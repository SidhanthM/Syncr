package dev.syncr.prototype.notes

import dev.syncr.core.CompanionSource
import dev.syncr.core.MessageWho

internal fun Prim.shifted(dx: Float, dy: Float): Prim = when (this) {
    is Prim.Text -> copy(x = x + dx, baseline = baseline + dy)
    is Prim.Fill -> copy(x = x + dx, y = y + dy)
    is Prim.Outline -> copy(x = x + dx, y = y + dy)
    is Prim.Line -> copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    is Prim.Picture -> copy(x = x + dx, y = y + dy)
}

/** Companion text laid out at the origin; [moved] places it. */
internal class RichResult(val prims: List<Prim>, val height: Float) {
    fun moved(dx: Float, dy: Float): List<Prim> = prims.map { it.shifted(dx, dy) }
}

/**
 * The companion's words: paragraphs, ordered and unordered lists, inline code, bold, italic and fenced code blocks
 * (contract ai-companion-v2: answers may contain lists, inline code and fenced code). Serif for prose, like the Mac's
 * margin cards (`MarginCard`, 50 units); code in a monospaced block on the code fill. Pure.
 */
internal class RichText(private val shaper: Shaper) {
    private val bullet = Regex("^\\s*[-*] +(.*)$")
    private val numbered = Regex("^\\s*([0-9]+)\\. +(.*)$")
    private val fence = Regex("^\\s*```(.*)$")

    fun body(text: String, x: Float, y: Float, width: Float, size: Float, tok: Tok): RichResult {
        val prims = ArrayList<Prim>()
        var cursor = y
        val pitch = { s: Float -> MacMetrics.lineHeight(s) + s / 4f }
        val lines = text.replace("\r", "").trimEnd('\n').split("\n")
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val f = fence.find(raw)
            if (f != null) {
                val language = f.groupValues[1].trim()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !fence.matches(lines[i])) { code += lines[i]; i++ }
                i++ // closing fence, or the end of an unfinished block
                val codeSize = size * 0.76f
                val inner = ArrayList<Prim>()
                var y2 = cursor + 14f
                if (language.isNotEmpty()) {
                    inner += Prim.Text(language, x + width - 16f, y2 + MacMetrics.ascent(codeSize * 0.8f), Attr(size = codeSize * 0.8f, tok = Tok.MUTED, kern = RIGHT_ALIGNED))
                }
                for (c in code) {
                    val attr = Attr(size = codeSize, family = Family.MONO)
                    val shaped = shaper.shape(c, if (c.isEmpty()) emptyList() else listOf(Run(0, c.length, attr)), width - 32f)
                    for (sl in shaped) {
                        for (seg in sl.segments) inner += Prim.Text(c.substring(seg.start, seg.end), x + 16f + seg.x, y2 + MacMetrics.ascent(codeSize), attr)
                        y2 += pitch(codeSize)
                    }
                }
                y2 += 14f
                prims += Prim.Fill(x, cursor, width, y2 - cursor, 14f, Tok.CODE_FILL, 0.12f)
                prims += inner
                cursor = y2 + size * 0.3f
                continue
            }
            if (raw.isBlank()) { cursor += size * 0.4f; i++; continue }
            val b = bullet.find(raw)
            val n = numbered.find(raw)
            val content = b?.groupValues?.get(1) ?: n?.groupValues?.get(2) ?: raw
            val indent = if (b != null || n != null) size * 1.1f else 0f
            if (b != null) prims += Prim.Text("•", x + indent * 0.2f, cursor + MacMetrics.ascent(size), Attr(size = size, family = Family.SERIF, tok = Tok.MUTED))
            if (n != null) prims += Prim.Text("${n.groupValues[1]}.", x, cursor + MacMetrics.ascent(size), Attr(size = size, family = Family.SERIF, tok = Tok.MUTED))
            val attrs = inlineAttrs(content, size, tok)
            val runs = typesetCompress(attrs)
            for (sl in shaper.shape(content, runs, width - indent)) {
                var line = size
                for (r in runs) if (r.end > sl.start && r.start < maxOf(sl.end, sl.start + 1) && !r.attr.hidden) line = maxOf(line, r.attr.size)
                for (seg in sl.segments) {
                    val attr = runs.firstOrNull { it.start <= seg.start && seg.start < it.end }?.attr ?: continue
                    if (attr.hidden || seg.end <= seg.start) continue
                    prims += Prim.Text(content.substring(seg.start, seg.end), x + indent + seg.x, cursor + MacMetrics.ascent(line), attr)
                }
                cursor += pitch(line)
            }
            cursor += size * 0.2f
            i++
        }
        return RichResult(prims, cursor - y)
    }

    private fun inlineAttrs(text: String, size: Float, tok: Tok): Array<Attr> {
        val a = Array(text.length) { Attr(size = size, family = Family.SERIF, tok = tok) }
        for (span in TextMarkdown.inline(text)) {
            val range = span.start until span.end
            for (k in range) a[k] = when (span.kind) {
                SpanKind.CODE -> a[k].copy(size = size * 0.88f, family = Family.MONO, background = Tok.INLINE_CODE)
                SpanKind.BOLD -> a[k].copy(weight = 700)
                SpanKind.ITALIC -> a[k].copy(italic = true)
                SpanKind.STRIKE -> a[k].copy(strike = true)
                else -> a[k]
            }
            for (s in span.syntax) for (k in s) a[k] = a[k].copy(size = MacMetrics.HIDDEN, tok = Tok.CLEAR)
        }
        return a
    }

    private fun typesetCompress(attrs: Array<Attr>): List<Run> = Typeset(shaper).compress(attrs)

    private fun sources(sources: List<CompanionSource>, x: Float, y: Float, width: Float): RichResult {
        if (sources.isEmpty()) return RichResult(emptyList(), 0f)
        val text = sources.joinToString("\n") { "↗ ${it.title} — ${hostOf(it.url)}" }
        return body(text.replace("\n", "\n\n").replace("\n\n", "\n"), x, y, width, 32f, Tok.MUTED)
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').ifEmpty { url }

    /** A margin note: a small dot, then the words in 50 unit serif, 56 units in from the card's left edge (card width 1000). */
    fun margin(text: String, sources: List<CompanionSource>, x: Float, y: Float, dot: Tok): RichResult {
        val inner = 1000f - 56f
        val prims = ArrayList<Prim>()
        prims += Prim.Fill(x, y + 26f, 20f, 20f, 10f, dot)
        val words = body(text, x + 56f, y, inner, 50f, Tok.INK)
        prims += words.prims
        var h = words.height
        val src = sources(sources, x + 56f, y + h + 16f, inner)
        if (src.height > 0) { prims += src.prims; h += 16f + src.height }
        return RichResult(prims, h)
    }

    /** A conversation under its passage: each message labelled by who spoke, the companion's with a rule beside it. */
    fun thread(messages: List<Pair<MessageWho, String>>, x: Float, y: Float, width: Float): RichResult {
        val prims = ArrayList<Prim>()
        var cursor = y
        for ((who, text) in messages) {
            val label = if (who == MessageWho.YOU) "You" else "Companion"
            prims += Prim.Text(label, x + 28f, cursor + MacMetrics.ascent(30f), Attr(size = 30f, tok = Tok.MUTED))
            cursor += MacMetrics.lineHeight(30f) + 8f
            val start = cursor
            val words = body(text, x + 28f, cursor, width - 28f, 44f, if (who == MessageWho.YOU) Tok.SOFT else Tok.INK)
            prims += words.prims
            if (who == MessageWho.COMPANION) prims += Prim.Fill(x, start, 4f, words.height, 0f, Tok.SEPARATOR)
            cursor += words.height + 22f
        }
        return RichResult(prims, cursor - y)
    }

    fun caption(text: String, x: Float, y: Float, width: Float, size: Float, tail: String): List<Prim> {
        val r = body("$text", x, y, width, size, Tok.SOFT)
        return r.prims
    }
}
