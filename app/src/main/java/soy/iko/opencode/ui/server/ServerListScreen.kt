package soy.iko.opencode.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.rememberRelativeTime
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    container: AppContainer,
    onConnected: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
) {
    val vm: ServerListViewModel = viewModel(factory = vmFactory { ServerListViewModel(container) })
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val connectingId by vm.connectingId.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val reconnecting by container.reconnecting.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.retry)
    val undoLabel = stringResource(R.string.undo)
    val serverRemovedLabel = stringResource(R.string.server_removed)
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val connectedId = activeConnection?.profile?.id

    LaunchedEffect(Unit) {
        vm.errorEvents.collect { event ->
            val result = if (event.profile != null) {
                snackbar.showSnackbar(message = event.message, actionLabel = retryLabel)
            } else {
                snackbar.showSnackbar(event.message)
            }
            if (result == SnackbarResult.ActionPerformed && event.profile != null) {
                vm.connect(event.profile, onConnected)
            }
        }
    }

    // Undo snackbar: when a server is marked for deferred deletion, offer Undo. If the
    // action is taken before the delay expires, the profile is kept and the delete
    // never fires — mirrors the session list's undo UX for consistency. Indefinite + a
    // matching timed dismiss keeps the Undo button visible for exactly the
    // undoServerDeleteDelayMs window (5s) and no longer: a fixed SnackbarDuration.Long
    // (~10s) outlasted the window, leaving the button on screen but dead for its
    // second half. Mirrors DiagnosticsScreen's undo pattern.
    LaunchedEffect(Unit) {
        vm.undoEvents.collect { profileId ->
            coroutineScope {
                val dismisser = launch {
                    delay(NetworkConfig.undoServerDeleteDelayMs)
                    snackbar.currentSnackbarData?.dismiss()
                }
                val result = snackbar.showSnackbar(
                    message = serverRemovedLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Indefinite,
                )
                dismisser.cancel()
                if (result == SnackbarResult.ActionPerformed) {
                    vm.undoDelete(profileId)
                }
            }
        }
    }

    // On cold start the container auto-reconnects to the last server; once it lands,
    // skip this screen and go straight to the session list. Consumed once so a later
    // manual visit to this screen doesn't bounce the user back out.
    LaunchedEffect(Unit) {
        container.autoConnectDone.collect { succeeded ->
            if (succeeded && container.consumeAutoConnect()) onConnected()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.servers_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProfile) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_server))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (reconnecting && profiles.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (profiles.isEmpty()) {
                EmptyServers(
                    onAdd = onAddProfile,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = connectingId != null,
                    onRefresh = { vm.refresh(onConnected) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            val isActive = profile.id == connectedId
                            if (isActive) {
                                // The active server can't be swipe-deleted (deleting it also
                                // disconnects, which deserves an explicit tap, not an
                                // accidental swipe). Render a plain card without the swipe
                                // affordance for the active row.
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .testTag("server_card")
                                        .clickable(enabled = connectingId == null, role = Role.Button) {
                                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            vm.connect(profile, onConnected)
                                        },
                                ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            profile.displayLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            profile.baseUrl + if (profile.hasAuth) stringResource(R.string.server_auth_short) else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            stringResource(R.string.connected),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        LastUsedText(profile.lastUsed)
                                    }
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(R.string.connected),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    // Keep edit available on the active server so credentials can be
                                    // fixed without disconnecting first. Delete is also offered: the
                                    // confirmation dialog warns that removing the active server also
                                    // disconnects (remove_server_active_text), and the ViewModel
                                    // disconnects immediately on confirm. Swipe remains disabled for
                                    // the active row so an accidental swipe can't disconnect.
                                    IconButton(onClick = { onEditProfile(profile.id) }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                                    }
                                    IconButton(onClick = { onDuplicateProfile(profile.id) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.duplicate_server))
                                    }
                                    IconButton(onClick = { pendingDeleteId = profile.id }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                                    }
                                }
                            }
                        } else {
                            // Swipe end-to-start reveals a delete affordance and opens the
                            // same confirmation dialog as the trash icon (matching the
                            // session list). confirmValueChange always returns false (snap
                            // back) so the dialog guards the actual deletion.
                            val swipeState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    pendingDeleteId = profile.id
                                    false
                                },
                            )
                            SwipeToDismissBox(
                                state = swipeState,
                                enableDismissFromStartToEnd = false,
                                modifier = Modifier.animateItem(),
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                },
                            ) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("server_card")
                                        .clickable(enabled = connectingId == null, role = Role.Button) {
                                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            vm.connect(profile, onConnected)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                profile.displayLabel,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                profile.baseUrl + if (profile.hasAuth) stringResource(R.string.server_auth_short) else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            LastUsedText(profile.lastUsed)
                                        }
                                        if (connectingId == profile.id) {
                                            val connectingLabel = stringResource(R.string.connecting)
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp).semantics { contentDescription = connectingLabel },
                                            )
                                        } else {
                                            IconButton(onClick = { onEditProfile(profile.id) }) {
                                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                                            }
                                            IconButton(onClick = { onDuplicateProfile(profile.id) }) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.duplicate_server))
                                            }
                                            IconButton(onClick = { pendingDeleteId = profile.id }) {
                                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    profiles.find { it.id == pendingDeleteId }?.let { profile ->
        val isActiveProfile = profile.id == connectedId
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.remove_server_title)) },
            text = {
                Text(
                    if (isActiveProfile) stringResource(R.string.remove_server_active_text, profile.displayLabel)
                    else stringResource(R.string.remove_server_text, profile.displayLabel),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    pendingDeleteId = null
                    // delete() defers the actual removal so the undo snackbar can cancel
                    // it; the row disappears on the next profiles emission.
                    vm.delete(profile)
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** "Last used X ago" or "Not used yet" for a server profile, so the user can spot stale
 *  configs at a glance. Mirrors the relative-time formatting used elsewhere. */
@Composable
private fun LastUsedText(lastUsed: Long) {
    if (lastUsed <= 0) {
        Text(
            stringResource(R.string.last_used_never),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val relative = rememberRelativeTime(lastUsed)
        if (relative.isNotEmpty()) {
            Text(
                stringResource(R.string.last_used, relative),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.no_servers_yet), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.no_servers_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(20.dp))
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.add_server), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
