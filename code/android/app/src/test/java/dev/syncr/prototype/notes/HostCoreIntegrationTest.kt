package dev.syncr.prototype.notes

import dev.syncr.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Opt-in -PsyncrNativeHost=true. Uses the SAME production adapter and real Rust, no fake. */
class HostCoreIntegrationTest {
    @Test fun olderSheetCanBeWrittenAfterStructureChangesUsingExposedHead() = runBlocking {
        val root = Files.createTempDirectory("syncr-sheet-dependency-").toFile()
        var repo = NotesRepository(root.absolutePath)
        try {
            repo.create("Sheet dependencies", PageFormat.SHEET16X10); assertNull(repo.state.value.error)
            val first = repo.state.value.snapshot!!.sheets.single()
            repeat(2) { repo.addSheet(); assertNull(repo.state.value.error) }
            val original = repo.state.value.snapshot!!
            val provenance = original.sheets.associate { it.id to it.creationOperationId }
            assertEquals(original.metadataHead.operationId, first.creationOperationId)
            assertEquals(3, provenance.values.toSet().size)
            repo.rename("Before reorder"); assertNull(repo.state.value.error)
            assertTrue(repo.leave()); repo.releaseLibrary()
            // Reorder is core-supported, not a new frontend feature. Use the public adapter
            // while the repository is closed; no SQL or inferred sheet-operation index.
            val library = GeneratedCoreAdapter().open(root.absolutePath)
            try {
                val note = library.openNote(original.noteId)
                try {
                    for (order in listOf(original.sheets.reversed(), original.sheets.drop(1) + original.sheets.first())) {
                        val head = note.snapshot().structureHead.operationId
                        note.submit(Command(newId(), listOf(head), CommandBody.ReorderSheets(order.map { it.id }, Base.Operation(head))))
                    }
                } finally { note.closeDurably() }
            } finally { library.release() }
            repo = NotesRepository(root.absolutePath)
            repo.open(original.noteId); assertNull(repo.state.value.error)
            assertEquals(original.sheets.drop(1).map { it.id } + first.id, repo.state.value.snapshot!!.sheets.map { it.id })
            repo.rename("After reorder"); assertNull(repo.state.value.error)
            assertTrue(repo.leave()); repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath)
            repo.open(original.noteId); assertNull(repo.state.value.error)
            assertEquals("After reorder", repo.state.value.snapshot!!.title)
            val reopened = repo.state.value.snapshot!!
            assertEquals(provenance, reopened.sheets.associate { it.id to it.creationOperationId })
            val samples = SampleChannels(listOf(10f), listOf(20f), listOf(.5f), listOf(0u), null, null)
            for (sheet in reopened.sheets) {
                val sequence = repo.state.value.snapshot!!.localSequence
                assertTrue(repo.stageStroke(InkPreview(newId(), sheet.id, samples, Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK))))
                assertEquals(listOf(provenance.getValue(sheet.id)), repo.state.value.pending.single().dependencies)
                repo.retry()
                assertNull("Every original/new sheet must remain writable using exposed creation provenance", repo.state.value.error)
                assertFalse(repo.state.value.unsaved)
                assertEquals(sequence + 1u, repo.state.value.snapshot!!.localSequence)
            }
            assertTrue(repo.leave()); repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath)
            repo.open(original.noteId); assertNull(repo.state.value.error)
            assertEquals(provenance.keys, repo.state.value.snapshot!!.strokes.map { it.sheetId }.toSet())
            assertEquals(3, repo.state.value.snapshot!!.strokes.size)
            assertEquals(provenance, repo.state.value.snapshot!!.sheets.associate { it.id to it.creationOperationId })
        } finally { repo.leave(discard = true); repo.releaseLibrary(); root.deleteRecursively() }
    }
    /** Only injects delivery failures; all successful operations/lookup/backup use real Rust. */
    private class DeliveryProbe(private val real: CoreAdapter = GeneratedCoreAdapter()) : CoreAdapter by real {
        val submitted = mutableListOf<Command>()
        var failBeforeSubmit = false
        var loseReply = false
        override fun open(root: String): LibraryAccess {
            val library = real.open(root)
            return object : LibraryAccess by library {
                override fun openNote(id: String): NoteAccess {
                    val note = library.openNote(id)
                    return object : NoteAccess by note {
                        override fun submit(command: Command): Receipt {
                            submitted += command
                            if (failBeforeSubmit) throw CoreException.StorageIo("Injected delivery failure", true)
                            val receipt = note.submit(command)
                            if (loseReply) throw CoreException.StorageIo("Injected lost reply", true)
                            return receipt
                        }
                    }
                }
            }
        }
    }
    @Test fun exactHistoryDependenciesRemainImmutableAcrossRetryExportAndLostReply() = runBlocking {
        val root = Files.createTempDirectory("syncr-history-dependency-").toFile()
        val probe = DeliveryProbe()
        var repo = NotesRepository(root.absolutePath, probe)
        try {
            repo.create("Exact history"); assertNull(repo.state.value.error)
            val initial = repo.state.value.snapshot!!
            val samples = SampleChannels(listOf(10f), listOf(20f), listOf(.5f), listOf(0u), null, null)
            val marks = List(4) { newId() }
            marks.forEach { mark ->
                assertTrue(repo.stageStroke(InkPreview(mark, initial.sheets.single().id, samples,
                    Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK))))
                repo.retry(); assertNull(repo.state.value.error)
            }
            // Two affected marks share one head; two unrelated heads must never be sent.
            assertTrue(repo.stageErase(marks.take(2).toSet())); repo.retry(); assertNull(repo.state.value.error)
            assertTrue(repo.leave()); repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath, probe)
            repo.open(initial.noteId); assertNull(repo.state.value.error)
            for ((index, redo) in listOf(false, true, false).withIndex()) {
                val before = repo.state.value.snapshot!!
                val target = (if (redo) before.redoTarget else before.undoTarget)!!
                val expected = before.strokes.filter { it.id in marks.take(2) }.map { it.head.operationId }.distinct().sorted()
                assertEquals(1, expected.size)
                assertEquals(expected, if (redo) before.redoDependencies else before.undoDependencies)
                if (index > 0) assertFalse(expected.contains(target)) // Original action is NOT an extra dependency.
                probe.failBeforeSubmit = index < 2
                probe.loseReply = index == 2
                repo.history(redo)
                assertTrue(repo.state.value.unsaved); assertNotNull(repo.state.value.error)
                val command = repo.state.value.pending.single().command!!
                assertEquals(expected, command.dependencies)
                assertEquals(if (redo) CommandBody.Redo(target) else CommandBody.Undo(target), command.body)
                repo.refresh() // Even a committed-but-lost reply must not rebase the pending command.
                repo.exportRecovery { archive ->
                    assertNotNull(archive.backup)
                    assertSame(command, archive.commands.single())
                }
                val submissions = probe.submitted.size
                probe.failBeforeSubmit = false; probe.loseReply = false
                repo.retry(); assertNull(repo.state.value.error); assertFalse(repo.state.value.unsaved)
                if (index == 2) assertEquals(submissions, probe.submitted.size) // Durable lookup, no second submit.
                else assertSame(command, probe.submitted.last())
                val after = repo.state.value.snapshot!!
                assertEquals(before.localSequence + 1u, after.localSequence)
                assertTrue(after.strokes.filter { it.id in marks.take(2) }.all { it.tombstone == redo })
                assertTrue(after.strokes.filter { it.id in marks.drop(2) }.all { !it.tombstone })
            }
        } finally { repo.leave(discard = true); repo.releaseLibrary(); root.deleteRecursively() }
    }
    @Test fun productionRepositoryCreateInkAtomicEraseHistorySheetsRenameExportAndReopen() = runBlocking {
        val root = Files.createTempDirectory("syncr-android-host-").toFile()
        val channels = SampleChannels(listOf(10f, 20f), listOf(20f, 30f), listOf(.5f, 1f), listOf(0u, 10u), null, null)
        val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        var repo = NotesRepository(root.absolutePath)
        fun assertHealthy() { assertNull(repo.state.value.error) }
        try {
            repo.loadLibrary(); assertHealthy(); repo.create("Frontend integration"); assertHealthy()
            val first = repo.state.value.snapshot!!
            val author = repo.state.value.authorId
            val marks = listOf(newId(), newId())
            marks.forEach { mark ->
                assertTrue(repo.stageStroke(InkPreview(mark, first.sheets.first().id, channels, brush)))
                repo.retry(); assertHealthy()
            }
            assertEquals(2, repo.state.value.snapshot!!.strokes.count { !it.tombstone })
            assertEquals(channels, repo.state.value.samples[marks.first()])
            val beforeErase = repo.state.value.snapshot!!.localSequence
            assertTrue(repo.stageErase(marks.toSet())); repo.retry(); assertHealthy()
            assertEquals(beforeErase + 1u, repo.state.value.snapshot!!.localSequence)
            assertTrue(repo.state.value.snapshot!!.strokes.all { it.tombstone })
            repo.history(false); assertHealthy()
            assertTrue(repo.state.value.snapshot!!.strokes.none { it.tombstone })
            val redo = repo.state.value.snapshot!!.redoTarget
            repo.addSheet(); assertHealthy(); repo.rename("Renamed"); assertHealthy()
            assertEquals(redo, repo.state.value.snapshot!!.redoTarget)
            assertTrue(repo.leave()); repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath)
            repo.loadLibrary(); assertHealthy(); repo.open(first.noteId); assertHealthy()
            assertEquals(author, repo.state.value.authorId)
            assertEquals("Renamed", repo.state.value.snapshot!!.title)
            assertEquals(2, repo.state.value.snapshot!!.sheets.size)
            assertEquals(redo, repo.state.value.snapshot!!.redoTarget)
            repo.history(true); assertHealthy()
            assertTrue(repo.state.value.snapshot!!.strokes.all { it.tombstone })
            // Explicit recovery writer sees exact pending typed commands + real safe core backup.
            val unsaved = newId()
            repo.stageStroke(InkPreview(unsaved, first.sheets.first().id, channels, brush))
            repo.exportRecovery { archive ->
                assertNotNull(archive.backup)
                val add = (archive.commands.single().body as CommandBody.Marks).mutations.single() as MarkMutation.AddStroke
                assertEquals(channels, GeneratedCoreAdapter().decode(add.samples))
            }
            assertHealthy(); assertTrue(repo.state.value.recoveryExported)
            assertTrue(repo.state.value.unsaved) // Archive is not a note commit.
            assertTrue(repo.leave()); repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath)
            repo.open(first.noteId); assertHealthy()
            assertFalse(repo.state.value.snapshot!!.strokes.any { it.id == unsaved })
            assertTrue(repo.leave()); repo.releaseLibrary()
        } finally { root.deleteRecursively() }
    }

    @Test fun phoneNoteInNotebookAutoPagesSelectionCommandsUndoMoveAndReopen() = runBlocking {
        val root = Files.createTempDirectory("syncr-phone-pages-").toFile()
        val ink = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        var repo = NotesRepository(root.absolutePath)
        fun assertHealthy() { assertNull(repo.state.value.error) }
        fun snap() = repo.state.value.snapshot!!
        try {
            repo.loadLibrary(); repo.createNotebook("Physics"); assertHealthy()
            repo.create("Lagrangians", PageFormat.PHONE, "Physics"); assertHealthy()
            val created = snap()
            assertEquals(PageFormat.PHONE, created.pageFormat); assertEquals("Physics", created.notebook)
            assertEquals(pageSize(PageFormat.PHONE), PageSize(created.pageWidth, created.pageHeight))
            assertEquals(1600f to 3200f, created.pageWidth to created.pageHeight)
            val h = created.pageHeight
            // Page 1: a stroke low on the last page queues exactly one AddSheet behind it.
            val low = SampleChannels(listOf(100f, 400f), listOf(0.8f * h, 0.85f * h), listOf(.5f, 1f), listOf(0u, 10u), null, null)
            val first = newId()
            assertTrue(repo.stageStroke(InkPreview(first, created.sheets.single().id, low, ink)))
            assertTrue(shouldAutoAddPage(0, 1, low.y.max(), h, repo.addSheetPending))
            assertTrue(repo.stageAddSheet()); assertFalse(repo.stageAddSheet())
            repo.retry(); assertHealthy(); assertFalse(repo.state.value.unsaved)
            assertEquals(2, snap().sheets.size)
            val page2 = snap().sheets[1]
            // Page 2: stored in page-2 units with page 2's own creator as the dependency.
            val second = newId()
            val top = SampleChannels(listOf(200f, 300f), listOf(100f, 150f), listOf(.5f, 1f), listOf(0u, 10u), null, null)
            assertTrue(repo.stageStroke(InkPreview(second, page2.id, top, ink)))
            assertEquals(listOf(page2.creationOperationId), repo.state.value.pending.single().dependencies)
            repo.retry(); assertHealthy()
            assertEquals(mapOf(first to created.sheets.single().id, second to page2.id), snap().strokes.associate { it.id to it.sheetId })
            assertEquals(top, repo.state.value.samples[second])
            // One Marks command per gesture: transform, restyle, delete.
            val move = translation(50.0, -20.0)
            var sequence = snap().localSequence
            assertTrue(repo.stageMarks(MarkEdit.Transform(setOf(first, second), move))); repo.retry(); assertHealthy()
            assertEquals(++sequence, snap().localSequence)
            assertTrue(snap().strokes.all { it.transform == move })
            assertTrue(repo.stageMarks(MarkEdit.Restyle(setOf(first, second), InkColour.RED))); repo.retry(); assertHealthy()
            assertEquals(++sequence, snap().localSequence)
            assertTrue(snap().strokes.all { it.brush.colour == InkColour.RED && it.transform == move })
            assertTrue(repo.stageMarks(MarkEdit.Delete(setOf(first)))); repo.retry(); assertHealthy()
            assertEquals(++sequence, snap().localSequence)
            assertTrue(snap().strokes.first { it.id == first }.tombstone)
            // Undo is one step per gesture.
            repo.history(false); assertHealthy()
            assertTrue(snap().strokes.none { it.tombstone })
            repo.history(false); assertHealthy()
            assertTrue(snap().strokes.all { it.brush.colour == InkColour.INK && it.transform == move })
            val noteId = created.noteId
            assertTrue(repo.leave())
            // Move the CLOSED note through the worker: open, SetNotebook, durable close. Then the library re-reads.
            repo.createNotebook("Mechanics"); assertHealthy()
            repo.moveNote(noteId, "Mechanics"); assertHealthy()
            assertNull(repo.state.value.libraryTask)
            val summary = repo.state.value.notes.single { it.id == noteId }
            assertEquals("Mechanics", summary.notebook); assertEquals(PageFormat.PHONE, summary.pageFormat)
            assertTrue(summary.modifiedAtMs >= summary.createdAtMs)
            assertEquals(mapOf("Mechanics" to 1uL, "Physics" to 0uL), repo.state.value.notebooks.associate { it.name to it.noteCount })
            assertEquals(summary.localSequence, repo.state.value.thumbnails.getValue(noteId).localSequence)
            assertEquals(2, repo.state.value.thumbnails.getValue(noteId).strokes.size + 1) // first page holds one stroke
            repo.deleteNotebook("Mechanics"); assertTrue(repo.state.value.error!!.startsWith("InvalidInput")) // in use
            repo.dismissMessage()
            repo.deleteNotebook("Physics"); assertHealthy()
            repo.renameNotebook("Mechanics", "Dynamics"); assertHealthy()
            assertEquals("Dynamics", repo.state.value.notes.single { it.id == noteId }.notebook)
            assertEquals(listOf("Dynamics"), repo.state.value.notebooks.map { it.name })
            repo.renameNote(noteId, "Lagrangian mechanics"); assertHealthy()
            repo.releaseLibrary()
            // Full restart: everything above is durable; SetNotebook/Rename never entered ink undo.
            repo = NotesRepository(root.absolutePath)
            repo.loadLibrary(); repo.open(noteId); assertHealthy()
            val reopened = snap()
            assertEquals("Dynamics", reopened.notebook); assertEquals("Lagrangian mechanics", reopened.title)
            assertEquals(2, reopened.sheets.size)
            assertTrue(reopened.strokes.all { !it.tombstone && it.transform == move && it.brush.colour == InkColour.INK })
            assertEquals(top, repo.state.value.samples[second]) // samples never change; the transform carries the move
            repo.history(false); assertHealthy() // undoes the transform
            assertTrue(snap().strokes.all { it.transform == IDENTITY })
            assertNotNull(snap().redoTarget)
        } finally { repo.leave(discard = true); repo.releaseLibrary(); root.deleteRecursively() }
    }

    @Test fun archiveHidesFromNormalViewsDeleteRestoreReopenAndDeleteForeverAgainstRealCore() = runBlocking {
        val root = Files.createTempDirectory("syncr-trash-").toFile()
        val thumbs = Files.createTempDirectory("syncr-trash-thumbs-").toFile()
        val ink = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        val samples = SampleChannels(listOf(10f, 20f), listOf(20f, 30f), listOf(.5f, 1f), listOf(0u, 10u), null, null)
        var clock = System.currentTimeMillis()
        var repo = NotesRepository(root.absolutePath, thumbnailDir = thumbs, now = { clock })
        fun assertHealthy() { assertNull(repo.state.value.error) }
        fun visible(filter: LibraryFilter) = repo.state.value.notes.filter { filter.accepts(it.notebook, it.archived) }.map { it.id }
        try {
            repo.loadLibrary(); repo.createNotebook("Physics"); assertHealthy()
            repo.create("Keep", PageFormat.PHONE, "Physics"); assertHealthy()
            val keep = repo.state.value.snapshot!!
            assertTrue(repo.stageStroke(InkPreview(newId(), keep.sheets.single().id, samples, ink))); repo.retry(); assertHealthy()
            assertTrue(repo.leave())
            repo.create("Other"); assertHealthy()
            val other = repo.state.value.snapshot!!.noteId
            assertTrue(repo.leave())

            // Archive (closed note, through the worker) -> hidden from All/notebook, listed under Archived.
            repo.archiveNote(keep.noteId, true); assertHealthy()
            assertNull(repo.state.value.libraryTask)
            assertEquals(listOf(other), visible(LibraryFilter.All))
            assertTrue(visible(LibraryFilter.Notebook("Physics")).isEmpty())
            assertEquals(listOf(keep.noteId), visible(LibraryFilter.Archived))
            assertEquals(1uL, repo.state.value.notebooks.single().noteCount) // still a member of Physics
            repo.archiveNote(keep.noteId, false); assertHealthy()
            assertEquals(setOf(keep.noteId, other), visible(LibraryFilter.All).toSet())
            assertTrue(visible(LibraryFilter.Archived).isEmpty())
            // Archive the OPEN note, then unarchive it again from the library.
            repo.open(keep.noteId); assertHealthy()
            repo.archiveOpenNote(true); assertHealthy(); assertTrue(repo.state.value.snapshot!!.archived)
            assertTrue(repo.leave())
            assertEquals(listOf(keep.noteId), visible(LibraryFilter.Archived))
            repo.archiveNote(keep.noteId, false); assertHealthy()

            // Delete -> Recently deleted -> Undo (restore) -> reopen identical.
            repo.open(keep.noteId); assertHealthy()
            val before = repo.state.value.snapshot!!
            repo.deleteNote(keep.noteId) // refused by the repository while open: no core call
            assertNull(repo.state.value.libraryTask); assertTrue(repo.state.value.notes.any { it.id == keep.noteId })
            assertTrue(repo.leave())
            repo.loadThumbnails()
            assertNotNull(ThumbnailCache.read(thumbs, keep.noteId, before.localSequence))
            repo.deleteNote(keep.noteId); assertHealthy()
            assertEquals(keep.noteId, repo.state.value.recentlyDeleted)
            assertEquals(listOf(other), repo.state.value.notes.map { it.id })
            val deleted = repo.state.value.deleted.single()
            assertEquals(keep.noteId, deleted.id); assertEquals("Keep", deleted.title); assertEquals("Physics", deleted.notebook)
            repo.open(keep.noteId); assertTrue(repo.state.value.error!!.startsWith("NotFound")); repo.dismissMessage()
            assertNull(repo.state.value.snapshot)
            repo.undoDelete(); assertHealthy()
            assertTrue(repo.state.value.deleted.isEmpty())
            repo.open(keep.noteId); assertHealthy()
            assertEquals(before.copy(cursor = repo.state.value.snapshot!!.cursor), repo.state.value.snapshot)
            assertTrue(repo.stageStroke(InkPreview(newId(), keep.sheets.single().id, samples, ink))); repo.retry(); assertHealthy()
            assertTrue(repo.leave()); repo.releaseLibrary()

            // Delete -> survives restart -> delete forever (confirmed in the UI) -> files and card gone.
            repo = NotesRepository(root.absolutePath, thumbnailDir = thumbs, now = { clock })
            repo.loadLibrary(); repo.deleteNote(keep.noteId); repo.deleteNote(other); assertHealthy()
            repo.releaseLibrary()
            repo = NotesRepository(root.absolutePath, thumbnailDir = thumbs, now = { clock })
            repo.loadLibrary(); assertHealthy()
            assertEquals(setOf(keep.noteId, other), repo.state.value.deleted.map { it.id }.toSet())
            assertTrue(repo.state.value.notes.isEmpty())
            repo.purgeNote(keep.noteId); assertHealthy()
            assertEquals(listOf(other), repo.state.value.deleted.map { it.id })
            assertNull(ThumbnailCache.read(thumbs, keep.noteId, before.localSequence + 1u))
            assertFalse(File(root, ".trash").listFiles()!!.any { it.name.startsWith(keep.noteId) })
            assertFalse(File(root, "${keep.noteId}.syncr").exists())
            // 30 days later the library load purges the rest.
            repo.releaseLibrary()
            clock = System.currentTimeMillis() + DELETED_RETENTION_MS + 1
            repo = NotesRepository(root.absolutePath, thumbnailDir = thumbs, now = { clock })
            repo.loadLibrary(); assertHealthy()
            assertTrue(repo.state.value.deleted.isEmpty()); assertTrue(repo.state.value.notes.isEmpty())
            assertTrue(File(root, ".trash").listFiles()!!.isEmpty())
        } finally { repo.leave(discard = true); repo.releaseLibrary(); root.deleteRecursively(); thumbs.deleteRecursively() }
    }
}
