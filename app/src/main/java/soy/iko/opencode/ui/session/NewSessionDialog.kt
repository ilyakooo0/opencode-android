package soy.iko.opencode.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R

/** The user's directory choice in the new-session picker. */
private sealed interface DirChoice {
    /** Create with no directory override — the server uses its launch cwd. */
    object ServerDefault : DirChoice

    /** A known worktree / recent-session directory. */
    data class Known(val path: String) : DirChoice

    /** A path the user types by hand. */
    object Custom : DirChoice
}

// Persist the radio selection across configuration changes / process death as a short
// string tag ("d" = server default, "c" = custom, "k:<path>" = a known directory).
private val dirChoiceSaver = Saver<DirChoice, String>(
    save = {
        when (it) {
            is DirChoice.ServerDefault -> "d"
            is DirChoice.Custom -> "c"
            is DirChoice.Known -> "k:${it.path}"
        }
    },
    restore = {
        when {
            it == "c" -> DirChoice.Custom
            it.startsWith("k:") -> DirChoice.Known(it.removePrefix("k:"))
            else -> DirChoice.ServerDefault
        }
    },
)

/**
 * Dialog shown when creating a new session: lets the user pick the working directory the
 * agent will run in. Offers the server's default (cwd), the directories the server knows
 * about ([options].projects) plus any directories of existing [sessionDirectories], and a
 * free-text path. Sends the resolved path (or null for the server default) to [onCreate].
 *
 * [lastChosenDirectory] preselects the directory used for the previous new session.
 */
@Composable
fun NewSessionDialog(
    options: DirectoryOptionsState,
    sessionDirectories: List<String>,
    lastChosenDirectory: String?,
    creating: Boolean,
    onCreate: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Directories to list as selectable rows: known projects + directories of existing
    // sessions + the last chosen one, minus the server default (which has its own row).
    val knownDirs = remember(options.projects, sessionDirectories, lastChosenDirectory, options.serverDefault) {
        (options.projects + sessionDirectories + listOfNotNull(lastChosenDirectory))
            .filter { it.isNotBlank() && it != options.serverDefault }
            .distinct()
    }

    // Preselect the last chosen directory (or the most-recent session's directory); both are
    // included in knownDirs above, so a non-null, non-default preselect always maps to a row.
    var choice by rememberSaveable(stateSaver = dirChoiceSaver) {
        val preselect = lastChosenDirectory ?: sessionDirectories.firstOrNull { it.isNotBlank() }
        mutableStateOf(
            if (preselect == null || preselect == options.serverDefault) DirChoice.ServerDefault
            else DirChoice.Known(preselect),
        )
    }
    var customText by rememberSaveable { mutableStateOf("") }

    val resolved: String? = when (val c = choice) {
        is DirChoice.ServerDefault -> null
        is DirChoice.Known -> c.path
        is DirChoice.Custom -> customText.trim().takeIf { it.isNotBlank() }
    }
    // Custom requires a non-blank path; the other choices are always valid.
    val canCreate = !creating && (choice !is DirChoice.Custom || customText.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(stringResource(R.string.new_session)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.directory_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                DirectoryRow(
                    label = stringResource(R.string.directory_server_default),
                    sublabel = options.serverDefault,
                    selected = choice is DirChoice.ServerDefault,
                    enabled = !creating,
                    onSelect = { choice = DirChoice.ServerDefault },
                )

                knownDirs.forEach { path ->
                    DirectoryRow(
                        label = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                        sublabel = path,
                        selected = (choice as? DirChoice.Known)?.path == path,
                        enabled = !creating,
                        onSelect = { choice = DirChoice.Known(path) },
                    )
                }

                DirectoryRow(
                    label = stringResource(R.string.directory_custom),
                    sublabel = null,
                    selected = choice is DirChoice.Custom,
                    enabled = !creating,
                    onSelect = { choice = DirChoice.Custom },
                )
                if (choice is DirChoice.Custom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, top = 4.dp, bottom = 4.dp),
                        placeholder = { Text(stringResource(R.string.directory_custom_hint)) },
                        singleLine = true,
                        enabled = !creating,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }

                if (options.loading && !options.loaded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.directory_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(resolved) }, enabled = canCreate) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DirectoryRow(
    label: String,
    sublabel: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sublabel.isNullOrBlank() && sublabel != label) {
                // Full path can be long; let it scroll horizontally rather than truncate so
                // the user can verify exactly which directory they're selecting.
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
