package soy.iko.opencode.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.SessionView
import soy.iko.opencode.ui.components.DualSnackbarHost
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.ErrorHost
import soy.iko.opencode.ui.components.InfoHost
import soy.iko.opencode.ui.components.LoadingPlaceholder
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(core: Core) {
    val view by core.view.collectAsState()
    val errorHostState = ErrorHost(
        core = core,
        dismissLabel = stringResource(R.string.action_dismiss),
        retryLabel = stringResource(R.string.action_retry),
    )
    val infoHostState = InfoHost(
        core = core,
        successConnectedLabel = stringResource(R.string.connect_success),
        successSessionCreatedLabel = stringResource(R.string.session_created),
        copiedLabel = stringResource(R.string.chat_copied),
        sessionDeletedLabel = stringResource(R.string.session_deleted),
    )
    val untitledLabel = stringResource(R.string.sessions_untitled)
    val idShortTemplate = stringResource(R.string.sessions_id_short)
    val deleteCdLabel = stringResource(R.string.sessions_cd_delete)
    val disconnectConfirmLabel = stringResource(R.string.sessions_disconnect_confirm)
    val disconnectYesLabel = stringResource(R.string.sessions_disconnect_confirm_yes)

    // Track the session pending deletion (for confirmation dialog) and
    // whether the disconnect-from-server confirmation should show.
    var pendingDelete by remember { mutableStateOf<SessionView?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.top_bar_sessions)) },
                navigationIcon = {
                    IconButton(onClick = { showDisconnectConfirm = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sessions_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { core.update(Event.CreateSession) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.sessions_cd_new),
                        )
                    }
                },
            )
        },
        snackbarHost = { DualSnackbarHost(errorHostState, infoHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                view.loading && view.sessions.isEmpty() -> {
                    LoadingPlaceholder()
                }
                view.sessions.isEmpty() -> {
                    EmptyState(message = stringResource(R.string.sessions_empty_hint))
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = view.loading,
                        onRefresh = { core.update(Event.LoadSessions) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = Dimens.listVerticalPadding),
                        ) {
                            items(view.sessions, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    untitledLabel = untitledLabel,
                                    idShortLabel = idShortTemplate.format(session.id.take(8)),
                                    deleteCdLabel = deleteCdLabel,
                                    onClick = { core.update(Event.SelectSession(session.id)) },
                                    onDelete = { pendingDelete = session },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(session.title.ifEmpty { untitledLabel }) },
            text = { Text(stringResource(R.string.sessions_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    core.update(Event.DeleteSession(session.id))
                    pendingDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.sessions_delete_confirm_yes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.crash_reports_cancel))
                }
            },
        )
    }

    // Disconnect confirmation dialog — going back to Connect is destructive
    // (drops the session list and requires a full reconnect), so confirm.
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            text = { Text(disconnectConfirmLabel) },
            confirmButton = {
                TextButton(onClick = {
                    core.update(Event.NavigateToConnect)
                    showDisconnectConfirm = false
                }) {
                    Text(disconnectYesLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.crash_reports_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionView,
    untitledLabel: String,
    idShortLabel: String,
    deleteCdLabel: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = session.title.ifEmpty { untitledLabel }
    ListItem(
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = idShortLabel,
                maxLines = 1,
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = deleteCdLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete,
            )
            .semantics(mergeDescendants = true) {
                // Merge the row into a single TalkBack utterance and announce
                // it as a button so users know it's actionable.
                role = Role.Button
                contentDescription = title
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionsScreenPreview() {
    OpencodeTheme {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                },
            )
        }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(
                        listOf(
                            SessionView("abc12345def", "Refactor the parser"),
                            SessionView("xyz12345abc", ""),
                        ),
                        key = { it.id },
                    ) { session ->
                        SessionRow(
                            session,
                            untitledLabel = "Untitled",
                            idShortLabel = "Session ${session.id.take(8)}",
                            deleteCdLabel = "Delete session",
                            onClick = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun SessionsScreenEmptyPreview() {
    OpencodeTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Sessions") }) }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(message = "No sessions yet")
            }
        }
    }
}
