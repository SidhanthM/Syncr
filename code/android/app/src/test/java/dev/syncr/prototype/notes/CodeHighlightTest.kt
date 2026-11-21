package dev.syncr.prototype.notes

import dev.syncr.core.CodeToken
import dev.syncr.prototype.ui.theme.CodeColours
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Code colouring (code-highlight-v1 H4): the core's spans land on the right characters, in the right colours. Uses the host core library. */
class CodeHighlightTest {
    private val painterLight = TextPainter(false, emptyMap())
    private val painterDark = TextPainter(true, emptyMap())
    private val python = "def greet(name):\n    # say hello\n    return \"hi \" + name\n"
    private val typeset = Typeset(FakeShaper())

    @Before fun inline() { CodeHighlight.post = Runnable::run }
    @After fun restore() { CodeHighlight.post = { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) } }

    private fun attrsFor(text: String, lineStart: String): Array<Attr> {
        val lines = TextMarkdown.lines(text)
        val code = CodeHighlight.perLine(text, lines)
        val i = lines.indexOfFirst { it.text.startsWith(lineStart) }
        return typeset.attributes(lines[i], false, code[i].orEmpty())
    }

    @Test fun pythonKeywordStringAndCommentGetTheirColours() {
        val text = "```python\n$python```\n"
        val def = attrsFor(text, "def greet")
        assertEquals(Tok.CODE_KEYWORD, def[0].tok); assertEquals(Tok.CODE_KEYWORD, def[2].tok)
        assertEquals(Tok.CODE_FUNCTION, def[4].tok)
        val comment = attrsFor(text, "    # say")
        assertTrue(comment.drop(4).all { it.tok == Tok.CODE_COMMENT && it.italic })
        val ret = attrsFor(text, "    return")
        val quote = "    return \"hi \" + name".indexOf('"')
        assertEquals(Tok.CODE_STRING, ret[quote].tok)
        assertEquals(Tok.CODE_KEYWORD, ret[4].tok)
        assertFalse(ret[quote].italic)
        for (dark in listOf(false, true)) {
            val painter = if (dark) painterDark else painterLight
            assertEquals(CodeColours.keyword(dark), painter.colour(Tok.CODE_KEYWORD))
            assertEquals(CodeColours.string(dark), painter.colour(Tok.CODE_STRING))
            assertEquals(CodeColours.comment(dark), painter.colour(Tok.CODE_COMMENT))
            assertEquals(CodeColours.number(dark), painter.colour(Tok.CODE_NUMBER))
            assertEquals(CodeColours.function(dark), painter.colour(Tok.CODE_FUNCTION))
            assertEquals(CodeColours.type(dark), painter.colour(Tok.CODE_TYPE))
            assertEquals(CodeColours.attribute(dark), painter.colour(Tok.CODE_ATTRIBUTE))
        }
    }

    @Test fun tokensMapToTheSharedGroups() {
        assertEquals(Tok.CODE_NUMBER, CodeHighlight.tokFor(CodeToken.CONSTANT))
        assertEquals(Tok.CODE_TYPE, CodeHighlight.tokFor(CodeToken.TAG))
        assertNull(CodeHighlight.tokFor(CodeToken.PUNCTUATION))
        assertNull(CodeHighlight.tokFor(CodeToken.VARIABLE))
        assertNull(CodeHighlight.tokFor(CodeToken.OPERATOR))
    }

    @Test fun offsetsStayAlignedAfterAstralCharacters() {
        val text = "```python\nx = \"😀😀\"  # ok 😀\ny = 1\n```\n"
        val first = attrsFor(text, "x = ")
        val hash = first.indexOfFirst { it.tok == Tok.CODE_COMMENT }
        assertEquals("x = \"😀😀\"  ".length, hash)
        assertTrue(first.drop(hash).all { it.tok == Tok.CODE_COMMENT })
        val second = attrsFor(text, "y = 1")
        assertEquals(Tok.CODE_NUMBER, second[4].tok)
    }

    @Test fun textOutsideBlocksAndPlainBlocksAreUntouched() {
        val text = "Some prose with def and \"quotes\"\n```\nlet x = 1\n```\n```cobol\nlet y = 2\n```\n"
        val lines = TextMarkdown.lines(text)
        val code = CodeHighlight.perLine(text, lines)
        assertTrue(code.isEmpty())
        for ((i, line) in lines.withIndex()) assertTrue(typeset.attributes(line, false, code[i].orEmpty()).all { it.tok != Tok.CODE_KEYWORD })
    }

    @Test fun fenceLinesKeepTheirOwnStyle() {
        val text = "```python\n$python```\n"
        val fence = TextMarkdown.lines(text).first()
        val a = typeset.attributes(fence, false, emptyList())
        assertTrue(a.all { it.tok == Tok.CLEAR })
    }

    @Test fun anUnclosedFenceStillColoursToTheEnd() {
        val text = "```python\nreturn 1 # n"
        val a = attrsFor(text, "return")
        assertEquals(Tok.CODE_KEYWORD, a[0].tok)
        assertEquals(Tok.CODE_COMMENT, a.last().tok)
    }

    @Test fun largeBlocksArriveLaterAndOnlyWhenStillWanted() {
        val queued = ArrayList<Runnable>()
        CodeHighlight.post = { queued += it }
        val block = StringBuilder()
        while (block.length < CodeHighlight.INLINE_LIMIT + 600) block.append("value = compute(\"text\", 42)  # note\n")
        val text = "```python\n$block```\n"
        val lines = TextMarkdown.lines(text)
        var ready = 0
        val first = CodeHighlight.perLine(text, lines) { ready++ }
        assertTrue("plain at first", first.isEmpty())
        val deadline = System.currentTimeMillis() + 5000
        while (queued.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(1, ready)
        assertTrue("used once landed", CodeHighlight.perLine(text, lines) { ready++ }.isNotEmpty())

        // A result for text that has since changed is cached but not announced.
        val changed = text.replace("42", "43") + "\n"
        val changedLines = TextMarkdown.lines(changed)
        CodeHighlight.perLine(changed, changedLines) { ready++ }
        CodeHighlight.perLine("moved on\n", TextMarkdown.lines("moved on\n")) { ready++ }
        val again = System.currentTimeMillis() + 5000
        while (queued.size < 2 && System.currentTimeMillis() < again) Thread.sleep(10)
        queued.last().run()
        assertEquals("stale result is not applied", 1, ready)
    }

    @Test fun theCompanionsCodeBlockIsColouredToo() {
        val rich = RichText(FakeShaper())
        val result = rich.body("Try:\n\n```python\ndef f():\n    return 1\n```\n", 0f, 0f, 1000f, 44f, Tok.INK)
        val texts = result.prims.filterIsInstance<Prim.Text>()
        assertTrue(texts.any { it.text == "def" && it.attr.tok == Tok.CODE_KEYWORD })
        assertTrue(texts.any { it.text == "1" && it.attr.tok == Tok.CODE_NUMBER })
        assertTrue(texts.any { it.text.startsWith("Try") && it.attr.tok == Tok.INK })
    }
}
