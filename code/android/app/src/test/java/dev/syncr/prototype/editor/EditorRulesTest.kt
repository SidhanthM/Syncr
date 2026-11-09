package dev.syncr.prototype.editor

import org.junit.Assert.*
import org.junit.Test

/** The numbered examples of tasks/contracts/editor-v2.md §3.6 (S) and §4 (T), one test each, named by number. */
class EditorRulesTest {
    /** `|` is the caret, `«…»` a selection, as in the contract. */
    private fun sel(marked: String): TextSel {
        val a = marked.indexOf('«'); val b = marked.indexOf('»')
        if (a >= 0) {
            val plain = marked.replace("«", "").replace("»", "")
            return TextSel(plain, a, b - 1)
        }
        val i = marked.indexOf('|')
        return TextSel(marked.replace("|", ""), i)
    }
    private fun show(s: TextSel): String =
        if (s.caret) s.text.substring(0, s.start) + "|" + s.text.substring(s.start)
        else s.text.substring(0, s.start) + "«" + s.text.substring(s.start, s.end) + "»" + s.text.substring(s.end)

    private fun run(marked: String, name: String): SlashResult {
        val s = sel(marked)
        val token = requireNotNull(Slash.token(s.text, s.start)) { "no token in $marked" }
        return Slash.run(s, token, Slash.commands.first { it.name == name }, "2026-11-16", "14:05")
    }
    private fun slash(marked: String, name: String) = show(run(marked, name).edit)
    private fun menu(marked: String): List<String> { val s = sel(marked); return Slash.matches(Slash.token(s.text, s.start)!!.query).map { it.name } }

    @Test fun S1_slashOpensMenuWithEveryCommand() {
        val s = sel("/|")
        assertEquals("", Slash.token(s.text, s.start)!!.query)
        assertEquals(Slash.commands, Slash.matches(""))
        assertEquals(23, Slash.commands.size)
    }
    @Test fun S2() = assertEquals("- [ ] Buy milk|", slash("Buy milk /to|", "todo"))
    @Test fun S3() = assertEquals("## |", slash("/h2|", "h2"))
    @Test fun S4() = assertEquals("# Title|", slash("## Title /h1|", "h1"))
    @Test fun S5() = assertEquals("item|", slash("- item /text|", "text"))
    @Test fun S6() = assertEquals("- step|", slash("3. step /bullet|", "bullet"))
    @Test fun S7() = assertEquals("    - [ ] nested|", slash("    - nested /todo|", "todo"))
    @Test fun S8() = assertEquals("### quoted|", slash("> quoted /h3|", "h3"))
    @Test fun S9() = assertEquals("Some text\n```\n|\n```", slash("Some text /code|", "code"))
    @Test fun S10() = assertEquals("---\n|", slash("/divider|", "divider"))
    @Test fun S11() = assertEquals("Text\n---\n|", slash("Text /divider|", "divider"))
    @Test fun S12() = assertEquals("Meet on 2026-11-16|", slash("Meet on /date|", "date"))
    @Test fun S13() = assertEquals("see [«text»](url)", slash("see /link|", "link"))
    @Test fun S14_noMenuInTheseSpots() {
        for (marked in listOf("word/h1|", "http://a.b/h|", "```\n/h|\n```", "`a /h|`", "[x](/h|)", "https://a.b /h|".replace(" /h|", "/h|"))) {
            val s = sel(marked)
            assertNull(marked, Slash.token(s.text, s.start))
        }
    }
    @Test fun S15_noMatchClosesAndTextStays() {
        val s = sel("/zz|")
        assertTrue(Slash.matches(Slash.token(s.text, s.start)!!.query).isEmpty())
        assertEquals("/zz", s.text)
    }
    @Test fun S16_escapeKeepsItClosedForThatToken() {
        val menu = SlashMenu()
        assertNotNull(menu.update(sel("/h|")))
        menu.escape()
        assertNull(menu.update(sel("/h|")))
        assertNull(menu.update(sel("/h1|")))
        // A different token reopens it.
        assertNotNull(menu.update(sel("/h1 and /b|")))
    }
    @Test fun S17() = assertEquals("a **|**", slash("a /bold|", "bold"))
    @Test fun S18() {
        val r = run("Why is V negative? /ask|", "ask")
        assertEquals("Why is V negative?|", show(r.edit))
        assertEquals(SlashAction.ASK, r.action)
        assertEquals(SlashAction.DRAW, run("a /draw|", "draw").action)
    }
    @Test fun S19_ranking() {
        assertEquals("todo", menu("/to|").first())
        assertTrue("todo" in menu("/ch|"))
        assertEquals(listOf("h1", "h2", "h3"), menu("/h|").take(3))
    }
    @Test fun S20_undoRestoresTheToken() {
        val start = sel("Buy milk /to|")
        val history = EditHistory(start)
        history.record(run("Buy milk /to|", "todo").edit, coalesce = false, now = 0)
        assertEquals("- [ ] Buy milk|", show(history.current))
        assertEquals("Buy milk /to|", show(history.undo()!!))
        assertEquals("- [ ] Buy milk|", show(history.redo()!!))
    }

    private fun enter(marked: String) = Typing.enter(sel(marked))?.let(::show)

    @Test fun T1() = assertEquals("- one\n- |", enter("- one|"))
    @Test fun T2() = assertEquals("- [x] done\n- [ ] |", enter("- [x] done|"))
    @Test fun T3() = assertEquals("3. three\n4. |", enter("3. three|"))
    @Test fun T3b_followingItemsRenumber() = assertEquals("1. a\n2. |\n3. b", enter("1. a|\n2. b"))
    @Test fun T4() = assertEquals("- on\n- |e", enter("- on|e"))
    @Test fun T5() = assertEquals("- one\n|", enter("- one\n- |"))
    @Test fun T6() = assertEquals("- one\n- |", enter("- one\n    - |"))
    @Test fun T7() {
        assertEquals("> quote\n> |", enter("> quote|"))
        assertEquals("|", enter("> |"))
    }
    @Test fun T8() {
        val indented = Typing.tab(sel("- one|"), outdent = false)
        assertEquals("    - one|", show(indented))
        assertEquals("- one|", show(Typing.tab(indented, outdent = true)))
    }
    @Test fun T9() {
        val s = sel("1. a\n1. b\n5. c|")
        assertEquals("1. a\n2. b\n3. c|", show(Lists.renumber(s)))
        // An edit in the list: pressing Enter after b renumbers the rest.
        assertEquals("1. a\n2. b\n3. |\n4. c", enter("1. a\n2. b|\n3. c"))
    }
    @Test fun T10() = assertEquals("|text", show(Typing.backspace(sel("- |text"))!!))
    @Test fun T11() {
        val on = Typing.toggle(sel("«word»"), Typing.Format.BOLD)
        assertEquals("**«word»**", show(on))
        assertEquals("«word»", show(Typing.toggle(on, Typing.Format.BOLD)))
    }
    @Test fun T12() {
        val on = Typing.toggle(sel("wo|rd"), Typing.Format.BOLD)
        assertEquals("**wo|rd**", show(on))
        assertEquals("wo|rd", show(Typing.toggle(on, Typing.Format.BOLD)))
    }
    @Test fun T11b_allFormatsToggle() {
        for (f in Typing.Format.entries) {
            val on = Typing.toggle(sel("«word»"), f)
            assertNotEquals(f.name, "word", on.text)
            assertTrue(f.name, Typing.active(on, f) || f == Typing.Format.LINK)
            assertEquals(f.name, "word", Typing.toggle(on, f).text)
        }
    }
    @Test fun T13() {
        assertEquals("(«word»)", show(Typing.type(sel("«word»"), '(')!!))
        for (c in "[{\"`*~=") assertNotNull("$c", Typing.type(sel("«word»"), c))
        assertEquals("[«word»]", show(Typing.type(sel("«word»"), '[')!!))
        assertEquals("**«word»**".replace("**", "*").let { "*«word»*" }, show(Typing.type(sel("«word»"), '*')!!))
    }
    @Test fun T14() {
        assertEquals("|", show(Typing.backspace(sel("(|)"))!!))
        assertEquals("(a)|", show(Typing.type(sel("(a|)"), ')')!!))
        assertEquals("(|)", show(Typing.type(sel("|"), '(')!!))
    }
    @Test fun T15() = assertEquals("[word](https://a.b/c)|", show(Typing.paste(sel("«word»"), "https://a.b/c")!!))
    @Test fun T16() {
        assertEquals("```\n|\n```", enter("```|"))
        assertNull("a closing fence is a plain newline", enter("```\nx\n```|"))
    }
    @Test fun T17() {
        var s = sel("- a|")
        s = Typing.toggleTask(s); assertEquals("- [ ] a|", show(s))
        s = Typing.toggleTask(s); assertEquals("- [x] a|", show(s))
        s = Typing.toggleTask(s); assertEquals("- [ ] a|", show(s))
    }
    @Test fun T18() {
        val flipped = Typing.flipTask("- [ ] a\nb", 0)!!
        assertEquals("- [x] a\nb", flipped.text)
        assertEquals("- [ ] a\nb", Typing.flipTask(flipped.text, 0)!!.text)
        assertNull(Typing.flipTask("plain", 0))
    }

    // How the keyboard's own edits are read back through the rules.
    private fun typed(before: String, after: String, composing: Boolean = false) =
        Typing.reconcile(sel(before), after, composing)?.let(::show)

    @Test fun reconcileReadsKeyboardEdits() {
        assertEquals("- one\n- |", typed("- one|", "- one\n"))
        assertEquals("(|)", typed("|", "("))
        assertNull(typed("|", "(", composing = true))
        assertEquals("(«word»)", typed("«word»", "("))
        assertEquals("|", typed("(|)", ")"))
        assertEquals("|text", typed("- |text", "-text"))
        assertEquals("[word](https://a.b)|", typed("«word»", "https://a.b"))
        assertNull(typed("ab|", "abc"))
        assertNull(typed("ab|", "a"))
    }
}
