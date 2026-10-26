package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.EntityHead
import dev.syncr.core.EventCursor
import dev.syncr.core.InkColour
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.SampleChannels
import dev.syncr.core.SheetView
import dev.syncr.core.StrokeView

/** Shared builders for the canvas unit tests. */
internal object CanvasFixtures {
    /** Medium pen: 4 canvas units wide. */
    val pen = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    val firstSheet = SheetView("sheet", "creation", 0f, 0f)

    fun line(vararg points: Pair<Float, Float>) = SampleChannels(
        points.map { it.first },
        points.map { it.second },
        points.map { 1f },
        points.indices.map { it.toUInt() },
        null,
        null,
    )

    fun stroke(
        id: String,
        sheet: String = "sheet",
        transform: Affine = IDENTITY,
        brush: Brush = pen,
        tombstone: Boolean = false,
    ) = StrokeView(id, sheet, "author", EntityHead("$id-head", null), transform, brush, tombstone)

    fun state(
        strokes: List<StrokeView> = emptyList(),
        samples: Map<String, SampleChannels> = emptyMap(),
        pending: List<PendingEdit> = emptyList(),
        sheets: List<SheetView> = listOf(firstSheet),
    ) = NotesState(
        snapshot = NoteSnapshot(
            "note", "Test", null, EntityHead("creation", null), EntityHead("creation", null), sheets, strokes, 1u,
            EventCursor("stream", 1u), null, null, emptyList(), emptyList(),
        ),
        samples = samples,
        pending = pending,
    )

    fun scene(state: NotesState) = CanvasScene().apply { load(state) }
}
