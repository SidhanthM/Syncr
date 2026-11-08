package dev.syncr.prototype.notes

import dev.syncr.core.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MobileTextHostTest {
    @Test fun phoneEditsMacTextAndPersistsContextualResponse() {
        val root = Files.createTempDirectory("syncr-mobile-edit-").toFile()
        val core = GeneratedCoreAdapter().open(root.absolutePath)
        val create = CreateNoteRequest(newId(), newId(), newId(), "Mobile typing", null)
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
}
