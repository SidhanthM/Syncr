package dev.syncr.prototype.notes

import dev.syncr.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NotesRepositoryTest {
    private val samples = SampleChannels(listOf(10f, 20f), listOf(20f, 30f), listOf(.5f, 1f), listOf(0u, 10u), null, null)
    private val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    private val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private fun snapshot(seq: ULong = 1u, strokes: List<StrokeView> = emptyList(), undo: String? = null, redo: String? = null,
        undoDependencies: List<String> = emptyList(), redoDependencies: List<String> = emptyList()) =
        NoteSnapshot("note", "Test", null, EntityHead("creation", null), EntityHead("creation", null),
            listOf(SheetView("sheet", "creation", 0f, 0f)),
            strokes, seq, EventCursor("stream", seq), undo, redo, undoDependencies, redoDependencies)
    private fun preview(id: String = "mark") = InkPreview(id, "sheet", samples, brush)

    /** Test-only fake, deliberately no operation reducer or storage implementation. */
    private inner class Fake : CoreAdapter, LibraryAccess, NoteAccess {
        var current = this@NotesRepositoryTest.snapshot()
        var failSubmit: Exception? = null
        var failSnapshot: Exception? = null
        var failClose = false
        var failCreation = false
        var creationCommitted = false
        var encodeFailure = false
        var afterSubmit: (() -> Unit)? = null
        var changes: MutableList<ChangePage> = mutableListOf()
        val commands = mutableListOf<Command>()
        val creates = mutableListOf<CreateNoteRequest>()
        val recorded = mutableMapOf<String, Receipt>()
        var closes = 0
        var opens = 0
        var releases = 0
        val notebooks = mutableListOf<String>()
        val renamedNotebooks = mutableListOf<Pair<String, String>>()
        var failRenameNotebook = false
        var sampleReads = 0
        /** Recently deleted: note ID -> deletion time. Purged IDs are gone for good. */
        val deleted = mutableMapOf<String, Long>()
        val purged = mutableListOf<String>()
        val trashCalls = mutableListOf<String>()
        var clock = 1_000_000_000_000L
        var failDelete: Exception? = null
        var failRestore: Exception? = null
        var failPurge: Exception? = null
        var encodeCount = 0
        var expectWorker = false
        private fun worker() { if (expectWorker) assertTrue(Thread.currentThread().name.startsWith("repository-test-io")) }
        override fun open(root: String): LibraryAccess { worker(); return this }
        override fun encode(channels: SampleChannels): ByteArray {
            worker(); encodeCount++; if (encodeFailure) throw CoreException.InvalidInput("codec rejected", false)
            return byteArrayOf(1, 2, 3)
        }
        override fun decode(bytes: ByteArray): SampleChannels { worker(); return samples }
        override fun authorId(): String { worker(); return "author" }
        override fun listNotes(): List<NoteSummary> { worker()
            return listOf(NoteSummary("old", "Old", 1u, "Physics", 10, 20, false),
                NoteSummary("note", current.title, current.localSequence, current.notebook, 10, 30, current.archived))
                .filter { it.id !in deleted && it.id !in purged } }
        override fun deleteNote(id: String) {
            worker(); trashCalls += "delete:$id"; failDelete?.let { throw it }
            if (id !in deleted) deleted[id] = clock
        }
        override fun listDeletedNotes(): List<DeletedNoteSummary> { worker()
            return deleted.entries.sortedByDescending { it.value }.map { (id, at) -> DeletedNoteSummary(id, "Deleted $id", null, at, 10) } }
        override fun restoreNote(id: String) { worker(); trashCalls += "restore:$id"; failRestore?.let { throw it }; deleted -= id }
        override fun purgeNote(id: String) { worker(); trashCalls += "purge:$id"; failPurge?.let { throw it }; deleted -= id; purged += id }
        override fun purgeDeletedOlderThan(cutoffMs: Long): UInt { worker(); trashCalls += "purgeOlderThan:$cutoffMs"
            failPurge?.let { throw it }
            val old = deleted.filterValues { it < cutoffMs }.keys
            old.forEach { deleted -= it; purged += it }
            return old.size.toUInt() }
        override fun listNotebooks(): List<NotebookSummary> { worker(); return notebooks.map { NotebookSummary(it, 0u) } }
        override fun createNotebook(name: String) { worker(); if (name !in notebooks) notebooks += name }
        override fun deleteNotebook(name: String) { worker(); notebooks -= name }
        override fun renameNotebook(oldName: String, newName: String) {
            worker(); renamedNotebooks += oldName to newName
            if (failRenameNotebook) throw CoreException.StaleBase("Member changed", true)
            notebooks -= oldName; notebooks += newName
        }
        override fun create(request: CreateNoteRequest): Receipt {
            worker(); creates += request; creationCommitted = true
            if (failCreation) throw CoreException.StorageIo("reply lost", true)
            return Receipt(request.operationId, 1u, current.cursor, false)
        }
        override fun lookupCreation(id: String): OperationLookup { worker(); return if (creationCommitted) OperationLookup.Committed(Receipt(id, 1u, current.cursor, false)) else OperationLookup.NotRecorded }
        override fun openNote(id: String): NoteAccess { worker(); opens++; return this }
        override fun release() { worker(); releases++ }
        override fun snapshot(): NoteSnapshot { worker(); failSnapshot?.let { throw it }; return current }
        override fun samples(id: String): ByteArray { worker(); sampleReads++; return byteArrayOf(1) }
        override fun submit(command: Command): Receipt {
            worker(); commands += command; failSubmit?.let { throw it }
            val receipt = Receipt(command.operationId, 2u, EventCursor("stream", 2u), false)
            recorded[command.operationId] = receipt
            afterSubmit?.invoke()
            return receipt
        }
        override fun lookup(id: String): OperationLookup { worker(); return recorded[id]?.let { OperationLookup.Committed(it) } ?: OperationLookup.NotRecorded }
        override fun changes(cursor: EventCursor): ChangePage { worker(); return if (changes.isNotEmpty()) changes.removeAt(0) else ChangePage.Events(emptyList(), cursor, false) }
        override fun export(path: String): ExportReceipt { worker(); throw CoreException.Internal("backup unavailable", false) }
        override fun closeDurably() { worker(); if (failClose) throw CoreException.StorageIo("checkpoint failed", true); closes++ }
    }
    private fun repo(fake: Fake, dispatcher: CoroutineDispatcher): NotesRepository {
        var ids = 0
        return NotesRepository("/unused", fake, dispatcher) { "id-${++ids}" }
    }

    @Test fun storageFullRetainsCompleteDraftBlocksClosureAndRetriesIdenticalCommand() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        fake.failSubmit = CoreException.StorageFull("Storage is full", true)
        assertTrue(repo.stageStroke(preview())); repo.retry()
        val failed = repo.state.value
        assertTrue(failed.unsaved); assertTrue(failed.status.startsWith("Save failed"))
        assertEquals("StorageFull: Storage is full", failed.error)
        assertEquals(samples, failed.pending.single().preview!!.channels)
        assertFalse(repo.leave()); assertEquals(0, fake.closes)
        val immutable = failed.pending.single().command!!
        fake.failSubmit = null
        fake.afterSubmit = { fake.current = snapshot(2u) }
        repo.retry()
        assertEquals(immutable, fake.commands.last())
        assertSame(fake.commands.first(), fake.commands.last())
        assertEquals(1, fake.encodeCount)
        assertFalse(repo.state.value.unsaved)
        assertTrue(repo.state.value.status.startsWith("Saved on this phone"))
        assertTrue(repo.leave()); assertEquals(1, fake.closes)
    }
    @Test fun receiptIsNotEnoughOverlayRemainsUntilSnapshotThenLostReplyUsesLookup() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        fake.afterSubmit = { fake.failSnapshot = CoreException.StorageIo("read failed", true) }
        repo.stageStroke(preview()); repo.retry()
        assertNotNull(repo.state.value.pending.single().receipt)
        assertFalse(repo.leave())
        fake.failSnapshot = null; fake.current = snapshot(2u)
        repo.retry()
        assertTrue(repo.state.value.pending.isEmpty()); assertEquals(1, fake.commands.size)
    }
    @Test fun submitReplyLostUsesPersistedLookupAndNeverAllocatesReplacementIds() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        fake.afterSubmit = { fake.current = snapshot(2u); throw CoreException.StorageIo("reply lost", true) }
        repo.stageStroke(preview()); repo.retry()
        val original = repo.state.value.pending.single().command!!
        assertNull(repo.state.value.pending.single().receipt)
        fake.afterSubmit = null; repo.retry()
        assertEquals(original.operationId, fake.commands.single().operationId)
        assertFalse(repo.state.value.unsaved)
    }
    @Test fun oldSnapshotNeverRemovesAcknowledgedPreviewOrSpins() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.stageStroke(preview()); repo.retry()
        assertNotNull(repo.state.value.pending.single().receipt)
        assertNotNull(repo.state.value.error)
        assertFalse(repo.state.value.busy)
        fake.current = snapshot(2u); repo.retry()
        assertFalse(repo.state.value.unsaved); assertEquals(1, fake.commands.size)
    }
    @Test fun creationReplyLostRetainsOriginalIdsAndLookupRecoversWithoutSecondCreate() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler))
        fake.failCreation = true; repo.create("Hello")
        val original = repo.state.value.creation!!
        assertEquals(original, fake.creates.single()); assertFalse(repo.leave())
        fake.failCreation = false; repo.retry()
        assertEquals(1, fake.creates.size); assertNull(repo.state.value.creation); assertNotNull(repo.state.value.snapshot)
    }
    @Test fun childrenWaitBehindFailedParentAndSurviveSnapshotRefresh() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.stageStroke(preview("one")); repo.stageStroke(preview("two"))
        fake.failSubmit = CoreException.MissingDependency("Parent missing", true); repo.retry(); repo.refresh()
        assertEquals(2, repo.state.value.pending.size); assertEquals(1, fake.commands.size)
        val first = repo.state.value.pending.first().command!!
        fake.failSubmit = null; fake.afterSubmit = { fake.current = snapshot(2u) }; repo.retry()
        assertEquals(first.operationId, fake.commands[1].operationId)
        assertEquals(3, fake.commands.size); assertFalse(repo.state.value.unsaved)
    }
    @Test fun wholeEraserGestureHasOneBatchSortedDependenciesAndCancelDoesNothing() = runTest {
        val fake = Fake()
        fake.current = snapshot(strokes = listOf(
            StrokeView("a", "sheet", "author", EntityHead("z-head", null), identity, brush, false),
            StrokeView("b", "sheet", "author", EntityHead("a-head", null), identity, brush, false)))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        assertFalse(repo.stageErase(emptySet())); assertEquals(0, fake.commands.size)
        assertTrue(repo.stageErase(setOf("a", "b")))
        assertEquals(setOf("a", "b"), repo.state.value.pending.single().hidden)
        fake.afterSubmit = { fake.current = snapshot(2u) }; repo.retry()
        val command = fake.commands.single()
        assertEquals(listOf("a-head", "z-head"), command.dependencies)
        assertEquals(2, (command.body as CommandBody.Marks).mutations.size)
        assertFalse(repo.stageErase((1..1025).map { "mark-$it" }.toSet()))
        assertEquals(1, fake.commands.size)
    }
    @Test fun undoRedoCaptureOnlyMatchingExactDependenciesAndNeverRebaseStaleRetryOrExport() = runTest {
        for (redo in listOf(false, true)) {
            val fake = Fake()
            val heads = mutableListOf("a-affected", "z-affected")
            fake.current = snapshot(strokes = listOf(
                StrokeView("unrelated", "sheet", "author", EntityHead("unrelated-head", null), identity, brush, false)),
                undo = "undo-target", redo = "redo-target",
                undoDependencies = if (redo) listOf("wrong-undo-head") else heads,
                redoDependencies = if (redo) heads else listOf("wrong-redo-head"))
            val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
            fake.failSubmit = CoreException.StaleBase("Ink changed", false); repo.history(redo)
            val original = fake.commands.single()
            assertEquals(if (redo) CommandBody.Redo("redo-target") else CommandBody.Undo("undo-target"), original.body)
            assertEquals(listOf("a-affected", "z-affected"), original.dependencies)
            assertTrue(repo.state.value.unsaved)
            heads.clear() // Generated records/lists are mutable; staged dependencies must be detached.
            fake.current = snapshot(3u, undo = "new-undo", redo = "new-redo",
                undoDependencies = listOf("new-head"), redoDependencies = listOf("new-head"))
            repo.refresh()
            repo.exportRecovery { assertSame(original, it.commands.single()) }
            repo.retry()
            assertSame(original, fake.commands.last())
            assertEquals(listOf("a-affected", "z-affected"), original.dependencies)
            fake.failSubmit = null; repo.retry()
            assertSame(original, fake.commands.last()); assertFalse(repo.state.value.unsaved)
        }
    }
    @Test fun absentHistoryTargetStagesNothing() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.history(false); repo.history(true)
        assertTrue(fake.commands.isEmpty()); assertFalse(repo.state.value.unsaved)
    }
    @Test fun firstSheetProvenanceIsCapturedBeforeRefreshAndExportPreparesPayloadOnlyOnce() = runTest {
        val fake = Fake()
        fake.current = snapshot().copy(structureHead = EntityHead("reorder", null),
            sheets = listOf(SheetView("sheet", "first-creation", 0f, 0f), SheetView("old-sheet", "old-creation", 0f, 3200f)))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        assertFalse(repo.stageStroke(preview().copy(sheetId = "unknown")))
        assertFalse(repo.stageStroke(preview().copy(sheetId = "old-sheet")))
        assertTrue(repo.stageStroke(preview()))
        assertEquals(listOf("first-creation"), repo.state.value.pending.single().dependencies)
        fake.current = snapshot().copy(structureHead = EntityHead("later-structure", null))
        repo.refresh()
        var original: Command? = null
        repo.exportRecovery { original = it.commands.single(); throw IOException("destination failed") }
        assertFalse(repo.state.value.recoveryExported)
        assertSame(original, repo.state.value.pending.single().command)
        fake.encodeFailure = true // A prepared payload must never be encoded again.
        repo.exportRecovery { assertSame(original, it.commands.single()) }
        fake.failSubmit = CoreException.StorageFull("Storage is full", true); repo.retry()
        assertSame(original, fake.commands.single())
        assertEquals(listOf("first-creation"), original!!.dependencies)
        val add = (original!!.body as CommandBody.Marks).mutations.single() as MarkMutation.AddStroke
        assertEquals("sheet", add.sheetId); assertEquals("mark", add.markId)
        assertArrayEquals(byteArrayOf(1, 2, 3), add.samples)
        fake.failSubmit = null; fake.afterSubmit = { fake.current = snapshot(2u) }; repo.retry()
        assertSame(original, fake.commands.last()); assertEquals(1, fake.encodeCount)
        assertFalse(repo.state.value.unsaved)
    }
    @Test fun recoveryNeedsSuccessfulDestinationCloseAndNewDraftRevokesAuthorization() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.stageStroke(preview())
        repo.exportRecovery { throw IOException("destination close failed") }
        assertFalse(repo.state.value.recoveryExported); assertFalse(repo.leave())
        var archive: RecoveryData? = null
        repo.exportRecovery { archive = it }
        assertEquals("author", archive!!.authorId)
        assertTrue((archive!!.commands.single().body as CommandBody.Marks).mutations.single() is MarkMutation.AddStroke)
        assertTrue(repo.state.value.recoveryExported); assertTrue(repo.state.value.unsaved)
        repo.stageStroke(preview("new")); assertFalse(repo.state.value.recoveryExported); assertFalse(repo.leave())
        repo.exportRecovery { }
        assertTrue(repo.leave()); assertTrue(repo.state.value.pending.isEmpty())
    }
    @Test fun draftAddedDuringDestinationWriteIsNotAuthorizedByEarlierExport() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.stageStroke(preview()); repo.exportRecovery { repo.stageStroke(preview("newer")) }
        assertFalse(repo.state.value.recoveryExported); assertFalse(repo.leave())
    }
    @Test fun failedDurableCloseDoesNotReleaseOrDiscardEvenWithExplicitDiscard() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.stageStroke(preview()); fake.failClose = true
        assertFalse(repo.leave(discard = true)); assertTrue(repo.state.value.unsaved)
        assertEquals(0, fake.closes); assertEquals(0, fake.releases)
        fake.failClose = false; assertTrue(repo.leave(discard = true)); repo.releaseLibrary()
        assertEquals(1, fake.closes); assertEquals(1, fake.releases)
    }
    @Test fun resyncInvalidatesRatherThanReducingAndSampleCacheSurvivesRefresh() = runTest {
        val fake = Fake()
        fake.current = snapshot(strokes = listOf(StrokeView("a", "sheet", "author", EntityHead("head", null), identity, brush, false)))
        fake.changes += ChangePage.ResyncRequired
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note"); repo.refresh()
        assertEquals(fake.current, repo.state.value.snapshot); assertEquals(1, fake.sampleReads)
    }
    @Test fun codecFailureRetainsRawMeasuredDraftAndNeverSendsFakeSamples() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        fake.encodeFailure = true; repo.stageStroke(preview()); repo.retry()
        assertEquals(samples, repo.state.value.pending.single().preview!!.channels)
        assertTrue(fake.commands.isEmpty()); assertFalse(repo.leave())
    }
    @Test fun delayedSaveRemainsUnsavedAndDurableCloseSerializesBehindIt() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val fake = Fake(); val repo = repo(fake, dispatcher); repo.open("note")
            fake.afterSubmit = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                fake.current = snapshot(2u)
            }
            repo.stageStroke(preview())
            val save = launch(start = CoroutineStart.UNDISPATCHED) { repo.retry() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(repo.state.value.unsaved); assertEquals("Saving — not yet saved", repo.state.value.status)
            assertEquals(0, fake.closes)
            val close = async(start = CoroutineStart.UNDISPATCHED) { repo.leave() }
            release.countDown(); save.join(); assertTrue(close.await())
            assertEquals(1, fake.closes); assertFalse(repo.state.value.unsaved)
        } finally { release.countDown(); dispatcher.close() }
    }
    @Test fun allBlockingCallsIncludingCodecAndCloseRunOnInjectedWorker() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "repository-test-io") }.asCoroutineDispatcher()
        try {
            val fake = Fake(); fake.expectWorker = true
            val repo = repo(fake, dispatcher); repo.loadLibrary(); repo.open("note")
            fake.afterSubmit = { fake.current = snapshot(2u) }
            repo.stageStroke(preview()); repo.retry(); repo.refresh(); repo.leave(); repo.releaseLibrary()
        } finally { dispatcher.close() }
    }

    private fun stroke(id: String, head: String, transform: Affine = identity, colour: InkColour = InkColour.INK) =
        StrokeView(id, "sheet", "author", EntityHead(head, null), transform, brush.copy(colour = colour), false)

    @Test fun eachSelectionGestureIsOneAtomicMarksCommandWithCurrentHeadsAndComposedTransforms() = runTest {
        val fake = Fake()
        val existing = Affine(2.0, 0.0, 0.0, 2.0, 5.0, 5.0)
        fake.current = snapshot(strokes = listOf(stroke("a", "z-head", existing, InkColour.RED), stroke("b", "a-head")))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        val move = translation(10.0, 20.0)
        assertTrue(repo.stageMarks(MarkEdit.Transform(setOf("a", "b"), move)))
        val staged = repo.state.value.pending.single()
        assertEquals(compose(move, existing), staged.transforms["a"]); assertEquals(move, staged.transforms["b"])
        fake.afterSubmit = { fake.current = fake.current.copy(localSequence = 2u, cursor = EventCursor("stream", 2u)) }; repo.retry()
        val transform = fake.commands.single()
        assertEquals(listOf("a-head", "z-head"), transform.dependencies)
        assertEquals(setOf(MarkMutation.TransformStroke("a", Base.Operation("z-head"), Affine(2.0, 0.0, 0.0, 2.0, 15.0, 25.0)),
            MarkMutation.TransformStroke("b", Base.Operation("a-head"), Affine(1.0, 0.0, 0.0, 1.0, 10.0, 20.0))),
            (transform.body as CommandBody.Marks).mutations.toSet())
        // Restyle sends only marks whose colour changes; a no-op recolour stages nothing.
        assertTrue(repo.stageMarks(MarkEdit.Restyle(setOf("a", "b"), InkColour.RED)))
        assertEquals(mapOf("b" to brush.copy(colour = InkColour.RED)), repo.state.value.pending.single().restyles)
        repo.retry()
        val restyle = fake.commands.last()
        assertEquals(listOf("a-head"), restyle.dependencies)
        assertEquals(listOf(MarkMutation.RestyleStroke("b", Base.Operation("a-head"), brush.copy(colour = InkColour.RED))),
            (restyle.body as CommandBody.Marks).mutations)
        fake.current = snapshot(3u, strokes = listOf(stroke("a", "h1", colour = InkColour.RED), stroke("b", "h2", colour = InkColour.RED)))
        repo.refresh()
        assertFalse(repo.stageMarks(MarkEdit.Restyle(setOf("a", "b"), InkColour.RED)))
        assertTrue(repo.stageMarks(MarkEdit.Delete(setOf("a", "b"))))
        assertEquals(setOf("a", "b"), repo.state.value.pending.single().hidden)
        repo.retry()
        assertEquals(3, fake.commands.size)
        assertTrue((fake.commands.last().body as CommandBody.Marks).mutations.all { it is MarkMutation.DeleteStroke })
    }
    @Test fun oneCanvasResizeAcrossLegacySheetsStoresSheetLocalTransforms() = runTest {
        val fake = Fake()
        val first = SheetView("sheet", "creation", 0f, 0f)
        val old = SheetView("legacy", "legacy-create", 0f, 3200f)
        fake.current = snapshot(strokes = listOf(stroke("a", "a-head"), stroke("b", "b-head").copy(sheetId = old.id)))
            .copy(sheets = listOf(first, old))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        val resize = scaleAbout(2.0, 100.0, 3300.0)
        assertTrue(repo.stageMarks(MarkEdit.Transform(setOf("a", "b"), resize)))
        val pending = repo.state.value.pending.single()
        assertEquals(resize, pending.transforms["a"])
        assertEquals(localGesture(old, resize), pending.transforms["b"])
        assertEquals(listOf("a-head", "b-head"), pending.command!!.dependencies)
        for ((mark, sheet) in listOf("a" to first, "b" to old)) {
            val before = transformedPoint(10f, 20f, canvasTransform(sheet, IDENTITY))
            val expected = transformedPoint(before.first, before.second, resize)
            assertEquals(expected, transformedPoint(10f, 20f, canvasTransform(sheet, pending.transforms.getValue(mark))))
        }
    }

    @Test fun selectionEditsWaitForSavedInkRejectSingularAndOversizedGestures() = runTest {
        val fake = Fake(); fake.current = snapshot(strokes = listOf(stroke("a", "head")))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        assertFalse(repo.stageMarks(MarkEdit.Transform(setOf("a"), scaleAbout(0.0, 0.0, 0.0))))
        assertTrue(repo.state.value.pending.isEmpty())
        assertFalse(repo.stageMarks(MarkEdit.Transform((1..1025).map { "m$it" }.toSet(), translation(1.0, 1.0))))
        assertEquals("Selection limit: entire gesture cancelled (maximum 1024 strokes).", repo.state.value.error)
        assertFalse(repo.stageMarks(MarkEdit.Transform(setOf("missing"), translation(1.0, 1.0))))
        repo.stageStroke(preview("unsaved"))
        assertFalse(repo.stageMarks(MarkEdit.Transform(setOf("a"), translation(1.0, 1.0))))
        assertTrue(repo.state.value.error!!.startsWith("Wait for saved ink"))
        assertEquals(1, repo.state.value.pending.size)
        assertTrue(fake.commands.isEmpty())
    }
    @Test fun drawingFarDownAndRightSubmitsOnlyInkWithoutAddingStructure() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        val far = preview().copy(channels = samples.copy(x = listOf(3200f, 4700f), y = listOf(10000f, 11000f)))
        assertTrue(repo.stageStroke(far))
        fake.afterSubmit = { fake.current = snapshot(2u) }
        repo.retry()
        val add = ((fake.commands.single().body as CommandBody.Marks).mutations.single() as MarkMutation.AddStroke)
        assertEquals("sheet", add.sheetId)
        assertEquals(1, repo.state.value.snapshot!!.sheets.size)
        assertFalse(repo.state.value.unsaved)
    }
    @Test fun createUsesCanvasAndTheChosenNotebook() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler))
        repo.create("Canvas note")
        assertEquals("Canvas note", fake.creates.single().title); assertNull(fake.creates.single().notebook)
        val other = Fake(); val second = repo(other, StandardTestDispatcher(testScheduler))
        second.create("Physics note", "Physics")
        assertEquals("Physics", other.creates.single().notebook)
    }
    @Test fun movingClosedNoteOpensSubmitsOneSetNotebookAndClosesDurablyWithImmutableRetry() = runTest {
        val fake = Fake(); fake.current = snapshot().copy(metadataHead = EntityHead("meta", null))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.loadLibrary()
        assertEquals(listOf("note", "old"), repo.state.value.notes.map { it.id }) // newest modified first
        fake.failSubmit = CoreException.StorageFull("Storage is full", true)
        repo.moveNote("note", "Physics")
        val task = repo.state.value.libraryTask as LibraryTask.EditNote
        val command = task.command!!
        assertEquals(CommandBody.SetNotebook("Physics", Base.Operation("meta")), command.body)
        assertEquals(listOf("meta"), command.dependencies)
        assertEquals("StorageFull: Storage is full", repo.state.value.error)
        assertNull(repo.state.value.snapshot); assertEquals(0, fake.closes)
        // Retry: the side handle is durably closed first, then the SAME command is resubmitted.
        fake.failSubmit = null
        fake.afterSubmit = { fake.current = fake.current.copy(notebook = "Physics", localSequence = 2u) }
        repo.retry()
        assertSame(command, fake.commands.last()); assertEquals(2, fake.commands.size)
        assertEquals(2, fake.closes); assertEquals(2, fake.opens)
        assertNull(repo.state.value.libraryTask); assertNull(repo.state.value.error); assertNull(repo.state.value.snapshot)
        assertEquals("Physics", repo.state.value.notes.first { it.id == "note" }.notebook) // read back from the core
    }
    @Test fun lostMoveReplyIsResolvedByLookupNotResubmitted() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler))
        fake.afterSubmit = { throw CoreException.StorageIo("reply lost", true) }
        repo.renameNote("note", "Renamed")
        assertNotNull(repo.state.value.libraryTask)
        fake.afterSubmit = null; repo.retry()
        assertEquals(1, fake.commands.size); assertTrue(fake.commands.single().body is CommandBody.RenameNote)
        assertNull(repo.state.value.libraryTask)
        repo.releaseLibrary(); assertEquals(fake.opens, fake.closes); assertEquals(1, fake.releases)
    }
    @Test fun notebookRenameRetriesTheSameCallAndCreateDeleteReloadFromCore() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.loadLibrary()
        repo.createNotebook("Physics"); assertEquals(listOf("Physics"), repo.state.value.notebooks.map { it.name })
        fake.failRenameNotebook = true; repo.renameNotebook("Physics", "Mechanics")
        assertNotNull(repo.state.value.libraryTask); assertNotNull(repo.state.value.error)
        fake.failRenameNotebook = false; repo.retry()
        assertEquals(listOf("Physics" to "Mechanics", "Physics" to "Mechanics"), fake.renamedNotebooks)
        assertEquals(listOf("Mechanics"), repo.state.value.notebooks.map { it.name }); assertNull(repo.state.value.libraryTask)
        repo.deleteNotebook("Mechanics"); assertTrue(repo.state.value.notebooks.isEmpty())
    }
    @Test fun leavingBuildsThumbnailFromDurableSnapshotWithoutExtraCoreReads() = runTest {
        val fake = Fake(); fake.current = snapshot(strokes = listOf(stroke("a", "head")))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        val reads = fake.sampleReads; val opens = fake.opens
        assertTrue(repo.leave())
        val thumbnail = repo.state.value.thumbnails.getValue("note")
        assertEquals(1, thumbnail.strokes.size); assertEquals(fake.current.localSequence, thumbnail.localSequence)
        assertEquals(reads, fake.sampleReads); assertEquals(opens, fake.opens)
        // Library thumbnails for the other note open a side handle on the worker and close it durably.
        repo.loadThumbnails()
        assertEquals(setOf("note", "old"), repo.state.value.thumbnails.keys)
        assertEquals(fake.opens, fake.closes)
    }

    private fun thumbnail(seq: ULong) = Thumbnail(seq, 1600f, 3200f, emptyList())

    @Test fun archivingClosedNoteSubmitsOneSetArchivedRetriedUnchangedAndUnarchiveIsTheSamePath() = runTest {
        val fake = Fake(); fake.current = snapshot().copy(metadataHead = EntityHead("meta", null))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.loadLibrary()
        fake.failSubmit = CoreException.StorageIo("disk", true)
        repo.archiveNote("note", true)
        val task = repo.state.value.libraryTask as LibraryTask.EditNote
        val command = task.command!!
        assertEquals(CommandBody.SetArchived(true, Base.Operation("meta")), command.body)
        assertEquals(listOf("meta"), command.dependencies)
        assertEquals("Couldn't archive the note. Nothing changed.", dev.syncr.prototype.ui.libraryTaskFailure(task))
        assertFalse(repo.state.value.notes.first { it.id == "note" }.archived) // never shown before the receipt
        fake.failSubmit = null
        fake.afterSubmit = { fake.current = fake.current.copy(archived = true, localSequence = 2u) }
        repo.retry()
        assertSame(command, fake.commands.last()); assertEquals(fake.opens, fake.closes)
        assertNull(repo.state.value.libraryTask)
        assertTrue(repo.state.value.notes.first { it.id == "note" }.archived) // read back from the core
        fake.current = fake.current.copy(metadataHead = EntityHead(command.operationId, null))
        fake.afterSubmit = { fake.current = fake.current.copy(archived = false, localSequence = 3u) }
        repo.archiveNote("note", false)
        assertEquals(CommandBody.SetArchived(false, Base.Operation(command.operationId)), fake.commands.last().body)
        assertFalse(repo.state.value.notes.first { it.id == "note" }.archived)
    }
    @Test fun archivingTheOpenNoteStagesOneMetadataCommand() = runTest {
        val fake = Fake(); fake.current = snapshot().copy(metadataHead = EntityHead("meta", null))
        val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        fake.afterSubmit = { fake.current = fake.current.copy(archived = true, localSequence = 2u, cursor = EventCursor("stream", 2u)) }
        repo.archiveOpenNote(true)
        assertEquals(Command("id-1", listOf("meta"), CommandBody.SetArchived(true, Base.Operation("meta"))), fake.commands.single())
        assertTrue(repo.state.value.snapshot!!.archived); assertFalse(repo.state.value.unsaved)
    }
    @Test fun deleteMovesToRecentlyDeletedOffersUndoAndUndoRestores() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.loadLibrary()
        repo.deleteNote("note")
        assertEquals(listOf("delete:note"), fake.trashCalls)
        assertEquals("note", repo.state.value.recentlyDeleted)
        assertEquals(listOf("old"), repo.state.value.notes.map { it.id })
        assertEquals(listOf("note"), repo.state.value.deleted.map { it.id })
        assertNull(repo.state.value.libraryTask); assertNull(repo.state.value.error)
        repo.undoDelete()
        assertEquals(listOf("delete:note", "restore:note"), fake.trashCalls)
        assertNull(repo.state.value.recentlyDeleted)
        assertEquals(setOf("note", "old"), repo.state.value.notes.map { it.id }.toSet())
        assertTrue(repo.state.value.deleted.isEmpty())
        repo.undoDelete(); assertEquals(2, fake.trashCalls.size) // nothing left to undo
    }
    @Test fun failedDeleteLeavesTheNoteVisibleWithAnErrorAndRetryIsTheSameCall() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.loadLibrary()
        fake.failDelete = CoreException.StorageIo("disk", true)
        repo.deleteNote("note")
        assertEquals(LibraryTask.DeleteNote("note"), repo.state.value.libraryTask)
        assertEquals("StorageIo: disk", repo.state.value.error)
        assertEquals("Couldn't move the note to Recently deleted. It is still in your library.",
            dev.syncr.prototype.ui.libraryTaskFailure(repo.state.value.libraryTask))
        assertTrue(repo.state.value.notes.any { it.id == "note" }); assertNull(repo.state.value.recentlyDeleted)
        repo.deleteNote("old") // one library task at a time
        assertEquals(listOf("delete:note"), fake.trashCalls)
        fake.failDelete = null; repo.retry()
        assertEquals(listOf("delete:note", "delete:note"), fake.trashCalls)
        assertFalse(repo.state.value.notes.any { it.id == "note" }); assertEquals("note", repo.state.value.recentlyDeleted)
        assertNull(repo.state.value.error)
    }
    @Test fun aNoteIsNeverDeletedFromTheLibraryWhileOpen() = runTest {
        val fake = Fake(); val repo = repo(fake, StandardTestDispatcher(testScheduler)); repo.open("note")
        repo.deleteNote("note"); repo.archiveNote("note", true)
        assertTrue(fake.trashCalls.isEmpty()); assertTrue(fake.commands.isEmpty()); assertNull(repo.state.value.libraryTask)
        assertTrue(repo.leave()); repo.deleteNote("note")
        assertEquals(listOf("delete:note"), fake.trashCalls); assertEquals(fake.opens, fake.closes)
    }
    @Test fun restoreAndDeleteForeverFromRecentlyDeletedDropTheThumbnailOnlyWhenPurged() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("thumbs").toFile()
        try {
            val fake = Fake(); var ids = 0
            val repo = NotesRepository("/unused", fake, StandardTestDispatcher(testScheduler), thumbnailDir = dir, now = { fake.clock }) { "id-${++ids}" }
            ThumbnailCache.write(dir, "note", thumbnail(1u)); ThumbnailCache.write(dir, "old", thumbnail(1u))
            repo.loadLibrary(); repo.deleteNote("note"); repo.dismissDeleted()
            assertNull(repo.state.value.recentlyDeleted)
            repo.loadLibrary() // a deleted note keeps its card for a restore
            assertNotNull(ThumbnailCache.read(dir, "note", 1u))
            fake.failRestore = CoreException.IdCollision("taken", false)
            repo.restoreNote("note")
            assertEquals("Couldn't restore the note. It is still in Recently deleted.", dev.syncr.prototype.ui.libraryTaskFailure(repo.state.value.libraryTask))
            repo.dismissMessage(); fake.failRestore = null
            repo.restoreNote("note"); assertTrue(repo.state.value.notes.any { it.id == "note" })
            repo.deleteNote("note")
            fake.failPurge = CoreException.StorageIo("disk", true); repo.purgeNote("note")
            assertEquals(listOf("note"), repo.state.value.deleted.map { it.id }); assertNotNull(repo.state.value.error)
            assertNotNull(ThumbnailCache.read(dir, "note", 1u))
            fake.failPurge = null; repo.retry()
            assertTrue(repo.state.value.deleted.isEmpty()); assertEquals(listOf("note"), fake.purged)
            assertNull(ThumbnailCache.read(dir, "note", 1u)); assertNotNull(ThumbnailCache.read(dir, "old", 1u))
            assertFalse("note" in repo.state.value.thumbnails)
        } finally { dir.deleteRecursively() }
    }
    @Test fun libraryLoadPurgesOnlyNotesDeletedOver30DaysAgoAndTheirThumbnails() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("thumbs").toFile()
        try {
            val fake = Fake(); var ids = 0
            val repo = NotesRepository("/unused", fake, StandardTestDispatcher(testScheduler), thumbnailDir = dir, now = { fake.clock }) { "id-${++ids}" }
            ThumbnailCache.write(dir, "note", thumbnail(1u)); ThumbnailCache.write(dir, "old", thumbnail(1u))
            ThumbnailCache.write(dir, "GONE", thumbnail(1u)) // orphan from an interrupted purge
            val day = 24L * 60 * 60 * 1000
            fake.deleted["note"] = fake.clock - 31 * day
            fake.deleted["old"] = fake.clock - 29 * day
            repo.loadLibrary()
            assertEquals(listOf("purgeOlderThan:${fake.clock - 30 * day}"), fake.trashCalls)
            assertEquals(listOf("note"), fake.purged); assertEquals(listOf("old"), repo.state.value.deleted.map { it.id })
            assertNull(ThumbnailCache.read(dir, "note", 1u)); assertNull(ThumbnailCache.read(dir, "GONE", 1u))
            assertNotNull(ThumbnailCache.read(dir, "old", 1u))
            repo.loadLibrary(); assertEquals(1, fake.trashCalls.size) // nothing expired: no core call
            // A purge failure is housekeeping: no error, the expired note simply stays listed.
            fake.clock += 2 * day; fake.failPurge = CoreException.StorageIo("disk", true)
            repo.loadLibrary()
            assertNull(repo.state.value.error); assertEquals(listOf("old"), repo.state.value.deleted.map { it.id })
            assertNotNull(ThumbnailCache.read(dir, "old", 1u))
        } finally { dir.deleteRecursively() }
    }
}
