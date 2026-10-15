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
import dev.syncr.core.PageFormat
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
            var defaultFormat by remember {
                mutableStateOf(if (preferences.getString("default-page-format", null) == PageFormat.SHEET16X10.name)
                    PageFormat.SHEET16X10 else PageFormat.PHONE)
            }
            val dark = appearance.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            SyncrTheme(dark = dark) {
                SyncrApp(dark = dark, appearance = appearance, defaultFormat = defaultFormat,
                    onAppearance = { appearance = it; preferences.edit().putString("appearance", it.name).apply() },
                    onDefaultFormat = { defaultFormat = it; preferences.edit().putString("default-page-format", it.name).apply() },
                    onFinish = { finish() }, onPointer = { startActivity(Intent(this, PointerActivity::class.java)) })
            }
        }
    }
}
