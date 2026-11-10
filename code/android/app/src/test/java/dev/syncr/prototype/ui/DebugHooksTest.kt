package dev.syncr.prototype.ui

import org.junit.Assert.*
import org.junit.Test

class DebugHooksTest {
    private val emulatorFp = "google/sdk_gphone64_arm64/emu64a:16/BE2A/1:userdebug/dev-keys"
    @Test fun debuggableEmulatorAllowed() {
        assertTrue(debugHooksAllowed(true, "ranchu", emulatorFp))
        assertTrue(debugHooksAllowed(true, "goldfish", "generic/sdk/x"))
    }
    @Test fun debuggableSamsungPhoneNotAllowed() =
        assertFalse(debugHooksAllowed(true, "qcom", "samsung/dm3qsqw/dm3q:14/UP1A/1:user/release-keys"))
    @Test fun nonDebuggableEmulatorNotAllowed() = assertFalse(debugHooksAllowed(false, "ranchu", emulatorFp))
}
