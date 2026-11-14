package dev.syncr.prototype.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ENGLISH)
private val clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val clock12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** The default stamp title (`yyyy-MM-dd HH.mm`, see [defaultNoteTitle]) read back as a time; null for any other text. */
fun parseTitleStamp(title: String): LocalDateTime? {
    if (title.length != 16) return null
    return try {
        LocalDateTime.parse(title, stamp).takeIf { stamp.format(it) == title }
    } catch (_: DateTimeParseException) { null }
}

/**
 * A time as words: "Today, 23:49", "Yesterday, 09:12", "Mon 16 Nov, 23:49", and "16 Nov 2025" for another year.
 * [is24h] follows the system's 12/24-hour setting.
 */
fun friendlyTime(at: LocalDateTime, now: LocalDateTime, is24h: Boolean): String {
    val clock = (if (is24h) clock24 else clock12).format(at)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
    return when {
        at.year != now.year -> dayMonthYear.format(at)
        days == 0L -> "Today, $clock"
        days == 1L -> "Yesterday, $clock"
        else -> "${weekdayDayMonth.format(at)}, $clock"
    }
}

fun friendlyTime(millis: Long, now: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
    friendlyTime(Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime(), Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime(), is24h)

/**
 * What to show for a note's title: a title that is exactly the default stamp reads as words, anything else as typed.
 * Display only; the stored title, Rename's text, the Mac and sync keep the stamp.
 */
fun displayTitle(title: String, now: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = parseTitleStamp(title) ?: return title
    return friendlyTime(at, Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime(), is24h)
}

/** [displayTitle] for the screen: now and the 12/24-hour setting come from the system. */
@androidx.compose.runtime.Composable
fun rememberDisplayTitle(title: String): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(title) {
        displayTitle(title, System.currentTimeMillis(), android.text.format.DateFormat.is24HourFormat(context))
    }
}
