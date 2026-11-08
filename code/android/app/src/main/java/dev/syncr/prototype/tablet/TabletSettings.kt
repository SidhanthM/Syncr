package dev.syncr.prototype.tablet

import android.content.Context
import androidx.core.content.edit

/** Tablet mode choices, persisted in SharedPreferences. */
class TabletSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tablet_mode", Context.MODE_PRIVATE)

    var link: TabletLink?
        get() = TabletLink.entries.firstOrNull { it.name == prefs.getString(KEY_LINK, null) }
        set(value) = prefs.edit { putString(KEY_LINK, value?.name) }

    var mode: MappingMode
        get() = enumOr(prefs.getString(KEY_MODE, null), MappingMode.ACTUAL_SIZE)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    var screen: MacScreen
        get() = runCatching {
            MacScreen(
                prefs.getFloat(KEY_W, MacScreen.DEFAULT.widthMm.toFloat()).toDouble(),
                prefs.getFloat(KEY_H, MacScreen.DEFAULT.heightMm.toFloat()).toDouble(),
            )
        }.getOrDefault(MacScreen.DEFAULT)
        set(value) = prefs.edit { putFloat(KEY_W, value.widthMm.toFloat()); putFloat(KEY_H, value.heightMm.toFloat()) }

    /** The user has checked actual size with a ruler for the current Mac screen size. */
    var calibrated: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATED, false)
        set(value) = prefs.edit { putBoolean(KEY_CALIBRATED, value) }

    var offsetXMm: Double
        get() = prefs.getFloat(KEY_OX, 0f).toDouble()
        set(value) = prefs.edit { putFloat(KEY_OX, value.toFloat()) }

    var offsetYMm: Double
        get() = prefs.getFloat(KEY_OY, 0f).toDouble()
        set(value) = prefs.edit { putFloat(KEY_OY, value.toFloat()) }

    /** Bluetooth address of the last Mac chosen in "Select paired Mac", reconnected automatically. */
    var hostAddress: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    /** Stream: the pad writes inside a pen frame on the Mac's page (Local) instead of covering the whole view. */
    var streamLocal: Boolean
        get() = prefs.getBoolean(KEY_LOCAL, true)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL, value) }

    /** Stream: the picture of what the pad covers fills the pad (true) or sits in its corner. */
    var pictureLarge: Boolean
        get() = prefs.getBoolean(KEY_PICTURE_LARGE, true)
        set(value) = prefs.edit { putBoolean(KEY_PICTURE_LARGE, value) }

    /** Local: the pen frame's width last used, in canvas units; 0 until one was chosen. */
    var frameWidth: Float
        get() = prefs.getFloat(KEY_FRAME_W, 0f)
        set(value) = prefs.edit { putFloat(KEY_FRAME_W, value) }

    /** How long the pen's fading trail lasts on the pad. */
    var trailMs: Long
        get() = prefs.getLong(KEY_TRAIL, TRAIL_DEFAULT_MS).coerceIn(TRAIL_MIN_MS.toLong(), TRAIL_MAX_MS.toLong())
        set(value) = prefs.edit { putLong(KEY_TRAIL, value.coerceIn(TRAIL_MIN_MS.toLong(), TRAIL_MAX_MS.toLong())) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val TRAIL_MIN_MS = 150f
        const val TRAIL_MAX_MS = 1200f
        const val TRAIL_DEFAULT_MS = 500L
        private const val KEY_TRAIL = "trail_ms"
        private const val KEY_LOCAL = "stream_local"
        private const val KEY_PICTURE_LARGE = "picture_large"
        private const val KEY_FRAME_W = "frame_width"
        private const val KEY_LINK = "link"
        private const val KEY_MODE = "mapping_mode"
        private const val KEY_W = "mac_width_mm"
        private const val KEY_H = "mac_height_mm"
        private const val KEY_CALIBRATED = "calibrated"
        private const val KEY_OX = "window_offset_x_mm"
        private const val KEY_OY = "window_offset_y_mm"
        private const val KEY_HOST = "host_address"
    }
}
