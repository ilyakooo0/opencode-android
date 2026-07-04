package soy.iko.opencode.ui.components

import java.util.concurrent.TimeUnit

/**
 * The time unit + count resolved from a delta, before locale-specific formatting. Kept as a
 * pure value so unit tests can verify the boundary logic without a [android.content.Context]
 * (string-resource resolution is Android-only and stubs to null under `isReturnDefaultValues`).
 */
sealed class RelativeTimeUnit {
    data class Now(val count: Int = 0) : RelativeTimeUnit()
    data class Minutes(val count: Long) : RelativeTimeUnit()
    data class Hours(val count: Long) : RelativeTimeUnit()
    data class Days(val count: Long) : RelativeTimeUnit()
    data class Weeks(val count: Long) : RelativeTimeUnit()
    data class Months(val count: Long) : RelativeTimeUnit()
    data class Years(val count: Long) : RelativeTimeUnit()
}

/**
 * Resolves the [RelativeTimeUnit] for a delta without any locale-specific formatting, so the
 * boundary logic is unit-testable on the JVM without a [android.content.Context].
 */
fun relativeTimeUnit(epochMillis: Long?): RelativeTimeUnit? {
    if (epochMillis == null || epochMillis <= 0) return null
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    if (diff < 0) return RelativeTimeUnit.Now()
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (minutes < 1) return RelativeTimeUnit.Now()
    if (minutes < 60) return RelativeTimeUnit.Minutes(minutes)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (hours < 24) return RelativeTimeUnit.Hours(hours)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) return RelativeTimeUnit.Days(days)
    val weeks = days / 7
    if (weeks < 5) return RelativeTimeUnit.Weeks(weeks)
    if (days < 365) return RelativeTimeUnit.Months(days / 30)
    val years = days / 365
    return RelativeTimeUnit.Years(years)
}
