package dev.syncr.prototype.notes

import android.os.Handler
import android.os.Looper
import dev.syncr.core.CodeToken
import dev.syncr.core.highlightCode
import dev.syncr.prototype.ui.theme.CodeColours
import java.util.concurrent.Executors

/** One coloured run inside a line, in UTF-16 offsets from the line's start. */
internal data class LineSpan(val start: Int, val end: Int, val token: CodeToken)

/**
 * Syntax colours for fenced code, from the core's tree-sitter highlighter (the Mac's `CodeHighlight`). Colour is a
 * display attribute only: the note text never changes. Results are cached by (language, code), so restyling a note
 * re-reads every block it did not touch and re-highlights only the block being edited.
 *
 * A block under [INLINE_LIMIT] bytes is highlighted on the calling thread (well under a millisecond). A larger one is
 * highlighted on a worker when the caller passes `onReady` (the editor, on the main thread): until the result lands the
 * block draws plain, and `onReady` runs on the main thread only if the latest styling pass still asks for that exact code.
 * Callers that are already off the main thread (the reading layer's renderer) pass no `onReady` and get the result at once.
 */
internal object CodeHighlight {
    const val INLINE_LIMIT = 4096
    /** Above this, a block is left plain rather than parsed. */
    const val HARD_LIMIT = 200_000
    private const val CAPACITY = 64

    private data class Key(val language: String, val code: String)

    private val cache = object : LinkedHashMap<Key, List<LineSpanOffsets>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<LineSpanOffsets>>) = size > CAPACITY
    }
    private val pending = HashSet<Key>()
    private val wanted = HashSet<Key>()
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "code-highlight").apply { isDaemon = true } } }

    /** Hands a callback to the main thread; tests replace it. */
    internal var post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }

    private class LineSpanOffsets(val start: Int, val end: Int, val token: CodeToken)

    private fun run(language: String, code: String): List<LineSpanOffsets> =
        highlightCode(language, code).map { LineSpanOffsets(it.start.toInt(), it.end.toInt(), it.token) }

    private fun spans(language: String, code: String, onReady: (() -> Unit)?): List<LineSpanOffsets>? {
        val key = Key(language, code)
        synchronized(cache) { cache[key]?.let { return it } }
        val size = code.toByteArray(Charsets.UTF_8).size
        if (size > HARD_LIMIT) return null
        if (size < INLINE_LIMIT || onReady == null) {
            val spans = run(language, code)
            synchronized(cache) { cache[key] = spans }
            return spans
        }
        val fresh = synchronized(cache) { wanted += key; pending.add(key) }
        if (fresh) worker.execute {
            val spans = run(language, code)
            post(Runnable {
                synchronized(cache) { pending -= key; cache[key] = spans }
                // Stale if the text has moved on since: the result stays cached but nothing restyles.
                if (synchronized(cache) { wanted.remove(key) }) onReady()
            })
        }
        return null
    }

    /** Forgets which large blocks the previous styling pass asked for. */
    fun beginPass() { synchronized(cache) { wanted.clear() } }

    /**
     * The coloured runs of every code line of [text], by line index. A line outside a fenced block, or in one with no
     * language, has none. [lines] is `TextMarkdown.lines(text)`.
     */
    fun perLine(text: String, lines: List<MdLine>, onReady: (() -> Unit)? = null): Map<Int, List<LineSpan>> {
        if (onReady != null) beginPass()
        var out: HashMap<Int, List<LineSpan>>? = null
        var i = 0
        while (i < lines.size) {
            val kind = lines[i].kind
            if (kind !is MdKind.Code) { i++; continue }
            var j = i
            while (j + 1 < lines.size && lines[j + 1].kind is MdKind.Code) j++
            val from = lines[i].start; val to = lines[j].end
            val language = kind.language
            val block = i..j
            i = j + 1
            if (language.isEmpty() || to <= from || to > text.length) continue
            val spans = spans(language, text.substring(from, to), onReady) ?: continue
            val result = out ?: HashMap<Int, List<LineSpan>>().also { out = it }
            for (k in block) {
                val line = lines[k]
                val inLine = ArrayList<LineSpan>()
                for (s in spans) {
                    val a = maxOf(s.start, line.start - from); val b = minOf(s.end, line.end - from)
                    if (b > a) inLine += LineSpan(a - (line.start - from), b - (line.start - from), s.token)
                }
                if (inLine.isNotEmpty()) result[k] = inLine
            }
        }
        return out ?: emptyMap()
    }

    /** The colour role a span draws in, or null for the plain code colour (variables, operators, punctuation). */
    fun tokFor(token: CodeToken): Tok? = when (token) {
        CodeToken.KEYWORD -> Tok.CODE_KEYWORD
        CodeToken.STRING -> Tok.CODE_STRING
        CodeToken.COMMENT -> Tok.CODE_COMMENT
        CodeToken.NUMBER, CodeToken.CONSTANT -> Tok.CODE_NUMBER
        CodeToken.FUNCTION -> Tok.CODE_FUNCTION
        CodeToken.TYPE, CodeToken.TAG -> Tok.CODE_TYPE
        CodeToken.ATTRIBUTE -> Tok.CODE_ATTRIBUTE
        CodeToken.VARIABLE, CodeToken.OPERATOR, CodeToken.PUNCTUATION -> null
    }

    /** Paints [spans] onto a line's per-character attributes. */
    fun apply(attrs: Array<Attr>, spans: List<LineSpan>) {
        for (s in spans) {
            val tok = tokFor(s.token) ?: continue
            val italic = CodeColours.isItalic(s.token)
            for (i in s.start.coerceAtLeast(0) until s.end.coerceAtMost(attrs.size)) attrs[i] = attrs[i].copy(tok = tok, italic = italic)
        }
    }
}
