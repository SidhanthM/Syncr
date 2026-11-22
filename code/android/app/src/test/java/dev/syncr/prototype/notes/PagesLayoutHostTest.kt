package dev.syncr.prototype.notes

import dev.syncr.core.CanvasCard
import dev.syncr.core.CardAnchor
import dev.syncr.core.PageExtent
import dev.syncr.core.PageMode
import dev.syncr.core.layoutCanvasCards
import dev.syncr.core.layoutPages
import dev.syncr.core.matchSize
import dev.syncr.core.pageSizes
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared page and canvas-card vectors (`code/shared/layout/pages-v1.json` and `canvas-cards-v1.json`) through the generated Kotlin binding. */
class PagesLayoutHostTest {
    private fun vectors(name: String): JSONArray = JSONObject(File("../../shared/layout/$name").canonicalFile.readText()).getJSONArray("vectors")
    private fun f(j: JSONObject, key: String) = j.getDouble(key).toFloat()

    @Test fun sharedPageVectorsGiveTheExpectedRects() {
        val all = vectors("pages-v1.json")
        assertTrue(all.length() >= 7)
        for (n in 0 until all.length()) {
            val v = all.getJSONObject(n)
            val name = v.getString("name")
            val input = v.getJSONObject("input")
            val pages = input.getJSONArray("pages").let { a ->
                List(a.length()) { a.getJSONObject(it).let { p -> PageExtent(p.getString("sheet_id"), f(p, "width"), f(p, "height")) } }
            }
            val mode = when (input.getString("mode")) { "double" -> PageMode.DOUBLE; "cover" -> PageMode.COVER; else -> PageMode.SINGLE }
            val got = layoutPages(pages, mode, f(input, "gap"))
            val want = v.getJSONArray("expected")
            assertEquals(name, want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                assertEquals(name, w.getString("sheet_id"), got[i].sheetId)
                assertEquals("$name row", w.getInt("row").toUInt(), got[i].row)
                assertEquals("$name x", f(w, "x"), got[i].x, 1e-3f)
                assertEquals("$name y", f(w, "y"), got[i].y, 1e-3f)
                assertEquals("$name width", f(w, "width"), got[i].width, 1e-3f)
                assertEquals("$name height", f(w, "height"), got[i].height, 1e-3f)
            }
        }
    }

    @Test fun sharedCardVectorsGiveTheExpectedPlacements() {
        val all = vectors("canvas-cards-v1.json")
        assertTrue(all.length() >= 6)
        for (n in 0 until all.length()) {
            val v = all.getJSONObject(n)
            val name = v.getString("name")
            val input = v.getJSONObject("input")
            val anchors = input.getJSONArray("anchors").let { a ->
                List(a.length()) { a.getJSONObject(it).let { p -> CardAnchor(p.getString("id"), f(p, "x"), f(p, "y"), f(p, "width"), f(p, "height")) } }
            }
            val cards = input.getJSONArray("cards").let { a ->
                List(a.length()) { a.getJSONObject(it).let { p -> CanvasCard(p.getString("id"), p.getString("anchor_id"), f(p, "width"), f(p, "height")) } }
            }
            val got = layoutCanvasCards(anchors, cards, f(input, "gap"))
            val want = v.getJSONArray("expected")
            assertEquals(name, want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                assertEquals(name, w.getString("id"), got[i].id)
                assertEquals("$name x", f(w, "x"), got[i].x, 0f)
                assertEquals("$name y", f(w, "y"), got[i].y, 0f)
            }
        }
    }

    @Test fun namedSizesMatchEitherWayRound() {
        assertEquals(6, pageSizes().size)
        assertEquals("A4", matchSize(1600f, 2262.9f)?.name)
        assertEquals("A4", matchSize(2262.9f, 1600f)?.name)
        assertNull(matchSize(1000f, 1000f))
        assertNotNull(matchSize(1127.6f, 1600f))
    }
}
