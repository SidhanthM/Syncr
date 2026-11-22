package dev.syncr.prototype

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import dev.syncr.prototype.link.PairLink
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.ui.Appearance
import dev.syncr.prototype.ui.SyncrApp
import dev.syncr.prototype.ui.theme.SyncrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideStatusBar()
        LinkController.get(this).bind(this)
        if (savedInstanceState == null) openPairLink(intent)
        setContent {
            val preferences = remember { getSharedPreferences("syncr-settings", MODE_PRIVATE) }
            var appearance by remember { mutableStateOf(Appearance.fromStored(preferences.getString("appearance", null))) }
            val dark = appearance.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            SyncrTheme(dark = dark) {
                SyncrApp(
                    dark = dark,
                    appearance = appearance,
                    onAppearance = {
                        appearance = it
                        preferences.edit { putString("appearance", it.name) }
                    },
                    onFinish = { finish() },
                    onPointer = { startActivity(Intent(this, PointerActivity::class.java)) },
                )
            }
        }
    }

    /** The phone's camera opened a `syncr://pair` link from the Mac's QR code: show the pairing screen for that Mac. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPairLink(intent)
    }

    private fun openPairLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        intent.data = null // a rotation or a return to the app must not open it again
        val link = LinkController.get(this)
        val pair = PairLink.parse(data)
        when {
            pair == null -> link.pairingProblem("a Mac", "That is not a Syncr pairing code. Show a new one on your Mac: Phone, Pair a Phone.")
            pair.expired(System.currentTimeMillis() / 1000) ->
                link.pairingProblem(pair.name, "This code has expired. Show a new one on your Mac: Phone, Pair a Phone.")
            else -> link.beginPairing(pair.endpoint)
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
