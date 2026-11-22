package dev.syncr.example

import dev.syncr.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Binding consumer, not a mock repository. Execute blocking calls only on an IO
 * worker in Android. Draft IDs and sample bytes must be retained across retries.
 * Executed by the host smoke against the real SQLite-backed Rust implementation.
 */
fun createAddSnapshotReopen(root: String): NoteSnapshot {
    val library = openLibrary(root)
    try {
        val request = CreateNoteRequest(newId(), newId(), newId(), "Example", null, NoteKind.NOTE)
        library.createNote(request)
        check(library.lookupCreation(request.operationId) is OperationLookup.Committed)
        check(library.createNote(request).duplicate)
        check(library.listNotes().single().id == request.noteId)
        val note = library.openNote(request.noteId)
        try {
            val initial = note.snapshot()
            check(initial.sheets.single().creationOperationId == request.operationId)
            check(initial.sheets.single().originX == 0f && initial.sheets.single().originY == 0f)
            check(initial.undoDependencies.isEmpty() && initial.redoDependencies.isEmpty())
            val markId = newId()
            val command = Command(
                operationId = newId(),
                dependencies = listOf(initial.sheets.single().creationOperationId),
                body = CommandBody.Marks(listOf(MarkMutation.AddStroke(
                    markId = markId,
                    sheetId = request.firstSheetId,
                    samples = encodeSamples(SampleChannels(
                        x = listOf(10f, 20f), y = listOf(30f, 40f),
                        pressure = listOf(0.5f, 1f), elapsedMs = listOf(0u, 10u),
                        tilt = null, orientation = null,
                    )),
                    brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK),
                ))),
            )
            val before = note.snapshot()
            val receipt = note.submit(command)
            check(receipt.localSequence == 2uL && !receipt.duplicate)
            check(note.submit(command).duplicate)
            try {
                note.submit(command.copy(body = CommandBody.RenameNote("Collision", Base.Absent)))
                error("Different same-ID payload was accepted")
            } catch (error: CoreException.IdCollision) {
                checkDiagnostic(error, error.detail, error.retryable, "This ID already identifies different data", false)
            }
            check(note.lookupOperation(command.operationId) is OperationLookup.Committed)
            val changes = note.changesSince(before.cursor, 256u) as ChangePage.Events
            check(changes.items.single().operationId == command.operationId)
            val snapshot = note.snapshot()
            check(snapshot.localSequence >= receipt.localSequence)
            check(snapshot.strokes.any { it.id == markId && it.head.operationId == command.operationId })
            check(decodeSamples(note.getSamples(markId)).x == listOf(10f, 20f))
            val erase = Command(newId(), listOf(command.operationId), CommandBody.Marks(listOf(
                MarkMutation.DeleteStroke(markId, Base.Operation(command.operationId)),
            )))
            note.submit(erase)
            check(note.snapshot().strokes.single().tombstone)
            val undoSnapshot = note.snapshot()
            check(undoSnapshot.undoDependencies == listOf(erase.operationId))
            val undo = Command(newId(), undoSnapshot.undoDependencies, CommandBody.Undo(undoSnapshot.undoTarget!!))
            note.submit(undo)
            val redoSnapshot = note.snapshot()
            check(!redoSnapshot.strokes.single().tombstone)
            check(redoSnapshot.redoDependencies == listOf(undo.operationId))
            val redo = Command(newId(), redoSnapshot.redoDependencies, CommandBody.Redo(redoSnapshot.redoTarget!!))
            note.submit(redo)
            check(note.snapshot().strokes.single().tombstone)
            val export = Files.createTempDirectory("syncr-export-smoke-")
            try {
                val backupPath = export.resolve("backup.syncr")
                check(note.exportCopy(backupPath.toString()).exportedLocalSequence == 5uL)
                val backupLibrary = openLibrary(export.toString())
                try {
                    val backupNote = backupLibrary.openNote(request.noteId)
                    try {
                        check(backupNote.snapshot().localSequence == 5uL)
                        check(backupNote.snapshot().undoTarget == null) // Different installation identity.
                        check(backupNote.snapshot().undoDependencies.isEmpty())
                        check(backupNote.snapshot().redoDependencies.isEmpty())
                        backupNote.closeNote()
                    } finally { backupNote.close() }
                } finally { backupLibrary.close() }
            } finally { export.toFile().deleteRecursively() }
            note.closeNote() // Fallible core checkpoint/close, not a UI discard decision.
            try {
                note.snapshot()
                error("Closed note was usable")
            } catch (error: CoreException.Closed) {
                checkDiagnostic(error, error.detail, error.retryable, "Note is closed", false)
            }
        } finally {
            note.destroy() // Release FFI reference; NOT a durable-close substitute.
        }
        val reopened = library.openNote(request.noteId)
        try {
            val result = reopened.snapshot()
            check(result.localSequence == 5uL)
            check(result.strokes.single().tombstone)
            check(result.undoTarget != null)
            check(result.undoDependencies == listOf(result.strokes.single().head.operationId))
            check(result.redoDependencies.isEmpty())
            reopened.closeNote()
            reopened.closeNote()
            return result
        } finally { reopened.destroy() }
    } finally { library.destroy() }
}

private fun typedText() {
    val root = Files.createTempDirectory("syncr-kotlin-text-")
    try {
        openLibrary(root.toString()).use { library ->
            val r = CreateNoteRequest(newId(), newId(), newId(), "Text", null, NoteKind.NOTE)
            library.createNote(r)
            library.openNote(r.noteId).use { note ->
                val initial = note.snapshot()
                val oldShape = NoteSnapshot(initial.noteId, initial.title, initial.notebook, initial.metadataHead,
                    initial.structureHead, initial.sheets, initial.strokes, initial.localSequence, initial.cursor,
                    null, null, emptyList(), emptyList(), kind = NoteKind.NOTE)
                check(oldShape.textBlocks.isEmpty())
                val block = newId()
                val add = Command(newId(), listOf(r.operationId), CommandBody.Marks(listOf(
                    MarkMutation.AddText(block, r.firstSheetId, TextRole.DOCUMENT, 0f, 0f, 640f,
                        "Aé🙂\n", TextStyle(BrushSize.MEDIUM, InkColour.INK)),
                )))
                note.submit(add)
                check(note.submit(add).duplicate)
                val splice = Command(newId(), listOf(add.operationId), CommandBody.Marks(listOf(
                    MarkMutation.SpliceText(block, Base.Operation(add.operationId), 1u, 2u, "字"),
                )))
                note.submit(splice)
                check(note.snapshot().textBlocks.single().text == "A字🙂\n")
                val undo = note.snapshot()
                note.submit(Command(newId(), undo.undoDependencies, CommandBody.Undo(undo.undoTarget!!)))
                check(note.snapshot().textBlocks.single().text == "Aé🙂\n")
                note.closeNote()
            }
            library.openNote(r.noteId).use { note ->
                val redo = note.snapshot()
                note.submit(Command(newId(), redo.redoDependencies, CommandBody.Redo(redo.redoTarget!!)))
                check(note.snapshot().textBlocks.single().text == "A字🙂\n")
                note.closeNote()
            }
        }
        println("PASS: Kotlin text add/splice/retry/undo/reopen/redo and snapshot default")
    } finally { root.toFile().deleteRecursively() }
}

private fun checkDiagnostic(
    error: CoreException, detail: String, retryable: Boolean,
    expectedDetail: String, expectedRetryable: Boolean,
) {
    check(detail == expectedDetail)
    check(retryable == expectedRetryable)
    check(error.message == "detail=$expectedDetail, retryable=$expectedRetryable")
    println("PASS: ${error.javaClass.simpleName}: detail=$detail, retryable=$retryable; message=${error.message}")
}

private fun copyFixture(root: java.nio.file.Path, name: String) =
    Files.copy(java.nio.file.Paths.get("fixtures/canvas-v2", name), root.resolve(name))

/**
 * Canvas amendment through the generated API on a real v2 note written by the pre-canvas core
 * (Phone pages, three sheets, a reorder in its log): it upgrades on open after a backup, each
 * sheet gets its fixed origin with the gaps closed, per-sheet creation provenance survives,
 * sheets can no longer be added or reordered, and history persists across full restarts.
 */
private fun legacyMultiSheetCanvas() {
    val root = Files.createTempDirectory("syncr-canvas-smoke-")
    try {
        val noteFiles = Files.list(java.nio.file.Paths.get("fixtures/canvas-v2")).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".syncr") }.sorted().toList()
        }
        for (file in noteFiles) copyFixture(root, file)
        copyFixture(root, "library.sqlite3")
        var lastAdd: Command? = null
        var phoneId = ""
        openLibrary(root.toString()).use { library ->
            val notes = library.listNotes()
            check(notes.size == 2)
            check(!root.resolve(".backups").toFile().exists()) // Listing never upgrades.
            val phone = notes.single { it.notebook == "Fixtures" }
            phoneId = phone.id
            library.openNote(phone.id).use { note ->
                val s = note.snapshot()
                check(s.sheets.size == 3)
                check(s.sheets.map { it.originX } == listOf(0f, 0f, 0f))
                check(s.sheets.map { it.originY } == listOf(0f, 3200f, 6400f)) // Phone pages, no gap.
                val backups = root.resolve(".backups").toFile().listFiles()!!.map { it.name }
                check(backups.size == 1 && backups.single().startsWith("${phone.id}.v2.") && backups.single().endsWith(".syncr"))
                val head = s.structureHead.operationId
                for (body in listOf(
                    CommandBody.AddSheet(newId(), Base.Operation(head)),
                    CommandBody.ReorderSheets(s.sheets.reversed().map { it.id }, Base.Operation(head)),
                )) {
                    val c = Command(newId(), listOf(head), body)
                    try { note.submit(c); error("Sheet structure changed on a canvas note") }
                    catch (error: CoreException.InvalidInput) {
                        checkDiagnostic(error, error.detail, error.retryable, "A note is one canvas: sheets can no longer be added or reordered", false)
                    }
                    check(note.lookupOperation(c.operationId) is OperationLookup.NotRecorded)
                    check(note.snapshot() == s)
                }
                for (sheet in s.sheets) {
                    val c = Command(newId(), listOf(sheet.creationOperationId), CommandBody.Marks(listOf(
                        MarkMutation.AddStroke(newId(), sheet.id, encodeSamples(SampleChannels(listOf(1f), listOf(2f), listOf(0.5f), listOf(0u), null, null)), Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)),
                    )))
                    val wrong = c.copy(operationId = newId(), dependencies = listOf(s.structureHead.operationId))
                    if (wrong.dependencies != c.dependencies) {
                        try { note.submit(wrong); error("Structure head replaced sheet creator") }
                        catch (_: CoreException.MissingDependency) { check(note.lookupOperation(wrong.operationId) is OperationLookup.NotRecorded) }
                    }
                    note.submit(c)
                    check(note.submit(c).duplicate)
                    check(note.snapshot().undoDependencies == listOf(c.operationId))
                    lastAdd = c
                }
                // New ink goes to the first sheet, in canvas coordinates, right of the phone column.
                val first = s.sheets.first()
                val far = Command(newId(), listOf(first.creationOperationId), CommandBody.Marks(listOf(
                    MarkMutation.AddStroke(newId(), first.id, encodeSamples(SampleChannels(listOf(4500f, 4510f), listOf(9000f, 9005f), listOf(0.5f, 0.5f), listOf(0u, 8u), null, null)), Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)),
                )))
                note.submit(far)
                lastAdd = far
                val top = note.snapshot()
                note.submit(Command(newId(), top.undoDependencies, CommandBody.Undo(top.undoTarget!!)))
                check(note.snapshot().redoTarget == far.operationId)
                note.closeNote()
            }
        }
        openLibrary(root.toString()).use { library ->
            library.openNote(phoneId).use { note ->
                val s = note.snapshot()
                check(s.sheets.map { it.originY } == listOf(0f, 3200f, 6400f))
                check(s.redoTarget == lastAdd!!.operationId)
                check(s.redoDependencies.size == 1)
                val redo = Command(newId(), s.redoDependencies, CommandBody.Redo(s.redoTarget!!))
                note.submit(redo)
                val after = note.snapshot()
                check(after.undoDependencies == listOf(redo.operationId))
                check(after.redoDependencies.isEmpty())
                check(note.submit(lastAdd!!).duplicate) // Old immutable command, not a refresh.
                check(note.snapshot() == after)
                note.closeNote()
            }
            check(root.resolve(".backups").toFile().listFiles()!!.size == 1) // Already a canvas: no new backup.
        }
        println("PASS: v2 multi-sheet note upgraded to one canvas with backup; origins without gaps; provenance; sheet commands rejected; history across restarts")
    } finally { root.toFile().deleteRecursively() }
}

/** Stroke mesh for hosts without an ink engine: default styles, triangle layout, bounds and errors. */
private fun strokeMesh() {
    val style = defaultStrokeStyle(Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK))
    check(style.size == 4f && style.pressureHigh > style.pressureLow && !style.discardSelfOverlap)
    val samples = encodeSamples(SampleChannels(listOf(10f, 30f, 60f), listOf(10f, 12f, 20f), listOf(0.3f, 0.5f, 0.4f),
        listOf(0u, 40u, 90u), null, null))
    val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    val mesh = tessellateStroke(samples, style, identity)
    check(mesh.rendererVersion == meshRendererVersion())
    check(mesh.vertices.isNotEmpty() && mesh.vertices.size % 8 == 0)
    check(mesh.indices.isNotEmpty() && mesh.indices.size % 12 == 0)
    val vertexCount = mesh.vertices.size / 8
    val indices = ByteBuffer.wrap(mesh.indices).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    check((0 until indices.limit()).all { indices[it] in 0 until vertexCount })
    val coords = ByteBuffer.wrap(mesh.vertices).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    check((0 until coords.limit()).all { coords[it].isFinite() })
    check(mesh.minX < 10f && mesh.maxX > 60f && mesh.minY < 10f && mesh.maxY > 20f)
    val again = tessellateStroke(samples, style, identity)
    check(again.vertices.contentEquals(mesh.vertices) && again.indices.contentEquals(mesh.indices))
    val tap = tessellateStroke(encodeSamples(SampleChannels(listOf(100f), listOf(100f), listOf(0.3f), listOf(0u), null, null)),
        style, identity)
    check(tap.maxX - tap.minX in 2f..6f)
    val highlighter = defaultStrokeStyle(Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.GREEN))
    check(tessellateStroke(samples, highlighter, identity).discardSelfOverlap)
    try {
        tessellateStroke(byteArrayOf(1, 2, 3), style, identity)
        error("Invalid samples were accepted")
    } catch (error: CoreException.InvalidInput) {
        check(!error.retryable)
    }
    println("PASS: stroke mesh defaults, layout, bounds, determinism, tap, highlighter overlap and invalid input")
}

/** Canvas size, flat notebooks and display timestamps through the generated API. */
private fun canvasAndNotebooks() {
    val root = Files.createTempDirectory("syncr-notebooks-smoke-")
    try {
        openLibrary(root.toString()).use { library ->
            check(canvasSize() == CanvasSize(4800f, 3000f, 1600f))
            val phone = CreateNoteRequest(newId(), newId(), newId(), "Phone", "Physics", NoteKind.NOTE)
            library.createNote(phone)
            check(library.createNote(phone).duplicate)
            try {
                library.createNote(phone.copy(notebook = null))
                error("Different notebook matched the creation")
            } catch (_: CoreException.IdCollision) {}
            try {
                library.createNote(phone.copy(operationId = newId(), noteId = newId(), notebook = "Inbox"))
                error("Reserved notebook name accepted")
            } catch (error: CoreException.InvalidInput) {
                checkDiagnostic(error, error.detail, error.retryable, "Inbox is reserved; use no notebook for Inbox", false)
            }
            val summary = library.listNotes().single()
            check(summary.notebook == "Physics")
            check(summary.createdAtMs > 0 && summary.modifiedAtMs >= summary.createdAtMs)
            library.createNotebook("Journal")
            check(library.listNotebooks() == listOf(NotebookSummary("Journal", 0uL), NotebookSummary("Physics", 1uL)))
            library.openNote(phone.noteId).use { note ->
                val s = note.snapshot()
                check(s.sheets.single().originX == 0f && s.sheets.single().originY == 0f)
                check(s.notebook == "Physics")
                val head = s.metadataHead.operationId
                val move = Command(newId(), listOf(head), CommandBody.SetNotebook("Journal", Base.Operation(head)))
                note.submit(move)
                check(note.submit(move).duplicate)
                check(note.snapshot().notebook == "Journal" && note.snapshot().undoTarget == null)
                library.renameNotebook("Journal", "Diary")
                check(note.snapshot().notebook == "Diary")
                try {
                    library.deleteNotebook("Diary")
                    error("Deleted a notebook that still has notes")
                } catch (_: CoreException.InvalidInput) {}
                note.closeNote()
            }
            check(library.listNotebooks() == listOf(NotebookSummary("Diary", 1uL)))
        }
        println("PASS: canvas size, creation identity, notebooks (set/rename/delete guard) and display timestamps")
    } finally { root.toFile().deleteRecursively() }
}

/** Archive metadata and Recently deleted (delete, restore, purge) through the generated API. */
private fun archiveAndRecentlyDeleted() {
    val root = Files.createTempDirectory("syncr-trash-smoke-")
    try {
        openLibrary(root.toString()).use { library ->
            val request = CreateNoteRequest(newId(), newId(), newId(), "Trash", null, NoteKind.NOTE)
            library.createNote(request)
            library.openNote(request.noteId).use { note ->
                val head = note.snapshot().metadataHead.operationId
                val archive = Command(newId(), listOf(head), CommandBody.SetArchived(true, Base.Operation(head)))
                note.submit(archive)
                check(note.submit(archive).duplicate)
                check(note.snapshot().archived && note.snapshot().undoTarget == null)
                try {
                    library.deleteNote(request.noteId)
                    error("Deleted a note with a live handle")
                } catch (error: CoreException.NoteLocked) {
                    checkDiagnostic(error, error.detail, error.retryable, "Close the note before deleting it", true)
                }
                note.closeNote()
            }
            check(library.listNotes().single().archived)
            library.deleteNote(request.noteId)
            library.deleteNote(request.noteId)
            check(library.listNotes().isEmpty())
            val deleted = library.listDeletedNotes().single()
            check(deleted.id == request.noteId && deleted.title == "Trash" && deleted.deletedAtMs >= deleted.createdAtMs)
            check(library.lookupCreation(request.operationId) is OperationLookup.Committed)
            check(library.createNote(request).duplicate && library.listNotes().isEmpty())
            library.restoreNote(request.noteId)
            check(library.listNotes().single().archived && library.listDeletedNotes().isEmpty())
            library.deleteNote(request.noteId)
            check(library.purgeDeletedOlderThan(Long.MAX_VALUE) == 1u)
            check(library.listDeletedNotes().isEmpty() && library.lookupCreation(request.operationId) is OperationLookup.NotRecorded)
            try {
                library.createNote(request)
                error("A purged note was recreated")
            } catch (_: CoreException.IdCollision) {}
        }
        println("PASS: archive metadata, delete with live-handle refusal, Recently deleted listing, restore, purge and no resurrection")
    } finally { root.toFile().deleteRecursively() }
}

/** Native host smoke: exercises real Rust codec, errors and library identity. */
private fun previewStream() {
    val noteId = newId(); val sheetId = newId(); val strokeId = newId()
    fun frame(seq: UInt, body: PreviewBody, stroke: String? = strokeId) =
        PreviewFrame(epoch = 1u, noteId = noteId, sheetId = sheetId, strokeId = stroke, sequence = seq, sentMs = 5000uL + seq.toULong(), body = body)
    val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    val channels = SampleChannels(listOf(1f, 2f), listOf(3f, 4f), listOf(0.5f, 0.6f), listOf(0u, 8u), null, null)
    val frames = listOf(
        frame(0u, PreviewBody.Begin(brush, 1u, CoordinateSpace.CANVAS)),
        frame(1u, PreviewBody.Samples(0u, channels)),
        frame(2u, PreviewBody.End(2u)),
    )
    val queue = PreviewSendQueue()
    queue.pushPreview(frame(9u, PreviewBody.Hover(5f, 6f), null))
    for (f in frames) queue.pushPreview(f)
    val receiver = PreviewReceiver(1u)
    val events = mutableListOf<PreviewEvent>()
    while (true) {
        val bytes = queue.pop() ?: break
        check(frameKind(bytes) == FrameKind.PREVIEW && bytes.size <= previewLimits().maxFrameBytes.toInt())
        check(decodePreviewFrame(bytes) == decodePreviewFrame(encodePreviewFrame(decodePreviewFrame(bytes))))
        events += receiver.accept(bytes, 100uL)
    }
    check(events.size == 4)
    check(events[0] is PreviewEvent.HoverMoved && events[1] is PreviewEvent.StrokeStarted)
    check((events[2] as PreviewEvent.SamplesAppended).channels == channels)
    check(events[3] is PreviewEvent.StrokeEnded)
    check(receiver.commit(strokeId).single() == PreviewEvent.Dropped(strokeId, PreviewDropReason.COMMITTED))
    check(receiver.tick(10_000uL).isEmpty())
    try { receiver.accept(byteArrayOf(1, 2, 3), 0uL); error("garbage accepted") } catch (_: CoreException.InvalidInput) {}
    val commit = StrokeCommit(noteId, sheetId, strokeId, brush, CoordinateSpace.VIEW_RELATIVE, encodeSamples(channels))
    val chunks = encodeStrokeCommit(commit)
    check(chunks.size == 1 && frameKind(chunks[0]) == FrameKind.STROKE_CHUNK)
    val back = StrokeReassembler().accept(chunks[0], 0uL)!! // ByteArray fields compare by identity, so compare the parts
    check(back.copy(samples = commit.samples) == commit && back.samples.contentEquals(commit.samples))
    println("PASS: preview frames, sender queue, receiver events, commit, stroke chunk reassembly and typed errors")
}

private fun imageSmoke() {
    val root = java.nio.file.Files.createTempDirectory("syncr-kotlin-image-").toFile()
    try {
        val library = openLibrary(root.path)
        val request = CreateNoteRequest(newId(), newId(), newId(), "Image", null, NoteKind.NOTE)
        library.createNote(request)
        val note = library.openNote(request.noteId)
        val bytes = java.io.File("fixtures/image-v2/pixel.png").readBytes()
        val id = newId()
        val operation = newId()
        note.submit(Command(operation, listOf(request.operationId), CommandBody.ImageMarks(listOf(
            ImageMutation.Add(id, bytes, ImageMediaType.PNG, 1u, 1u,
                ImagePlacement.Canvas(request.firstSheetId, 0.0, 0.0, 100.0, 100.0))
        ))))
        note.submitAgent(Command(newId(), listOf(operation), CommandBody.ImageMarks(listOf(
            ImageMutation.MarkSeen(id, Base.Operation(operation))
        ))))
        check(note.getImageBytes(id).contentEquals(bytes))
        check(note.snapshot().images[0].seenByCompanion)
        note.closeNote()
        println("PASS: Kotlin image add/read/seen")
    } finally { root.deleteRecursively() }
}

fun main() {
    imageSmoke()
    val clean = filterPicture("""<svg viewBox="0 0 320 100"><g data-step="1" data-caption="A line"><line x2="80" stroke="var(--voice)"/><text x="10" y="30">Label</text></g></svg>""")
    check(filterPicture(clean) == clean)
    val picture = pictureDisplayList(clean)
    check(picture.width == 320f && picture.steps.size == 1 && picture.steps[0].items.size == 2)
    println("PASS: Kotlin picture filter and display list")
    val channels = SampleChannels(
        x = listOf(1f, -0.0f), y = listOf(2f, -3f),
        pressure = listOf(0f, 1f), elapsedMs = listOf(0u, 42u),
        tilt = null, orientation = null,
    )
    val blob = encodeSamples(channels)
    check(decodeSamples(blob) == channels)
    check(decodeSamples(blob).x[1].toRawBits() == (-0.0f).toRawBits())
    check(encodeSamples(decodeSamples(blob)).contentEquals(blob))
    for ((tilt, orientation) in listOf(
        Pair(listOf(0f, 1f), null),
        Pair(null, listOf(-1f, 1f)),
        Pair(listOf(0f, 1f), listOf(-1f, 1f)),
    )) {
        val optional = channels.copy(tilt = tilt, orientation = orientation)
        val encoded = encodeSamples(optional)
        check(decodeSamples(encoded) == optional)
        check(encodeSamples(decodeSamples(encoded)).contentEquals(encoded))
    }
    try {
        decodeSamples(byteArrayOf(1, 2, 3))
        error("Malformed samples were accepted")
    } catch (error: CoreException.InvalidInput) {
        checkDiagnostic(error, error.detail, error.retryable, "Invalid sample header", false)
    }
    try {
        decodeSamples(blob.copyOf().also { it[4] = 2 })
        error("Newer sample encoding was accepted")
    } catch (error: CoreException.UnsupportedVersion) {
        checkDiagnostic(error, error.detail, error.retryable, "Unsupported sample encoding version", false)
    }

    // Exercise unsigned record serialization through Rust-owned FFI buffers.
    val unsigned = Receipt(newId(), ULong.MAX_VALUE, EventCursor(newId(), ULong.MAX_VALUE), false)
    check(FfiConverterTypeReceipt.lift(FfiConverterTypeReceipt.lower(unsigned)) == unsigned)

    syncSmoke()
    typedText()
    legacyMultiSheetCanvas()
    canvasAndNotebooks()
    archiveAndRecentlyDeleted()
    strokeMesh()
    previewStream()
    val root = Files.createTempDirectory("syncr-jni-smoke-")
    try {
        val library: SyncrLibrary = openLibrary(root.toString())
        val author = library.authorId()
        try {
            try {
                openLibrary(root.toString())
                error("Second writer acquired the root")
            } catch (error: CoreException.NoteLocked) {
                checkDiagnostic(error, error.detail, error.retryable, "Library is already open by a writer", true)
            }
        } finally { library.close() } // AutoCloseable releases the library FFI handle/lock only.
        library.close() // Wrapper release is idempotent; this is NOT Note.closeNote().
        val reopened: SyncrLibrary = openLibrary(root.toString())
        try { check(reopened.authorId() == author) } finally { reopened.close() }
        val durable = createAddSnapshotReopen(root.toString())
        check(root.toFile().listFiles()!!.count { it.extension == "syncr" } == 1)
        val finalLibrary = openLibrary(root.toString())
        try {
            check(finalLibrary.authorId() == author)
            val finalNote = finalLibrary.openNote(durable.noteId)
            try {
                check(finalNote.snapshot().localSequence == durable.localSequence)
                check(finalNote.snapshot().strokes == durable.strokes)
                finalNote.closeNote()
            } finally { finalNote.close() }
        } finally { finalLibrary.close() }
        println("PASS: Rust codec, typed errors, unsigned buffers, process lock and identity persistence")
        println("PASS: native SQLite create/add/snapshot/erase/undo/redo/export/closeNote/reopen; immutable retry and library restart")
    } finally { root.toFile().deleteRecursively() }
}

fun syncSmoke() {
    val tmp = Files.createTempDirectory("syncr-jni-sync-")
    try {
        openLibrary(tmp.resolve("mac").toString()).use { macLib ->
            openLibrary(tmp.resolve("phone").toString()).use { phoneLib ->
                val r = CreateNoteRequest(newId(), newId(), newId(), "Sync", null, NoteKind.NOTE)
                macLib.createNote(r)
                val mac = macLib.openNote(r.noteId)
                val copy = tmp.resolve("copy.syncr").toString()
                mac.exportCopy(copy)
                check(phoneLib.installCopy(copy).id == r.noteId)
                val phone = phoneLib.openNote(r.noteId)
                val head = mac.snapshot().metadataHead.operationId
                mac.submit(Command(newId(), listOf(head), CommandBody.RenameNote("Renamed on Mac", Base.Operation(head))))
                val copy2 = tmp.resolve("copy2.syncr").toString()
                mac.exportCopy(copy2)
                val report = phone.mergeCopy(copy2)
                check(report.applied == 1u && report.rejected.isEmpty())
                check(phone.snapshot().title == "Renamed on Mac")
                check(phone.syncDigest() == mac.syncDigest())
                check(phone.mergeCopy(copy2).applied == 0u)
                mac.closeNote(); phone.closeNote()
            }
        }
        println("PASS: sync install/merge/digest")
    } finally { tmp.toFile().deleteRecursively() }
}
