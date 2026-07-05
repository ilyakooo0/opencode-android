package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.R

/**
 * Modal asking the user to approve a tool the agent wants to run. The agent run is
 * paused until one of these responses is sent. Non-dismissable by tap-outside so the
 * decision is explicit.
 *
 * After [NetworkConfig.permissionAutoRejectMs] the prompt auto-rejects so a forgotten
 * prompt doesn't hold the run open indefinitely (the foreground service keeps the process
 * alive, so without a timeout a walked-away user blocks the run forever). A "still waiting"
 * reminder appears after [NetworkConfig.permissionReminderThresholdMs].
 */
@Composable
fun PermissionDialog(
    permission: Permission,
    onRespond: (PermissionResponse) -> Unit,
    position: Int = 0,
    total: Int = 0,
    onAutoReject: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    // Guard against double-respond: back press + button tap, or rapid double-tap,
    // could call onRespond twice. Once a response is sent, subsequent calls are no-ops.
    // Keyed on permission.id: the host mounts this dialog against a conflating StateFlow,
    // so a null -> permissionB transition can collapse to just permissionB, reusing this
    // composable for a different permission. Without the key `responded` would stay true
    // and every button (and back-press) would be a no-op — an undismissable modal.
    // Plain `remember` (not `rememberSaveable`): a failed respondPermission re-enqueues
    // the same permission id (ChatViewModel.respondPermission -> enqueuePermission), and
    // rememberSaveable would restore a stale `true` from the Bundle across a config
    // change in between, locking the re-queued modal with all buttons no-op. Plain
    // remember resets to false on every fresh composition, so a re-queued permission is
    // always answerable.
    var responded by remember(permission.id) { mutableStateOf(false) }
    // Elapsed ms since the dialog opened for this permission, used to surface a "still
    // waiting" reminder after the threshold. Caps at the auto-reject deadline.
    var elapsedMs by remember(permission.id) { mutableStateOf(0L) }
    val respond: (PermissionResponse) -> Unit = { response ->
        if (!responded) {
            responded = true
            onRespond(response)
        }
    }
    // Auto-reject after the configured timeout. A forgotten prompt otherwise blocks the run
    // indefinitely (the foreground service holds the process alive). LaunchedEffect keyed on
    // permission.id so a fresh prompt resets the timer. Disabled when the timeout is 0.
    val autoRejectMs = NetworkConfig.permissionAutoRejectMs
    LaunchedEffect(permission.id, autoRejectMs) {
        if (autoRejectMs <= 0L) return@LaunchedEffect
        val tickMs = 1000L
        while (elapsedMs < autoRejectMs) {
            delay(tickMs)
            elapsedMs = (elapsedMs + tickMs).coerceAtMost(autoRejectMs)
        }
        // Haptic on auto-reject so a walked-away user is signaled that the decision was made.
        // Guard on `responded`: if the user answered within the final tick before the timeout,
        // the delay() resumed before recomposition cancelled this effect. respond(REJECT) is
        // itself guarded by `responded` (no-op), but onAutoReject() posts a real "permission
        // auto-rejected" notification — without this guard a user who explicitly answered would
        // get a false "you didn't respond" notification for a permission they did respond to.
        if (!responded) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            // Leave a persistent trace (notification) so a returning user who missed the dialog
            // closing understands why the run stopped — the in-app dialog is gone by then.
            onAutoReject()
            respond(PermissionResponse.REJECT)
        }
    }
    val showReminder = elapsedMs >= NetworkConfig.permissionReminderThresholdMs
    // In the final 60 seconds, switch to a per-second countdown so the imminent auto-reject
    // is clearly signaled (a full minute of per-second countdown, not just the last 10s,
    // so the user isn't surprised at the 10s mark by a sudden format change).
    val showSecondsWarning = autoRejectMs > 0L && elapsedMs >= autoRejectMs - 60_000L
    AlertDialog(
        // Back press routes here (dismissOnBackPress defaults true) and is treated as an
        // explicit reject — the safe default. Tap-outside is disabled below so it can't
        // dismiss silently. A host-composition BackHandler doesn't work: AlertDialog
        // renders in its own window whose back dispatcher never reaches the host.
        // A haptic fires so an accidental back-press/gesture-mis-tap is at least perceptible
        // — without it the run would stop with no visible/audible feedback that the back
        // press was the cause.
        onDismissRequest = {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            respond(PermissionResponse.REJECT)
        },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A backlog indicator ("N of M") when more than one request is queued, so the
                // user knows the newest dialog isn't the only pending decision.
                if (total > 1) {
                    Text(
                        stringResource(R.string.permission_progress, position, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(permission.title ?: stringResource(R.string.permission_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The command / pattern can be long or multi-line; make it selectable and cap
                // its height with an inner scroll so it can't push the Allow/Reject buttons off
                // screen. Keeps the monospace styling.
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        // Tool-specific detail (command, file scope, etc.) extracted from
                        // Permission.metadata. The server may attach structured detail
                        // beyond the type+pattern that helps the user decide (e.g. the exact
                        // command a bash tool will run, or a file scope). Render it as a
                        // collapsible monospace block so a verbose metadata object doesn't
                        // dominate the dialog but remains reachable. Tolerant of any JSON
                        // shape (object/array/primitive) since the field is JsonElement.
                        permission.metadata?.let { PermissionMetadataBlock(it) }
                    }
                }
                Text(
                    stringResource(R.string.permission_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // After the reminder threshold, surface that the run is waiting so the user
                // understands their inaction is holding up the agent. A nudge, not a force.
                // In the final 10 seconds, switch to a per-second countdown so the imminent
                // auto-reject isn't a surprise.
                if (showSecondsWarning) {
                    val remainingSec = ((autoRejectMs - elapsedMs) / 1000L).coerceAtLeast(0L)
                    Text(
                        stringResource(R.string.permission_warning_seconds, remainingSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (showReminder && autoRejectMs > 0L) {
                    val remainingMin = ((autoRejectMs - elapsedMs) / 60_000L).coerceAtLeast(0L)
                    Text(
                        stringResource(R.string.permission_waiting, remainingMin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            // At very large font scales (200%+ accessibility text scale) the four full-width
            // buttons can overflow the dialog's max height, pushing Reject off-screen and
            // making it unreachable — the opposite of safe. Scroll + cap the column so the
            // full set stays answerable; vertically centering when it fits keeps the look
            // unchanged at normal scales.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
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
                        respond(PermissionResponse.SESSION)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.allow_for_session)) }
                // Explain the scope of "Always allow" so the user understands what they're
                // granting (this tool + pattern in every session) BEFORE choosing it. Placed
                // above the button (informed-consent ordering) so a top-down reader sees the
                // scope before reaching the action.
                Text(
                    stringResource(R.string.always_allow_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        respond(PermissionResponse.ALWAYS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.always_allow)) }
                // Reject is an OutlinedButton (not a low-prominence TextButton) in the
                // same column so the actions are visually balanced — the prior layout buried
                // Reject as a small dismiss-button, nudging users toward granting. Reject
                // uses the error color to signal its consequence.
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

/**
 * Collapsible monospace block rendering [Permission.metadata] — tool-specific detail
 * (e.g. the exact command a bash tool will run, a file scope) the server attaches
 * beyond type+pattern. Tolerant of any JSON shape: a bare string primitive is shown
 * as-is; an object/array is pretty-printed. Collapsed by default so a verbose metadata
 * object doesn't dominate the dialog, but reachable in one tap.
 */
@Composable
private fun PermissionMetadataBlock(metadata: kotlinx.serialization.json.JsonElement) {
    val pretty = remember(metadata) {
        if (metadata is kotlinx.serialization.json.JsonPrimitive && metadata.isString) {
            metadata.content
        } else {
            runCatching {
                soy.iko.opencode.data.network.OpencodeJson.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    metadata,
                )
            }.getOrDefault(metadata.toString())
        }
    }.takeIf { it.isNotBlank() } ?: return
    var expanded by remember(metadata) { mutableStateOf(false) }
    val detailsLabel = stringResource(R.string.permission_details)
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedState else collapsedState }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detailsLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            Text(
                pretty,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
