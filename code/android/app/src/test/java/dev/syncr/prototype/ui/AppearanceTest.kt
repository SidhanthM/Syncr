package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun savedChoiceDefaultsSafelyToSystem() {
        assertEquals(Appearance.SYSTEM, Appearance.fromStored(null))
        assertEquals(Appearance.SYSTEM, Appearance.fromStored("unknown"))
        assertEquals(Appearance.DARK, Appearance.fromStored("DARK"))
        assertEquals(Appearance.LIGHT, Appearance.fromStored("LIGHT"))
    }

    @Test fun choiceOverridesOrFollowsDeviceAppearance() {
        assertTrue(Appearance.SYSTEM.isDark(true))
        assertFalse(Appearance.SYSTEM.isDark(false))
        assertTrue(Appearance.DARK.isDark(false))
        assertFalse(Appearance.LIGHT.isDark(true))
    }
}
