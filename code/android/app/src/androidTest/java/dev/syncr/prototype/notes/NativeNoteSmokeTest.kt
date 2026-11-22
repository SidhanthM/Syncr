package dev.syncr.prototype.notes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real JNI/JNA gate. Compile is not execution; run only with owner-approved device installation. */
@RunWith(AndroidJUnit4::class)
class NativeNoteSmokeTest {
    @Test fun createDrawEraseUndoRedoCloseAndReopenThroughRust() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "native-smoke-${System.nanoTime()}")
        val core = GeneratedCoreAdapter()
        val library = core.open(root.absolutePath)
        try {
            val request = CreateNoteRequest(newId(), newId(), newId(), "Native smoke", null, NoteKind.NOTE)
            library.create(request)
            val note = library.openNote(request.noteId)
            val channels = SampleChannels(listOf(3400f, 4700f), listOf(12000f, 12100f), listOf(.5f, 1f), listOf(0u, 10u), null, null)
            val mark = newId()
            val add = Command(newId(), listOf(note.snapshot().sheets.single().creationOperationId), CommandBody.Marks(listOf(MarkMutation.AddStroke(
                mark, request.firstSheetId, core.encode(channels), Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)))))
            note.submit(add)
            assertEquals(channels, core.decode(note.samples(mark)))
            val added = note.snapshot()
            val erase = Command(newId(), listOf(add.operationId), CommandBody.Marks(listOf(
                MarkMutation.DeleteStroke(mark, Base.Operation(add.operationId)))))
            note.submit(erase)
            assertTrue(note.snapshot().strokes.single().tombstone)
            val erased = note.snapshot()
            note.submit(Command(newId(), erased.undoDependencies, CommandBody.Undo(erased.undoTarget!!)))
            assertFalse(note.snapshot().strokes.single().tombstone)
            val undone = note.snapshot()
            note.submit(Command(newId(), undone.redoDependencies,
                CommandBody.Redo(undone.redoTarget!!)))
            assertTrue(note.snapshot().strokes.single().tombstone)
            val finalSequence = note.snapshot().localSequence
            assertTrue(finalSequence > added.localSequence)
            note.closeDurably()
            val reopened = library.openNote(request.noteId)
            assertEquals(finalSequence, reopened.snapshot().localSequence)
            assertEquals(channels, core.decode(reopened.samples(mark)))
            assertEquals(CanvasSize(4800f, 3000f, 1600f), canvasSize())
            assertEquals(listOf(0f to 0f), reopened.snapshot().sheets.map { it.originX to it.originY })
            assertTrue(reopened.snapshot().strokes.single().tombstone)
            assertNotNull(reopened.snapshot().undoTarget)
            reopened.closeDurably()
        } finally { library.release(); root.deleteRecursively() }
    }
}
