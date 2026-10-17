package dev.syncr.prototype.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Creation time of a ULID-identified note: the first 10 Crockford base32 characters are
 * milliseconds since the Unix epoch (48 bits). Returns null for anything that is not a ULID.
 */
fun ulidMillis(id: String): Long? {
    if (id.length != 26) return null
    var millis = 0L
    for (index in 0 until 10) {
        val digit = CROCKFORD.indexOf(id[index].uppercaseChar())
        if (digit < 0) return null
        millis = millis * 32 + digit
    }
    // 10 × 5 bits = 50; a canonical ULID timestamp fits in 48 bits (first char 0–7).
    return if (millis <= 0xFFFF_FFFF_FFFFL) millis else null
}

private val displayDate = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val titleStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")

/** Compact absolute date, e.g. `2026.11.11`. */
fun displayDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    displayDate.format(Instant.ofEpochMilli(millis).atZone(zone))

/** Default title for a new note, the design §6.1 naming style: `2026-11-11 14.32`. */
fun defaultNoteTitle(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    titleStamp.format(Instant.ofEpochMilli(millis).atZone(zone))

private val monthDay = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)

/**
 * Quiet relative date for library cards (the mock's "Today", "2 hours ago", "Last week"):
 * under a minute "Just now"; under an hour "N minutes ago"; under six hours "N hours ago"; then
 * "Today", "Yesterday", the weekday within a week, "Last week" within two, else the date.
 * A time in the future (clock change) reads "Just now".
 */
fun relativeDate(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val elapsed = now - millis
    if (elapsed < 60_000) return "Just now"
    val minutes = elapsed / 60_000
    if (minutes < 60) return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    val hours = minutes / 60
    if (hours < 6) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
    val then = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> then.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        days < 14 -> "Last week"
        then.year == today.year -> monthDay.format(then)
        else -> monthDayYear.format(then)
    }
}

/**
 * Caption for a note in Recently deleted: "Deleted today", "Deleted 1 day ago", "Deleted N days ago",
 * then when it will be removed for good ([retentionDays] after deletion, purged on library load).
 * Whole elapsed days, not calendar days; a future time (clock change) reads "today".
 */
fun deletedCaption(deletedAtMs: Long, now: Long, retentionDays: Int = 30): String {
    val days = maxOf(0L, (now - deletedAtMs) / 86_400_000L)
    val deleted = when (days) { 0L -> "Deleted today"; 1L -> "Deleted 1 day ago"; else -> "Deleted $days days ago" }
    val left = retentionDays - days
    val removal = when {
        left <= 0L -> "removed on next launch"
        left == 1L -> "removed tomorrow"
        else -> "removed in $left days"
    }
    return "$deleted · $removal"
}
