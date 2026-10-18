package dev.syncr.prototype.notes

import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThumbnailTest {
    private val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.BLUE)
    private fun channels(n: Int) = SampleChannels(List(n) { it.toFloat() }, List(n) { 2f * it }, List(n) { 1f }, List(n) { it.toUInt() }, null, null)
    private fun snapshot(strokes: List<StrokeView>) = NoteSnapshot("note", "T", null,
        EntityHead("c", null), EntityHead("c", null), listOf(SheetView("one", "c", 0f, 0f), SheetView("two", "c2", 0f, 1000f)), strokes, 7u,
        EventCursor("s", 7u), null, null, emptyList(), emptyList())

    @Test fun canvasThumbnailIncludesLegacyOriginsTransformsAndDecimates() {
        val moved = Affine(1.0, 0.0, 0.0, 1.0, 100.0, 0.0)
        val snap = snapshot(listOf(
            StrokeView("a", "one", "x", EntityHead("h", null), moved, brush, false),
            StrokeView("gone", "one", "x", EntityHead("h", null), IDENTITY, brush, true),
            StrokeView("b", "two", "x", EntityHead("h", null), IDENTITY, brush, false)))
        val t = buildThumbnail(snap, { channels(10) }, maxPoints = 5)
        assertEquals(7uL, t.localSequence); assertEquals(4800f, t.width); assertEquals(3000f, t.viewHeight)
        assertEquals(2, t.strokes.size)
        assertEquals(1000f, t.strokes[1].points[1]) // no gap before the legacy second sheet
        val points = t.strokes.first().points
        assertEquals(100f, points[0]); assertEquals(0f, points[1]) // transformed
        assertEquals(109f, points[points.size - 2]) // last sample kept
        assertTrue(points.size / 2 <= 6)
        // Pressure and time travel with the kept samples so cards draw with the page's brushes.
        val stroke = t.strokes.first()
        assertEquals(points.size / 2, stroke.pressure.size); assertEquals(points.size / 2, stroke.elapsedMs.size)
        assertEquals(0, stroke.elapsedMs.first()); assertEquals(9, stroke.elapsedMs.last())
    }
    @Test fun preCanvasCacheIsRejectedEvenAtTheSameSequence() {
        val dir = Files.createTempDirectory("old-thumbs").toFile()
        try {
            java.io.DataOutputStream(java.io.File(dir, "NOTE01.thumb").outputStream()).use { out ->
                out.writeInt(0x53544843); out.writeLong(7)
                out.writeFloat(1600f); out.writeFloat(3200f); out.writeInt(0)
            }
            assertNull(ThumbnailCache.read(dir, "NOTE01", 7u))
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskCacheRoundTripsAndIgnoresStaleSequences() {
        val dir = Files.createTempDirectory("thumbs").toFile()
        try {
            val t = buildThumbnail(snapshot(listOf(StrokeView("a", "one", "x", EntityHead("h", null), IDENTITY, brush, false))), { channels(3) })
            ThumbnailCache.write(dir, "NOTE01", t)
            val read = ThumbnailCache.read(dir, "NOTE01", 7u)!!
            assertArrayEquals(t.strokes.single().points, read.strokes.single().points, 0f)
            assertArrayEquals(t.strokes.single().pressure, read.strokes.single().pressure, 0f)
            assertArrayEquals(t.strokes.single().elapsedMs, read.strokes.single().elapsedMs)
            assertEquals(brush, read.strokes.single().brush)
            assertNull(ThumbnailCache.read(dir, "NOTE01", 8u))
            assertNull(ThumbnailCache.read(dir, "../escape", 7u))
            assertNull(ThumbnailCache.read(null, "NOTE01", 7u))
        } finally { dir.deleteRecursively() }
    }
}
