package soy.iko.opencode.ui.components

import java.util.concurrent.TimeUnit

/** Formats an epoch-millis timestamp as a short relative string (e.g. "3m", "2h", "5d"). */
fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    val diff = System.currentTimeMillis() - epochMillis
    if (diff < 0) return "now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (minutes < 1) return "now"
    if (minutes < 60) return "${minutes}m"
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (hours < 24) return "${hours}h"
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) return "${days}d"
    val weeks = days / 7
    if (weeks < 5) return "${weeks}w"
    val months = days / 30
    if (months < 12) return "${months}mo"
    return "${days / 365}y"
}
