package dev.syncr.prototype.notes

import dev.syncr.core.Oklch
import dev.syncr.core.oklchGamutMap
import dev.syncr.prototype.ui.theme.NotePalette
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared OKLCH vectors (`code/shared/colour/oklch-v1.json`) through the generated Kotlin binding. */
class OklchVectorsHostTest {
    private val root = JSONObject(File("../../shared/colour/oklch-v1.json").canonicalFile.readText())

    private fun ink(j: JSONObject) = Oklch(j.getDouble("l").toFloat(), j.getDouble("c").toFloat(), j.getDouble("h").toFloat())

    @Test fun conversionAndGamutMappingMatchTheSharedVectors() {
        val vectors = root.getJSONArray("convert")
        assertTrue(vectors.length() >= 20)
        for (n in 0 until vectors.length()) {
            val v = vectors.getJSONObject(n)
            val want = v.getString("srgb").toLong(16).toInt()
            val got = ink(v).toArgb()
            for (shift in listOf(16, 8, 0)) {
                val delta = Math.abs(((got shr shift) and 255) - ((want shr shift) and 255))
                assertTrue("$v got ${Integer.toHexString(got)}", delta <= 1) // the reference is independent: one step is rounding
            }
            assertEquals(v.toString(), v.getDouble("mapped_c"), oklchGamutMap(ink(v)).c.toDouble(), 2e-4)
        }
    }

    @Test fun nearBlackAndNearWhiteGreysFollowTheTheme() {
        val vectors = root.getJSONArray("theme")
        for (n in 0 until vectors.length()) {
            val v = vectors.getJSONObject(n)
            val ink = ink(v)
            val light = ink.strokeArgb(dark = false)
            val dark = ink.strokeArgb(dark = true)
            if (v.getBoolean("theme_ink")) {
                assertEquals(v.toString(), NotePalette.ink(false), light)
                assertEquals(v.toString(), NotePalette.ink(true), dark)
            } else {
                assertEquals(v.toString(), light, dark) // exactly as chosen in both themes
                assertEquals(v.toString(), ink.toArgb(), light)
            }
        }
    }

    @Test fun theSnapReducesChromaOnlyAndTheFieldEdgeIsTheSnap() {
        val far = Oklch(0.7f, 0.3f, 200f)
        val snapped = OklchInk.snapToGamut(far)
        assertEquals(far.l, snapped.l, 0f)
        assertEquals(far.h, snapped.h, 0f)
        assertTrue(snapped.c < far.c && !OklchInk.inGamut(far) && OklchInk.inGamut(snapped))
        assertEquals(OklchInk.maxChroma(far.l, far.h), snapped.c, 1e-3f)
    }
}
