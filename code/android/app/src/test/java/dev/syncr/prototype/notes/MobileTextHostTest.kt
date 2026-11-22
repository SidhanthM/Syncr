package dev.syncr.prototype.notes

import dev.syncr.core.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MobileTextHostTest {
    @Test fun phoneEditsMacTextAndPersistsContextualResponse() {
        val root = Files.createTempDirectory("syncr-mobile-edit-").toFile()
        val core = GeneratedCoreAdapter().open(root.absolutePath)
        val create = CreateNoteRequest(newId(), newId(), newId(), "Mobile typing", null, NoteKind.NOTE)
        try {
            core.create(create)
            val note = core.openNote(create.noteId)
            try {
                val empty = TextCommands.draft(note.snapshot())
                note.submit(requireNotNull(TextCommands.save(note.snapshot(), empty, "Mac café 😀\nSecond line", ::newId)))
                val draft = TextCommands.draft(note.snapshot())
                note.submit(requireNotNull(TextCommands.save(note.snapshot(), draft, "Mac café 😀!\nSecond line", ::newId)))
                assertEquals("Mac café 😀!\nSecond line", note.snapshot().textBlocks.single().text)
                assertThrows(IllegalArgumentException::class.java) { TextCommands.save(note.snapshot(), draft, "stale overwrite", ::newId) }
                val current = TextCommands.draft(note.snapshot())
                val command = TextCommands.explanation(note.snapshot(), current, 4, 11, "An accented word and an emoji.", ::newId)
                note.submit(command)
                var snapshot = note.snapshot()
                val anchor = snapshot.companionItems.first { it.body is CompanionBody.Anchor }.body as CompanionBody.Anchor
                val target = anchor.target as AnchorTarget.Text
                assertEquals("café 😀", current.text.substring(4, 11))
                assertEquals(4u, target.start); assertEquals(14u, target.end)
                var margin = snapshot.companionItems.first { it.body is CompanionBody.MarginNote }
                note.submit(TextCommands.state(snapshot, margin.id, margin.head.operationId, MarginState.PINNED, ::newId))
                snapshot = note.snapshot(); margin = snapshot.companionItems.first { it.id == margin.id }
                assertEquals(MarginState.PINNED, (margin.body as CompanionBody.MarginNote).state)
                val scene = NoteTextSceneBuilder(FakeShaper()) { null }.build(snapshot)
                assertTrue(scene.hits.any { !it.target.response && it.target.id == current.blockId })
                assertTrue(scene.hits.any { it.target.response && it.target.id == margin.id })
                val append = TextCommands.draft(snapshot)
                note.submit(requireNotNull(TextCommands.save(snapshot, append, append.text + "\n\nAn explanation", ::newId)))
                snapshot = note.snapshot(); margin = snapshot.companionItems.first { it.id == margin.id }
                note.submit(TextCommands.state(snapshot, margin.id, margin.head.operationId, MarginState.DISMISSED, ::newId))
                assertFalse(NoteTextSceneBuilder(FakeShaper()) { null }.build(note.snapshot()).hits.any { it.target.id == margin.id })
            } finally { note.closeDurably() }
            val reopened = core.openNote(create.noteId)
            try {
                assertTrue(reopened.snapshot().textBlocks.single().text.endsWith("An explanation"))
                assertEquals(MarginState.DISMISSED, (reopened.snapshot().companionItems.first { it.body is CompanionBody.MarginNote }.body as CompanionBody.MarginNote).state)
            } finally { reopened.closeDurably() }
        } finally { core.release(); root.deleteRecursively() }
    }

    /** An ask saved on the phone has the Mac's shape, so both devices show it under its passage; its picture passes the core's filter. */
    @Test fun askIsStoredAsTheMacStoresItWithItsPicture() {
        val svg = "<svg viewBox=\"0 0 320 120\"><text fill=\"var(--ink)\" font-family=\"var(--font-voice)\" font-size=\"16\" x=\"18\" y=\"28\">Boxes</text>" +
            "<g data-caption=\"One box.\" data-step=\"1\"><rect x=\"30\" y=\"50\" width=\"100\" height=\"40\" fill=\"none\" stroke=\"var(--soft)\"/></g></svg>"
        val root = Files.createTempDirectory("syncr-mobile-ask-").toFile()
        val core = GeneratedCoreAdapter().open(root.absolutePath)
        val create = CreateNoteRequest(newId(), newId(), newId(), "Asking", null, NoteKind.NOTE)
        try {
            core.create(create)
            val note = core.openNote(create.noteId)
            try {
                val text = "Café 😀 first line\nWhy is V negative?\nLast line"
                note.submit(requireNotNull(TextCommands.save(note.snapshot(), TextCommands.draft(note.snapshot()), text, ::newId)))
                val draft = TextCommands.draft(note.snapshot())
                val span = dev.syncr.prototype.companion.PhoneCompanion.passage(text, text.indexOf("Why"), text.indexOf("Why"))!!
                val parsed = dev.syncr.prototype.companion.PhoneCompanion.parse("Because it is stored energy.\n\n```svg\n$svg\n```")
                val clean = filterPicture(parsed.svg!!)
                val ask = TextCommands.ask(note.snapshot(), draft, span.start, span.end, "Why negative?", parsed.text, "Boxes" to clean, ::newId)
                note.submit(ask.command)
                note.submit(requireNotNull(TextCommands.embed(note.snapshot(), draft.blockId!!, ask.pictureId!!, span.end, ::newId)))
                val s = note.snapshot()
                val thread = s.companionItems.first { it.id == ask.threadId }.body as CompanionBody.MarginNote
                assertEquals(MarginKind.QUESTION, thread.kind); assertEquals("You", thread.reader); assertEquals(MarginState.OPEN, thread.state)
                val anchor = (s.companionItems.first { it.id == thread.anchorId }.body as CompanionBody.Anchor).target as AnchorTarget.Text
                assertEquals("Why is V negative?", String(text.toByteArray().copyOfRange(anchor.start.toInt(), anchor.end.toInt())))
                val messages = s.companionItems.mapNotNull { it.body as? CompanionBody.ThreadMessage }.filter { it.marginId == ask.threadId }.sortedBy { it.order }
                assertEquals(listOf(MessageWho.YOU to "Why negative?", MessageWho.COMPANION to "Because it is stored energy."), messages.map { it.who to it.text })
                val picture = s.companionItems.first { it.id == ask.pictureId }.body as CompanionBody.Picture
                assertEquals(clean, picture.svg); assertEquals("Boxes", picture.title)
                // The embed line sits after the passage's own line, before the next.
                assertEquals("Café 😀 first line\nWhy is V negative?\n![[syncr-drawing:${ask.pictureId}]]\n\nLast line", s.textBlocks.single().text)
                // The phone's own scene draws the answer and the picture.
                val scene = NoteTextSceneBuilder(FakeShaper()) { source -> pictureDisplayList(source) }.build(s)
                assertTrue(scene.pictures.containsKey(ask.pictureId))
                // A stale draft never saves an answer over changed text.
                assertThrows(IllegalArgumentException::class.java) { TextCommands.ask(s, draft, span.start, span.end, "again", "x", null, ::newId) }
            } finally { note.closeDurably() }
        } finally { core.release(); root.deleteRecursively() }
    }
}
