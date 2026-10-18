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
        writeRecovery(output, RecoveryData("note", "author", CreateNoteRequest("c", "note", "sheet", "Canvas", null), listOf(command), byteArrayOf(7, 8)))
        val json = JSONObject(output.toString("UTF-8"))
        assertEquals(2, json.getInt("version")); assertEquals("syncr-recovery", json.getString("format"))
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
    @Test fun destinationCloseFailurePropagatesInsteadOfCountingAsExported() {
        val output = object : ByteArrayOutputStream() { override fun close() { throw IOException("Chosen destination failed on close") } }
        try {
            writeRecovery(output, RecoveryData("note", "author", null, emptyList()))
            fail("Close failure must not count as recovery export success")
        } catch (expected: IOException) { assertTrue(expected.message!!.contains("close")) }
    }
}
