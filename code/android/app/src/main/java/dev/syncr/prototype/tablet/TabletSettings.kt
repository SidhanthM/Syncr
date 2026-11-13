package dev.syncr.prototype.tablet

import android.content.Context
import androidx.core.content.edit

enum class TabletOrientation { LANDSCAPE, PORTRAIT, AUTO }

/** Wi-Fi Pen/Trackpad choices, persisted locally in SharedPreferences. */
class TabletSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tablet_mode", Context.MODE_PRIVATE)

    var trackpad: Boolean
        get() = prefs.getBoolean("trackpad", false)
        set(value) = prefs.edit { putBoolean("trackpad", value) }

    var orientation: TabletOrientation
        get() = runCatching { TabletOrientation.valueOf(prefs.getString("orientation", null).orEmpty()) }.getOrDefault(TabletOrientation.LANDSCAPE)
        set(value) = prefs.edit { putString("orientation", value.name) }

    var trackpadTuning: TrackpadTuning
        get() = TrackpadTuning(responseValue(KEY_POINTER_SPEED, TrackpadTuning.POINTER_SPEED),
            responseValue(KEY_POINTER_ACCELERATION, TrackpadTuning.POINTER_ACCELERATION),
            responseValue(KEY_SCROLL_SPEED, TrackpadTuning.SCROLL_SPEED),
            responseValue(KEY_SCROLL_ACCELERATION, TrackpadTuning.SCROLL_ACCELERATION)).normalized()
        set(value) {
            val safe = value.normalized()
            prefs.edit {
                putFloat(KEY_POINTER_SPEED, safe.pointerSpeed); putFloat(KEY_POINTER_ACCELERATION, safe.pointerAcceleration)
                putFloat(KEY_SCROLL_SPEED, safe.scrollSpeed); putFloat(KEY_SCROLL_ACCELERATION, safe.scrollAcceleration)
            }
        }

    private fun responseValue(key: String, range: TrackpadRange): Float =
        range.clamp(runCatching { prefs.getFloat(key, range.default) }.getOrDefault(range.default))

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

    companion object {
        const val TRAIL_MIN_MS = 150f
        const val TRAIL_MAX_MS = 1200f
        const val TRAIL_DEFAULT_MS = 500L
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_POINTER_ACCELERATION = "pointer_acceleration"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_SCROLL_ACCELERATION = "scroll_acceleration"
        private const val KEY_TRAIL = "trail_ms"
        private const val KEY_LOCAL = "stream_local"
        private const val KEY_PICTURE_LARGE = "picture_large"
        private const val KEY_FRAME_W = "frame_width"
    }
}
