package dev.syncr.prototype.notes

/**
 * The Mac's line-based Markdown reading (`code/mac/Sources/SyncrMac/Markdown.swift`), ported rule for rule so the
 * phone sees the same line kinds and inline spans. Offsets are UTF-16 units, like the Mac's NSRange. Pure.
 */
internal sealed interface MdKind {
    data object Plain : MdKind
    data class Heading(val level: Int) : MdKind
    data object Bullet : MdKind
    data class Numbered(val n: Int) : MdKind
    data class Task(val checked: Boolean) : MdKind
    data object Quote : MdKind
    data class Fence(val language: String) : MdKind
    data class Code(val language: String) : MdKind
    data class Rule(val marker: String) : MdKind
}

internal enum class SpanKind { BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, LINK, WIKI, TAG }

/** [start] and [end] are absolute offsets in the whole text; [syntax] are the hidden delimiter ranges. */
internal data class MdSpan(val kind: SpanKind, val start: Int, val end: Int, val syntax: List<IntRange>)

/** One source line (no trailing newline). [prefixEnd] is the absolute end of the line's prefix. */
internal data class MdLine(val kind: MdKind, val start: Int, val end: Int, val prefixEnd: Int, val text: String, val spans: List<MdSpan>) {
    val prefixLength get() = prefixEnd - start
}

internal object TextMarkdown {
    private val fence = Regex("^\\s*```(.*)$")
    private val heading = Regex("^(#{1,6})\\s+")
    private val task = Regex("^\\s*[-*] \\[([ xX])\\] ")
    private val bullet = Regex("^\\s*[-*] ")
    private val numbered = Regex("^\\s*([0-9]+)\\. ")
    private val quote = Regex("^\\s*> ")

    fun lines(text: String): List<MdLine> {
        val result = ArrayList<MdLine>()
        var offset = 0
        var fenceLanguage: String? = null
        val source = text.split("\n")
        val frontMatterEnd = if (source.firstOrNull() == "---") source.drop(1).indexOf("---").takeIf { it >= 0 }?.plus(1) else null
        for ((index, raw) in source.withIndex()) {
            val length = raw.length
            if (frontMatterEnd != null && index <= frontMatterEnd) {
                result += MdLine(MdKind.Plain, offset, offset + length, offset, raw, emptyList())
                offset += length + 1
                continue
            }
            var kind: MdKind = MdKind.Plain
            var prefix = 0
            val trimmed = raw.trim()
            val fenceMatch = fence.find(raw)
            if (fenceMatch != null) {
                val language = fenceMatch.groupValues[1].trim()
                kind = MdKind.Fence(language); prefix = length
                fenceLanguage = if (fenceLanguage == null) language else null
            } else if (fenceLanguage != null) {
                kind = MdKind.Code(fenceLanguage)
            } else if (trimmed == "---" || trimmed == "- - -" || trimmed == "...") {
                kind = MdKind.Rule(trimmed); prefix = length
            } else heading.find(raw)?.let { kind = MdKind.Heading(it.groupValues[1].length); prefix = it.value.length }
                ?: task.find(raw)?.let { kind = MdKind.Task(it.groupValues[1] != " "); prefix = it.value.length }
                ?: bullet.find(raw)?.let { kind = MdKind.Bullet; prefix = it.value.length }
                ?: numbered.find(raw)?.let { kind = MdKind.Numbered(it.groupValues[1].toIntOrNull() ?: 1); prefix = it.value.length }
                ?: quote.find(raw)?.let { kind = MdKind.Quote; prefix = it.value.length }
            val isCode = kind is MdKind.Code || kind is MdKind.Fence
            result += MdLine(kind, offset, offset + length, offset + prefix, raw, if (isCode) emptyList() else inline(raw, offset))
            offset += length + 1
        }
        return result
    }

    private val code = Regex("`[^`]+`")
    private val wiki = Regex("(?<!!)\\[\\[([^]\\n|]+)(?:\\|([^]\\n]+))?\\]\\]")
    private val link = Regex("(?<!!)\\[([^]\\n]+)\\]\\(([^)\\n]+)\\)")
    private val url = Regex("https?://[^\\s<>]+")
    private val tag = Regex("(?<![\\w/#])#[\\p{L}\\p{N}_-]+(?:/[\\p{L}\\p{N}_-]+)*")
    private val pairs = listOf(
        Triple(Regex("\\*\\*[^*]+\\*\\*"), SpanKind.BOLD, 2),
        Triple(Regex("(?<!\\*)\\*(?!\\*)[^*]+\\*(?!\\*)"), SpanKind.ITALIC, 1),
        Triple(Regex("~~[^~]+~~"), SpanKind.STRIKE, 2),
        Triple(Regex("==[^=]+=="), SpanKind.HIGHLIGHT, 2),
    )

    fun inline(text: String, offset: Int = 0): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val occupied = ArrayList<IntRange>()
        fun add(regex: Regex, kind: (MatchResult) -> SpanKind, syntax: (MatchResult) -> List<IntRange>) {
            for (m in regex.findAll(text)) {
                val r = m.range
                if (occupied.any { it.first <= r.last && r.first <= it.last }) continue
                out += MdSpan(kind(m), offset + r.first, offset + r.last + 1, syntax(m).map { (offset + it.first)..(offset + it.last) })
                occupied += r
            }
        }
        add(code, { SpanKind.CODE }) { listOf(it.range.first..it.range.first, it.range.last..it.range.last) }
        add(wiki, { SpanKind.WIKI }) { m ->
            val shown = m.groups[2]?.range ?: m.groups[1]!!.range
            listOf(m.range.first until shown.first, (shown.last + 1)..m.range.last)
        }
        add(link, { SpanKind.LINK }) { m ->
            val label = m.groups[1]!!.range
            listOf(m.range.first..m.range.first, (label.last + 1)..m.range.last)
        }
        for ((regex, kind, n) in pairs) add(regex, { kind }) { listOf(it.range.first until it.range.first + n, (it.range.last + 1 - n)..it.range.last) }
        add(url, { SpanKind.LINK }) { emptyList() }
        add(tag, { SpanKind.TAG }) { emptyList() }
        return out.sortedBy { it.start }
    }
}
