package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.R

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
    // Intercept back so the dialog can't be dismissed without an explicit choice.
    BackHandler { onRespond(PermissionResponse.REJECT) }
    AlertDialog(
        onDismissRequest = { /* require an explicit choice */ },
        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
        title = { Text(permission.title ?: stringResource(R.string.permission_title)) },
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
                    stringResource(R.string.permission_text),
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
                ) { Text(stringResource(R.string.allow_once)) }
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onRespond(PermissionResponse.ALWAYS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.always_allow)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onRespond(PermissionResponse.REJECT)
            }) {
                Text(stringResource(R.string.reject), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
