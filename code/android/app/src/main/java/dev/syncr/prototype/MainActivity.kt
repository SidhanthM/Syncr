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
import androidx.core.view.WindowInsetsControllerCompat
import dev.syncr.prototype.ui.Appearance
import dev.syncr.prototype.ui.SyncrApp
import dev.syncr.prototype.ui.theme.SyncrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                SyncrApp(dark = dark, appearance = appearance,
                    onAppearance = { appearance = it; preferences.edit().putString("appearance", it.name).apply() },
                    onFinish = { finish() }, onPointer = { startActivity(Intent(this, PointerActivity::class.java)) })
            }
        }
    }
}
