package soy.iko.opencode.ui.session

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.LocalRelativeTimeTick
import soy.iko.opencode.ui.components.rememberRelativeTime
import soy.iko.opencode.ui.components.rememberRelativeTimeTick
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
) {
    val vm: SessionListViewModel = viewModel(factory = vmFactory { SessionListViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val serverLabel by vm.serverLabel.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val switchingId by vm.switchingId.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    val anyRunActive by container.anyRunActive.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val isOnline by container.isOnline.collectAsStateWithLifecycle()
    val connectedId = activeConnection?.profile?.id
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val sessionDeletedLabel = stringResource(R.string.session_deleted)
    // One shared timer drives every relative-time label in the session list instead of
    // each card spinning up its own coroutine + lifecycle observer while scrolling.
    val timeTick = rememberRelativeTimeTick()
    var showServerMenu by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by rememberSaveable { mutableStateOf(false) }
    val pendingDelete = pendingDeleteId?.let { id -> state.sessions.firstOrNull { it.id == id } }
    val pendingRename = pendingRenameId?.let { id -> state.sessions.firstOrNull { it.id == id } }

    // Close the server dropdown on back press instead of navigating away.
    BackHandler(enabled = showServerMenu) { showServerMenu = false }

    LaunchedEffect(Unit) {
        vm.transientErrors.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    // Undo snackbar: when a session is marked for deferred deletion, offer Undo. If the
    // action is taken before the delay expires, the session is restored and the REST
    // delete never fires.
    LaunchedEffect(Unit) {
        vm.undoEvents.collect { sessionId ->
            val result = snackbar.showSnackbar(
                message = sessionDeletedLabel,
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                vm.undoDelete(sessionId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ServerSwitcherMenu(
                        serverLabel = serverLabel,
                        profiles = profiles,
                        connectedId = connectedId,
                        switchingId = switchingId,
                        expanded = showServerMenu,
                        onExpand = { showServerMenu = true },
                        onDismiss = { showServerMenu = false },
                        onSelect = { profile ->
                            showServerMenu = false
                            vm.switchServer(profile)
                        },
                        onAddServer = {
                            showServerMenu = false
                            onAddServer()
                        },
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_recent)) },
                                onClick = { vm.setSortMode(SessionSortMode.RECENT); showSortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_title)) },
                                onClick = { vm.setSortMode(SessionSortMode.TITLE); showSortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.files))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = {
                        // Confirm before disconnecting if an agent run is active in any
                        // session — disconnecting kills the SSE stream and the run.
                        if (anyRunActive) showDisconnectConfirm = true else onDisconnect()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.disconnect))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!creating) vm.createSession(onCreated = onOpenSession) },
                icon = {
                    if (creating) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                text = { Text(stringResource(R.string.new_session)) },
                // Disable while a creation is in flight so a double-tap can't spawn two
                // sessions. The container guard in createSession is the real protection;
                // this is the visual signal.
                expanded = !creating,
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(
                state = connectionState,
                modifier = Modifier.align(Alignment.TopCenter),
                isOnline = isOnline,
                onRetry = { vm.retryConnection() },
            )
            SessionListBody(
                state = state,
                unread = unread,
                refreshing = refreshing,
                haptics = haptics,
                onRefresh = vm::refresh,
                onQueryChange = vm::setQuery,
                onOpenSession = onOpenSession,
                onCreateSession = { vm.createSession(onCreated = onOpenSession) },
                onRename = { pendingRenameId = it },
                onDelete = { pendingDeleteId = it },
            )
        }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_text, session.displayTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    pendingDeleteId = null
                    vm.deleteSession(session)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pendingRename?.let { session ->
        var title by rememberSaveable(session.id) { mutableStateOf(session.title ?: "") }
        RenameSessionDialog(
            title = title,
            onTitleChange = { title = it },
            onConfirm = {
                val newName = title.trim()
                pendingRenameId = null
                if (newName.isNotEmpty() && newName != session.title) vm.renameSession(session, newName)
            },
            onDismiss = { pendingRenameId = null },
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.disconnect_active_title)) },
            text = { Text(stringResource(R.string.disconnect_active_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    onDisconnect()
                }) { Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SessionListBody(
    state: SessionListState,
    unread: Map<String, Int>,
    refreshing: Boolean,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    when {
        state.loading -> {
            val loadingLabel = stringResource(R.string.loading)
            CircularProgressIndicator(
                Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = loadingLabel },
            )
        }
        state.sessions.isEmpty() && state.error != null -> Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.retry))
            }
        }
        state.sessions.isEmpty() -> EmptySessions(
            onCreate = onCreateSession,
            modifier = Modifier.align(Alignment.Center),
        )
        else -> Column(modifier = Modifier.fillMaxSize()) {
            val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("session_search"),
                placeholder = { Text(stringResource(R.string.search_sessions)) },
                label = { Text(stringResource(R.string.search_sessions)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            )
            val sessions = remember(state.sessions, state.query, state.previews) {
                // Fast-path an empty query: filtered just returns sessions, so skip
                // the recompute (and the fresh list reference) when only previews
                // churned in the background. With a non-empty query the previews
                // map affects the match set, so recompute on any of the three.
                if (state.query.isBlank()) state.sessions else state.filtered
            }
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            stringResource(R.string.no_sessions_match, state.query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            // Swipe end-to-start reveals a delete affordance and opens the
                            // same confirmation dialog as the trash icon. We never commit
                            // the dismissal (always reset to Settled) so the card snaps back
                            // and the dialog guards against accidental data loss.
                            val swipeState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    // Haptic at the trigger so the swipe path matches the icon-path
                                    // delete, which vibrates on confirm. Returns false (snap back) so
                                    // the dialog guards the actual deletion.
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    onDelete(session.id)
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
                                SessionCard(
                                    session = session,
                                    preview = state.previews[session.id],
                                    unreadCount = unread[session.id] ?: 0,
                                    onClick = { onOpenSession(session.id) },
                                    onRename = { onRename(session.id) },
                                    onDelete = { onDelete(session.id) },
                                    modifier = Modifier.testTag("session_card"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_session)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.session_title_hint)) },
                label = { Text(stringResource(R.string.rename_session)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onConfirm() }),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = title.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    session: Session,
    preview: String?,
    unreadCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (unreadCount > 0) {
                            val unreadLabel = stringResource(R.string.unread_count, unreadCount)
                            // Count badge: shows the number of unread messages so the user
                            // can tell a single reply from a burst. Falls back to a dot for
                            // a count of 1 (the common "one reply" case) to avoid clutter.
                            if (unreadCount == 1) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .semantics { contentDescription = unreadLabel },
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                        .semantics { contentDescription = unreadLabel },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        unreadCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        Text(
                            session.displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val time = rememberRelativeTime(session.time?.updated ?: session.time?.created)
                    if (time.isNotEmpty()) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptySessions(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.no_sessions_yet), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.no_sessions_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.new_session), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Title + dropdown for quick-switching between saved servers. Highlights the active
 *  server with a check mark and primary color so the user can tell which one they're
 *  on at a glance. Includes an "Add server" item so adding a new server doesn't require
 *  navigating out to Settings → Manage servers — a long path for a common action. */
@Composable
private fun ServerSwitcherMenu(
    serverLabel: String,
    profiles: List<ServerProfile>,
    connectedId: String?,
    switchingId: String?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onAddServer: () -> Unit,
) {
    Column {
        Box {
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { onExpand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(serverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.switch_server))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismiss,
            ) {
                profiles.forEach { profile ->
                    val isActiveProfile = profile.id == connectedId
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.displayLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isActiveProfile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        profile.baseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isActiveProfile) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(R.string.connected),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (switchingId == profile.id) {
                                    val switchingLabel = stringResource(R.string.loading)
                                    CircularProgressIndicator(
                                        Modifier
                                            .size(18.dp)
                                            .semantics { contentDescription = switchingLabel },
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        },
                        onClick = { onSelect(profile) },
                    )
                }
                // Divider + Add server item so the user can add a server without leaving
                // the session list. The trailing icon reinforces the primary action.
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_server)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        onDismiss()
                        onAddServer()
                    },
                )
            }
        }
    }
}
