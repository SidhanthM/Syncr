package dev.syncr.prototype.notes

import dev.syncr.core.MarginAnchor
import dev.syncr.core.MarginInput
import dev.syncr.core.MarginItem
import dev.syncr.core.MarginItemKind
import dev.syncr.core.MarginLane
import dev.syncr.core.MarginLine
import dev.syncr.core.MarginPolicy
import dev.syncr.core.layoutMargins
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared margin vectors (`code/shared/layout/margin-v1.json`) through the generated Kotlin binding. */
class MarginLayoutHostTest {
    private fun anchor(j: JSONObject): MarginAnchor = when (j.getString("kind")) {
        "text" -> MarginAnchor.Text(j.getLong("offset").toUInt())
        "canvas" -> MarginAnchor.Canvas(j.getDouble("y").toFloat())
        else -> MarginAnchor.None
    }

    private fun input(j: JSONObject): MarginInput {
        val lines = j.getJSONArray("lines").let { a ->
            List(a.length()) { a.getJSONObject(it).let { l -> MarginLine(l.getLong("start").toUInt(), l.getLong("end").toUInt(), l.getDouble("top").toFloat()) } }
        }
        val items = j.getJSONArray("items").let { a ->
            List(a.length()) {
                a.getJSONObject(it).let { i ->
                    MarginItem(i.getString("id"), if (i.getString("kind") == "picture") MarginItemKind.PICTURE else MarginItemKind.NOTE,
                        anchor(i.getJSONObject("anchor")), i.getDouble("height").toFloat())
                }
            }
        }
        return MarginInput(if (j.getString("policy") == "phone") MarginPolicy.PHONE else MarginPolicy.DESKTOP,
            j.getDouble("column_left").toFloat(), j.getDouble("column_right").toFloat(), j.getDouble("card_width").toFloat(),
            j.getDouble("gap").toFloat(), j.getDouble("lane_top").toFloat(), j.getDouble("spacing").toFloat(),
            j.getDouble("default_top").toFloat(), lines, items)
    }

    @Test fun sharedVectorsGiveTheExpectedLayout() {
        val root = JSONObject(File("../../shared/layout/margin-v1.json").canonicalFile.readText())
        val vectors = root.getJSONArray("vectors")
        assertTrue(vectors.length() >= 8)
        for (n in 0 until vectors.length()) {
            val v = vectors.getJSONObject(n)
            val name = v.getString("name")
            val out = layoutMargins(input(v.getJSONObject("input")))
            val expected = v.getJSONObject("expected")
            val places = expected.getJSONArray("placements")
            assertEquals(name, places.length(), out.placements.size)
            for (i in 0 until places.length()) {
                val want = places.getJSONObject(i)
                val got = out.placements[i]
                assertEquals(name, want.getString("id"), got.id)
                assertEquals(name, if (want.getString("lane") == "left") MarginLane.LEFT else MarginLane.RIGHT, got.lane)
                assertEquals("$name x", want.getDouble("x").toFloat(), got.x, 0f)
                assertEquals("$name y", want.getDouble("y").toFloat(), got.y, 0f)
                assertEquals("$name width", want.getDouble("width").toFloat(), got.width, 0f)
                assertEquals("$name height", want.getDouble("height").toFloat(), got.height, 0f)
            }
            assertEquals("$name bottom", expected.getDouble("bottom").toFloat(), out.bottom, 0f)
        }
    }
}
