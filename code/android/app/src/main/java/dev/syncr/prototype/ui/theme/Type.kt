package dev.syncr.prototype.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One type scale, in the system sans (design `--sans`). The design's phone mock is drawn at about 2/3 of
 * phone scale, so its sizes are multiplied by roughly 1.5 here ("Notes" 24 → 30, card name 10.5 → 15,
 * chip 10 → 14, date 9 → 13). Sizes are in sp so they follow the user's font size.
 */
@Immutable
data class SyncrType(
    /** Page heading ("Notes", "Settings"). */
    val heading: TextStyle,
    /** Sheet titles and a note's name in its sheet. */
    val subheading: TextStyle,
    /** Card names, row titles, dialog titles. */
    val title: TextStyle,
    /** Reading text: banners, dialog copy, settings rows. */
    val body: TextStyle,
    /** Chips, buttons, section labels and the note header. */
    val label: TextStyle,
    /** Dates, hints and details. */
    val caption: TextStyle,
    /** A note's title on a card, a row or the floating chip: display-leaning, tightly tracked. */
    val noteTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    /** The quiet line under a note's title (notebook, when), set in capitals by the caller. */
    val meta: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

internal val SyncrTypeScale = SyncrType(
    heading = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    subheading = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    title = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)
