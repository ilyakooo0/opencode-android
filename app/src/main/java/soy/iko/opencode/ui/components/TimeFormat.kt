package soy.iko.opencode.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
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
    val years = (days / 365).coerceAtLeast(1)
    return "${years}y"
}

/**
 * Returns a relative-time string for [epochMillis] that auto-refreshes so the label
 * doesn't go stale (e.g. "3m" → "4m" → "5m" while the screen is visible). Re-evaluates
 * every [intervalMs] (default 30 s — coarse enough for battery, fine enough for the
 * smallest unit we show, which is minutes). Pauses the timer when the lifecycle is
 * not at least resumed (screen off / app backgrounded) to avoid battery drain.
 */
@Composable
fun rememberRelativeTime(epochMillis: Long?, intervalMs: Long = 30_000L): String {
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(epochMillis, lifecycleOwner) {
        if (epochMillis == null) return@LaunchedEffect
        // Observe lifecycle to pause the timer when the screen isn't visible.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                tick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        try {
            while (true) {
                delay(intervalMs)
                tick++
            }
        } finally {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // derivedStateOf reads tick so Compose recomposes when it changes, and recomputes
    // the relative-time string from the current wall clock on each tick.
    // Keying remember on epochMillis ensures a new derivedStateOf is created when the
    // timestamp changes, so the new value is reflected immediately.
    val formatted by remember(epochMillis) {
        derivedStateOf {
            // Read tick to create a recomposition dependency on the timer.
            tick
            relativeTime(epochMillis)
        }
    }
    return formatted
}
