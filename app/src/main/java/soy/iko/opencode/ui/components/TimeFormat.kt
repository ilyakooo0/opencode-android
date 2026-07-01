package soy.iko.opencode.ui.components

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Formats an epoch-millis timestamp as a short relative string (e.g. "3m", "2h", "5d").
 *
 * For coarse units (minutes/hours/days) prefers Android's locale-aware
 * [DateUtils.getRelativeTimeSpanString] so the abbreviations match the device locale
 * instead of hardcoded English. Falls back to a compact numeric form (e.g. "5m",
 * "3h", "2d") when [DateUtils] returns null (e.g. under JVM unit tests where
 * `isReturnDefaultValues = true` stubs the framework call to null). For
 * weeks/months/years — which [DateUtils] doesn't provide short abbreviations for —
 * uses the compact numeric form with a locale-stable unit suffix. Always returns a
 * non-empty string for a valid timestamp.
 */
fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    if (diff < 0) return "now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (minutes < 1) return "now"
    if (minutes < 60) {
        val locale = DateUtils.getRelativeTimeSpanString(
            epochMillis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )?.toString()
        return locale?.takeIf { it.isNotBlank() } ?: "${minutes}m"
    }
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (hours < 24) {
        val locale = DateUtils.getRelativeTimeSpanString(
            epochMillis, now, DateUtils.HOUR_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )?.toString()
        return locale?.takeIf { it.isNotBlank() } ?: "${hours}h"
    }
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        val locale = DateUtils.getRelativeTimeSpanString(
            epochMillis, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )?.toString()
        return locale?.takeIf { it.isNotBlank() } ?: "${days}d"
    }
    // DateUtils doesn't offer a short week/month/year abbreviation; use a compact
    // numeric form. The suffix is locale-stable (English letter) but these are
    // rare timestamps (older than a week) and the numeric value is the primary signal.
    val weeks = days / 7
    if (weeks < 5) return "${weeks}w"
    val months = days / 30
    if (months < 12) return "${months}mo"
    val years = (days / 365).coerceAtLeast(1)
    return "${years}y"
}

/** Re-evaluation interval for relative-time labels — coarse enough for battery, fine
 *  enough for the smallest unit we show (minutes). */
private const val RELATIVE_TIME_INTERVAL_MS = 30_000L

/**
 * A shared, screen-level "tick" that advances periodically so every relative-time label
 * in the subtree refreshes together. Provide it once near the top of a scrollable screen:
 *
 *   val tick = rememberRelativeTimeTick()
 *   CompositionLocalProvider(LocalRelativeTimeTick provides tick) { ... }
 *
 * [rememberRelativeTime] then reads this single source instead of each timestamp spinning
 * up its own coroutine + lifecycle observer — a list with many timestamped rows avoids a
 * timer churn as items enter and leave the viewport.
 *
 * Defaults to 0 (no provider); [rememberRelativeTime] falls back to a self-managed timer
 * then, so it keeps auto-refreshing even without a provider.
 */
val LocalRelativeTimeTick = compositionLocalOf<Long> { 0L }

/**
 * Advances a tick counter while the lifecycle is at least resumed (immediately on resume,
 * then every [intervalMs]), and suspends while the screen isn't visible to avoid battery
 * drain. Provides one shared time source for an entire subtree via [LocalRelativeTimeTick].
 */
@Composable
fun rememberRelativeTimeTick(intervalMs: Long = RELATIVE_TIME_INTERVAL_MS): Long {
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Refresh immediately on resume (so a stale label doesn't wait an interval),
            // then periodically so labels stay current.
            tick++
            while (true) {
                delay(intervalMs)
                tick++
            }
        }
    }
    return tick
}

/**
 * Formats [epochMillis] relative to now, recomputing as time passes. Prefers the shared
 * screen-level tick from [LocalRelativeTimeTick] (one timer for the whole subtree); when
 * no provider is present it falls back to its own lifecycle-bound timer, so callers don't
 * need a provider to get auto-refreshing labels.
 */
@Composable
fun rememberRelativeTime(epochMillis: Long?): String {
    val sharedTick = LocalRelativeTimeTick.current
    return if (sharedTick != 0L) {
        // Reading sharedTick above registers a recomposition dependency on the shared
        // ticker, so the label recomputes (cheaply) whenever the screen-level tick advances.
        relativeTime(epochMillis)
    } else {
        rememberRelativeTimeStandalone(epochMillis)
    }
}

/** Self-contained auto-refreshing label used when no [LocalRelativeTimeTick] provider exists. */
@Composable
private fun rememberRelativeTimeStandalone(epochMillis: Long?): String {
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.LaunchedEffect(epochMillis, lifecycleOwner) {
        if (epochMillis == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Tick immediately on resume so a stale label refreshes without waiting for
            // the first interval, then periodically.
            tick++
            while (true) {
                delay(RELATIVE_TIME_INTERVAL_MS)
                tick++
            }
        }
    }

    // derivedStateOf reads tick so Compose recomposes when it changes, and recomputes
    // the relative-time string from the current wall clock on each tick. Keying remember
    // on epochMillis ensures a new derivedStateOf is created when the timestamp changes.
    val formatted by remember(epochMillis) {
        derivedStateOf {
            // Read tick to create a recomposition dependency on the timer.
            tick
            relativeTime(epochMillis)
        }
    }
    return formatted
}
