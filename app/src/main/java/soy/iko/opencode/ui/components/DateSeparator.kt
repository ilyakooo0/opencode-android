package soy.iko.opencode.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.ui.theme.OpencodeTheme
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A centered date label inserted between messages that fall on different
 * calendar days. Uses the user's locale date format (via [DateFormat.getLongDateFormat])
 * so it respects system preferences.
 */
@Composable
fun DateSeparator(epochSeconds: ULong, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val todayLabel = stringResource(R.string.date_today)
    val yesterdayLabel = stringResource(R.string.date_yesterday)
    val label = remember(epochSeconds) { formatDateLabel(epochSeconds, context, todayLabel, yesterdayLabel) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatDateLabel(
    epochSeconds: ULong,
    context: android.content.Context,
    todayLabel: String,
    yesterdayLabel: String,
): String {
    val date = Date(epochSeconds.toLong() * 1000)
    val today = dayOfEpoch((Calendar.getInstance(Locale.getDefault()).timeInMillis / 1000).toULong())
    val yesterday = today - 1
    val target = dayOfEpoch(epochSeconds)
    return when (target) {
        today -> todayLabel
        yesterday -> yesterdayLabel
        else -> {
            val formatter = DateFormat.getLongDateFormat(context)
            formatter.format(date)
        }
    }
}

/**
 * Returns the calendar day (as epoch days) for the given epoch seconds,
 * in the device's default timezone. Returns 0 for time == 0 (unknown).
 */
internal fun dayOfEpoch(epochSeconds: ULong): Int {
    if (epochSeconds == 0uL) return Int.MIN_VALUE
    val cal = Calendar.getInstance(Locale.getDefault())
    cal.timeInMillis = epochSeconds.toLong() * 1000
    return cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
}

@Preview(showBackground = true)
@Composable
private fun DateSeparatorPreview() {
    OpencodeTheme {
        DateSeparator(epochSeconds = 1_700_000_000uL)
    }
}
