package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    AlertDialog(
        onDismissRequest = { /* require an explicit choice */ },
        title = { Text(permission.title ?: "Permission requested") },
        text = {
            Column {
                permission.type?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                }
                permission.patternText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onRespond(PermissionResponse.ONCE) }) { Text("Allow once") }
                TextButton(onClick = { onRespond(PermissionResponse.ALWAYS) }) { Text("Always allow") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onRespond(PermissionResponse.REJECT) }) {
                Text("Reject", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
