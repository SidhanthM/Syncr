package dev.syncr.prototype.tablet

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.syncr.prototype.ui.theme.SyncrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** No real Mac, pointer injection, library data or user preferences are touched. */
class TrackpadControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customSlidersSupportAccessibleChangesAndResetInLightTheme() = controls(dark = false)
    @Test fun customSlidersSupportAccessibleChangesAndResetInDarkTheme() = controls(dark = true)

    private fun controls(dark: Boolean) {
        var held by mutableStateOf(TrackpadTuning())
        var saves = 0
        compose.setContent {
            SyncrTheme(dark = dark) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackpadControls(held) { held = it; saves++ }
                }
            }
        }
        for (label in listOf("Pointer speed", "Pointer acceleration", "Scroll speed", "Scroll acceleration")) {
            compose.onNodeWithContentDescription(label).assertExists()
        }
        compose.onNodeWithContentDescription("Pointer speed").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithContentDescription("Pointer acceleration").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithContentDescription("Scroll speed").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithContentDescription("Scroll acceleration").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle {
            assertEquals(TrackpadTuning(8f, 0f, 12f, 2f), held)
            assertEquals(4, saves)
        }
        compose.onNodeWithText("Reset Trackpad response").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TrackpadTuning(), held); assertEquals(5, saves) }
        compose.onNodeWithText("Reset Trackpad response").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Pointer speed").performScrollTo().performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(held.pointerSpeed > 3f); assertEquals(6, saves) }
    }

    @Test fun settingsPersistAcrossInstancesAndDefendAgainstCorruptValues() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefName = "trackpad-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = super.getSharedPreferences(prefName, mode)
        }
        try {
            val settings = TabletSettings(isolated)
            assertEquals(TrackpadTuning(), settings.trackpadTuning)
            val chosen = TrackpadTuning(5f, 0f, 9f, 1.25f)
            settings.trackpadTuning = chosen
            assertEquals(chosen, TabletSettings(isolated).trackpadTuning)
            settings.trackpadTuning = TrackpadTuning(-10f, Float.NaN, 100f, Float.POSITIVE_INFINITY)
            assertEquals(TrackpadTuning(0.5f, 0.75f, 12f, 0.5f), TabletSettings(isolated).trackpadTuning)
            isolated.getSharedPreferences("tablet_mode", Context.MODE_PRIVATE).edit()
                .putString("pointer_speed", "invalid type").putFloat("scroll_speed", Float.NaN).commit()
            assertEquals(3f, TabletSettings(isolated).trackpadTuning.pointerSpeed)
            assertEquals(4f, TabletSettings(isolated).trackpadTuning.scrollSpeed)
        } finally { target.deleteSharedPreferences(prefName) }
    }
}
