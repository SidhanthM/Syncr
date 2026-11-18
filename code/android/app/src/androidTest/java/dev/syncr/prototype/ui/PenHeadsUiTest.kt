package dev.syncr.prototype.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.syncr.core.InkColour
import dev.syncr.core.Oklch
import dev.syncr.core.PenLook
import dev.syncr.prototype.notes.Ink
import dev.syncr.prototype.notes.InkPreferences
import dev.syncr.prototype.notes.InkTool
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.SyncrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated component state: no note library, link, provider, or user's preferences. */
class PenHeadsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headsRememberTheirInksInLight() = heads(false)
    @Test fun headsRememberTheirInksInDark() = heads(true)

    private fun heads(dark: Boolean) {
        val blue = Ink.Custom(Oklch(.62f, .12f, 250f))
        val rose = Ink.Custom(Oklch(.68f, .12f, 20f))
        var prefs by mutableStateOf(InkPreferences().withInk(InkTool.FOUNTAIN, blue).withInk(InkTool.BALLPOINT, rose))
        var opened: PenLook? = null
        compose.setContent {
            SyncrTheme(dark) {
                Column(Modifier.background(Syncr.colors.paper).padding(16.dp)) {
                    ToolOptions(prefs.brushFor(InkTool.of(prefs.penLook)), {}, {}, penLook = prefs.penLook,
                        onPenLook = { prefs = prefs.copy(penLook = it) },
                        inkOf = { prefs.headInk(it, dark) },
                        customInk = (prefs.inkFor(InkTool.of(prefs.penLook)) as? Ink.Custom)?.tipColour(dark),
                        onOpenPicker = { opened = prefs.penLook })
                    PenHead(Head.FOUNTAIN, blue.tipColour(dark), height = 80.dp, freeze = .42f)
                }
            }
        }
        compose.onNodeWithContentDescription("Ballpoint pen").performClick()
        compose.runOnIdle { assertEquals(rose, prefs.inkFor(InkTool.of(prefs.penLook))) }
        compose.onNodeWithContentDescription("Fountain pen").performClick()
        compose.runOnIdle { assertEquals(blue, prefs.inkFor(InkTool.of(prefs.penLook))) }
        // A long press on a different head edits that head, not the previously active pen.
        compose.onNodeWithContentDescription("Ballpoint pen").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(PenLook.BALLPOINT, opened) }
        screenshot("heads-${if (dark) "dark" else "light"}")
    }

    @Test fun pickerGesturesAndAccessibilityInLight() = picker(false)
    @Test fun pickerGesturesAndAccessibilityInDark() = picker(true)

    private fun picker(dark: Boolean) {
        var current by mutableStateOf(Oklch(.62f, .12f, 190f))
        var saves = 0
        var named by mutableStateOf<InkColour?>(null)
        var closed = 0
        val recent = List(8) { Oklch(.6f, .08f, it * 40f) }
        compose.setContent {
            SyncrTheme(dark) {
                Column(Modifier.background(Syncr.colors.paper).padding(16.dp)) {
                    InkPicker(current, recent, named, onChange = {}, onCommit = { current = it; saves++; named = null },
                        onNamed = { named = it }, onClose = { closed++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Hue ring").performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        compose.runOnIdle { assertEquals(120f, current.h, .01f); assertEquals(1, saves) }
        val adjustments = compose.onNodeWithContentDescription("Lightness and chroma")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(adjustments.first { it.label == "Increase lightness" }.action()) }
        compose.runOnIdle { assertEquals(.67f, current.l, .01f); assertEquals(2, saves) }
        // Repeat after committing/recomposing: gestures must not retain a stale colour or callback.
        repeat(2) { i ->
            compose.onNodeWithContentDescription("Lightness and chroma").performTouchInput {
                click(Offset(width * .2f, height * (.3f + i * .1f)))
            }
            compose.runOnIdle { assertEquals(3 + i, saves); assertEquals(.7f - i * .1f, current.l, .02f) }
        }
        val saved = current.copy()
        compose.onNodeWithContentDescription("Lightness and chroma").performTouchInput {
            down(center); moveBy(Offset(25f, 20f)); cancel()
        }
        compose.runOnIdle { assertEquals(4, saves); assertEquals(saved, current) }
        compose.onNodeWithContentDescription("Orange").performClick()
        compose.runOnIdle { assertEquals(InkColour.ORANGE, named) }
        val last = "Recent ink, lightness 0.60, hue 280"
        compose.onNodeWithContentDescription(last).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(280f, current.h, .01f); assertEquals(5, saves) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ink-field-ready").fetchSemanticsNodes().isNotEmpty() }
        screenshot("picker-${if (dark) "dark" else "light"}")
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(3000) { closed == 1 }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "pen-head-evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
