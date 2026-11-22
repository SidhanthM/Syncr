package dev.syncr.prototype.canvas

import android.content.Context

/** The canvas's own per-device settings; never note data. Plain, testable codecs plus a thin SharedPreferences shell. */
internal data class SavedView(val zoom: Double, val centerX: Double, val centerY: Double) {
    fun encode() = "$zoom,$centerX,$centerY"

    companion object {
        fun decode(s: String?): SavedView? {
            val p = s?.split(',') ?: return null
            if (p.size != 3) return null
            val v = p.map { it.toDoubleOrNull() ?: return null }
            if (v.any { !it.isFinite() } || v[0] <= 0.0) return null
            return SavedView(v[0].coerceIn(InfiniteViewport.MIN_ZOOM, InfiniteViewport.MAX_ZOOM), v[1], v[2])
        }
    }
}

internal class CanvasViewStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("canvas_views", Context.MODE_PRIVATE)

    fun view(noteId: String): SavedView? = runCatching { SavedView.decode(prefs.getString("view.$noteId", null)) }.getOrNull()
    fun saveView(noteId: String, v: SavedView) { runCatching { prefs.edit().putString("view.$noteId", v.encode()).apply() } }

    var minimap: Boolean
        get() = runCatching { prefs.getBoolean("minimap", true) }.getOrDefault(true)
        set(v) { runCatching { prefs.edit().putBoolean("minimap", v).apply() } }
}
