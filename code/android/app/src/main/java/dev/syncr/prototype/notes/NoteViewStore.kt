package dev.syncr.prototype.notes

import android.content.Context

/** Per-note reading settings kept on this device, never in the note (as xnotes keeps them app-side). */
object NoteViewStore {
    private const val FILE = "syncr-note-views"

    fun encode(s: PageViewSettings) = "${s.mode.name}:${if (s.flip) "flip" else "scroll"}"

    fun decode(text: String?): PageViewSettings {
        val parts = text?.split(':') ?: return PageViewSettings()
        val mode = PageArrangement.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: PageArrangement.SINGLE
        return PageViewSettings(mode, parts.getOrNull(1) == "flip")
    }

    fun get(context: Context, noteId: String): PageViewSettings = try {
        decode(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(noteId, null))
    } catch (_: Exception) { PageViewSettings() }

    fun set(context: Context, noteId: String, settings: PageViewSettings) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(noteId, encode(settings)).apply()
        } catch (_: Exception) { }
    }
}
