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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // Guard against double-respond: back press + button tap, or rapid double-tap,
    // could call onRespond twice. Once a response is sent, subsequent calls are no-ops.
    var responded by rememberSaveable { mutableStateOf(false) }
    val respond: (PermissionResponse) -> Unit = { response ->
        if (!responded) {
            responded = true
            onRespond(response)
        }
    }
    AlertDialog(
        // Back press routes here (dismissOnBackPress defaults true) and is treated as an
        // explicit reject — the safe default. Tap-outside is disabled below so it can't
        // dismiss silently. A host-composition BackHandler doesn't work: AlertDialog
        // renders in its own window whose back dispatcher never reaches the host.
        onDismissRequest = { respond(PermissionResponse.REJECT) },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
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
                        respond(PermissionResponse.ONCE)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.allow_once)) }
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        respond(PermissionResponse.ALWAYS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.always_allow)) }
                // Reject is an OutlinedButton (not a low-prominence TextButton) in the
                // same column so the three actions are visually balanced — the prior
                // layout buried Reject as a small dismiss-button, nudging users toward
                // granting. Reject uses the error color to signal its consequence.
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        respond(PermissionResponse.REJECT)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.reject), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
