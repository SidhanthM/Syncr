package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.syncr.core.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Library card preview of the canvas's top-left: origin-placed, transformed, decimated samples, with
 * pressure and time so cards draw with the same Ink brushes as the page. Disposable display data
 * built on the IO worker; never a source of truth.
 */
class ThumbStroke(val points: FloatArray, val brush: Brush, val pressure: FloatArray = FloatArray(points.size / 2) { 0.5f },
    val elapsedMs: IntArray = IntArray(points.size / 2) { it })
class Thumbnail(val localSequence: ULong, val width: Float, val viewHeight: Float, val strokes: List<ThumbStroke>)

/** Pure: keeps at most about [maxPoints] points overall by taking every k-th sample (and each last one). */
/** [onlySheet] limits it to one page's ink (the sidebar's page thumbnails); the width is then that page's. */
fun buildThumbnail(snapshot: NoteSnapshot, samples: (String) -> SampleChannels?, maxPoints: Int = 4000, geometry: NoteGeometry = PagedGeometry(),
    onlySheet: String? = null): Thumbnail {
    val sheets = snapshot.sheets.associateBy { it.id }
    // A note of pages shows its first page, at the page's own width; a legacy column shows the top of the column.
    val firstPage = if (onlySheet != null) snapshot.pages.firstOrNull { it.sheetId == onlySheet }
        else if (PageSpots.isLegacy(snapshot) || snapshot.kind == dev.syncr.core.NoteKind.CANVAS) null else snapshot.pages.firstOrNull { !it.tombstone }
    val strokes = snapshot.strokes.filter { !it.tombstone && it.sheetId in sheets && (firstPage == null || it.sheetId == firstPage.sheetId) }
        .mapNotNull { s -> samples(s.id)?.let { s to it } }
    val total = strokes.sumOf { it.second.x.size }
    val step = maxOf(1, (total + maxPoints - 1) / maxPoints)
    return Thumbnail(snapshot.localSequence, firstPage?.width ?: geometry.thumbnailWidth, geometry.thumbnailHeight, strokes.map { (stroke, channels) ->
        val kept = channels.x.indices.filter { it % step == 0 || it == channels.x.lastIndex }
        val points = FloatArray(kept.size * 2)
        kept.forEachIndexed { i, index ->
            val (x, y) = transformedPoint(channels.x[index], channels.y[index], canvasTransform(sheets.getValue(stroke.sheetId), stroke.transform))
            points[2 * i] = x
            points[2 * i + 1] = y
        }
        ThumbStroke(points, stroke.brush.copy(), FloatArray(kept.size) { channels.pressure[kept[it]] },
            IntArray(kept.size) { channels.elapsedMs[kept[it]].toInt() })
    })
}

/** Optional on-disk cache so library cards don't reopen every note on each launch. Failures are ignored. */
internal object ThumbnailCache {
    private const val MAGIC = 0x53544845 // STHE: strokes keep their look, ink and feel; STHD and older caches are ignored
    private fun file(dir: File, noteId: String) =
        if (noteId.isNotEmpty() && noteId.all { it.isLetterOrDigit() }) File(dir, "$noteId.thumb") else null

    fun read(dir: File?, noteId: String, localSequence: ULong): Thumbnail? {
        if (dir == null) return null
        return try {
            val file = file(dir, noteId)?.takeIf { it.exists() } ?: return null
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readLong().toULong() != localSequence) return null
                val width = input.readFloat()
                val height = input.readFloat()
                val strokes = List(input.readInt()) {
                    val family = BrushFamily.entries[input.readByte().toInt()]
                    val size = BrushSize.entries[input.readByte().toInt()]
                    val colour = InkColour.entries[input.readByte().toInt()]
                    val look = input.readByte().toInt().takeIf { it > 0 }?.let { PenLook.entries[it - 1] }
                    val custom = if (input.readBoolean()) Oklch(input.readFloat(), input.readFloat(), input.readFloat()) else null
                    val feel = if (input.readBoolean()) PenFeel(input.readByte().toUByte(), input.readByte().toUByte(), input.readBoolean()) else null
                    val brush = Brush(family, size, colour, look, custom, feel)
                    val points = FloatArray(input.readInt()) { input.readFloat() }
                    val pressure = FloatArray(points.size / 2) { input.readFloat() }
                    val elapsed = IntArray(points.size / 2) { input.readInt() }
                    ThumbStroke(points, brush, pressure, elapsed)
                }
                Thumbnail(localSequence, width, height, strokes)
            }
        } catch (_: Exception) {
            null // A damaged cache entry is rebuilt from the note.
        }
    }

    /** Drops the cached card for a permanently deleted note. */
    fun delete(dir: File?, noteId: String) {
        try { file(dir ?: return, noteId)?.delete() } catch (_: Exception) { }
    }

    /** Drops cached cards for notes that are neither in the library nor in Recently deleted (e.g. purged). */
    fun retainOnly(dir: File?, noteIds: Set<String>) {
        try {
            dir?.listFiles()?.forEach { f ->
                if (f.name.endsWith(".thumb") && f.name.removeSuffix(".thumb") !in noteIds) f.delete()
            }
        } catch (_: Exception) { }
    }

    fun write(dir: File?, noteId: String, thumbnail: Thumbnail) {
        try {
            val target = file(dir ?: return, noteId) ?: return
            dir.mkdirs()
            val temp = File(dir, "${target.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(thumbnail.localSequence.toLong())
                out.writeFloat(thumbnail.width)
                out.writeFloat(thumbnail.viewHeight)
                out.writeInt(thumbnail.strokes.size)
                thumbnail.strokes.forEach { s ->
                    out.writeByte(s.brush.family.ordinal)
                    out.writeByte(s.brush.size.ordinal)
                    out.writeByte(s.brush.colour.ordinal)
                    out.writeByte((s.brush.look?.ordinal ?: -1) + 1)
                    out.writeBoolean(s.brush.customInk != null)
                    s.brush.customInk?.let { out.writeFloat(it.l); out.writeFloat(it.c); out.writeFloat(it.h) }
                    out.writeBoolean(s.brush.feel != null)
                    s.brush.feel?.let { out.writeByte(it.pressure.toInt()); out.writeByte(it.smoothing.toInt()); out.writeBoolean(it.taper) }
                    out.writeInt(s.points.size)
                    s.points.forEach(out::writeFloat)
                    s.pressure.forEach(out::writeFloat)
                    s.elapsedMs.forEach(out::writeInt)
                }
            }
            // Write-then-rename, so a crash never leaves a half-written card.
            if (!temp.renameTo(target)) temp.delete()
        } catch (_: Exception) { }
    }
}

/**
 * Draws a thumbnail with the page's own Ink brushes, so cards match the note. [canvas] must already map
 * page units to pixels at [scale]; hairlines are kept at least [minPixels] wide. Safe off the UI thread.
 */
fun drawThumbnail(canvas: Canvas, thumbnail: Thumbnail, dark: Boolean, scale: Float, minPixels: Float = 1.2f) {
    val renderer = CanvasStrokeRenderer.create()
    val toScreen = Matrix().apply { setScale(scale, scale) }
    thumbnail.strokes.forEach { s ->
        val n = s.points.size / 2
        if (n == 0) return@forEach
        val channels = SampleChannels(List(n) { s.points[2 * it] }, List(n) { s.points[2 * it + 1] }, s.pressure.toList(),
            s.elapsedMs.map { it.toUInt() }, null, null)
        renderer.draw(canvas, InkBrushes.stroke(channels, InkBrushes.brush(s.brush, dark, minSize = minPixels / scale)), toScreen)
    }
}
