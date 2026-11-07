package dev.syncr.prototype.notes

import dev.syncr.core.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Real core, temporary library only; opt-in -PsyncrNativeHost=true. A note the Mac wrote reads back as text and comments. */
class NoteTextHostTest {
    private val svg = "<svg viewBox=\"0 0 320 120\"><text fill=\"var(--ink)\" font-family=\"var(--font-voice)\" font-size=\"16\" x=\"18\" y=\"28\">Boxes</text>" +
        "<g data-caption=\"One box.\" data-step=\"1\"><rect x=\"30\" y=\"50\" width=\"100\" height=\"40\" fill=\"none\" stroke=\"var(--soft)\"/></g></svg>"

    @Test fun macTextAndCompanionContentReadBackAndInkNeverTouchesIt() {
        val root = Files.createTempDirectory("syncr-phone-reader-").toFile()
        val request = CreateNoteRequest(newId(), newId(), newId(), "Two sum", null)
        val adapter = GeneratedCoreAdapter(); val core = adapter.open(root.absolutePath)
        try {
            core.create(request)
            val note = core.openNote(request.noteId)
            try {
                val blockId = newId()
                val text = "Two sum: find a pair.\nUse a map."
                note.submit(Command(newId(), listOf(request.operationId), CommandBody.Marks(listOf(
                    MarkMutation.AddText(blockId, request.firstSheetId, TextRole.DOCUMENT, 0f, 0f, 1600f, text, TextStyle(BrushSize.MEDIUM, InkColour.INK))))))
                val block = note.snapshot().textBlocks.single()
                val anchor = newId(); val margin = newId(); val ask = newId(); val answer = newId(); val picture = newId()
                note.submit(Command(newId(), listOf(block.head.operationId), CommandBody.CompanionMarks(listOf(
                    CompanionMutation(anchor, Base.Absent, false, CompanionBody.Anchor(AnchorTarget.Text(blockId, 0u, 21u, false)))))))
                val a = note.snapshot().companionItems.single { it.id == anchor }
                note.submit(Command(newId(), listOf(a.head.operationId), CommandBody.CompanionMarks(listOf(
                    CompanionMutation(margin, Base.Absent, false, CompanionBody.MarginNote(anchor, MarginKind.EXPLANATION, "Hash maps give O(1) lookups.", "staff", MarginState.OPEN, emptyList())),
                    CompanionMutation(picture, Base.Absent, false, CompanionBody.Picture(anchor, "Boxes", filterPicture(svg)))))))
                val m = note.snapshot().companionItems.single { it.id == margin }
                note.submit(Command(newId(), listOf(m.head.operationId), CommandBody.CompanionMarks(listOf(
                    CompanionMutation(ask, Base.Absent, false, CompanionBody.ThreadMessage(margin, 0u, MessageWho.YOU, "How do I do it?", emptyList())),
                    CompanionMutation(answer, Base.Absent, false, CompanionBody.ThreadMessage(margin, 1u, MessageWho.COMPANION,
                        "Store each number:\n\n- seen = {}\n- check `target - n`\n\n```python\nseen = {}\n```", emptyList()))))))

                val snapshot = note.snapshot()
                val scene = NoteTextSceneBuilder(FakeShaper()) { s -> try { pictureDisplayList(s) } catch (_: Exception) { null } }.build(snapshot)
                val texts = scene.prims.filterIsInstance<Prim.Text>().map { it.text }
                assertTrue(texts.contains("Two sum"))
                assertTrue(texts.contains("Use a map."))
                assertTrue(texts.any { it.startsWith("Hash maps") })
                assertTrue(texts.contains("How do I do it?") && texts.contains("Companion"))
                assertTrue(texts.contains("seen = {}")) // the fenced code block
                assertTrue(texts.contains("target - n")) // inline code
                assertEquals(1, scene.prims.filterIsInstance<Prim.Picture>().size)
                assertEquals(1, scene.pictures.size)

                // New ink leaves the text and companion items byte for byte as they were.
                val before = snapshot.textBlocks to snapshot.companionItems
                val samples = SampleChannels(listOf(10f, 20f), listOf(30f, 40f), listOf(.5f, .8f), listOf(0u, 8u), null, null)
                note.submit(Command(newId(), listOf(snapshot.sheets.first().creationOperationId), CommandBody.Marks(listOf(
                    MarkMutation.AddStroke(newId(), snapshot.sheets.first().id, adapter.encode(samples), Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK))))))
                val after = note.snapshot()
                assertEquals(before, after.textBlocks to after.companionItems)
            } finally { note.closeDurably() }
        } finally { core.release(); root.deleteRecursively() }
    }
}
