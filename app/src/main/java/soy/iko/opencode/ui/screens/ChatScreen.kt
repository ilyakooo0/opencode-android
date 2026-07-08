package soy.iko.opencode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.MessageView
import soy.iko.opencode.core.SseState
import soy.iko.opencode.ui.components.ChatInputBar
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.ErrorHost
import soy.iko.opencode.ui.components.ErrorSnackbarHost
import soy.iko.opencode.ui.components.GeneratingIndicator
import soy.iko.opencode.ui.components.InfoHost
import soy.iko.opencode.ui.components.LoadingPlaceholder
import soy.iko.opencode.ui.components.MessageBubble
import soy.iko.opencode.ui.components.SseDisconnectedBanner
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(core: Core) {
    val view by core.view.collectAsState()
    val sseState by core.sseState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Track whether the user is parked at the bottom of the list. We only
    // auto-scroll when they are, so reading history isn't yanked away as
    // new messages stream in.
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= view.messages.size - 2
        }
    }

    LaunchedEffect(view.messages.size) {
        if (view.messages.isNotEmpty() && (isAtBottom || view.messages.size == 1)) {
            listState.animateScrollToItem(view.messages.size - 1)
        }
    }

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

    val showSseBanner = sseState is SseState.Error ||
        (sseState == SseState.Disconnected && view.currentSessionId != null && !view.loading)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = view.currentSessionTitle.ifEmpty {
                                view.currentSessionId?.take(8)?.plus("…")
                                    ?: stringResource(R.string.top_bar_chat_fallback)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (view.currentSessionTitle.isNotEmpty() && view.currentSessionId != null) {
                            Text(
                                text = view.currentSessionId!!.take(8),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { core.update(Event.NavigateToSessions) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_cd_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        core.update(Event.SendMessage(inputText))
                        inputText = ""
                    }
                },
                enabled = !view.loading,
                placeholder = stringResource(R.string.chat_input_placeholder),
                sendContentDescription = stringResource(R.string.chat_cd_send),
            )
        },
        snackbarHost = { ErrorSnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSseBanner) {
                    val bannerMessage = (sseState as? SseState.Error)?.message
                        ?: stringResource(R.string.sse_disconnected)
                    SseDisconnectedBanner(
                        message = bannerMessage,
                        onReconnect = { core.reconnectSse() },
                        reconnectLabel = stringResource(R.string.sse_reconnect),
                    )
                }

                when {
                    view.loading && view.messages.isEmpty() -> {
                        LoadingPlaceholder()
                    }
                    view.messages.isEmpty() -> {
                        EmptyState(message = stringResource(R.string.chat_empty))
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = Dimens.listHorizontalPadding),
                            verticalArrangement = Arrangement.spacedBy(Dimens.listItemSpacing),
                            contentPadding = PaddingValues(vertical = Dimens.listVerticalPadding),
                        ) {
                            items(view.messages, key = { it.id }) { message ->
                                MessageBubble(message)
                            }
                            // "Thinking" indicator shown after the user sends, while
                            // the assistant reply hasn't arrived yet.
                            item(key = "__generating__") {
                                AnimatedVisibility(visible = view.generating) {
                                    GeneratingIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatScreenPreview() {
    OpencodeTheme {
        PreviewChatScreen(
            title = "Refactor the parser",
            sessionId = "abc12345",
            messages = listOf(
                MessageView("u1", "user", "Can you clean up parse_messages?", 1_700_000_000uL),
                MessageView(
                    "a1",
                    "assistant",
                    "Sure — here's a **plan**:\n\n1. Extract helpers\n2. Add tests",
                    1_700_000_010uL,
                ),
            ),
            generating = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun ChatScreenGeneratingPreview() {
    OpencodeTheme {
        PreviewChatScreen(
            title = "New session",
            sessionId = "xyz12345",
            messages = listOf(MessageView("u1", "user", "Hello!", 1_700_000_000uL)),
            generating = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun ChatScreenEmptyPreview() {
    OpencodeTheme {
        PreviewChatScreen(title = "", sessionId = null, messages = emptyList(), generating = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewChatScreen(
    title: String,
    sessionId: String?,
    messages: List<MessageView>,
    generating: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title.ifEmpty {
                            sessionId?.take(8)?.plus("…") ?: "Chat"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(text = "", onTextChange = {}, onSend = {}, enabled = true)
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                messages.isEmpty() -> EmptyState(message = "Send a message to start chatting")
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        items(messages, key = { it.id }) { MessageBubble(it) }
                        if (generating) {
                            item(key = "__generating__") { GeneratingIndicator() }
                        }
                    }
                }
            }
        }
    }
}
