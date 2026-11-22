package dev.syncr.prototype.notes

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.syncr.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RecoveryExportTest {
    @Test fun versionedArchiveRetainsExactIdsDependenciesSampleBytesAndBackup() {
        val blob = byteArrayOf(0, 1, -1, 42)
        val command = Command("operation", listOf("creation"), CommandBody.Marks(listOf(MarkMutation.AddStroke(
            "mark", "sheet", blob, Brush(BrushFamily.HIGHLIGHTER, BrushSize.LARGE, InkColour.ORANGE)))))
        val output = ByteArrayOutputStream()
        writeRecovery(output, RecoveryData("note", "author", CreateNoteRequest("c", "note", "sheet", "Canvas", null, NoteKind.NOTE), listOf(command), byteArrayOf(7, 8)))
        val json = JSONObject(output.toString("UTF-8"))
        assertEquals(3, json.getInt("version")); assertEquals("syncr-recovery", json.getString("format"))
        assertEquals("note", json.getString("note_id")); assertEquals("author", json.getString("author_id"))
        assertFalse(json.getJSONObject("creation").has("page_format"))
        assertEquals("Canvas", json.getJSONObject("creation").getString("title"))
        val saved = json.getJSONArray("commands").getJSONObject(0)
        assertEquals(command.operationId, saved.getString("operation_id"))
        assertEquals("creation", saved.getJSONArray("dependencies").getString(0))
        val mutation = saved.getJSONObject("body").getJSONArray("mutations").getJSONObject(0)
        assertArrayEquals(blob, Base64.decode(mutation.getString("samples_base64"), Base64.DEFAULT))
        assertArrayEquals(byteArrayOf(7, 8), Base64.decode(json.getString("core_backup_base64"), Base64.DEFAULT))
    }
    @Test fun everyPhoneCommandIsWrittenAndNoneBlocksTheBackup() {
        val base = Base.Confirmed(3uL)
        val ink = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK, PenLook.values().first(), Oklch(0.5f, 0.1f, 200f), PenFeel(3u, 4u, true))
        val commands = listOf(
            Command("a", emptyList(), CommandBody.Marks(listOf(
                MarkMutation.AddStroke("s", "sheet", byteArrayOf(1), ink),
                MarkMutation.AddText("t", "sheet", TextRole.FREE, 10f, 20f, 300f, "hello", TextStyle(BrushSize.SMALL, InkColour.RED)),
                MarkMutation.SpliceText("t", base, 1u, 2u, "i"),
                MarkMutation.SetTextWidth("t", base, 400f),
                MarkMutation.TransformText("t", base, Affine(1.0, 0.0, 0.0, 1.0, 5.0, 6.0)),
                MarkMutation.RestyleText("t", base, TextStyle(BrushSize.LARGE, InkColour.BLUE)),
                MarkMutation.DeleteText("t", base), MarkMutation.RestoreText("t", base)))),
            Command("b", emptyList(), CommandBody.SetWaypoints(listOf(Waypoint("Home", 1f, 2f, 0.5f)), base)),
            Command("c", emptyList(), CommandBody.SetCanvasBackground(PagePattern.GRID, null, 82.6f, null, base)),
            Command("d", emptyList(), CommandBody.ImageMarks(listOf(ImageMutation.Delete("img", base)))))
        val output = ByteArrayOutputStream()
        writeRecovery(output, RecoveryData("note", "author", null, commands, byteArrayOf(7, 8)))
        val json = JSONObject(output.toString("UTF-8"))
        val saved = json.getJSONArray("commands")
        assertEquals(4, saved.length())
        val mutations = saved.getJSONObject(0).getJSONObject("body").getJSONArray("mutations")
        assertEquals(listOf("AddStroke", "AddText", "SpliceText", "SetTextWidth", "TransformText", "RestyleText", "DeleteText", "RestoreText"),
            (0 until mutations.length()).map { mutations.getJSONObject(it).getString("type") })
        assertEquals("hello", mutations.getJSONObject(1).getString("text"))
        val brush = mutations.getJSONObject(0).getJSONObject("brush")
        assertEquals(200.0, brush.getJSONObject("custom_ink").getDouble("h"), 1e-3)
        assertTrue(brush.getJSONObject("feel").getBoolean("taper"))
        assertEquals("Home", saved.getJSONObject(1).getJSONObject("body").getJSONArray("waypoints").getJSONObject(0).getString("name"))
        assertEquals("GRID", saved.getJSONObject(2).getJSONObject("body").getString("pattern"))
        assertEquals("ImageMarks", saved.getJSONObject(3).getJSONObject("body").getString("type"))
        assertArrayEquals(byteArrayOf(7, 8), Base64.decode(json.getString("core_backup_base64"), Base64.DEFAULT))
    }
    @Test fun destinationCloseFailurePropagatesInsteadOfCountingAsExported() {
        val output = object : ByteArrayOutputStream() { override fun close() { throw IOException("Chosen destination failed on close") } }
        try {
            writeRecovery(output, RecoveryData("note", "author", null, emptyList()))
            fail("Close failure must not count as recovery export success")
        } catch (expected: IOException) { assertTrue(expected.message!!.contains("close")) }
    }
}
