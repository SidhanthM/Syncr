package dev.syncr.prototype.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * The one decision for the debug-only broadcast hooks (`DEBUG_FOREIGN_EDIT`, `DEBUG_STIPPLE`, the recorded stream): a
 * debuggable build on an emulator. The owner's phone runs the debug build too, so debuggable alone is not enough.
 */
internal fun debugHooksAllowed(debuggable: Boolean, hardware: String, fingerprint: String): Boolean =
    debuggable && (hardware.equals("ranchu", true) || hardware.equals("goldfish", true) ||
        fingerprint.startsWith("generic", true) || fingerprint.contains("emulator", true))

internal fun debugHooksAllowed(context: Context): Boolean = debugHooksAllowed(
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, Build.HARDWARE, Build.FINGERPRINT)
