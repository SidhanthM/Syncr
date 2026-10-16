package dev.syncr.prototype.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.syncr.prototype.R

/** The one accent (design §8.1): active tool and selection only. Never a "Mac confirmed" ring in this milestone. */
val DustyRose = Color(0xffd39999)

/**
 * Paper and chrome colours as ARGB ints, shared with the Android [dev.syncr.prototype.notes.InkSheetView].
 * Ink colours stay in `inkColour()`; the paper never competes with them.
 */
object PaperPalette {
    fun desk(dark: Boolean): Int = if (dark) 0xff0e0e0f.toInt() else 0xfff4f3f1.toInt()
    fun paper(dark: Boolean): Int = if (dark) 0xff1b1b1d.toInt() else 0xffffffff.toInt()
    /** Soft page shadow and hairline edge; dotted lines are reserved for lasso and selection. */
    fun shadow(dark: Boolean): Int = if (dark) 0x66000000 else 0x1f000000
    fun hairline(dark: Boolean): Int = if (dark) 0xff2c2c2f.toInt() else 0xffe6e4e0.toInt()
    fun quiet(dark: Boolean): Int = if (dark) 0xff8e8b91.toInt() else 0xff9b9a97.toInt()
}

@Immutable
data class SyncrColors(
    val desk: Color,
    val paper: Color,
    val chrome: Color,
    val ink: Color,
    val muted: Color,
    /** Hairline dividers and card edges (Notion-like calm, no dotted motif). */
    val dots: Color,
    val accent: Color,
    /** Floating tool dot/bar: the inverse of the paper so it never blends in. */
    val tool: Color,
    val onTool: Color,
    val ringSaved: Color,
    val attention: Color,
    val dark: Boolean,
)

private val Light = SyncrColors(
    desk = Color(0xffffffff), paper = Color(0xfff7f6f3), chrome = Color(0xffffffff),
    ink = Color(0xff37352f), muted = Color(0xff787774), dots = Color(PaperPalette.hairline(false)), accent = DustyRose,
    tool = Color(0xff1c1b1e), onTool = Color(0xfff7f4ef), ringSaved = Color(0xff9a958f), attention = Color(0xffc4800f), dark = false,
)
private val Dark = SyncrColors(
    desk = Color(0xff191919), paper = Color(0xff202020), chrome = Color(0xff252525),
    ink = Color(0xffe9e9e7), muted = Color(0xff9b9b9b), dots = Color(PaperPalette.hairline(true)), accent = DustyRose,
    tool = Color(0xffeae6e1), onTool = Color(0xff161617), ringSaved = Color(0xff75717a), attention = Color(0xffe3a63c), dark = true,
)

/** Dot-matrix face, now used in one subtle place only: the page indicator. Doto (OFL, see assets/licenses/Doto-OFL.txt). */
@OptIn(ExperimentalTextApi::class)
val DisplayFace: FontFamily = FontFamily(
    Font(R.font.doto, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

@Immutable
data class SyncrType(
    /** Large regular-weight page heading ("Notes"). */
    val heading: TextStyle,
    /** Small quiet label, sentence case. */
    val label: TextStyle,
    /** The single dot-matrix use: page indicator numerals. */
    val pageIndicator: TextStyle,
    val display: TextStyle,
    val displaySmall: TextStyle,
    val body: TextStyle,
    val title: TextStyle,
    val caption: TextStyle,
)

private val Type = SyncrType(
    heading = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    pageIndicator = TextStyle(fontFamily = DisplayFace, fontSize = 13.sp, letterSpacing = 0.8.sp),
    display = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    displaySmall = TextStyle(fontSize = 13.sp),
    body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    title = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),

    caption = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
)

private val LocalColors = staticCompositionLocalOf { Light }
private val LocalType = staticCompositionLocalOf { Type }

object Syncr {
    val colors: SyncrColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: SyncrType @Composable @ReadOnlyComposable get() = LocalType.current
}

/** Light/dark follow the system. Material primary is the ink colour so stock controls stay monochrome. */
@Composable
fun SyncrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.ink, onPrimary = c.desk, secondary = c.muted, background = c.desk, onBackground = c.ink,
        surface = c.chrome, onSurface = c.ink, surfaceContainerLow = c.chrome, surfaceContainer = c.chrome,
        surfaceContainerHigh = c.chrome, surfaceContainerHighest = c.chrome, onSurfaceVariant = c.muted,
        outline = c.dots, outlineVariant = c.dots, error = c.attention,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = c.paper, secondary = c.muted, background = c.desk, onBackground = c.ink,
        surface = c.chrome, onSurface = c.ink, surfaceContainerLow = c.chrome, surfaceContainer = c.chrome,
        surfaceContainerHigh = c.chrome, surfaceContainerHighest = c.chrome, onSurfaceVariant = c.muted,
        outline = c.dots, outlineVariant = c.dots, error = c.attention,
    )
    CompositionLocalProvider(LocalColors provides c, LocalType provides Type) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
