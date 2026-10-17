package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    /** A map standing in for SharedPreferences. */
    private class MapValues(val map: MutableMap<String, Any> = mutableMapOf()) : PreferenceValues, PreferenceWriter {
        override fun string(key: String) = map[key] as? String
        override fun boolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun float(key: String, default: Float) = map[key] as? Float ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun emptyStoreGivesDefaults() {
        val p = AppPreferencesStore.read(MapValues())
        assertEquals(AppPreferences().copy(bubbleX = p.bubbleX, bubbleY = p.bubbleY), p)
        assertEquals(SideButtonAction.ERASE, p.sideButton)
        assertTrue(p.twoFingerUndo)
        assertTrue(p.doubleTapFit)
        assertEquals(PalmDelay.NORMAL, p.palmDelay)
        assertFalse(p.leftHanded)
        assertTrue(p.bubbleX.isNaN() && p.bubbleY.isNaN())
    }

    @Test fun palmDelaysAreTheSpecifiedMillis() {
        assertEquals(listOf(150L, 300L, 500L), PalmDelay.entries.map { it.millis })
        assertEquals(300L, PalmDelay.NORMAL.millis)
    }

    @Test fun valuesRoundTrip() {
        val store = MapValues()
        val p = AppPreferences(sideButton = SideButtonAction.OFF, twoFingerUndo = false, doubleTapFit = false,
            palmDelay = PalmDelay.LONG, leftHanded = true, bubbleX = 0.25f, bubbleY = 0.75f)
        AppPreferencesStore.write(p, store)
        assertEquals(p, AppPreferencesStore.read(store))
    }

    @Test fun unknownStoredChoicesFallBackToDefaults() {
        val store = MapValues(mutableMapOf("pen-side-button" to "SQUEEZE", "pen-palm-delay" to "FOREVER"))
        val p = AppPreferencesStore.read(store)
        assertEquals(SideButtonAction.ERASE, p.sideButton)
        assertEquals(PalmDelay.NORMAL, p.palmDelay)
    }

    @Test fun ownersExistingBubblePositionIsKept() {
        // FloatingChrome stored these keys before the store existed.
        val p = AppPreferencesStore.read(MapValues(mutableMapOf("bubble-x" to 0.4f, "bubble-y" to 0.6f)))
        assertEquals(0.4f, p.bubbleX)
        assertEquals(0.6f, p.bubbleY)
    }

    @Test fun resetRemovesThePositionAndBadFractionsReadAsUnmoved() {
        val store = MapValues(mutableMapOf("bubble-x" to 0.4f, "bubble-y" to 0.6f))
        AppPreferencesStore.write(AppPreferences(), store)
        assertFalse(store.map.containsKey("bubble-x") || store.map.containsKey("bubble-y"))
        val bad = AppPreferencesStore.read(MapValues(mutableMapOf("bubble-x" to 3f, "bubble-y" to Float.POSITIVE_INFINITY)))
        assertTrue(bad.bubbleX.isNaN() && bad.bubbleY.isNaN())
    }
}
