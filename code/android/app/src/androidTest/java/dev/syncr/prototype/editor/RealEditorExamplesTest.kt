package dev.syncr.prototype.editor

import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import dev.syncr.prototype.notes.CanvasTextInput
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Every numbered example of tasks/contracts/editor-v2.md §3.6 (S) and §4 (T), driven through the real editing field
 * ([CanvasTextInput] and its `EditText`, with its styling and undo history), on a device. Keys and characters arrive as a
 * keyboard sends them: `commitText` on the field's input connection, and key events for Enter, Backspace and Tab. The
 * toolbar's buttons and the slash menu's rows run the same functions the Compose closures call (`apply`), so they are
 * driven here as `apply` of the same rule. Run with `am instrument` on the emulator; see the phone-finish report.
 */
class RealEditorExamplesTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: FrameLayout
    private lateinit var input: CanvasTextInput
    private lateinit var connection: InputConnection

    @Before fun setUp() {
        compose.setContent { AndroidView(factory = { ctx -> FrameLayout(ctx).also { host = it } }, modifier = Modifier.fillMaxSize()) }
        compose.waitForIdle()
        onMain { input = CanvasTextInput(host) }
    }

    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block); instrumentation.waitForIdleSync() }
    private val field get() = host.getChildAt(0) as EditText

    /** `|` is the caret, `«…»` a selection, as in the contract. */
    private fun sel(marked: String): TextSel {
        val a = marked.indexOf('«'); val b = marked.indexOf('»')
        if (a >= 0) return TextSel(marked.replace("«", "").replace("»", ""), a, b - 1)
        return TextSel(marked.replace("|", ""), marked.indexOf('|'))
    }
    private fun show(s: TextSel): String =
        if (s.start == s.end) s.text.substring(0, s.start) + "|" + s.text.substring(s.start)
        else s.text.substring(0, s.start) + "«" + s.text.substring(s.start, s.end) + "»" + s.text.substring(s.end)

    private fun open(marked: String) {
        val s = sel(marked)
        onMain {
            if (input.active) input.end()
            input.begin(s.text, RectF(0f, 0f, 1400f, 4000f), null, EditorExtras(), false)
            input.apply(s, step = false)
        }
        onMain { connection = field.onCreateInputConnection(EditorInfo())!! }
    }
    private fun now() = show(input.current())

    // How a keyboard sends things.
    private fun type(text: String) = onMain { for (c in text) connection.commitText(c.toString(), 1) }
    private fun commit(text: String) = onMain { connection.commitText(text, 1) }
    private fun key(code: Int, meta: Int = 0) = onMain {
        val t = SystemClock.uptimeMillis()
        field.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        field.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }
    private fun enter() = key(KeyEvent.KEYCODE_ENTER)
    private fun backspace() = key(KeyEvent.KEYCODE_DEL)
    private fun apply(next: (TextSel) -> TextSel) = onMain { input.apply(next(input.current())) }

    /** Enter on the menu's first row, as the panel does: the text as it is now, the token at the caret, the row. */
    private fun slashEnter(marked: String, expected: String): SlashResult {
        open(marked)
        val live = input.current()
        val token = requireNotNull(Slash.token(live.text, live.start)) { "no menu in $marked" }
        val row = Slash.matches(token.query).first()
        assertEquals("first row of $marked", expected, row.name)
        val result = Slash.run(live, token, row, "2026-11-16", "14:05")
        onMain { input.apply(result.edit) }
        return result
    }
    private fun menuAt(marked: String): List<String> {
        open(marked)
        val live = input.current()
        return Slash.matches(Slash.token(live.text, live.start)!!.query).map { it.name }
    }

    // ---- §3.6 ----
    @Test fun S1() {
        open("|"); type("/")
        assertEquals("/|", now())
        val live = input.current()
        assertEquals("", Slash.token(live.text, live.start)!!.query)
        assertEquals(Slash.commands, Slash.matches(""))
    }
    @Test fun S2() { slashEnter("Buy milk /to|", "todo"); assertEquals("- [ ] Buy milk|", now()) }
    @Test fun S3() { slashEnter("/h2|", "h2"); assertEquals("## |", now()) }
    @Test fun S4() { slashEnter("## Title /h1|", "h1"); assertEquals("# Title|", now()) }
    @Test fun S5() { slashEnter("- item /text|", "text"); assertEquals("item|", now()) }
    @Test fun S6() { slashEnter("3. step /bullet|", "bullet"); assertEquals("- step|", now()) }
    @Test fun S7() { slashEnter("    - nested /todo|", "todo"); assertEquals("    - [ ] nested|", now()) }
    @Test fun S8() { slashEnter("> quoted /h3|", "h3"); assertEquals("### quoted|", now()) }
    @Test fun S9() { slashEnter("Some text /code|", "code"); assertEquals("Some text\n```\n|\n```", now()) }
    @Test fun S10() { slashEnter("/divider|", "divider"); assertEquals("---\n|", now()) }
    @Test fun S11() { slashEnter("Text /divider|", "divider"); assertEquals("Text\n---\n|", now()) }
    @Test fun S12() { slashEnter("Meet on /date|", "date"); assertEquals("Meet on 2026-11-16|", now()) }
    @Test fun S13() { slashEnter("see /link|", "link"); assertEquals("see [«text»](url)", now()) }
    @Test fun S14() {
        // Typed as a keyboard does, then the spots the contract lists.
        open("|"); type("word/h1")
        assertNull(Slash.token(input.current().text, input.current().start))
        for (marked in listOf("http://a.b/h|", "```\n/h|\n```", "`a /h|`", "[x](/h|)")) {
            open(marked)
            assertNull(marked, Slash.token(input.current().text, input.current().start))
        }
    }
    @Test fun S15() {
        open("|"); type("/zz")
        val live = input.current()
        assertTrue(Slash.matches(Slash.token(live.text, live.start)!!.query).isEmpty())
        assertEquals("/zz|", now())
    }
    @Test fun S16() {
        val menu = SlashMenu()
        open("|"); type("/h")
        assertNotNull(menu.update(input.current()))
        menu.escape(); type("1")
        assertEquals("/h1|", now())
        assertNull(menu.update(input.current()))
    }
    @Test fun S17() { slashEnter("a /bold|", "bold"); assertEquals("a **|**", now()) }
    @Test fun S18() {
        val r = slashEnter("Why is V negative? /ask|", "ask")
        assertEquals("Why is V negative?|", now())
        assertEquals(SlashAction.ASK, r.action)
    }
    @Test fun S19() {
        assertEquals("todo", menuAt("/to|").first())
        assertTrue("todo" in menuAt("/ch|"))
        assertEquals(listOf("h1", "h2", "h3"), menuAt("/h|").take(3))
    }
    @Test fun S20() {
        slashEnter("Buy milk /to|", "todo")
        assertEquals("- [ ] Buy milk|", now())
        onMain { input.undo() }
        assertEquals("Buy milk /to|", now())
        onMain { input.redo() }
        assertEquals("- [ ] Buy milk|", now())
    }

    // ---- §4 ----
    @Test fun T1() { open("- one|"); enter(); assertEquals("- one\n- |", now()) }
    @Test fun T2() { open("- [x] done|"); enter(); assertEquals("- [x] done\n- [ ] |", now()) }
    @Test fun T3() { open("3. three|"); enter(); assertEquals("3. three\n4. |", now()) }
    @Test fun T4() { open("- on|e"); enter(); assertEquals("- on\n- |e", now()) }
    @Test fun T5() { open("- one\n- |"); enter(); assertEquals("- one\n|", now()) }
    @Test fun T6() { open("- one\n    - |"); enter(); assertEquals("- one\n- |", now()) }
    @Test fun T7() {
        open("> quote|"); enter(); assertEquals("> quote\n> |", now())
        open("> |"); enter(); assertEquals("|", now())
    }
    @Test fun T8() {
        open("- one|"); key(KeyEvent.KEYCODE_TAB); assertEquals("    - one|", now())
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON); assertEquals("- one|", now())
    }
    @Test fun T9() {
        open("1. a\n1. b\n5. c|"); enter()
        assertEquals("1. a\n2. b\n3. c\n4. |", now())
    }
    @Test fun T10() { open("- |text"); backspace(); assertEquals("|text", now()) }
    @Test fun T11() {
        open("«word»")
        apply { Typing.toggle(it, Typing.Format.BOLD) }; assertEquals("**«word»**", now())
        apply { Typing.toggle(it, Typing.Format.BOLD) }; assertEquals("«word»", now())
    }
    @Test fun T12() {
        open("wo|rd")
        apply { Typing.toggle(it, Typing.Format.BOLD) }; assertEquals("**wo|rd**", now())
        apply { Typing.toggle(it, Typing.Format.BOLD) }; assertEquals("wo|rd", now())
    }
    @Test fun T13() {
        for ((c, pair) in mapOf("(" to "()", "[" to "[]", "{" to "{}", "\"" to "\"\"", "`" to "``", "*" to "**", "~" to "~~", "=" to "==")) {
            open("«word»"); commit(c)
            assertEquals(c, "${pair[0]}«word»${pair[1]}", now())
        }
    }
    @Test fun T14() {
        open("(|)"); backspace(); assertEquals("|", now())
        open("(a|)"); commit(")"); assertEquals("(a)|", now())
        open("|"); commit("("); assertEquals("(|)", now())
    }
    @Test fun T15() {
        // A real paste (the field's own paste action over the clipboard), then the keyboard's clipboard suggestion (a commit).
        val clipboard = instrumentation.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        onMain { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", "https://a.b/c")) }
        open("«word»"); onMain { field.onTextContextMenuItem(android.R.id.paste) }
        assertEquals("[word](https://a.b/c)|", now())
        open("«word»"); commit("https://a.b/c"); assertEquals("[word](https://a.b/c)|", now())
    }
    @Test fun T16() { open("```|"); enter(); assertEquals("```\n|\n```", now()) }
    @Test fun T17() {
        open("- a|")
        apply(Typing::toggleTask); assertEquals("- [ ] a|", now())
        apply(Typing::toggleTask); assertEquals("- [x] a|", now())
        apply(Typing::toggleTask); assertEquals("- [ ] a|", now())
    }
    @Test fun T18() {
        // The caret is on the second line; the box of the first is rendered, and a tap on it flips it.
        open("- [ ] a\nb|")
        val box = FloatArray(2)
        onMain {
            val layout = field.layout!!
            box[0] = layout.getPrimaryHorizontal(0) + 12f
            box[1] = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        }
        val before = input.current()
        onMain {
            val t = SystemClock.uptimeMillis()
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
                val e = MotionEvent.obtain(t, at, action, box[0], box[1], 0)
                field.dispatchTouchEvent(e); e.recycle()
            }
        }
        assertEquals("- [x] a\nb|", now())
        assertEquals("the caret did not move", before.start, input.current().start)
    }
}
