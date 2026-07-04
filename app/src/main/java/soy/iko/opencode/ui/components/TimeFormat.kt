package soy.iko.opencode.ui.components

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import soy.iko.opencode.R

/**
 * Formats an epoch-millis timestamp as a short, compact relative string (e.g. "3m", "2h",
 * "5d", "2w", "3mo", "1y").
 *
 * The compact single-letter form is deliberate: these labels sit in dense action rows and list
 * rows next to other controls, so they must stay narrow. (An earlier version used
 * [DateUtils.getRelativeTimeSpanString] with `FORMAT_ABBREV_RELATIVE` for minutes/hours/days,
 * but that returns a full phrase like "5 min. ago" / "2 hr. ago" — not the compact form the
 * examples and the numeric fallback imply — which wraps or crowds those rows.) Always returns a
 * non-empty string for a valid timestamp.
 *
 * The unit suffixes are resolved from string resources so they follow the device locale
 * (e.g. "3m" in English, "3 min" in Spanish) instead of a hardcoded English letter.
 */
fun relativeTime(context: Context, epochMillis: Long?): String {
    return when (val unit = relativeTimeUnit(epochMillis)) {
        null -> ""
        is RelativeTimeUnit.Now -> context.getString(R.string.time_now)
        is RelativeTimeUnit.Minutes -> context.getString(R.string.time_minutes_abbrev, unit.count)
        is RelativeTimeUnit.Hours -> context.getString(R.string.time_hours_abbrev, unit.count)
        is RelativeTimeUnit.Days -> context.getString(R.string.time_days_abbrev, unit.count)
        is RelativeTimeUnit.Weeks -> context.getString(R.string.time_weeks_abbrev, unit.count)
        is RelativeTimeUnit.Months -> context.getString(R.string.time_months_abbrev, unit.count)
        is RelativeTimeUnit.Years -> context.getString(R.string.time_years_abbrev, unit.count)
    }
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
    val context = LocalContext.current
    return if (sharedTick != 0L) {
        // Reading sharedTick above registers a recomposition dependency on the shared
        // ticker, so the label recomputes (cheaply) whenever the screen-level tick advances.
        relativeTime(context, epochMillis)
    } else {
        rememberRelativeTimeStandalone(epochMillis)
    }
}

/** Self-contained auto-refreshing label used when no [LocalRelativeTimeTick] provider exists. */
@Composable
private fun rememberRelativeTimeStandalone(epochMillis: Long?): String {
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

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
            relativeTime(context, epochMillis)
        }
    }
    return formatted
}

/**
 * Formats [epochMillis] as a full, locale-aware absolute timestamp (date + time + year),
 * e.g. "Jul 2, 2026, 14:33". Complements the compact [relativeTime] label shown in lists
 * and bubbles — the relative form is scannable, the absolute form is precise. Returns an
 * empty string for a null/invalid timestamp. Uses the device locale and 12/24-hour setting.
 */
fun absoluteTime(context: Context, epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    return DateUtils.formatDateTime(
        context,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )
}

/**
 * A relative-time label (auto-refreshing, per [rememberRelativeTime]) that reveals the full
 * absolute timestamp in a tooltip on long-press (or hover). Lets a compact "3m"/"2h" stay
 * the default while the exact date/time is one long-press away — without spending screen
 * space on it. Renders nothing when the timestamp is absent so callers can drop it in
 * unconditionally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelativeTimeText(
    epochMillis: Long?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val relative = rememberRelativeTime(epochMillis)
    if (relative.isEmpty()) return
    val context = LocalContext.current
    val absolute = remember(epochMillis) { absoluteTime(context, epochMillis) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(absolute) } },
        state = rememberTooltipState(),
    ) {
        Text(relative, style = style, color = color, modifier = modifier)
    }
}
