package soy.iko.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.SessionView
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.ErrorHost
import soy.iko.opencode.ui.components.ErrorSnackbarHost
import soy.iko.opencode.ui.components.InfoHost
import soy.iko.opencode.ui.components.LoadingPlaceholder
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(core: Core) {
    val view by core.view.collectAsState()
    val snackbarHostState = ErrorHost(
        core = core,
        dismissLabel = stringResource(R.string.action_dismiss),
        retryLabel = stringResource(R.string.action_retry),
    )
    InfoHost(
        core = core,
        successConnectedLabel = stringResource(R.string.connect_success),
        successSessionCreatedLabel = stringResource(R.string.session_created),
        snackbarHostState = snackbarHostState,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.top_bar_sessions)) },
                navigationIcon = {
                    IconButton(onClick = { core.update(Event.NavigateToConnect) }) {
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
                    // Disconnect: returns to the Connect screen to switch
                    // servers / credentials. Labeled and iconed as logout so
                    // users understand it ends the session, not "settings".
                    IconButton(onClick = { core.update(Event.NavigateToConnect) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.sessions_cd_disconnect),
                        )
                    }
                },
            )
        },
        snackbarHost = { ErrorSnackbarHost(snackbarHostState) },
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
                    EmptyState(message = stringResource(R.string.sessions_empty))
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = view.loading,
                        onRefresh = { core.update(Event.LoadSessions) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn {
                            items(view.sessions, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    untitledLabel = stringResource(R.string.sessions_untitled),
                                    onClick = { core.update(Event.SelectSession(session.id)) },
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
private fun SessionRow(
    session: SessionView,
    untitledLabel: String,
    onClick: () -> Unit,
) {
    val title = session.title.ifEmpty { untitledLabel }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(session.id.take(8)) },
        leadingContent = {
            Icon(
                Icons.Default.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .clickable(onClick = onClick)
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
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Disconnect")
                    }
                },
            )
        }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn {
                    items(
                        listOf(
                            SessionView("abc12345def", "Refactor the parser"),
                            SessionView("xyz12345abc", ""),
                        ),
                        key = { it.id },
                    ) { session ->
                        SessionRow(session, "Untitled", onClick = {})
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
