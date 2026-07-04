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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
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
    val loading by vm.loading.collectAsStateWithLifecycle()
    val connectingId by vm.connectingId.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val reconnecting by container.reconnecting.collectAsStateWithLifecycle()
    // The active server's real SSE state, so its card reflects a dropped/failed stream instead of
    // always reading "Connected" (which is misleading while reconnecting or after an auth failure).
    val activeSseState by (activeConnection?.events?.state
        ?: kotlinx.coroutines.flow.flowOf(soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connected))
        .collectAsStateWithLifecycle(initialValue = soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connected)
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.retry)
    val undoLabel = stringResource(R.string.undo)
    val serverRemovedLabel = stringResource(R.string.server_removed)
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    // Optional name/URL filter. Only surfaced once the user has enough profiles that scanning
    // by eye is slower than typing (a handful of servers is faster to just tap), matching the
    // threshold logic the file/session pickers use for their search fields.
    var serverQuery by rememberSaveable { mutableStateOf("") }
    val connectedId = activeConnection?.profile?.id
    // Profile being shown as a scannable QR (Share as QR overflow action), and a decoded QR
    // payload awaiting the user's confirm before it's saved (Import from image action).
    var pendingQrProfile by remember { mutableStateOf<ServerProfile?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // QR import (image pick → decode → confirm → save) is encapsulated in its own composable so the
    // branches don't inflate ServerListScreen's complexity. It renders the confirm dialog inline.
    val qrImportLauncher = ServerQrImportHandler(
        vm = vm,
        scope = scope,
        snackbar = snackbar,
        context = context,
    )

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
        // collectLatest (not collect): a serialized collect queues each snackbar behind
        // the previous one's full window, so under rapid deletes a later profile's VM
        // delete timer (started at emit time) fires before its snackbar is ever shown —
        // a dead Undo button. collectLatest instead cancels the current snackbar and
        // shows the newest immediately, keeping its Undo window aligned with its timer.
        // Tracking `pending` mirrors DiagnosticsScreen: when a newer delete supersedes the
        // prior snackbar mid-window, the prior profile's deferred delete (still scheduled
        // on the container's scope) would fire with no reachable Undo. Withdrawing it via
        // undoDelete cancels the timer and re-shows the row (no-op if it already fired), so
        // no profile is silently deleted without a chance to undo.
        var pending: String? = null
        vm.undoEvents.collectLatest { profileId ->
            pending?.let { vm.undoDelete(it) }
            pending = profileId
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
                    pending = null
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.servers_title)) },
                actions = {
                    // Sort menu (recent / name). Only earns its space once there are a few
                    // servers to order; with one or two it's faster to scan by eye.
                    if (profiles.size > 1) {
                        val sortMode by vm.sortMode.collectAsStateWithLifecycle()
                        // rememberSaveable so an open sort dropdown survives a rotation instead
                        // of closing and leaving the user to re-open it.
                        var sortMenu by rememberSaveable { mutableStateOf(false) }
                        // Close the sort dropdown on back press instead of navigating away,
                        // matching the session list's dropdown back-handling.
                        BackHandler(enabled = sortMenu) { sortMenu = false }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.sort_by))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_recent)) },
                                trailingIcon = {
                                    if (sortMode == ServerSortMode.RECENT) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = { vm.setSortMode(ServerSortMode.RECENT); sortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_name)) },
                                trailingIcon = {
                                    if (sortMode == ServerSortMode.NAME) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = { vm.setSortMode(ServerSortMode.NAME); sortMenu = false },
                            )
                        }
                    }
                    // Import a server by decoding a QR code from a saved image (e.g. a screenshot
                    // of another device's Share-as-QR dialog). Camera scanning would need extra
                    // permissions; an image picker reuses the system gallery and covers the
                    // cross-device transfer use case.
                    IconButton(onClick = { qrImportLauncher.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.import_from_image))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProfile) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_server))
            }
        },
    ) { padding ->
        // Consume the top inset on the Column so the reconnecting indicator (its first child)
        // renders just below the TopAppBar instead of at y=0 under it. The children below then
        // drop their own top padding (folding the bottom inset into the list's contentPadding)
        // so the list isn't double-padded at the top.
        Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (reconnecting && profiles.isNotEmpty()) {
                val reconnectingLabel = stringResource(R.string.reconnecting)
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = reconnectingLabel },
                )
            }
            if (loading) {
                // Initial DataStore read in flight: show a spinner rather than flashing the
                // "No servers yet" empty state, which would appear then vanish once profiles
                // arrive. Mirrors the session list's explicit loading state.
                val loadingLabel = stringResource(R.string.loading)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                        Spacer(Modifier.size(12.dp))
                        Text(loadingLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (profiles.isEmpty()) {
                EmptyServers(
                    onAdd = onAddProfile,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val filtered = remember(profiles, serverQuery) { filterServerProfiles(profiles, serverQuery) }
                PullToRefreshBox(
                    isRefreshing = connectingId != null,
                    onRefresh = { vm.refresh(onConnected) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 16.dp,
                            bottom = 96.dp + padding.calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Show the filter field only past a small threshold so a typical
                        // 1–2 server setup isn't cluttered with a search bar.
                        if (profiles.size >= NetworkConfig.serverListSearchThreshold) {
                            item(key = "__search") {
                                ServerListSearchField(
                                    query = serverQuery,
                                    onQueryChange = { serverQuery = it },
                                )
                            }
                        }
                        if (filtered.isEmpty()) {
                            item(key = "__no_match") {
                                EmptyState(
                                    icon = Icons.Filled.Search,
                                    title = stringResource(R.string.no_server_matches),
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                )
                            }
                        }
                        items(filtered, key = { it.id }) { profile ->
                            val isActive = profile.id == connectedId
                            if (isActive) {
                                // The active server can't be swipe-deleted (deleting it also
                                // disconnects, which deserves an explicit tap, not an
                                // accidental swipe). Render a plain card without the swipe
                                // affordance for the active row.
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(reducedMotionAnimateItem())
                                        .testTag("server_card")
                                        .clickable(enabled = connectingId == null, role = Role.Button) {
                                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            vm.connect(profile, onConnected)
                                        },
                                ) {
                                    ServerCardContent(
                                        profile = profile,
                                        isActive = true,
                                        isConnecting = false,
                                        activeState = activeSseState,
                                        onEdit = { onEditProfile(profile.id) },
                                        onDuplicate = { onDuplicateProfile(profile.id) },
                                        onPendingDelete = { pendingDeleteId = profile.id },
                                        onShareQr = { pendingQrProfile = profile },
                                    )
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
                                modifier = reducedMotionAnimateItem(),
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
                                    ServerCardContent(
                                        profile = profile,
                                        isActive = false,
                                        isConnecting = connectingId == profile.id,
                                        onEdit = { onEditProfile(profile.id) },
                                        onDuplicate = { onDuplicateProfile(profile.id) },
                                        onPendingDelete = { pendingDeleteId = profile.id },
                                        onShareQr = { pendingQrProfile = profile },
                                    )
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

    // Share-as-QR dialog for the selected profile. (The import confirm dialog is rendered by
    // ServerQrImportHandler above.)
    pendingQrProfile?.let { profile ->
        soy.iko.opencode.ui.components.QrShareDialog(profile = profile, onDismiss = { pendingQrProfile = null })
    }
}

/** Shared content for a server profile card — used by both the active-row (plain Card)
 *  and non-active-row (SwipeToDismissBox) branches, eliminating ~60 lines of duplication.
 *  Shows the label, URL, optional "Connected" label, last-used time, and a trailing area
 *  with a check icon (active), connecting spinner, or overflow menu. */
@Composable
private fun ServerCardContent(
    profile: ServerProfile,
    isActive: Boolean,
    isConnecting: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onPendingDelete: () -> Unit,
    onShareQr: () -> Unit = {},
    activeState: soy.iko.opencode.data.network.EventStreamClient.ConnectionState? = null,
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
            if (isActive) {
                // Reflect the live SSE state instead of a static "Connected": a dropped or failed
                // stream reads as the real status so a glance at the card isn't misleading.
                val (statusText, statusColor) = when (activeState) {
                    soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Failed,
                    soy.iko.opencode.data.network.EventStreamClient.ConnectionState.AuthFailed ->
                        stringResource(R.string.connection_failed_short) to MaterialTheme.colorScheme.error
                    soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connecting,
                    soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Disconnected ->
                        stringResource(R.string.reconnecting) to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> stringResource(R.string.connected) to MaterialTheme.colorScheme.primary
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            LastUsedText(profile.lastUsed)
        }
        if (isActive) {
            // Swap the green check for a spinner while reconnecting, or an error glyph on a hard
            // failure, so the trailing icon matches the status text on the left.
            when (activeState) {
                soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connecting,
                soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Disconnected -> {
                    val reconnectingLabel = stringResource(R.string.reconnecting)
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp).semantics { contentDescription = reconnectingLabel },
                        strokeWidth = 2.dp,
                    )
                }
                soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Failed,
                soy.iko.opencode.data.network.EventStreamClient.ConnectionState.AuthFailed -> {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = stringResource(R.string.connection_failed_short),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                }
                else -> {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.connected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (isConnecting) {
            val connectingLabel = stringResource(R.string.connecting)
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).semantics { contentDescription = connectingLabel },
            )
        } else {
            var showRowMenu by rememberSaveable(profile.id) { mutableStateOf(false) }
            // Close this row's overflow on back press instead of navigating away, matching
            // the sort menu BackHandler above.
            BackHandler(enabled = showRowMenu) { showRowMenu = false }
            val editLabel = stringResource(R.string.edit)
            val duplicateLabel = stringResource(R.string.duplicate_server)
            val removeLabel = stringResource(R.string.remove)
            // Tie the overflow button's a11y label to this server so a TalkBack user scrolling
            // a long list can tell the rows apart (a bare "More" on every row is ambiguous).
            val moreLabel = stringResource(R.string.more_options_for, profile.displayLabel)
            Box {
                IconButton(onClick = { showRowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = moreLabel)
                }
                DropdownMenu(
                    expanded = showRowMenu,
                    onDismissRequest = { showRowMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(editLabel) },
                        onClick = { showRowMenu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(duplicateLabel) },
                        onClick = { showRowMenu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_qr)) },
                        onClick = { showRowMenu = false; onShareQr() },
                    )
                    DropdownMenuItem(
                        text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                        onClick = { showRowMenu = false; onPendingDelete() },
                    )
                }
            }
        }
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
        EmptyState(
            icon = Icons.Filled.Dns,
            title = stringResource(R.string.no_servers_yet),
            description = stringResource(R.string.no_servers_hint),
            actionIcon = Icons.Filled.Add,
            actionLabel = stringResource(R.string.add_server),
            onAction = onAdd,
        )
    }
}

/** Owns the QR-import flow: an image-picker launcher plus the confirm dialog for a decoded
 *  server payload. Extracted from [ServerListScreen] so its branches (null checks, decode
 *  result, confirm) don't push the parent composable over the cyclomatic-complexity threshold.
 *  Renders the confirm dialog inline. */
@Composable
private fun ServerQrImportHandler(
    vm: ServerListViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    context: android.content.Context,
): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    var pendingImport by remember { mutableStateOf<soy.iko.opencode.ui.components.ServerProfileQr?>(null) }
    val qrAddedMsg = stringResource(R.string.qr_added)
    val qrImportFailedMsg = stringResource(R.string.qr_import_failed)
    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val qr = soy.iko.opencode.ui.components.decodeServerQr(context, uri)
            if (qr != null) pendingImport = qr else snackbar.showSnackbar(qrImportFailedMsg)
        }
    }
    pendingImport?.let { qr ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.qr_import_title)) },
            text = {
                Column {
                    Text(qr.baseUrl, style = MaterialTheme.typography.bodyLarge)
                    if (qr.label.isNotBlank()) {
                        Text(
                            qr.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (qr.password != null) {
                        Text(
                            stringResource(R.string.qr_includes_password),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        vm.importFromQr(qr)
                        snackbar.showSnackbar(qrAddedMsg)
                    }
                }) { Text(stringResource(R.string.add_server)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    return launcher
}

/** Filter server profiles by label/base URL. Extracted from [ServerListScreen] to keep its
 *  cyclomatic complexity under the detekt threshold. Empty query returns all profiles. */
private fun filterServerProfiles(profiles: List<ServerProfile>, query: String): List<ServerProfile> {
    val q = query.trim()
    if (q.isEmpty()) return profiles
    return profiles.filter {
        it.label.contains(q, ignoreCase = true) || it.baseUrl.contains(q, ignoreCase = true)
    }
}

/** The server-list filter field with a clear button. Extracted from [ServerListScreen] to keep
 *  its cyclomatic complexity under the detekt threshold. */
@Composable
private fun ServerListSearchField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.search_servers)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        // Match the session/file search fields: ImeAction.Search dismisses the keyboard on
        // submit, so a user filtering servers isn't left with the IME covering the list.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}
