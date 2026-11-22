package dev.syncr.prototype.notes

import dev.syncr.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real generated bindings/core, temporary libraries only; opt-in -PsyncrNativeHost=true. */
class TextCompatibilityHostTest {
    @Test fun phoneInkHistoryAndExportPreserveIgnoredTextWithoutAllowingTextUndo() = runBlocking {
        val root = Files.createTempDirectory("syncr-phone-text-").toFile()
        val copyRoot = Files.createTempDirectory("syncr-phone-text-copy-").toFile()
        val request = CreateNoteRequest(newId(), newId(), newId(), "Text stays opaque", null, NoteKind.NOTE)
        var expected = emptyList<TextBlockView>()
        val seed = GeneratedCoreAdapter().open(root.absolutePath)
        try {
            seed.create(request)
            val note = seed.openNote(request.noteId)
            try {
                note.submit(Command(newId(), listOf(request.operationId), CommandBody.Marks(listOf(
                    MarkMutation.AddText(newId(), request.firstSheetId, TextRole.DOCUMENT, 10f, 20f, 500f,
                        "Text from the Mac: 日本語 👋", TextStyle(BrushSize.MEDIUM, InkColour.INK)),
                ))))
                expected = note.snapshot().textBlocks
                assertFalse(handwritingHistoryAvailable(note.snapshot(), false))
            } finally { note.closeDurably() }
        } finally { seed.release() }
        val repo = NotesRepository(root.absolutePath)
        try {
            repo.loadLibrary(); repo.open(request.noteId)
            assertNull(repo.state.value.error)
            assertEquals(expected, repo.state.value.snapshot!!.textBlocks)
            assertTrue(CanvasScene().apply { load(repo.state.value) }.previews.isEmpty())
            // A generic phone undo on the text-only history does nothing, including via finger gestures.
            val before = repo.state.value.snapshot!!
            repo.history(false)
            assertEquals(before, repo.state.value.snapshot)
            val samples = SampleChannels(listOf(10f, 20f), listOf(30f, 40f), listOf(.5f, .8f), listOf(0u, 8u), null, null)
            assertTrue(repo.stageStroke(InkPreview(newId(), request.firstSheetId, samples,
                Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK))))
            repo.retry(); assertNull(repo.state.value.error)
            assertTrue(handwritingHistoryAvailable(repo.state.value.snapshot!!, false))
            repo.history(false); assertNull(repo.state.value.error)
            assertEquals(expected, repo.state.value.snapshot!!.textBlocks)
            assertTrue(handwritingHistoryAvailable(repo.state.value.snapshot!!, true))
            repo.history(true); assertNull(repo.state.value.error)
            assertEquals(expected, repo.state.value.snapshot!!.textBlocks)
            val export = File(copyRoot, "${request.noteId}.syncr")
            assertNotNull(repo.exportCopy(request.noteId, export.absolutePath))
            assertTrue(repo.leave())
            repo.open(request.noteId)
            assertEquals(expected, repo.state.value.snapshot!!.textBlocks)
            val copied = GeneratedCoreAdapter().open(copyRoot.absolutePath)
            try {
                copied.openNote(request.noteId).let { note ->
                    try { assertEquals(expected, note.snapshot().textBlocks) } finally { note.closeDurably() }
                }
            } finally { copied.release() }
        } finally {
            repo.leave(discard = true); repo.releaseLibrary()
            root.deleteRecursively(); copyRoot.deleteRecursively()
        }
    }
}
