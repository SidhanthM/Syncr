package dev.syncr.prototype.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/** Slide the hardware override in, then slide left back to the unchanged selected tool. */
@Composable
internal fun TemporaryEraserGlyph(tools: ToolState, held: Boolean) {
    val reduced = Syncr.reducedMotion
    // If Eraser was already selected there is nothing to swap, including on release.
    val activeOverride = held && !tools.eraser
    Box(Modifier.size(22.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        AnimatedContent(targetState = activeOverride, label = "S Pen eraser override",
            transitionSpec = {
                if (reduced) Motion.menuIn(true) togetherWith Motion.menuOut(true)
                else {
                    // Engage: pen leaves right, eraser enters from left.
                    // Release: eraser leaves left, pen returns from right.
                    val direction = if (targetState) 1 else -1
                    slideInHorizontally(Motion.quick()) { -direction * it } togetherWith
                        slideOutHorizontally(Motion.quick()) { direction * it }
                }
            }) { erasing ->
            if (erasing) ToolGlyph(tools.brush, eraser = true, lasso = false)
            else ToolGlyph(tools.brush, tools.eraser, tools.lasso, tools.typing)
        }
    }
}
