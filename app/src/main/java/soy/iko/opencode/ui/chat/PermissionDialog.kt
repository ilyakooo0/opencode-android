package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionResponse

/**
 * Modal asking the user to approve a tool the agent wants to run. The agent run is
 * paused until one of these responses is sent. Non-dismissable by tap-outside so the
 * decision is explicit.
 */
@Composable
fun PermissionDialog(
    permission: Permission,
    onRespond: (PermissionResponse) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = { /* require an explicit choice */ },
        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
        title = { Text(permission.title ?: "Permission requested") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                permission.type?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                }
                permission.patternText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Allow this tool to run?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onRespond(PermissionResponse.ONCE)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow once") }
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onRespond(PermissionResponse.ALWAYS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Always allow") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onRespond(PermissionResponse.REJECT)
            }) {
                Text("Reject", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
