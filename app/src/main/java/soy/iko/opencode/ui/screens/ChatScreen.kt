package soy.iko.opencode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.UiState
import soy.iko.opencode.ui.components.MessageBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, dispatch: (Event) -> Unit) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Show a jump-to-bottom button once the user scrolls up away from the latest
    // message. derivedStateOf keeps this from recomposing on every pixel of scroll —
    // it only flips when the near-bottom threshold is actually crossed.
    val showScrollButton by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex < totalItems - 3
        }
    }

    // Track how many messages the user has "seen" (i.e. was at the bottom for). While
    // they're scrolled up, any messages beyond that count are unseen — flag the FAB.
    var lastSeenCount by remember { mutableStateOf(0) }
    LaunchedEffect(showScrollButton, state.messages.size) {
        if (!showScrollButton) {
            lastSeenCount = state.messages.size
        }
    }
    val hasUnseenMessages = showScrollButton && state.messages.size > lastSeenCount

    // Keep the newest content in view as it streams in, but only when the user is
    // already near the bottom. If they've scrolled up to read history, leave it alone.
    val lastLen = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLen, state.generating) {
        if (state.messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            val nearBottom = totalItems == 0 || lastVisibleIndex >= totalItems - 3
            if (nearBottom) {
                listState.animateScrollToItem(state.messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentSessionTitle.ifEmpty { "Chat" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { dispatch(Event.NavigateToSessions) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions")
                    }
                },
                actions = {
                    if (state.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                draft = state.draft,
                generating = state.generating,
                onDraftChange = { dispatch(Event.DraftChanged(it)) },
                onSend = { dispatch(Event.SendMessage(state.draft)) },
                onStop = { dispatch(Event.CancelGeneration) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.sseConnected && !state.generating) {
                ReconnectingBanner()
            }
            if (state.loading && state.messages.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.messages.isEmpty() && !state.loading) {
                    Text(
                        text = "Send a message to start the conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
                ScrollToBottomButton(
                    visible = showScrollButton,
                    showBadge = hasUnseenMessages,
                    onClick = {
                        coroutineScope.launch {
                            if (state.messages.isNotEmpty()) {
                                listState.animateScrollToItem(state.messages.size - 1)
                            }
                        }
                    },
                )
            }
        }
    }
}

// Extracted into a BoxScope extension so the top-level AnimatedVisibility overload
// resolves cleanly. Called inline inside a Column, the enclosing ColumnScope receiver
// would otherwise capture the ColumnScope.AnimatedVisibility overload and fail to compile.
@Composable
private fun BoxScope.ScrollToBottomButton(visible: Boolean, showBadge: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomEnd),
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Box(Modifier.padding(16.dp)) {
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Scroll to latest message",
                )
            }
            if (showBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

@Composable
private fun ReconnectingBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "Reconnecting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    generating: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message opencode…") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!generating && draft.isNotBlank()) onSend()
                }),
            )
            if (generating) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
