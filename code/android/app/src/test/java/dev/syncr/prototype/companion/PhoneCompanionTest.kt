package dev.syncr.prototype.companion

import org.junit.Assert.*
import org.junit.Test

class PhoneCompanionTest {
    private val note = "# Lagrangians\n\nL = T - V, then vary the action.\n\n\nWhy is the sign of V negative here?\n"

    private fun passage(start: Int, end: Int = start) = PhoneCompanion.passage(note, start, end)?.let { note.substring(it.start, it.end) }

    @Test fun passageIsTheSelectionElseTheParagraphAtTheCaretElseTheNearestBefore() {
        val a = note.indexOf("vary")
        assertEquals("vary the action", passage(a, a + 15))
        assertEquals("L = T - V, then vary the action.", passage(a))
        // A blank line: the paragraph before it. The very start: the first paragraph.
        assertEquals("L = T - V, then vary the action.", passage(note.indexOf("\n\n\n") + 2))
        assertEquals("# Lagrangians", passage(0))
        assertEquals("Why is the sign of V negative here?", passage(note.length))
        assertEquals("Why is the sign of V negative here?", passage(note.length + 50, -3))
        // A selection of spaces only falls back to the paragraph; no words at all is no passage.
        assertEquals("first", PhoneCompanion.passage("\n\n  first\n", 0, 2)?.let { "\n\n  first\n".substring(it.start, it.end) })
        assertNull(PhoneCompanion.passage("", 0, 0)); assertNull(PhoneCompanion.passage(" \n \n", 1, 1))
    }

    @Test fun requestCarriesTheNoteThePassageAndTheQuestion() {
        val span = PhoneCompanion.passage(note, note.indexOf("Why"), note.indexOf("Why"))
        val r = PhoneCompanion.request("Mechanics", note, span, "  ", picture = true)
        assertTrue(r.startsWith("Note title: Mechanics\nNote text:\n<<<\n# Lagrangians"))
        assertTrue(r.contains("Passage the question is about:\n<<<\nWhy is the sign of V negative here?\n>>>"))
        assertTrue(r.contains("Question: Explain this.\n")) // an empty question explains the passage
        assertTrue(r.endsWith("The user asked for a picture with the answer.\n"))
        val plain = PhoneCompanion.request("T", "", null, "What is a Lagrangian?", picture = false)
        assertFalse(plain.contains("Passage")); assertFalse(plain.contains("picture")); assertTrue(plain.contains("Question: What is a Lagrangian?"))
    }

    @Test fun aLongNoteIsSentAsAWindowRoundThePassageOnWholeCharacters() {
        val long = "😀".repeat(20_000) + "\nTHE PASSAGE\n" + "é".repeat(30_000)
        val at = long.indexOf("THE PASSAGE")
        val r = PhoneCompanion.request("T", long, PhoneCompanion.Span(at, at + 11), "why", false)
        assertTrue(r.contains("Note text (part of a longer note):"))
        assertTrue(r.length < PhoneCompanion.MAX_NOTE_CHARS + 400)
        assertTrue(r.contains("\nTHE PASSAGE\n"))
        assertFalse(Regex("[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]").containsMatchIn(r)) // no split emoji
    }

    @Test fun answerSplitsIntoWordsAndOnePicture() {
        val svg = "<svg viewBox=\"0 0 320 80\"><title>Two boxes</title><rect x=\"16\" y=\"16\" width=\"80\" height=\"40\"/></svg>"
        val fenced = PhoneCompanion.parse("It is negative because V is stored.\n\n- first\n- second\n\n```svg\n$svg\n```\n")
        assertEquals("It is negative because V is stored.\n\n- first\n- second", fenced.text)
        assertEquals(svg, fenced.svg)
        val bare = PhoneCompanion.parse("Before.\n$svg\nAfter.")
        assertEquals("Before.\n\nAfter.", bare.text); assertEquals(svg, bare.svg)
        // Code fences that are not the picture's stay; a picture that never closed is dropped and its words kept.
        val code = PhoneCompanion.parse("Use a map.\n\n```python\nseen = {}\n```")
        assertEquals("Use a map.\n\n```python\nseen = {}\n```", code.text); assertNull(code.svg)
        val cut = PhoneCompanion.parse("Words first.\n```svg\n<svg viewBox=\"0 0 320 80\"><rect")
        assertEquals("Words first.", cut.text); assertNull(cut.svg)
        assertEquals(PhoneCompanion.Answer("", svg), PhoneCompanion.parse("```svg\n$svg\n```"))
    }

    @Test fun previewHidesThePictureWhileItIsBeingWritten() {
        assertEquals("Half a sen" to false, PhoneCompanion.preview("Half a sen"))
        assertEquals("Words." to true, PhoneCompanion.preview("Words.\n\n```svg\n<svg viewBox"))
        assertEquals("Words." to true, PhoneCompanion.preview("Words.\n<svg viewBox=\"0 0 320 80\"><rect"))
        assertEquals("```python\nx = 1" to false, PhoneCompanion.preview("```python\nx = 1"))
    }

    @Test fun pictureTitleComesFromThePictureThenTheQuestion() {
        assertEquals("Two boxes", PhoneCompanion.pictureTitle("<svg><title> Two boxes </title></svg>", "draw it"))
        assertEquals("Draw the pendulum", PhoneCompanion.pictureTitle("<svg></svg>", "Draw the pendulum\nplease"))
        assertEquals("Picture", PhoneCompanion.pictureTitle("<svg></svg>", "  "))
    }

    @Test fun streamReportsWordsAsTheyArriveAndNamesUsageLimits() {
        val seen = ArrayList<String>()
        var clock = 0L
        val stream = ResponseStream(seen::add, everyMs = 50) { clock }
        fun delta(s: String) = stream.event("""{"type":"response.output_text.delta","delta":"$s"}""")
        clock = 100; delta("One ")
        clock = 120; delta("two ") // too soon to show
        clock = 160; delta("three")
        assertEquals(listOf("One ", "One two three"), seen)
        stream.event("""{"type":"response.completed","response":{"status":"completed"}}""")
        assertEquals("One two three", stream.result())
        val limit = assertThrows(IllegalStateException::class.java) {
            ResponseStream().event("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        }
        assertTrue(limit.message!!.contains("usage limit is reached"))
        assertThrows(IllegalStateException::class.java) { ResponseStream().also { it.event("""{"type":"response.output_text.delta","delta":"x"}""") }.result() }
    }
}
