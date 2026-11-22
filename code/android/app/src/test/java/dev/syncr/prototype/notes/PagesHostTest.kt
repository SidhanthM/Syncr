package dev.syncr.prototype.notes

import dev.syncr.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Page commands, layout and ink-per-page against the real core (opt-in: -PsyncrNativeHost=true). */
class PagesHostTest {
    private val a4 = 1600f to 2262.9f
    private fun brush() = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)
    private fun samples(y: Float) = SampleChannels(listOf(100f, 200f), listOf(y, y + 10f), listOf(.5f, .6f), listOf(0u, 8u), null, null)

    private suspend fun fresh(layout: NewNoteLayout? = NewNoteLayout(a4.first, a4.second), kind: NoteKind = NoteKind.NOTE, block: suspend (NotesRepository) -> Unit) {
        val root = Files.createTempDirectory("syncr-pages-").toFile()
        val repo = NotesRepository(root.absolutePath)
        try {
            repo.loadLibrary()
            repo.create("Pages", null, kind, layout)
            assertNull(repo.state.value.error)
            block(repo)
        } finally { repo.leave(discard = true); repo.releaseLibrary(); root.deleteRecursively() }
    }

    private fun live(repo: NotesRepository) = repo.state.value.snapshot!!.pages.filter { !it.tombstone }

    @Test fun aNewNoteStartsAsOneA4PageWithItsLookWithoutShowingTheLegacyPage() = runBlocking {
        val look = NewNoteLayout(a4.first, a4.second, PagePattern.GRID, Rgba(250u, 245u, 230u, 255u), Rgba(100u, 100u, 100u, 90u))
        fresh(look) { repo ->
            val note = repo.state.value.snapshot!!
            assertEquals(NoteKind.NOTE, note.kind)
            val page = live(repo).single()
            assertEquals(a4, page.width to page.height); assertFalse(page.autoHeight)
            assertEquals(PagePattern.GRID, note.pageStyle!!.resolved.pattern)
            assertEquals(Rgba(250u, 245u, 230u, 255u), note.pageStyle!!.resolved.paper)
            assertFalse(PageSpots.isLegacy(note))
            val spots = PageSpots.of(note, PageViewSettings())
            assertEquals(1, spots.size); assertEquals(0f, spots[0].x); assertEquals(0f, spots[0].y)
        }
    }

    @Test fun aNoteWithoutALayoutStaysALegacyColumn() = runBlocking {
        fresh(null) { repo ->
            assertTrue(PageSpots.isLegacy(repo.state.value.snapshot!!))
            assertTrue(PageSpots.of(repo.state.value.snapshot!!, PageViewSettings()).isEmpty())
        }
    }

    @Test fun aCanvasHasNoPages() = runBlocking {
        fresh(null, NoteKind.CANVAS) { repo ->
            val note = repo.state.value.snapshot!!
            assertEquals(NoteKind.CANVAS, note.kind)
            assertTrue(PageSpots.of(note, PageViewSettings()).isEmpty())
            assertFalse(PageSpots.isLegacy(note))
        }
    }

    @Test fun addingPagesInsertsAfterAndDoubleModeLaysThemOutInRows() = runBlocking {
        fresh { repo ->
            val first = live(repo).single().sheetId
            val second = repo.addPage(first, 1127.6f, 1600f)!!
            val third = repo.addPage(first, 1600f, 2262.9f)!!
            assertEquals(listOf(first, third, second), live(repo).map { it.sheetId })
            val single = PageSpots.of(repo.state.value.snapshot!!, PageViewSettings())
            assertEquals(listOf(0, 1, 2), single.map { it.row })
            assertEquals(2262.9f + Xn.GAP, single[1].y, 0.01f)
            val dbl = PageSpots.of(repo.state.value.snapshot!!, PageViewSettings(PageArrangement.DOUBLE))
            assertEquals(listOf(0, 0, 1), dbl.map { it.row })
            assertEquals(1600f + Xn.GAP, dbl[1].x, 0.01f)
            val flip = PageSpots.of(repo.state.value.snapshot!!, PageViewSettings(PageArrangement.SINGLE, flip = true))
            assertTrue(flip.all { it.y == 0f })
            assertTrue(flip[1].x > flip[0].right)
        }
    }

    @Test fun inkBelongsToThePageItWasWrittenOnAndSurvivesDeletingAnotherPage() = runBlocking {
        fresh { repo ->
            val first = live(repo).single().sheetId
            val second = repo.addPage(first, a4.first, a4.second)!!
            assertTrue(repo.stageStroke(InkPreview(newId(), second, samples(50f), brush())))
            repo.retry(); assertNull(repo.state.value.error)
            val stroke = repo.state.value.snapshot!!.strokes.single()
            assertEquals(second, stroke.sheetId)
            assertTrue(repo.deletePages(listOf(first)))
            assertEquals(listOf(second), live(repo).map { it.sheetId })
            // The last page cannot go, in the core's words, and nothing is staged.
            assertFalse(repo.deletePages(listOf(second)))
            assertEquals(PageCommands.LAST_PAGE, repo.state.value.error)
            assertFalse(repo.state.value.unsaved)
            // Ink on a deleted page is refused.
            assertFalse(repo.stageStroke(InkPreview(newId(), first, samples(10f), brush())))
        }
    }

    @Test fun rotatingTurnsThePageAndItsInkTogetherAsOneUndoStep() = runBlocking {
        fresh { repo ->
            val page = live(repo).single().sheetId
            repo.stageStroke(InkPreview(newId(), page, samples(100f), brush())); repo.retry()
            val before = repo.state.value.snapshot!!.strokes.single().transform
            assertTrue(repo.rotatePages(listOf(page), PageTurn.CLOCKWISE))
            val after = repo.state.value.snapshot!!
            assertEquals(a4.second to a4.first, after.pages[0].width to after.pages[0].height)
            // (100,100) on a 1600x2262.9 page lands at (2262.9 - 100, 100) when turned clockwise.
            val moved = transformedPoint(100f, 100f, after.strokes.single().transform)
            assertEquals(2262.9f - 100f, moved.first, 0.01f); assertEquals(100f, moved.second, 0.01f)
            repo.history(false)
            val undone = repo.state.value.snapshot!!
            assertEquals(a4.first, undone.pages[0].width, 0.01f)
            assertEquals(before, undone.strokes.single().transform)
        }
    }

    @Test fun duplicatingCopiesInkAfterTheLastPickedPage() = runBlocking {
        fresh { repo ->
            val first = live(repo).single().sheetId
            val second = repo.addPage(first, a4.first, a4.second)!!
            repo.stageStroke(InkPreview(newId(), first, samples(300f), brush())); repo.retry()
            val copies = repo.duplicatePages(listOf(first))
            assertNull(repo.state.value.error); assertEquals(1, copies.size)
            assertEquals(listOf(first, copies[0], second), live(repo).map { it.sheetId })
            val onCopy = repo.state.value.snapshot!!.strokes.filter { it.sheetId == copies[0] }
            assertEquals(1, onCopy.size)
            assertEquals(samples(300f), repo.state.value.samples[onCopy[0].id])
            // The pages and their ink undo together.
            repo.history(false)
            assertEquals(listOf(first, second), live(repo).map { it.sheetId })
        }
    }

    @Test fun movingPagesReordersThemAndKeepsTheirInk() = runBlocking {
        fresh { repo ->
            val a = live(repo).single().sheetId
            val b = repo.addPage(a, a4.first, a4.second)!!
            val c = repo.addPage(b, a4.first, a4.second)!!
            assertEquals(listOf(a, b, c), live(repo).map { it.sheetId })
            assertTrue(repo.movePages(listOf(a), c))
            assertEquals(listOf(b, c, a), live(repo).map { it.sheetId })
            assertTrue(repo.movePages(listOf(c, a), null))
            assertEquals(listOf(c, a, b), live(repo).map { it.sheetId })
        }
    }

    @Test fun pageSizeChangesTheSizeWithoutMovingInk() = runBlocking {
        fresh { repo ->
            val page = live(repo).single().sheetId
            repo.stageStroke(InkPreview(newId(), page, samples(100f), brush())); repo.retry()
            val before = repo.state.value.snapshot!!.strokes.single().transform
            assertTrue(repo.resizePage(page, 1127.6f, 1600f))
            assertEquals(1127.6f to 1600f, live(repo)[0].let { it.width to it.height })
            assertEquals(before, repo.state.value.snapshot!!.strokes.single().transform)
        }
    }

    @Test fun aLegacyNoteGainsAnA4PageBelowItAndKeepsItsInk() = runBlocking {
        fresh(null) { repo ->
            val legacy = live(repo).single()
            assertTrue(legacy.autoHeight)
            repo.stageStroke(InkPreview(newId(), legacy.sheetId, samples(200f), brush())); repo.retry()
            val added = repo.addPage(legacy.sheetId, a4.first, a4.second)!!
            val note = repo.state.value.snapshot!!
            assertEquals(listOf(legacy.sheetId, added), live(repo).map { it.sheetId })
            assertTrue(live(repo)[0].autoHeight)
            val spots = PageSpots.of(note, PageViewSettings())
            assertEquals(2, spots.size)
            assertEquals(0f, spots[1].x) // the legacy page is placed as its 1,600 column, so the A4 page sits under it
            assertEquals(spots[0].height + Xn.GAP, spots[1].y, 0.01f)
            assertEquals(1, note.strokes.size)
        }
    }

    @Test fun freeTextOnALaterPageIsAFreeBlockOnThatSheetAndTheDraftFollowsItAfterTheFirstSave() = runBlocking {
        fresh { repo ->
            val first = live(repo).single().sheetId
            val second = repo.addPage(first, a4.first, a4.second)!!
            val place = FreePlace(second, 120f, 300f, 900f, newId())
            val draft = repo.textDraft(null, place)
            assertNull(draft.blockId); assertEquals("", draft.text)
            repo.saveText(draft, "")
            assertTrue(repo.state.value.snapshot!!.textBlocks.isEmpty()) // nothing typed, nothing stored
            repo.saveText(draft, "Notes on page two")
            val block = repo.state.value.snapshot!!.textBlocks.single()
            assertEquals(TextRole.FREE, block.role); assertEquals(second, block.sheetId)
            assertEquals(120f to 300f, block.originX to block.originY); assertEquals(place.blockId, block.id)
            val after = repo.textDraft(draft.blockId, draft.place)
            assertEquals(block.id, after.blockId); assertEquals("Notes on page two", after.text)
            // The document, by contrast, still goes on the first page.
            repo.saveText(repo.textDraft(), "Document")
            val doc = repo.state.value.snapshot!!.textBlocks.first { it.role == TextRole.DOCUMENT }
            assertEquals(first, doc.sheetId)
            // Free text copies with its page.
            val copies = repo.duplicatePages(listOf(second))
            val copied = repo.state.value.snapshot!!.textBlocks.single { it.sheetId == copies[0] }
            assertEquals("Notes on page two", copied.text)
        }
    }

    @Test fun thePageSizeDialogStartsFromTheNamedSizeOfThePage() = runBlocking {
        fresh { repo ->
            val page = live(repo).single()
            dev.syncr.prototype.ui.choiceFor(page).let { assertEquals("A4", it.size); assertFalse(it.landscape) }
            val turned = page.copy(width = page.height, height = page.width)
            dev.syncr.prototype.ui.choiceFor(turned).let { assertEquals("A4", it.size); assertTrue(it.landscape) }
            val odd = page.copy(width = 1000f, height = 1234f)
            dev.syncr.prototype.ui.choiceFor(odd).let { assertEquals("Custom", it.size); assertEquals("131.3", it.customWidth) }
        }
    }
}
