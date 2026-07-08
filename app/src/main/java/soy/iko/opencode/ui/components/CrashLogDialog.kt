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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * Crash report file contents are read once via [remember] (off the first
 * recomposition) rather than on every recomposition — [File.readText] is disk
 * I/O and shouldn't run in composition repeatedly.
 *
 * "Clear all" is a destructive action: it's placed in the dismiss slot (left,
 * neutral) and tinted with the error color, and a confirmation step guards
 * against accidental taps. "Close" is the affirmative (right) action.
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
    clearConfirmLabel: String = "Clear all crash reports?",
    clearConfirmYesLabel: String = "Clear",
) {
    // Read each report's text once, not on every recomposition.
    val contents = remember(reports) {
        reports.map { it.name to it.readText() }
    }
    var confirmingClear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (confirmingClear) confirmingClear = false else onDismiss()
        },
        modifier = modifier,
        title = { Text(title) },
        text = {
            if (confirmingClear) {
                Text(
                    text = clearConfirmLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    contents.forEachIndexed { index, (name, text) ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(Dimens.spaceTiny))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (index < contents.lastIndex) {
                            Spacer(Modifier.height(Dimens.spaceMedium))
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Primary (right) button: "Close" is the non-destructive action.
            TextButton(onClick = onDismiss) { Text(closeLabel) }
        },
        dismissButton = {
            if (confirmingClear) {
                // Confirm step: the affirmative "Clear" sits on the right now,
                // so expose a "Cancel" on the left to abort.
                TextButton(onClick = { confirmingClear = false }) { Text(closeLabel) }
            } else {
                // Destructive action in the dismiss slot (left), tinted error.
                TextButton(onClick = { confirmingClear = true }) {
                    Text(
                        text = clearLabel,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )

    // Second-stage confirmation dialog: actually clear when the user confirms.
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(title) },
            text = { Text(clearConfirmLabel) },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmingClear = false
                }) {
                    Text(
                        text = clearConfirmYesLabel,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text(closeLabel) }
            },
        )
    }
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
