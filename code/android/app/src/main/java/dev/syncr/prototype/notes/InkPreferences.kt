package dev.syncr.prototype.notes

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the Pen family is drawn on this device. A local rendering choice: stored samples never change. */
enum class PenLook { FOUNTAIN, BALLPOINT, FINELINER }

/**
 * Local ink-rendering preferences. They change only how strokes are drawn here, never a note's data.
 * [pressure] and [smoothing] are 0..1, where 0.5 is the tuned default.
 */
data class InkPreferences(
    val penLook: PenLook = PenLook.FOUNTAIN,
    val pressure: Float = 0.5f,
    val smoothing: Float = 0.5f,
    val taper: Boolean = true,
    /** Draw a short predicted tail ahead of the pen on the wet layer. Never stored, saved or exported. */
    val predictPenMotion: Boolean = true,
)

/** Process-wide store over the shared "syncr-settings" preferences, observable by views and Compose. */
object InkPreferencesStore {
    private const val FILE = "syncr-settings"
    private var flow: MutableStateFlow<InkPreferences>? = null

    fun state(context: Context): StateFlow<InkPreferences> = synchronized(this) {
        flow ?: MutableStateFlow(read(context)).also { flow = it }
    }.asStateFlow()

    fun update(context: Context, change: (InkPreferences) -> InkPreferences) {
        val next = change(state(context).value).let {
            it.copy(pressure = it.pressure.coerceIn(0f, 1f), smoothing = it.smoothing.coerceIn(0f, 1f))
        }
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString("ink-pen-look", next.penLook.name)
            putFloat("ink-pressure", next.pressure)
            putFloat("ink-smoothing", next.smoothing)
            putBoolean("ink-taper", next.taper)
            putBoolean("ink-predict", next.predictPenMotion)
        }
        synchronized(this) { flow!!.value = next }
    }

    private fun read(context: Context): InkPreferences {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaults = InkPreferences()
        return InkPreferences(
            penLook = PenLook.entries.firstOrNull { it.name == prefs.getString("ink-pen-look", null) } ?: defaults.penLook,
            pressure = prefs.getFloat("ink-pressure", defaults.pressure),
            smoothing = prefs.getFloat("ink-smoothing", defaults.smoothing),
            taper = prefs.getBoolean("ink-taper", defaults.taper),
            predictPenMotion = prefs.getBoolean("ink-predict", defaults.predictPenMotion),
        )
    }
}
