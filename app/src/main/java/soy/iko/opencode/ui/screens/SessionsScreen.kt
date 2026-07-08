package soy.iko.opencode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.SessionView
import soy.iko.opencode.core.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(state: UiState, dispatch: (Event) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = { dispatch(Event.NavigateToConnect) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                    }
                },
                actions = {
                    IconButton(onClick = { dispatch(Event.LoadSessions) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dispatch(Event.CreateSession) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                state.sessions.isEmpty() && !state.loading -> EmptySessions()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onOpen = { dispatch(Event.SelectSession(session.id)) },
                            onDelete = { dispatch(Event.DeleteSession(session.id)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionView, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySessions() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Tap “New session” to start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
