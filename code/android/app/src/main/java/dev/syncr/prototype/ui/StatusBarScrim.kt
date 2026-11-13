package dev.syncr.prototype.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.asPaddingValues
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/**
 * The desk colour at 90% behind the status bar, so scrolled text never draws under the clock. It fades in on the
 * [Motion] fade only while [visible] (content sits beneath it). It takes no touches.
 */
@Composable
fun StatusBarScrim(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, Motion.fade(Syncr.reducedMotion), label = "status scrim")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().height(top).graphicsLayer { this.alpha = alpha }
        .background(Syncr.colors.desk.copy(alpha = 0.9f)))
}
