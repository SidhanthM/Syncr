package dev.syncr.prototype.notes

import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test

/** Scene cases that place margin cards, so they run the core's `layout_margins` (host-only, like the other `*HostTest`s). */
class TextSceneMarginHostTest {
    @Test fun marginNoteSitsBesideItsPassageInTheMargin() = with(TextSceneTest()) {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 10u, 14u, false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.EXPLANATION, "Think of a hash map.", "staff", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block("aaaa\nbbbb\ncccc\ndddd")), items))
        val card = texts(s).first { it.text.startsWith("Think") }
        assertEquals(1600f + 40f + 56f, card.x, 0.01f)
        // The passage starts on the third line: 260 + 2 * 82.
        val dot = s.prims.filterIsInstance<Prim.Fill>().first { it.w == 20f && it.h == 20f }
        assertEquals(260f + 164f + 26f, dot.y, 0.01f)
    }

    @Test fun anchorsUseUtf8OffsetsForNonAsciiText() = with(TextSceneTest()) {
        val text = "é😀 first\nsecond"
        val start = text.toByteArray().size - "second".length
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", start.toUInt(), (start + 6).toUInt(), false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.QUESTION, "why", "r", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block(text)), items))
        val dot = s.prims.filterIsInstance<Prim.Fill>().first { it.w == 20f && it.h == 20f }
        assertEquals(260f + 82f + 26f, dot.y, 0.01f)
    }

    @Test fun pictureWithoutAnEmbedWaitsInTheMargin() = with(TextSceneTest()) {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 5u, false))),
            item("p", CompanionBody.Picture("anchor", "Box", "<svg/>")),
        )
        val place = scene(note(listOf(block("intro\nafter")), items)) { picture }.prims.filterIsInstance<Prim.Picture>().single()
        assertEquals(1640f, place.x, 0f); assertEquals(1000f, place.w, 0f)
    }

    @Test fun narrowerPageMovesTheMarginAndWrapsEarlier() = with(TextSceneTest()) {
        val items = listOf(
            item("anchor", CompanionBody.Anchor(AnchorTarget.Text("tb", 0u, 1u, false))),
            item("m", CompanionBody.MarginNote("anchor", MarginKind.FLAG, "note", "r", MarginState.OPEN, emptyList())),
        )
        val s = scene(note(listOf(block("x", width = 1200f)), items))
        assertEquals(1200f + 40f + 56f, texts(s).first { it.text == "note" }.x, 0.01f)
    }
}
