package soy.iko.opencode.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import soy.iko.opencode.R
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme
import java.io.File

/**
 * Dialog listing persisted crash reports. Each report shows the file name
 * (header) and the full stack trace (body). Height wraps content but is
 * capped at 60% of the viewport via [Modifier.heightIn] so very long traces
 * scroll internally without consuming the whole screen.
 *
 * Crash report file contents are read asynchronously via [LaunchedEffect] on
 * [Dispatchers.IO] so disk I/O never blocks the composition thread.
 *
 * "Clear all" is a destructive action guarded by a confirmation step. The
 * single dialog swaps its content and buttons between the list view and the
 * confirm view, so there's never a second dialog stacked on top.
 *
 *  - List view:     dismiss = "Clear all" (error-tinted), confirm = "Close"
 *  - Confirm view:  dismiss = "Cancel",    confirm = "Clear"  (error-tinted)
 *
 * Each report has a "Copy" and "Share" button for filing bug reports. Tapping
 * "Copy" writes to the clipboard and invokes [onCopied] so the caller can
 * surface a snackbar.
 */
@Composable
fun CrashLogDialog(
    reports: List<File>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onCopied: () -> Unit = {},
    title: String = "Crash Reports",
    clearLabel: String = "Clear all",
    closeLabel: String = "Close",
    cancelLabel: String = "Cancel",
    clearConfirmLabel: String = "Clear all crash reports?",
    clearConfirmYesLabel: String = "Clear",
    copyLabel: String = "Copy",
    shareLabel: String = "Share",
    emptyLabel: String = "No crash reports",
) {
    // Read crash report contents asynchronously on Dispatchers.IO so disk
    // I/O never blocks the composition thread.
    var contents by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(reports) {
        contents = withContext(Dispatchers.IO) {
            reports.map { it.name to it.readText() }
        }
    }
    var confirmingClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
            } else if (contents.isEmpty()) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    contents.forEachIndexed { index, (name, text) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(Dimens.spaceSmall))
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("crash_report", text),
                                    )
                                    onCopied()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = Dimens.spaceSmall,
                                    vertical = 0.dp,
                                ),
                            ) { Text(copyLabel, style = MaterialTheme.typography.labelSmall) }
                            TextButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, name)
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = Dimens.spaceSmall,
                                    vertical = 0.dp,
                                ),
                            ) { Text(shareLabel, style = MaterialTheme.typography.labelSmall) }
                        }
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
            if (confirmingClear) {
                // Affirmative: clear (error-tinted).
                TextButton(onClick = {
                    onClear()
                    confirmingClear = false
                }) {
                    Text(
                        text = clearConfirmYesLabel,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                // Non-destructive: close.
                TextButton(onClick = onDismiss) { Text(closeLabel) }
            }
        },
        dismissButton = {
            if (confirmingClear) {
                // Abort the clear.
                TextButton(onClick = { confirmingClear = false }) { Text(cancelLabel) }
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
