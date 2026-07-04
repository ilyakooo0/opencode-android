package soy.iko.opencode.ui.session

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.repo.RecentSession
import soy.iko.opencode.data.repo.RecentSessionsStore
import soy.iko.opencode.data.repo.SwipeAction
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.platform.AppShortcuts
import soy.iko.opencode.platform.SessionsWidgetProvider
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.LoadingSize
import soy.iko.opencode.ui.components.LoadingSpinner
import soy.iko.opencode.ui.components.LocalRelativeTimeTick
import soy.iko.opencode.ui.components.LocalReducedMotion
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
import soy.iko.opencode.ui.components.RelativeTimeText
import soy.iko.opencode.ui.components.rememberRelativeTimeTick
import soy.iko.opencode.ui.vmFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpenSearch: () -> Unit = {},
    // Incremented by a host (the two-pane empty-detail pane) to open the new-session
    // directory picker, so that pane shares this screen's dialog instead of bypassing it.
    externalNewSessionTrigger: Int = 0,
    selectedSessionId: String? = null,
) {
    val vm: SessionListViewModel = viewModel(factory = vmFactory { SessionListViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val serverLabel by vm.serverLabel.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val switchingId by vm.switchingId.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val mutedSessions by container.mutedSessions.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    val directoryOptions by vm.directoryOptions.collectAsStateWithLifecycle()
    val anyRunActive by container.anyRunActive.collectAsStateWithLifecycle()
    val runningSessionIds by container.runningSessionIds.collectAsStateWithLifecycle()
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val isOnline by container.isOnline.collectAsStateWithLifecycle()
    // Outbox sending state: shows a "sending queued…" indicator in the top bar when the
    // app is flushing queued messages from the offline outbox, so the user knows their
    // messages are being delivered (not just sitting in the outbox silently).
    val outboxSending by container.outboxSending.collectAsStateWithLifecycle()
    // Swipe-action customization: read the user's configured left/right swipe actions.
    // Defaults match the prior hardcoded behavior (left=delete, right=archive).
    val swipeLeftActionStr by container.settingsStore.swipeLeftAction.collectAsStateWithLifecycle(initialValue = "DELETE")
    val swipeRightActionStr by container.settingsStore.swipeRightAction.collectAsStateWithLifecycle(initialValue = "ARCHIVE")
    // Drafts map (session id -> draft text) so each list row can show a "Draft" chip
    // when the user has unsaved input for that conversation. DraftStore.drafts is an
    // observable StateFlow that already maintains the full map; we only read it here.
    val drafts by container.draftStore.drafts.collectAsStateWithLifecycle()
    val connectedId = activeConnection?.profile?.id
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val retryLabel = stringResource(R.string.retry)
    val sessionDeletedLabel = stringResource(R.string.session_deleted)
    // One shared timer drives every relative-time label in the session list instead of
    // each card spinning up its own coroutine + lifecycle observer while scrolling.
    val timeTick = rememberRelativeTimeTick()
    var showServerMenu by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var showMainMenu by rememberSaveable { mutableStateOf(false) }
    var showNewSessionDialog by rememberSaveable { mutableStateOf(false) }
    // Open the new-session directory picker, kicking off a fetch of the server's known
    // directories so they're ready by the time the user looks at the list.
    val openNewSession = {
        if (!creating) { vm.loadDirectoryOptions(); showNewSessionDialog = true }
    }
    // A host bumps externalNewSessionTrigger to open this exact directory-picker dialog
    // (e.g. the two-pane detail pane's "New session" button), reusing one dialog path.
    LaunchedEffect(externalNewSessionTrigger) {
        if (externalNewSessionTrigger > 0) openNewSession()
    }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by rememberSaveable { mutableStateOf(false) }
    // Multi-select: a set of selected session ids. Non-empty ⇒ selection mode (contextual top
    // bar, tap-to-toggle instead of tap-to-open, FAB hidden). Long-press a card to enter it.
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val inSelection = selectedIds.isNotEmpty()
    var showBulkDelete by rememberSaveable { mutableStateOf(false) }
    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val pendingDelete = pendingDeleteId?.let { id -> state.sessions.firstOrNull { it.id == id } }
    val pendingRename = pendingRenameId?.let { id -> state.sessions.firstOrNull { it.id == id } }

    // Feed the home-screen widget, launcher shortcuts, and "Resume last" from the loaded
    // session list — the best source of session titles. Cheap, idempotent, and off the main
    // thread (the store write dispatches to IO); the widget refresh no-ops when none is placed.
    val platformContext = LocalContext.current.applicationContext
    SyncShortcutsAndWidget(platformContext, state)

    // Reflect total unread sessions as a launcher-icon badge (silent notification on a
    // badge-enabled channel). Skips itself when the session list isn't loading a server yet.
    val totalUnread = remember(unread) { unread.values.sum() }
    LaunchedEffect(totalUnread) {
        soy.iko.opencode.notification.SessionNotifications.updateUnreadBadge(platformContext, totalUnread)
    }

    // Close the open dropdown on back press instead of navigating away.
    BackHandler(enabled = showServerMenu) { showServerMenu = false }
    BackHandler(enabled = showSortMenu) { showSortMenu = false }
    BackHandler(enabled = showMainMenu) { showMainMenu = false }

    // Dismiss the server switcher once an in-flight switch resolves (switchingId returns to
    // null), so its per-row spinner stays visible for the whole switch instead of the menu
    // closing the instant a profile is tapped.
    LaunchedEffect(switchingId) {
        if (switchingId == null && showServerMenu) showServerMenu = false
    }

    LaunchedEffect(Unit) {
        vm.transientErrors.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        vm.transientInfo.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    // Retryable failures (create/rename): offer a Retry action so a transient network error
    // is recoverable in one tap instead of forcing the user back through the dialog/FAB.
    LaunchedEffect(Unit) {
        vm.retryableErrors.collect { err ->
            val result = snackbar.showSnackbar(
                message = err.message,
                actionLabel = retryLabel,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) err.retry()
        }
    }

    // Undo snackbar: when a session is marked for deferred deletion, offer Undo. If the
    // action is taken before the delay expires, the session is restored and the REST
    // delete never fires. Indefinite + a matching timed dismiss keeps the Undo button
    // visible for exactly the undoDeleteDelayMs window (5s) and no longer: a fixed
    // SnackbarDuration.Long (~10s) outlasted the window, leaving the button on screen
    // but dead for its second half. Mirrors DiagnosticsScreen's undo pattern.
    LaunchedEffect(Unit) {
        // collectLatest (not collect): a serialized collect queues each snackbar behind
        // the previous one's full window, so under rapid deletes a later session's VM
        // delete timer (started at emit time) fires before its snackbar is ever shown —
        // a dead Undo button. collectLatest instead cancels the current snackbar and
        // shows the newest immediately, keeping its Undo window aligned with its timer.
        // Tracking `pending` mirrors DiagnosticsScreen: when a newer delete supersedes the
        // prior snackbar mid-window, the prior session's deferred REST delete (still
        // scheduled on the container's scope) would fire with no reachable Undo. Withdrawing
        // it via undoDelete cancels the timer and restores the row (no-op if it already
        // fired), so no session is silently deleted without a chance to undo.
        var pending: String? = null
        merge(vm.undoEvents, container.externalSessionUndoEvents).collectLatest { sessionId ->
            pending?.let { vm.undoDelete(it) }
            pending = sessionId
            coroutineScope {
                val dismisser = launch {
                    delay(NetworkConfig.undoDeleteDelayMs)
                    snackbar.currentSnackbarData?.dismiss()
                }
                val result = snackbar.showSnackbar(
                    message = sessionDeletedLabel,
                    actionLabel = undoLabel,
                    duration = androidx.compose.material3.SnackbarDuration.Indefinite,
                )
                dismisser.cancel()
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    vm.undoDelete(sessionId)
                    pending = null
                }
            }
        }
    }

    SessionActionUndoEffect(vm, snackbar)
    BulkSessionActionUndoEffect(vm, snackbar)

    Scaffold(
        topBar = {
            // Contextual action bar while sessions are selected (long-press to enter): count as
            // the title, with select-all and bulk archive / mark-read / delete actions. Mirrors
            // the standard Android selection-mode pattern; exits (clearing the selection) on the
            // back/close action or the system back gesture (BackHandler below).
            if (inSelection) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        val visibleSessions = state.filtered
                        IconButton(onClick = {
                            selectedIds = visibleSessions.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = {
                            vm.bulkMarkRead(selectedIds)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.MarkChatRead, contentDescription = stringResource(R.string.bulk_mark_read))
                        }
                        IconButton(onClick = {
                            vm.bulkArchive(selectedIds, archive = true)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.bulk_archive))
                        }
                        IconButton(onClick = { showBulkDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.bulk_delete))
                        }
                    },
                )
            } else {
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
                        // The active-profile no-op and the active-run confirmation are handled
                        // inside ServerSwitcherMenu, so onSelect only fires for a genuine switch.
                        onSelect = { profile -> vm.switchServer(profile) },
                        onAddServer = {
                            showServerMenu = false
                            onAddServer()
                        },
                        anyRunActive = anyRunActive,
                    )
                },
                actions = {
                    // Outbox sending indicator: a small text + spinner so the user knows
                    // queued messages are being flushed (not sitting silently in the outbox).
                    if (outboxSending) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                stringResource(R.string.sending_queued),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_all))
                    }
                    SortMenu(
                        sortMode = state.sortMode,
                        sortDescending = state.sortDescending,
                        showArchived = state.showArchived,
                        hiddenArchivedCount = state.hiddenArchivedCount,
                        expanded = showSortMenu,
                        onExpand = { showSortMenu = true },
                        onDismiss = { showSortMenu = false },
                        onSetSortMode = { vm.setSortMode(it) },
                        onToggleDirection = { vm.toggleSortDirection() },
                        onSetShowArchived = { vm.setShowArchived(it) },
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    // Less-frequent destinations (files, manual refresh, disconnect) live in an
                    // overflow so the bar stays uncluttered — pull-to-refresh and live SSE make
                    // the standalone Refresh icon largely redundant.
                    IconButton(onClick = { showMainMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = showMainMenu, onDismissRequest = { showMainMenu = false }) {
                        // Only offer bulk mark-read when there's actually something unread.
                        if (unread.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_all_read)) },
                                leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                                onClick = { showMainMenu = false; container.clearAllUnread() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files)) },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = { showMainMenu = false; onOpenFiles() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.refresh)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = { showMainMenu = false; vm.refresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.disconnect)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            },
                            onClick = {
                                showMainMenu = false
                                // Confirm before disconnecting if an agent run is active in any
                                // session — disconnecting kills the SSE stream and the run.
                                if (anyRunActive) showDisconnectConfirm = true else onDisconnect()
                            },
                        )
                    }
                },
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!inSelection) {
                ExtendedFloatingActionButton(
                    onClick = openNewSession,
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
            }
        },
    ) { padding ->
        // Exit selection mode on the system back gesture instead of navigating away.
        androidx.activity.compose.BackHandler(enabled = inSelection) { selectedIds = emptySet() }
        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        // Banner sits in the column flow (not overlaid via TopCenter) so it pushes the list
        // down when it appears instead of covering the search field and top cards. Its own
        // AnimatedVisibility collapses it to zero height when the stream is healthy, so the
        // body fills the space normally.
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(padding)) {
            ConnectionBanner(
                state = connectionState,
                isOnline = isOnline,
                onRetry = { vm.retryConnection() },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                SessionListBody(
                    state = state,
                    unread = unread,
                    mutedSessions = mutedSessions,
                    refreshing = refreshing,
                    haptics = haptics,
                    selectedSessionId = selectedSessionId,
                    connected = activeConnection != null,
                    onRefresh = vm::refresh,
                    onReconnect = vm::retryConnection,
                    onQueryChange = vm::setQuery,
                    onOpenSession = onOpenSession,
                    onCreateSession = openNewSession,
                    onRename = { pendingRenameId = it },
                    onDelete = { pendingDeleteId = it },
                    onPin = { vm.togglePin(it) },
                    onArchive = { vm.toggleArchive(it) },
                    onMarkUnread = { vm.markUnread(it) },
                    onToggleMute = { container.toggleSessionMute(it) },
                    onFilterByDirectory = vm::setDirectoryFilter,
                    onClearDirectoryFilter = { vm.setDirectoryFilter(null) },
                    onShowArchived = { vm.setShowArchived(true) },
                    inSelection = inSelection,
                    selectedIds = selectedIds,
                    onToggleSelect = ::toggleSelected,
                    runningSessionIds = runningSessionIds,
                    drafts = drafts,
                    swipeLeftAction = swipeLeftActionStr,
                    swipeRightAction = swipeRightActionStr,
                )
            }
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
        DisconnectActiveConfirmDialog(
            onConfirm = onDisconnect,
            onDismiss = { showDisconnectConfirm = false },
        )
    }

    if (showBulkDelete) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text(stringResource(R.string.bulk_delete_title)) },
            // Bulk delete is NOT undoable (unlike single delete which offers Undo), so the
            // warning must be explicit — matching the single-delete dialog's irreversibility text
            // rather than the bare "%d selected" count.
            text = { Text(stringResource(R.string.bulk_delete_text, count)) },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDelete = false
                    vm.bulkDelete(selectedIds)
                    selectedIds = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showNewSessionDialog) {
        // Distinct session directories, most-recent first (state.sessions is sorted), so the
        // picker can offer directories the user has recently worked in even if the server's
        // project list is unavailable.
        val sessionDirectories = remember(state.sessions) {
            state.sessions.mapNotNull { it.directory?.takeIf { d -> d.isNotBlank() } }.distinct()
        }
        NewSessionDialog(
            options = directoryOptions,
            sessionDirectories = sessionDirectories,
            lastChosenDirectory = vm.lastChosenDirectory,
            creating = creating,
            onCreate = { dir, title ->
                vm.createSession(directory = dir, title = title) { id ->
                    showNewSessionDialog = false
                    onOpenSession(id)
                }
            },
            onDismiss = { showNewSessionDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SessionListBody(
    state: SessionListState,
    unread: Map<String, Int>,
    mutedSessions: Set<String>,
    refreshing: Boolean,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    selectedSessionId: String?,
    connected: Boolean,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (Session) -> Unit,
    onArchive: (Session) -> Unit,
    onMarkUnread: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onFilterByDirectory: (String) -> Unit,
    onClearDirectoryFilter: () -> Unit,
    onShowArchived: () -> Unit,
    inSelection: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    runningSessionIds: Set<String> = emptySet(),
    drafts: Map<String, String> = emptyMap(),
    swipeLeftAction: String = "DELETE",
    swipeRightAction: String = "ARCHIVE",
) {
    // Crossfade between content states (loading/error/empty/list) so the transition
    // reads as a smooth fade instead of an instant snap. Matches the file viewer's
    // AnimatedContent pattern. Reduced motion is honored by Crossfade's default spec.
    val stateKey = when {
        state.loading -> "loading"
        state.sessions.isEmpty() && state.error != null -> "error"
        state.sessions.isEmpty() -> "empty"
        else -> "list"
    }
    Crossfade(
        targetState = stateKey,
        animationSpec = tween(NetworkConfig.motionFadeDurationMs.toInt()),
        label = "session_list_state",
    ) { key ->
        when (key) {
        "loading" -> {
            // Skeleton loader: pulse-animated placeholder rows that mirror the eventual
            // session-card layout (avatar circle + title bar + subtitle bar) so the user
            // sees a structured preview of the list instead of a bare spinner. Matches
            // the chat screen's skeleton pattern. Respects reduced-motion (static 0.5
            // alpha instead of pulsing). The whole column is one semantics node so
            // TalkBack announces "Loading sessions…" once.
            val loadingLabel = stringResource(R.string.loading_sessions)
            val reducedMotion = LocalReducedMotion.current
            val transition = rememberInfiniteTransition(label = "session_skeleton")
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.7f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "session_skeleton_alpha",
            )
            val skeletonAlpha = if (reducedMotion) 0.5f else pulse
            val skeletonColor = MaterialTheme.colorScheme.surfaceVariant
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .semantics { contentDescription = loadingLabel },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(6) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Avatar circle placeholder.
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(skeletonColor)
                                .alpha(skeletonAlpha),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Title bar placeholder.
                            Box(
                                Modifier
                                    .fillMaxWidth(0.7f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(skeletonColor)
                                    .alpha(skeletonAlpha),
                            )
                            // Subtitle bar placeholder (shorter, for directory/time).
                            Box(
                                Modifier
                                    .fillMaxWidth(0.45f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(skeletonColor)
                                    .alpha(skeletonAlpha),
                            )
                        }
                    }
                }
            }
        }
        "error" -> Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = if (connected) onRefresh else onReconnect) {
                Text(stringResource(R.string.retry))
            }
        }
        "empty" -> EmptyState(
            icon = Icons.Filled.ChatBubbleOutline,
            title = stringResource(R.string.no_sessions_yet),
            description = stringResource(R.string.no_sessions_hint),
            actionIcon = Icons.Filled.Add,
            actionLabel = stringResource(R.string.new_session),
            onAction = onCreateSession,
            modifier = Modifier.align(Alignment.Center),
        )
        else -> Column(modifier = Modifier.fillMaxSize()) {
            val keyboardController = LocalSoftwareKeyboardController.current
            // Match the sibling screens' convention: hide the search field until the list is
            // long enough that typing beats scanning by eye (NetworkConfig.sessionListSearchThreshold).
            val showSearch = state.sessions.size >= NetworkConfig.sessionListSearchThreshold
            if (showSearch) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("session_search"),
                    label = { Text(stringResource(R.string.search_sessions)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                )
            }
            // List-level indicator that a directory filter is scoping the list. Tapping the
            // trailing X clears it (equivalent to tapping the active card chip again). The
            // Row scrolls horizontally so a long path doesn't clip the clear action.
            state.directoryFilter?.let { dir ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = true,
                        onClick = onClearDirectoryFilter,
                        label = { Text(stringResource(R.string.directory_filter_chip, dir)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_filter),
                            )
                        },
                    )
                }
            }
            val sessions = remember(
                state.sessions, state.query, state.previews,
                state.pinnedIds, state.archivedIds, state.showArchived,
            ) { state.filtered }
            if (sessions.isEmpty()) {
                // Distinguish why the list is empty so the offered recovery action matches
                // the active filter: a query clears the search, a directory filter clears
                // the project scope, and an all-archived list reveals archived sessions.
                val message = when {
                    state.query.isNotEmpty() ->
                        stringResource(R.string.no_sessions_match, state.query)
                    state.directoryFilter != null ->
                        stringResource(R.string.no_sessions_in_directory)
                    else ->
                        stringResource(R.string.no_sessions_archived_hidden)
                }
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
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        when {
                            state.query.isNotEmpty() -> {
                                Spacer(Modifier.size(8.dp))
                                TextButton(onClick = { onQueryChange("") }) {
                                    Text(stringResource(R.string.clear_search))
                                }
                            }
                            state.directoryFilter != null -> {
                                Spacer(Modifier.size(8.dp))
                                TextButton(onClick = onClearDirectoryFilter) {
                                    Text(stringResource(R.string.clear_filter))
                                }
                            }
                            state.hiddenArchivedCount > 0 -> {
                                Spacer(Modifier.size(8.dp))
                                TextButton(onClick = onShowArchived) {
                                    Text(stringResource(R.string.show_archived))
                                }
                            }
                        }
                    }
                }
            } else {
                // Order child (sub-)sessions immediately under their parent, tracking depth
                // so the UI can indent them into a tree.
                val nodes = remember(sessions) { buildSessionTree(sessions) }
                // Windowed render: compose only the first `renderCap` rows, growing as the
                // user scrolls near the bottom. Bounds the LazyColumn's key+arrange work on a
                // huge list and keeps the tree-build + filter cost proportional to what's seen.
                var renderCap by remember {
                    mutableStateOf(NetworkConfig.sessionListInitialPage)
                }
                val visibleNodes = remember(nodes, renderCap) {
                    if (nodes.size <= renderCap) nodes else nodes.take(renderCap)
                }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    // Grow the window when the user scrolls within a page of the cap so the
                    // "infinite scroll" feels seamless (no spinner — the next batch is already
                    // in the list by the time they reach the bottom).
                    androidx.compose.runtime.LaunchedEffect(listState, nodes.size) {
                        androidx.compose.runtime.snapshotFlow {
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= renderCap - NetworkConfig.sessionListPageStep / 2
                        }.distinctUntilChanged().collect { nearEnd ->
                            if (nearEnd && renderCap < nodes.size) {
                                renderCap = (renderCap + NetworkConfig.sessionListPageStep).coerceAtMost(nodes.size)
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visibleNodes, key = { it.session.id }) { node ->
                            val session = node.session
                            // Swipe end-to-start reveals a delete affordance and opens the
                            // same confirmation dialog as the trash icon. We never commit
                            // the dismissal (always reset to Settled) so the card snaps back
                            // and the dialog guards against accidental data loss.
                            val isArchivedForSwipe = session.id in state.archivedIds
                            // Parse the configured swipe actions, falling back to the
                            // defaults on an unknown value.
                            val swipeLeft = remember(swipeLeftAction) {
                                runCatching { SwipeAction.valueOf(swipeLeftAction) }.getOrDefault(SwipeAction.DELETE)
                            }
                            val swipeRight = remember(swipeRightAction) {
                                runCatching { SwipeAction.valueOf(swipeRightAction) }.getOrDefault(SwipeAction.ARCHIVE)
                            }
                            // Apply a swipe action to the session. NONE is a no-op.
                            val applySwipe: (SwipeAction) -> Unit = { action ->
                                when (action) {
                                    SwipeAction.DELETE -> onDelete(session.id)
                                    SwipeAction.ARCHIVE -> onArchive(session)
                                    SwipeAction.MARK_READ -> onMarkUnread(session.id)
                                    SwipeAction.NONE -> {}
                                }
                            }
                            val swipeState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    when (value) {
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            applySwipe(swipeLeft)
                                            false
                                        }
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            applySwipe(swipeRight)
                                            false
                                        }
                                        SwipeToDismissBoxValue.Settled -> false
                                    }
                                },
                            )
                            // Per-item callbacks memoized on the session id so SessionCard
                            // stays skippable: the list emits a fresh SessionListState on
                            // every background preview/unread update, and freshly-allocated
                            // lambdas would otherwise force every visible card to recompose
                            // on each of those emissions even when its own data is unchanged.
                            val onCardClick = remember(session.id) { { onOpenSession(session.id) } }
                            val onCardRename = remember(session.id) { { onRename(session.id) } }
                            val onCardDelete = remember(session.id) { { onDelete(session.id) } }
                            val onCardPin = remember(session.id) { { onPin(session) } }
                            val onCardArchive = remember(session.id) { { onArchive(session) } }
                            val onCardMarkUnread = remember(session.id) { { onMarkUnread(session.id) } }
                            val onCardToggleMute = remember(session.id) { { onToggleMute(session.id) } }
                            val isMuted = session.id in mutedSessions
                            val isPinned = session.id in state.pinnedIds
                            val isArchived = session.id in state.archivedIds
                            SwipeToDismissBox(
                                state = swipeState,
                                // Swipe end-to-start deletes (dialog-guarded); start-to-end
                                // archives/unarchives (instantly undoable via snackbar).
                                // Disabled entirely during selection mode so a swipe can't
                                // trigger delete/archive while the user is multi-selecting —
                                // the gestures would conflict and an errant swipe could drop
                                // a session that was merely being selected.
                                enableDismissFromStartToEnd = !inSelection,
                                enableDismissFromEndToStart = !inSelection,
                                // Indent sub-sessions under their parent (capped so deep
                                // nesting doesn't squeeze the card off-screen).
                                modifier = Modifier
                                    .then(reducedMotionAnimateItem())
                                    .padding(start = (node.depth.coerceAtMost(3) * 16).dp),
                                backgroundContent = {
                                    val archiving = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                    val bg = if (archiving) {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(bg)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = if (archiving) Alignment.CenterStart else Alignment.CenterEnd,
                                    ) {
                                        if (archiving) {
                                            val label = stringResource(
                                                if (isArchivedForSwipe) R.string.session_unarchive
                                                else R.string.session_archive,
                                            )
                                            Icon(
                                                if (isArchivedForSwipe) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                                contentDescription = label,
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        } else {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.delete),
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                },
                            ) {
                                SessionCard(
                                    session = session,
                                    preview = state.previews[session.id],
                                    unreadCount = unread[session.id] ?: 0,
                                    isSelected = if (inSelection) session.id in selectedIds
                                        else session.id == selectedSessionId,
                                    isPinned = isPinned,
                                    isArchived = isArchived,
                                    isMuted = isMuted,
                                    isRunning = session.id in runningSessionIds,
                                    hasDraft = session.id in drafts,
                                    onClick = onCardClick,
                                    onRename = onCardRename,
                                    onDelete = onCardDelete,
                                    onPin = onCardPin,
                                    onArchive = onCardArchive,
                                    onMarkUnread = onCardMarkUnread,
                                    onToggleMute = onCardToggleMute,
                                    onFilterByDirectory = onFilterByDirectory,
                                    isDirectoryFiltered = state.directoryFilter != null &&
                                        session.directory?.trimEnd('/') == state.directoryFilter,
                                    inSelection = inSelection,
                                    onToggleSelect = { onToggleSelect(session.id) },
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
}

/** A session plus its nesting [depth] (0 = top level) for the sub-session tree. */
private data class SessionNode(val session: soy.iko.opencode.data.model.Session, val depth: Int)

/**
 * Keep the home-screen widget, launcher shortcuts, and "Resume last" in sync with the
 * loaded session list. When the list is populated, write recents and point "Resume last"
 * at the most recent session. When the server is genuinely empty (done loading, no error),
 * clear "Resume last" so it doesn't point at a deleted/nonexistent session. A transient
 * load failure (error set) leaves existing shortcuts intact — wiping them on a network
 * blip would vanish a valid shortcut until the next successful load.
 */
@Composable
private fun SyncShortcutsAndWidget(
    platformContext: android.content.Context,
    state: SessionListState,
) {
    val serverIsEmpty = state.sessions.isEmpty() && state.error == null && !state.loading
    LaunchedEffect(serverIsEmpty) {
        if (serverIsEmpty) AppShortcuts.update(platformContext, null)
    }
    LaunchedEffect(state.sessions) {
        val sessions = state.sessions
        if (sessions.isEmpty()) return@LaunchedEffect
        val recents = sessions.take(RecentSessionsStore.MAX).map { RecentSession(it.id, it.displayTitle) }
        RecentSessionsStore.write(platformContext, recents)
        AppShortcuts.update(platformContext, recents.firstOrNull())
        SessionsWidgetProvider.refresh(platformContext)
    }
}

/**
 * Order [sessions] so each child (a session whose `parentID` is also present) appears
 * immediately after its parent, tracking depth for indentation. The input order (already
 * sorted by the VM) is preserved for roots and within each sibling group. Sessions whose
 * parent isn't in the list are treated as roots; a `parentID` cycle can't drop a row (any
 * session not reached from a root is appended at depth 0).
 */
private fun buildSessionTree(sessions: List<soy.iko.opencode.data.model.Session>): List<SessionNode> {
    // Fast path: no parent links at all (the common case) → everything is top-level.
    if (sessions.none { it.parentID != null }) return sessions.map { SessionNode(it, 0) }
    val ids = sessions.mapTo(HashSet()) { it.id }
    val childrenByParent = LinkedHashMap<String, MutableList<soy.iko.opencode.data.model.Session>>()
    val roots = ArrayList<soy.iko.opencode.data.model.Session>()
    for (s in sessions) {
        val parent = s.parentID
        if (parent != null && parent in ids) childrenByParent.getOrPut(parent) { ArrayList() }.add(s)
        else roots.add(s)
    }
    val result = ArrayList<SessionNode>(sessions.size)
    val visited = HashSet<String>()
    fun emit(session: soy.iko.opencode.data.model.Session, depth: Int) {
        if (!visited.add(session.id)) return
        result.add(SessionNode(session, depth))
        childrenByParent[session.id]?.forEach { emit(it, depth + 1) }
    }
    roots.forEach { emit(it, 0) }
    // Safety: append any session not reachable from a root (e.g. a parentID cycle).
    for (s in sessions) if (s.id !in visited) result.add(SessionNode(s, 0))
    return result
}

@Composable
private fun RenameSessionDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Drive the field with a TextFieldValue seeded with the whole title selected so the
    // dialog opens with the text highlighted for immediate overtyping; edits sync back to
    // the hoisted title (capped) that gates the confirm button and the Done action.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(title, selection = TextRange(0, title.length)))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_session)) },
        text = {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { v ->
                    val capped = if (v.text.length > NetworkConfig.maxSessionTitleChars) {
                        val t = v.text.take(NetworkConfig.maxSessionTitleChars)
                        TextFieldValue(t, selection = TextRange(t.length))
                    } else {
                        v
                    }
                    fieldValue = capped
                    onTitleChange(capped.text)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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

@Composable
private fun SessionTitle(text: String, unread: Boolean) {
    // Unread is conveyed by both color AND weight so the distinction survives colorblindness /
    // low-contrast themes, not color alone. Extracted from SessionCard to keep that function
    // under the cyclomatic-complexity threshold.
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun SessionCard(
    session: Session,
    preview: String?,
    unreadCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isPinned: Boolean = false,
    isArchived: Boolean = false,
    isRunning: Boolean = false,
    hasDraft: Boolean = false,
    onFilterByDirectory: ((String) -> Unit)? = null,
    isDirectoryFiltered: Boolean = false,
    inSelection: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    // In two-pane mode the selected row is highlighted (border + container tint) so
    // the user can tell which conversation is open in the detail pane at a glance —
    // without it every row looks identical and the open session is a guess. In multi-select
    // mode a checked row is tinted the same way.
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
        else androidx.compose.ui.graphics.Color.Transparent
    Card(
        modifier = modifier
            .fillMaxWidth()
            // Mute an archived row (unless it's the open one) so it reads as backgrounded
            // when "show archived" surfaces it alongside active sessions.
            .then(if (isArchived && !isSelected) Modifier.alpha(0.6f) else Modifier)
            // Merge the row's text into one semantics node so TalkBack reads it as a single
            // item; the overflow menu button stays its own actionable node.
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                role = Role.Button,
                // In selection mode a tap toggles the row's checked state; otherwise it opens
                // the conversation. A long-press enters selection mode on that row.
                onClick = {
                    if (inSelection && onToggleSelect != null) onToggleSelect() else onClick()
                },
                onLongClick = onToggleSelect?.let { { it() } },
                onLongClickLabel = onToggleSelect?.let { stringResource(R.string.select) },
            ),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // In multi-select mode a checkbox leads the row so the checked state is obvious,
                // and the monogram is hidden (it competes for the leading slot).
                if (inSelection) {
                    androidx.compose.material3.Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect?.invoke() },
                    )
                }
                if (!inSelection) {
                // Leading monogram avatar so a long list is scannable at a glance instead of a
                // wall of identical text rows. Color is derived (stably) from the session id so
                // the same session always gets the same tint, while staying within tonal theme
                // roles to match the Tokyo Night palette.
                SessionAvatar(session)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRunning) {
                            val runningLabel = stringResource(R.string.session_running_badge)
                            LoadingSpinner(
                                size = LoadingSize.Inline,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .semantics { contentDescription = runningLabel },
                            )
                        }
                        if (unreadCount > 0 && !isMuted) {
                            val unreadLabel = stringResource(R.string.unread_count, unreadCount)
                            // Count badge: shows the number of unread messages so the user
                            // can tell a single reply from a burst. Falls back to a dot for
                            // a count of 1 (the common "one reply" case) to avoid clutter.
                            // Suppressed when the session is muted (the count is still tracked
                            // internally so unmuting restores the badge).
                            if (unreadCount == 1) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(10.dp)
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
                                        if (unreadCount > 99) stringResource(R.string.count_overflow)
                                        else unreadCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        if (isPinned) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.session_pinned),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (isArchived) {
                            Icon(
                                Icons.Filled.Archive,
                                contentDescription = stringResource(R.string.session_archived_badge),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isMuted) {
                            Icon(
                                Icons.Filled.NotificationsOff,
                                contentDescription = stringResource(R.string.session_muted_badge),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Draft chip: a muted "Draft" label when the user has unsent input
                        // for this session, so a user scanning the list can tell which
                        // conversations have a half-written reply waiting. Sourced from the
                        // observable DraftStore.drafts map. Reads as a status, not unread
                        // emphasis, so it doesn't compete with the unread badge.
                        if (hasDraft) {
                            val draftLabel = stringResource(R.string.draft_indicator)
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                    .semantics { contentDescription = draftLabel },
                            ) {
                                Text(
                                    draftLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                        // Server-side state badges (shared / reverted). These are surfaced in
                        // the chat screen but weren't in the list, so the list never reflected
                        // them. Muted so they read as status, not as unread emphasis.
                        if (session.isShared) {
                            Icon(
                                Icons.Filled.Link,
                                contentDescription = stringResource(R.string.session_shared_badge),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (session.isReverted) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = stringResource(R.string.session_reverted_badge),
                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SessionTitle(session.displayTitle, unreadCount > 0)
                    }
                    // Long-press the relative label to reveal the full absolute timestamp.
                    RelativeTimeText(
                        session.time?.updated ?: session.time?.created,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // The working directory the agent runs in for this session. Shown because
                    // the list spans sessions across directories, so the folder name tells
                    // otherwise-similar sessions apart at a glance.
                    session.displayDirectory?.let { dir ->
                        val dirDesc = stringResource(R.string.session_directory_desc, session.directory.orEmpty())
                        // Tapping the directory chip filters the list to this project (and taps
                        // again to clear), so a user working across sessions in one repo can scope
                        // the list without typing. Highlighted while the filter is active.
                        val filterLabel = stringResource(
                            if (isDirectoryFiltered) R.string.clear_project_filter else R.string.filter_by_project,
                        )
                        // Disable the chip during selection mode so a tap toggles selection rather
                        // than silently applying a directory filter mid-multi-select.
                        val chipEnabled = onFilterByDirectory != null && !inSelection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .then(
                                    if (chipEnabled) {
                                        Modifier.clickable(role = Role.Button, onClickLabel = filterLabel) {
                                            onFilterByDirectory(session.directory ?: dir)
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .semantics(mergeDescendants = true) { contentDescription = dirDesc },
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (isDirectoryFiltered) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                dir,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDirectoryFiltered) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                // Overflow menu replaces the inline edit + delete IconButtons so the
                // card row is decluttered — two always-visible icons per row made a
                // long list look busy. Swipe-to-delete and the overflow cover the same
                // actions; swipe remains the gesture path, overflow the discovery path.
                var showRowMenu by rememberSaveable(session.id) { mutableStateOf(false) }
                // Close this row's overflow on back press instead of navigating away, matching
                // the top-bar dropdowns (showServerMenu/showSortMenu/showMainMenu).
                BackHandler(enabled = showRowMenu) { showRowMenu = false }
                val renameLabel = stringResource(R.string.rename)
                val deleteLabel = stringResource(R.string.delete)
                // Include the session title in the overflow button's a11y label so a TalkBack
                // user scrolling a long list doesn't hear an undifferentiated run of "More"
                // buttons with no indication of which session each belongs to.
                val moreLabel = stringResource(R.string.more_options_for, session.displayTitle)
                val pinLabel = stringResource(if (isPinned) R.string.session_unpin else R.string.session_pin)
                val archiveLabel = stringResource(if (isArchived) R.string.session_unarchive else R.string.session_archive)
                val markUnreadLabel = stringResource(R.string.session_mark_unread)
                val muteLabel = stringResource(if (isMuted) R.string.session_unmute else R.string.session_mute)
                val copyIdLabel = stringResource(R.string.copy_session_id)
                val rowContext = LocalContext.current
                val clipLabelSessionId = stringResource(R.string.clip_label_session_id)
                Box {
                    IconButton(onClick = { showRowMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = moreLabel)
                    }
                    DropdownMenu(
                        expanded = showRowMenu,
                        onDismissRequest = { showRowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(pinLabel) },
                            onClick = { showRowMenu = false; onPin() },
                        )
                        DropdownMenuItem(
                            text = { Text(archiveLabel) },
                            onClick = { showRowMenu = false; onArchive() },
                        )
                        // Only offer "Mark as unread" when the session isn't already badged —
                        // re-marking an unread session is a no-op that would just clutter the menu.
                        if (unreadCount == 0) {
                            DropdownMenuItem(
                                text = { Text(markUnreadLabel) },
                                onClick = { showRowMenu = false; onMarkUnread() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(muteLabel) },
                            onClick = { showRowMenu = false; onToggleMute() },
                        )
                        DropdownMenuItem(
                            text = { Text(copyIdLabel) },
                            onClick = {
                                showRowMenu = false
                                copyToClipboard(rowContext, clipLabelSessionId, session.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(renameLabel) },
                            onClick = { showRowMenu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                            onClick = { showRowMenu = false; onDelete() },
                        )
                    }
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

/**
 * Leading monogram avatar for a session row: a single letter (preferring the directory's
 * initial, since directories are usually project names) on a tonal circle whose color is
 * derived stably from the session id. Gives the list visual identity so a long list is
 * scannable at a glance, without introducing random non-theme colors.
 */
@Composable
private fun SessionAvatar(session: Session) {
    val scheme = MaterialTheme.colorScheme
    val letter = remember(session.id, session.displayDirectory, session.displayTitle) {
        val source = session.displayDirectory?.firstOrNull()?.takeIf { it.isLetterOrDigit() }
            ?: session.displayTitle.firstOrNull()?.takeIf { it.isLetterOrDigit() }
            ?: '·'
        source.uppercaseChar()
    }
    // Stable per-id color picked from a few tonal container roles, so the same session always
    // gets the same tint while staying within the theme palette (Tokyo Night or dynamic).
    val (bg, fg) = remember(session.id, scheme) {
        val options = listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
        )
        val idx = ((session.id.hashCode() % options.size) + options.size) % options.size
        options[idx]
    }
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.toString(),
            color = fg,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Confirmation + Undo for pin/archive toggles. Archiving hides the row from the default
// view, so without this the user gets no feedback and no way back. collectLatest so a rapid
// re-toggle supersedes the prior snackbar instead of queuing behind it.
@Composable
private fun SessionActionUndoEffect(vm: SessionListViewModel, snackbar: SnackbarHostState) {
    val undoLabel = stringResource(R.string.undo)
    val archived = stringResource(R.string.session_archived)
    val unarchived = stringResource(R.string.session_unarchived)
    val pinned = stringResource(R.string.session_pinned_msg)
    val unpinned = stringResource(R.string.session_unpinned_msg)
    LaunchedEffect(Unit) {
        vm.sessionActionEvents.collectLatest { event ->
            val message = when (event.kind) {
                SessionActionKind.ARCHIVED -> archived
                SessionActionKind.UNARCHIVED -> unarchived
                SessionActionKind.PINNED -> pinned
                SessionActionKind.UNPINNED -> unpinned
            }
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                vm.undoSessionAction(event)
            }
        }
    }
}

// Confirmation + Undo for bulk archive actions. Mirrors SessionActionUndoEffect but for
// the bulk path, so archiving N sessions at once shows a single snackbar (not N) with Undo.
@Composable
private fun BulkSessionActionUndoEffect(vm: SessionListViewModel, snackbar: SnackbarHostState) {
    val undoLabel = stringResource(R.string.undo)
    val archived = stringResource(R.string.bulk_archived)
    val unarchived = stringResource(R.string.bulk_unarchived)
    LaunchedEffect(Unit) {
        vm.bulkSessionActionEvents.collectLatest { event ->
            val message = when (event.kind) {
                SessionActionKind.ARCHIVED -> archived.format(event.ids.size)
                SessionActionKind.UNARCHIVED -> unarchived.format(event.ids.size)
                SessionActionKind.PINNED, SessionActionKind.UNPINNED -> return@collectLatest
            }
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                vm.undoBulkAction(event)
            }
        }
    }
}

@Composable
private fun SortMenu(
    sortMode: SessionSortMode,
    sortDescending: Boolean,
    showArchived: Boolean,
    hiddenArchivedCount: Int,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSetSortMode: (SessionSortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onSetShowArchived: (Boolean) -> Unit,
) {
    Box {
        IconButton(onClick = onExpand) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_recent)) },
                trailingIcon = if (sortMode == SessionSortMode.RECENT) {
                    { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sort_active)) }
                } else null,
                onClick = { onSetSortMode(SessionSortMode.RECENT); onDismiss() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_title)) },
                trailingIcon = if (sortMode == SessionSortMode.TITLE) {
                    { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sort_active)) }
                } else null,
                onClick = { onSetSortMode(SessionSortMode.TITLE); onDismiss() },
            )
            androidx.compose.material3.HorizontalDivider()
            // Direction toggle: the label shows the current direction and the arrow its
            // sense; tapping flips it and re-sorts the list in place.
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (sortDescending) R.string.sort_descending else R.string.sort_ascending))
                },
                leadingIcon = {
                    Icon(
                        if (sortDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = null,
                    )
                },
                onClick = onToggleDirection,
            )
            // Toggle archived visibility. Only offered once at least one session is
            // archived, so the menu stays uncluttered otherwise.
            if (showArchived || hiddenArchivedCount > 0) {
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived))
                    },
                    onClick = { onSetShowArchived(!showArchived); onDismiss() },
                )
            }
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
    anyRunActive: Boolean = false,
) {
    // Switching servers kills the SSE stream and any in-flight run (same hazard as disconnecting),
    // so confirm first when a run is active. Kept inside the switcher so the call site stays a
    // plain onSelect and SessionListScreen's complexity doesn't grow.
    var pendingSwitch by rememberSaveable { mutableStateOf<ServerProfile?>(null) }
    Column {
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
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
                        onClick = {
                            when {
                                isActiveProfile -> onDismiss()
                                anyRunActive -> pendingSwitch = profile
                                // Don't dismiss here: keep the switcher open so its per-row spinner
                                // (driven by switchingId) stays visible during the switch. The
                                // LaunchedEffect in SessionListScreen dismisses it once it resolves.
                                else -> onSelect(profile)
                            }
                        },
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
    SwitchServerConfirmDialog(
        target = pendingSwitch,
        onSwitch = { profile -> pendingSwitch = null; onSelect(profile) },
        onDismiss = { pendingSwitch = null },
    )
}

/** Confirmation shown before switching to another server while an agent run is active, since the
 *  switch kills the SSE stream and any in-flight run (same hazard as disconnecting). Renders
 *  nothing when [target] is null (the profile vanished before the dialog showed). */
@Composable
private fun SwitchServerConfirmDialog(
    target: soy.iko.opencode.data.model.ServerProfile?,
    onSwitch: (soy.iko.opencode.data.model.ServerProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disconnect_active_title)) },
        text = { Text(stringResource(R.string.disconnect_active_text)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSwitch(target) }) {
                Text(stringResource(R.string.switch_server), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Confirmation shown before disconnecting while an agent run is active, since disconnecting kills
 *  the SSE stream and any in-flight run. */
@Composable
private fun DisconnectActiveConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disconnect_active_title)) },
        text = { Text(stringResource(R.string.disconnect_active_text)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
