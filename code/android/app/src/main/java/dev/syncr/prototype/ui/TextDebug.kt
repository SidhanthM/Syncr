package dev.syncr.prototype.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.syncr.prototype.link.TextTrace

/**
 * Emulator-only (the gate of [debugHooksAllowed]): `DEBUG_TEXT_TRACE --es do on|off|reset|report` turns the typed-text trace
 * ([TextTrace], remaining-v1 L.2) on and off and logs its line under `SyncrText`. The typing driver itself lives in the
 * editor ([TEXT_TYPE_BURST]), which must be open.
 */
@Composable
internal fun TextDebugHooks() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        if (!debugHooksAllowed(context)) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getStringExtra("do")) {
                    "on" -> TextTrace.enabled = true
                    "off" -> TextTrace.enabled = false
                    "reset" -> TextTrace.reset()
                    "report" -> Log.i(TEXT_TAG, TextTrace.report())
                }
                Log.i(TEXT_TAG, "trace ${intent.getStringExtra("do")} enabled=${TextTrace.enabled}")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(TEXT_TRACE), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

internal const val TEXT_TAG = "SyncrText"
private const val TEXT_TRACE = "dev.syncr.prototype.DEBUG_TEXT_TRACE"

/** `DEBUG_TYPE_BURST --ei count 15 --ei interval 100 --ei bursts 8 --ei gap 1500`: keys typed at the caret of the open editor. */
internal const val TEXT_TYPE_BURST = "dev.syncr.prototype.DEBUG_TYPE_BURST"
