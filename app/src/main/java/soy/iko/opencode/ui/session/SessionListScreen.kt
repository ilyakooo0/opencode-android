package soy.iko.opencode.ui.session

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.relativeTime
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
    val serverLabel by vm.serverLabel.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val switchingId by vm.switchingId.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var showServerMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    var pendingRename by remember { mutableStateOf<Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Box {
                            Row(
                                modifier = Modifier.clickable { showServerMenu = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(serverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch server")
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
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = "Files")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Disconnect")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.createSession(onCreated = onOpenSession) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    state.error!!,
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
                        placeholder = { Text("Search sessions…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                    )
                    val sessions = state.filtered
                    if (sessions.isEmpty()) {
                        Text(
                            "No sessions match “${state.query}”.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(sessions, key = { it.id }) { session ->
                                SessionCard(
                                    session = session,
                                    preview = state.previews[session.id],
                                    onClick = { onOpenSession(session.id) },
                                    onRename = { pendingRename = session },
                                    onDelete = { pendingDelete = session },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete session?") },
            text = { Text("“${session.displayTitle}” will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    pendingDelete = null
                    vm.deleteSession(session)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingRename?.let { session ->
        var title by remember { mutableStateOf(session.title ?: "") }
        RenameSessionDialog(
            title = title,
            onTitleChange = { title = it },
            onConfirm = {
                val newName = title.trim()
                pendingRename = null
                if (newName.isNotEmpty() && newName != session.title) vm.renameSession(session, newName)
            },
            onDismiss = { pendingRename = null },
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
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Session title") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SessionCard(
    session: Session,
    preview: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    session.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val time = relativeTime(session.time?.updated ?: session.time?.created)
                if (time.isNotEmpty()) {
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Start a conversation with opencode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  New session")
        }
    }
}
