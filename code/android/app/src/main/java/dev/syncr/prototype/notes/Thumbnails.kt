package dev.syncr.prototype.notes

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.syncr.core.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Library card preview of a note's FIRST page: transformed, decimated samples in page units, with
 * pressure and time so cards draw with the same Ink brushes as the page. Disposable display data
 * built on the IO worker; never a source of truth.
 */
class ThumbStroke(val points: FloatArray, val brush: Brush, val pressure: FloatArray = FloatArray(points.size / 2) { 0.5f },
    val elapsedMs: IntArray = IntArray(points.size / 2) { it })
class Thumbnail(val localSequence: ULong, val pageWidth: Float, val pageHeight: Float, val strokes: List<ThumbStroke>)

/** Pure: keeps at most about [maxPoints] points overall by taking every k-th sample (and each last one). */
fun buildThumbnail(snapshot: NoteSnapshot, samples: (String) -> SampleChannels?, maxPoints: Int = 4000): Thumbnail {
    val sheet = snapshot.sheets.firstOrNull()?.id
    val strokes = snapshot.strokes.filter { it.sheetId == sheet && !it.tombstone }.mapNotNull { s -> samples(s.id)?.let { s to it } }
    val total = strokes.sumOf { it.second.x.size }
    val step = maxOf(1, (total + maxPoints - 1) / maxPoints)
    return Thumbnail(snapshot.localSequence, snapshot.pageWidth, snapshot.pageHeight, strokes.map { (stroke, channels) ->
        val kept = channels.x.indices.filter { it % step == 0 || it == channels.x.lastIndex }
        val points = FloatArray(kept.size * 2)
        kept.forEachIndexed { i, index ->
            val (x, y) = transformedPoint(channels.x[index], channels.y[index], stroke.transform)
            points[2 * i] = x; points[2 * i + 1] = y
        }
        ThumbStroke(points, stroke.brush.copy(), FloatArray(kept.size) { channels.pressure[kept[it]] },
            IntArray(kept.size) { channels.elapsedMs[kept[it]].toInt() })
    })
}

/** Optional on-disk cache so library cards don't reopen every note on each launch. Failures are ignored. */
internal object ThumbnailCache {
    private const val MAGIC = 0x53544843 // STHC: v2 adds pressure and time; older files are ignored
    private fun file(dir: File, noteId: String) =
        if (noteId.isNotEmpty() && noteId.all { it.isLetterOrDigit() }) File(dir, "$noteId.thumb") else null

    fun read(dir: File?, noteId: String, localSequence: ULong): Thumbnail? {
        if (dir == null) return null
        return try { file(dir, noteId)?.takeIf { it.exists() }?.let { f ->
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readLong().toULong() != localSequence) return@use null
                val w = input.readFloat(); val h = input.readFloat()
                Thumbnail(localSequence, w, h, List(input.readInt()) {
                    val brush = Brush(BrushFamily.entries[input.readByte().toInt()], BrushSize.entries[input.readByte().toInt()],
                        InkColour.entries[input.readByte().toInt()])
                    val points = FloatArray(input.readInt()) { input.readFloat() }
                    ThumbStroke(points, brush, FloatArray(points.size / 2) { input.readFloat() }, IntArray(points.size / 2) { input.readInt() })
                })
            }
        } } catch (_: Exception) { null }
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
                out.writeInt(MAGIC); out.writeLong(thumbnail.localSequence.toLong())
                out.writeFloat(thumbnail.pageWidth); out.writeFloat(thumbnail.pageHeight)
                out.writeInt(thumbnail.strokes.size)
                thumbnail.strokes.forEach { s ->
                    out.writeByte(s.brush.family.ordinal); out.writeByte(s.brush.size.ordinal); out.writeByte(s.brush.colour.ordinal)
                    out.writeInt(s.points.size); s.points.forEach(out::writeFloat)
                    s.pressure.forEach(out::writeFloat); s.elapsedMs.forEach(out::writeInt)
                }
            }
            if (!temp.renameTo(target)) temp.delete()
        } catch (_: Exception) { }
    }
}

/**
 * Draws a thumbnail with the page's own Ink brushes, so cards match the note. [canvas] must already map
 * page units to pixels at [scale]; hairlines are kept at least [minPixels] wide. Safe off the UI thread.
 */
fun drawThumbnail(canvas: Canvas, thumbnail: Thumbnail, dark: Boolean, scale: Float,
    prefs: InkPreferences = InkPreferences(), minPixels: Float = 1.2f) {
    val renderer = CanvasStrokeRenderer.create()
    val toScreen = Matrix().apply { setScale(scale, scale) }
    thumbnail.strokes.forEach { s ->
        val n = s.points.size / 2
        if (n == 0) return@forEach
        val channels = SampleChannels(List(n) { s.points[2 * it] }, List(n) { s.points[2 * it + 1] }, s.pressure.toList(),
            s.elapsedMs.map { it.toUInt() }, null, null)
        renderer.draw(canvas, InkBrushes.stroke(channels, InkBrushes.brush(s.brush, dark, prefs, minSize = minPixels / scale)), toScreen)
    }
}
