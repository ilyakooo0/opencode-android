package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme
import java.io.File

/**
 * Dialog listing persisted crash reports. Each report shows the file name
 * (header) and the full stack trace (body). Height wraps content but is
 * capped at 60% of the viewport via [Modifier.heightIn] so very long traces
 * scroll internally without consuming the whole screen.
 */
@Composable
fun CrashLogDialog(
    reports: List<File>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Crash Reports",
    clearLabel: String = "Clear all",
    closeLabel: String = "Close",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                reports.forEachIndexed { index, file ->
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Dimens.spaceTiny))
                    Text(
                        text = file.readText(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (index < reports.lastIndex) {
                        Spacer(Modifier.height(Dimens.spaceMedium))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text(clearLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(closeLabel) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun CrashLogDialogPreview() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "crash_preview.txt").apply {
        writeText("java.lang.RuntimeException: preview crash\n\tat foo.bar.baz(Preview.kt:1)")
    }
    OpencodeTheme {
        CrashLogDialog(
            reports = listOf(tmp),
            onDismiss = {},
            onClear = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CrashLogDialogEmptyPreview() {
    OpencodeTheme {
        CrashLogDialog(reports = emptyList(), onDismiss = {}, onClear = {})
    }
}
