package soy.iko.opencode.ui.session

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.rememberRelativeTime
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: SessionListViewModel = viewModel(factory = vmFactory { SessionListViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val transientError by vm.transientError.collectAsStateWithLifecycle()
    val serverLabel by vm.serverLabel.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val switchingId by vm.switchingId.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var showServerMenu by remember { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = pendingDeleteId?.let { id -> state.sessions.firstOrNull { it.id == id } }
    val pendingRename = pendingRenameId?.let { id -> state.sessions.firstOrNull { it.id == id } }

    LaunchedEffect(transientError) {
        val msg = transientError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearTransientError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clickable { showServerMenu = true }
                                    .semantics { role = Role.Button },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(serverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.switch_server))
                            }
                            DropdownMenu(
                                expanded = showServerMenu,
                                onDismissRequest = { showServerMenu = false },
                            ) {
                                profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        profile.displayLabel,
                                                        style = MaterialTheme.typography.bodyLarge,
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
                                                if (switchingId == profile.id) {
                                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                                }
                                            }
                                        },
                                        onClick = {
                                            showServerMenu = false
                                            vm.switchServer(profile)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.files))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.disconnect))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.createSession(onCreated = onOpenSession) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_session)) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(
                state = connectionState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.sessions.isEmpty() && state.error != null -> Text(
                    state.error ?: "",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                state.sessions.isEmpty() -> EmptySessions(
                    onCreate = { vm.createSession(onCreated = onOpenSession) },
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.search_sessions)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    val sessions = state.filtered
                    if (sessions.isEmpty()) {
                        Text(
                            stringResource(R.string.no_sessions_match, state.query),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = { vm.refresh() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(sessions, key = { it.id }) { session ->
                                    SessionCard(
                                        session = session,
                                        preview = state.previews[session.id],
                                        unread = unread.contains(session.id),
                                        onClick = { onOpenSession(session.id) },
                                        onRename = { pendingRenameId = session.id },
                                        onDelete = { pendingDeleteId = session.id },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
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
        var title by remember(session.id) { mutableStateOf(session.title ?: "") }
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
    unread: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Swipe toward the start reveals a delete affordance; releasing snaps back and opens
    // the same confirmation dialog the trash icon uses, so the destructive action is
    // always confirmed.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            onDelete()
            false // snap back; the dialog owns the actual deletion
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeDeleteBackground() },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onClick() },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (unread) {
                        val unreadLabel = stringResource(R.string.unread)
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .semantics { contentDescription = unreadLabel },
                        )
                    }
                    Text(
                        session.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val time = rememberRelativeTime(session.time?.updated ?: session.time?.created)
                    if (time.isNotEmpty()) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Red background with a trash icon shown behind a session card as it is swiped away. */
@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
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
