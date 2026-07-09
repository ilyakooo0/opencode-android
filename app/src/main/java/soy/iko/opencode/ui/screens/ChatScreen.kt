package soy.iko.opencode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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

    // Hide the jump-to-bottom FAB while the keyboard is up: it would otherwise
    // sit over the top of the IME. Reading the ime inset bottom during composition
    // observes snapshot state, so this flips as the keyboard opens/closes.
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
                    val title = state.currentSessionTitle.ifEmpty { "Chat" }
                    Text(
                        text = if (state.messages.isNotEmpty()) "$title (${state.messages.size})" else title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { dispatch(Event.NavigateToSessions) },
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
                loading = state.loading && state.messages.isEmpty(),
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
            if (state.error != null && state.messages.isNotEmpty()) {
                ErrorBanner(
                    message = state.error,
                    onDismiss = { dispatch(Event.DismissError) },
                )
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
                    itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                        // Insert a date separator whenever a message falls on a different
                        // calendar day than the one before it (and above the first message).
                        val previous = state.messages.getOrNull(index - 1)
                        if (shouldShowDateSeparator(previous, message)) {
                            DateSeparator(message.time)
                        }
                        val sameSenderAsPrevious = index > 0 && state.messages[index - 1].isUser == message.isUser
                        MessageBubble(
                            message,
                            onRetry = { dispatch(Event.SendMessage(message.text)) },
                            compactSpacing = sameSenderAsPrevious,
                        )
                    }
                    // While the assistant is spinning up but hasn't emitted a streaming
                    // message yet (only the user's message is present), show a typing
                    // indicator so the wait doesn't look like a stall.
                    if (state.generating && state.messages.none { it.streaming }) {
                        item(key = "typing-indicator") {
                            TypingIndicator()
                        }
                    }
                }
                ScrollToBottomButton(
                    visible = showScrollButton && !isImeVisible,
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

private val dateSeparatorFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d")

/** Convert an epoch-millis timestamp to the local calendar day it falls on. */
private fun localDayOf(epochMillis: Long): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()

/**
 * A separator is shown above a message when it starts a new calendar day — i.e. the
 * first timestamped message, or one whose day differs from the previous message's.
 * Messages without a valid time (time <= 0) never get one.
 */
private fun shouldShowDateSeparator(previous: soy.iko.opencode.core.MessageView?, current: soy.iko.opencode.core.MessageView): Boolean {
    if (current.time <= 0) return false
    if (previous == null || previous.time <= 0) return true
    return localDayOf(previous.time) != localDayOf(current.time)
}

/** Human-friendly day label: "Today", "Yesterday", or "MMM d" (e.g. "Jul 8"). */
private fun dateSeparatorLabel(day: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(dateSeparatorFormat)
    }
}

@Composable
private fun DateSeparator(epochMillis: Long) {
    Text(
        text = dateSeparatorLabel(localDayOf(epochMillis)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

// Assistant-style bubble with three sequentially-pulsing dots, shown as a stand-in
// until the real streaming reply starts. Mirrors MessageBubble's left-aligned,
// surfaceVariant look so it reads as an incoming message.
@Composable
private fun TypingIndicator() {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Stagger the three dots so they fade in and out in sequence.
                listOf(0, 150, 300).forEach { staggerMs ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, delayMillis = staggerMs),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot",
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
                    )
                }
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
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    generating: Boolean,
    loading: Boolean,
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
                enabled = !loading,
                placeholder = { Text("Message opencode…") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!generating && !loading && draft.isNotBlank()) onSend()
                }),
            )
            if (generating) {
                FilledTonalButton(onClick = onStop) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Stop")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = draft.isNotBlank() && !loading,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
