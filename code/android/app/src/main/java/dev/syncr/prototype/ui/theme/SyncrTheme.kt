package dev.syncr.prototype.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { SyncrTypeScale }
private val LocalReducedMotion = staticCompositionLocalOf { false }

object Syncr {
    val colors: SyncrColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: SyncrType @Composable @ReadOnlyComposable get() = LocalType.current
    /** True when the system asks for no animations; every animation in the app checks this. */
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * Light or dark, following the caller (the system setting or the user's choice). Material's primary is the ink
 * colour so stock controls stay monochrome, and Material's type roles map onto [SyncrType] so dialogs, menus and
 * text fields share the one scale.
 */
@Composable
fun SyncrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduced = remember(context) { runCatching { Motion.reducedMotion(context) }.getOrDefault(false) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.ink, onPrimary = c.chrome, primaryContainer = c.tint, onPrimaryContainer = c.ink,
        secondary = c.muted, onSecondary = c.chrome, secondaryContainer = c.tint, onSecondaryContainer = c.ink,
        tertiary = c.muted, background = c.desk, onBackground = c.ink,
        surface = c.chrome, onSurface = c.ink, surfaceVariant = c.tint, onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.chrome, surfaceContainerLow = c.chrome, surfaceContainer = c.chrome,
        surfaceContainerHigh = c.chrome, surfaceContainerHighest = c.tint, surfaceTint = c.chrome,
        inverseSurface = c.ink, inverseOnSurface = c.chrome,
        outline = c.lineStrong, outlineVariant = c.dots, scrim = c.scrim,
        error = c.destructive, onError = c.chrome,
    )
    val t = SyncrTypeScale
    val typography = Typography(
        headlineSmall = t.subheading,
        titleLarge = t.title, titleMedium = t.title, titleSmall = t.label,
        bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
        labelLarge = t.label, labelMedium = t.label, labelSmall = t.caption,
    )
    CompositionLocalProvider(LocalColors provides c, LocalType provides t, LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
