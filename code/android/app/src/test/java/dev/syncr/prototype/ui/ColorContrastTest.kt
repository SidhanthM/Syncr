package dev.syncr.prototype.ui

import androidx.compose.ui.graphics.Color
import dev.syncr.prototype.ui.theme.DarkColors
import dev.syncr.prototype.ui.theme.LightColors
import dev.syncr.prototype.ui.theme.SyncrColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Mobile polish C.1: text keeps 4.5:1 on every level of the surface ladder, and dark surfaces rise. */
class ColorContrastTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun levels(c: SyncrColors) = listOf("desk" to c.desk, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3)

    @Test fun inkMutedAndDestructiveKeepFourPointFiveOnEveryLevelInBothThemes() {
        for ((theme, c) in listOf("light" to LightColors, "dark" to DarkColors)) {
            for ((level, ground) in levels(c)) for ((name, text) in listOf("ink" to c.ink, "muted" to c.muted, "destructive" to c.destructive)) {
                val r = ratio(text, ground)
                assertTrue("$theme $name on $level is $r", r >= 4.5)
            }
        }
    }

    @Test fun darkSurfacesGetLighterAsTheyRise() {
        val d = DarkColors
        val l = listOf(d.desk, d.surface1, d.surface2, d.surface3).map(::luminance)
        for (i in 1 until l.size) assertTrue("level $i is lighter", l[i] > l[i - 1])
    }

    @Test fun lightSurfacesStayNearWhite() {
        for ((_, c) in levels(LightColors)) assertTrue(luminance(c) > 0.95)
    }

    @Test fun pressedAndSelectedAreTheInkColourAtSixAndTenPercent() {
        for (c in listOf(LightColors, DarkColors)) {
            assertEquals(c.ink.red, c.tint.red, 0.01f); assertEquals(0.06f, c.tint.alpha, 0.01f)
            assertEquals(c.ink.red, c.selectedFill.red, 0.01f); assertEquals(0.10f, c.selectedFill.alpha, 0.01f)
        }
    }
}
