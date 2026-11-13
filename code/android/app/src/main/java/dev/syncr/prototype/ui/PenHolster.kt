package dev.syncr.prototype.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Best effort: Samsung phones with an S Pen silo broadcast `com.samsung.pen.INSERT` with a boolean extra `penInsert`
 * (true: the pen went into its holster) when it moves. The names are from memory of community code, NOT confirmed
 * from Samsung documentation (a public search found nothing); if they are wrong nothing happens. Never verified on
 * hardware. Phones and emulators that never send it behave as before.
 */
object PenHolster {
    const val ACTION = "com.samsung.pen.INSERT"
    const val EXTRA = "penInsert"

    /** Whether the pen is now in its holster, or null when this is not the holster broadcast. */
    fun inserted(action: String?, penInsert: Boolean?): Boolean? = if (action == ACTION && penInsert != null) penInsert else null
}

/** Listens for the holster broadcast while the note is on screen; [onChange] hears true when the pen is put away. */
@Composable
internal fun PenHolsterEffect(onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onChange)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val has = intent.hasExtra(PenHolster.EXTRA)
                PenHolster.inserted(intent.action, if (has) intent.getBooleanExtra(PenHolster.EXTRA, false) else null)?.let { latest.value(it) }
            }
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(PenHolster.ACTION), ContextCompat.RECEIVER_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}
