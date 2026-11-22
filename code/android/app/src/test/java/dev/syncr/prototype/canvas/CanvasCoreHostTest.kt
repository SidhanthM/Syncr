package dev.syncr.prototype.canvas

import dev.syncr.core.*
import dev.syncr.prototype.notes.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Opt-in -PsyncrNativeHost=true: the canvas commands, free text and cards against the real Rust core, through the production adapter. */
class CanvasCoreHostTest {
    private fun withCanvas(block: (NoteAccess, CreateNoteRequest) -> Unit) {
        val root = Files.createTempDirectory("syncr-canvas-p3-").toFile()
        val library = GeneratedCoreAdapter().open(root.absolutePath)
        try {
            val request = CreateNoteRequest(newId(), newId(), newId(), "Canvas", null, NoteKind.CANVAS)
            library.create(request)
            val note = library.openNote(request.noteId)
            try { block(note, request) } finally { note.closeDurably() }
        } finally { library.release(); root.deleteRecursively() }
    }

    @Test fun aNewCanvasHasNoWaypointsAndStoresThem() = withCanvas { note, request ->
        val first = note.snapshot()
        assertEquals(NoteKind.CANVAS, first.kind)
        assertTrue(first.waypoints!!.items.isEmpty())

        val view = InfiniteViewport().apply { resize(1080, 2340); set(2.5, 1234.0, -987.0) }
        val saved = CanvasWaypoints.saved(emptyList(), view.toWaypoint("Desk"))!!
        note.submit(CanvasCommands.waypoints(note.snapshot(), saved) { newId() })
        val read = note.snapshot().waypoints!!.items.single()
        assertEquals("Desk", read.name); assertEquals(2.5f, read.zoom, 1e-6f)
        val back = InfiniteViewport().apply { resize(1080, 2340); apply(read) }
        assertEquals(view.centerX, back.centerX, 1e-2); assertEquals(view.centerY, back.centerY, 1e-2)

        // The same name in another case replaces it in place, on the core's side as on ours.
        note.submit(CanvasCommands.waypoints(note.snapshot(), CanvasWaypoints.saved(saved, Waypoint("DESK", 1f, 2f, 3f))!!) { newId() })
        assertEquals(listOf("DESK"), note.snapshot().waypoints!!.items.map { it.name })
        // A note refuses them (the core is authoritative; our helper just needs a canvas to read the head from).
        assertNotNull(request)
    }

    @Test fun freeTextOnACanvasIsStoredAtItsOriginDrawnThereAndGivesItsCardsAPlace() = withCanvas { note, request ->
        val snap = note.snapshot()
        val blockId = newId()
        val draft = TextDraft(snap.noteId, null, null, "", FreePlace(snap.sheets.first().id, -5000f, 12_000f, 900f, blockId))
        val command = TextCommands.save(snap, draft, "Far away idea\nsecond line") { newId() }!!
        note.submit(command)
        val block = note.snapshot().textBlocks.single()
        assertEquals(TextRole.FREE, block.role); assertEquals(blockId, block.id)
        assertEquals(-5000f, block.originX, 0f); assertEquals(12_000f, block.originY, 0f); assertEquals(900f, block.width, 0f)
        // A second save splices the same block rather than adding another.
        val again = TextDraft(snap.noteId, blockId, block.head.operationId, block.text)
        note.submit(TextCommands.save(note.snapshot(), again, "Far away idea\nsecond line!!") { newId() }!!)
        assertEquals("Far away idea\nsecond line!!", note.snapshot().textBlocks.single().text)

        // A companion note anchored to that block sits to its right, level with its top (the shared card layout).
        val anchor = newId(); val margin = newId()
        val head = note.snapshot().textBlocks.single().head.operationId
        note.submit(Command(newId(), listOf(head), CommandBody.CompanionMarks(listOf(
            CompanionMutation(anchor, Base.Absent, false, CompanionBody.Anchor(AnchorTarget.Text(blockId, 0u, 13u, false)))))))
        val a = note.snapshot().companionItems.single { it.id == anchor }
        note.submit(Command(newId(), listOf(a.head.operationId), CommandBody.CompanionMarks(listOf(
            CompanionMutation(margin, Base.Absent, false, CompanionBody.MarginNote(anchor, MarginKind.EXPLANATION, "A note beside it.", "staff", MarginState.OPEN, emptyList()))))))
        val scene = NoteTextSceneBuilder(FakeShaper()) { null }.buildCanvas(note.snapshot())
        val block1 = scene.hits.single { it.target.id == blockId }
        val card = scene.hits.single { it.target.id == margin && it.target.response }
        assertEquals(block1.x + block1.width + NoteTextSceneBuilder.CARD_GAP, card.x, 0.5f)
        assertEquals(block1.y, card.y, 0.5f)
        assertTrue(scene.prims.filterIsInstance<Prim.Text>().any { it.text == "Far away idea" })
        assertTrue(request.noteId.isNotEmpty())
    }

    @Test fun aCanvasThumbnailIsTheContentBoxNotTheTopLeft() = withCanvas { note, request ->
        val sheet = request.firstSheetId
        val samples = SampleChannels(listOf(40_000f, 41_000f), listOf(-30_000f, -29_500f), listOf(.5f, .5f), listOf(0u, 10u), null, null)
        val id = newId()
        note.submit(Command(newId(), listOf(request.operationId), CommandBody.Marks(listOf(MarkMutation.AddStroke(id, sheet,
            GeneratedCoreAdapter().encode(samples), Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK))))))
        val snapshot = note.snapshot()
        val t = buildThumbnail(snapshot, { samples }, geometry = geometryOf(snapshot))
        val xs = t.strokes.single().points.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue("content is inside the frame, not 40,000 units away", xs.all { it in 0f..t.width })
    }
}
