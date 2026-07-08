package soy.iko.opencode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.MessageStatus
import soy.iko.opencode.core.MessageView
import soy.iko.opencode.core.SseState
import soy.iko.opencode.ui.components.ChatInputBar
import soy.iko.opencode.ui.components.DateSeparator
import soy.iko.opencode.ui.components.DualSnackbarHost
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.ErrorHost
import soy.iko.opencode.ui.components.GeneratingIndicator
import soy.iko.opencode.ui.components.InfoHost
import soy.iko.opencode.ui.components.LoadingPlaceholder
import soy.iko.opencode.ui.components.MessageBubble
import soy.iko.opencode.ui.components.SseDisconnectedBanner
import soy.iko.opencode.ui.components.SseStatusBanner
import soy.iko.opencode.ui.components.dayOfEpoch
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(core: Core) {
    val view by core.view.collectAsState()
    val sseState by core.sseState.collectAsState()
    // Wire the draft to the core's ViewModel so it survives rotation and
    // process death. The core stores draftMessage; we seed local state from
    // it and write via Event.DraftChanged on every keystroke.
    var inputText by remember(view.draftMessage) { mutableStateOf(view.draftMessage) }
    val listState = rememberLazyListState()
    var scrollRequest by rememberSaveable { mutableIntStateOf(0) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var sseBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current

    // Track whether the user is parked at the bottom of the list. We only
    // auto-scroll when they are, so reading history isn't yanked away as
    // new messages stream in.
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems == 0 || lastVisible >= totalItems - 2
        }
    }

    LaunchedEffect(view.messages.size) {
        if (view.messages.isNotEmpty() && (isAtBottom || view.messages.size == 1)) {
            listState.animateScrollToItem(view.messages.size - 1)
        }
    }

    // Scroll-to-bottom when the user taps the FAB. Keyed on a counter so
    // repeated taps keep firing.
    LaunchedEffect(scrollRequest) {
        if (scrollRequest > 0 && view.messages.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // Auto-focus the input when the chat is empty so the user can start
    // typing immediately without an extra tap. Only request focus once per
    // session entry — keyed on the session id so switching sessions retriggers.
    val sessionId = view.currentSessionId
    LaunchedEffect(sessionId, view.messages.isEmpty()) {
        if (sessionId != null && view.messages.isEmpty() && !view.loading && !view.generating) {
            inputFocusRequester.requestFocus()
        }
    }

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

    val showSseBanner = (sseState is SseState.Error ||
        (sseState == SseState.Disconnected && view.currentSessionId != null && !view.loading)) &&
        !sseBannerDismissed

    // Reset the dismissed flag whenever a fresh SSE error arrives so the
    // banner reappears for a new disconnect.
    LaunchedEffect((sseState as? SseState.Error)?.message) {
        if (sseState is SseState.Error) sseBannerDismissed = false
    }

    // Resolve nullable session fields into locals once per recomposition so we
    // never smart-cast a delegate-backed collected property across calls.
    val sessionTitle = view.currentSessionTitle
    val chatFallback = stringResource(R.string.top_bar_chat_fallback)
    val idShortTemplate = stringResource(R.string.chat_session_id_short)
    val title = remember(sessionId, sessionTitle) {
        when {
            sessionTitle.isNotEmpty() -> sessionTitle
            sessionId != null -> {
                val prefix = sessionId.take(8)
                if (sessionId.length > prefix.length) "$prefix…" else prefix
            }
            else -> chatFallback
        }
    }
    val subtitle = remember(sessionId, sessionTitle) {
        if (sessionTitle.isNotEmpty() && sessionId != null) {
            idShortTemplate.format(sessionId.take(8))
        } else null
    }

    val refreshLabel = stringResource(R.string.chat_menu_refresh)
    val overflowLabel = stringResource(R.string.chat_menu_overflow)
    val chatEmptyHint = stringResource(R.string.chat_empty_hint)
    val copySessionIdCd = stringResource(R.string.chat_cd_copy_session_id)
    val sessionIdCopiedLabel = stringResource(R.string.chat_session_id_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null && sessionId != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(sessionId))
                                            core.notifyCopied()
                                        },
                                        onLongClick = {
                                            clipboardManager.setText(AnnotatedString(sessionId))
                                            core.notifyCopied()
                                        },
                                    )
                                    .semantics {
                                        contentDescription = copySessionIdCd
                                    },
                            ) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(Dimens.iconInlineSpinner),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                actions = {
                    // Overflow menu with a "Refresh messages" action. Gives
                    // a discoverable manual reload path (e.g. when the SSE
                    // banner has been dismissed) and a home for future items.
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = overflowLabel,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(refreshLabel) },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    sessionId?.let { core.update(Event.LoadMessages(it)) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = {
                    inputText = it
                    core.update(Event.DraftChanged(it))
                },
                onSend = {
                    if (inputText.isNotBlank()) {
                        core.update(Event.SendMessage(inputText))
                        inputText = ""
                        core.update(Event.DraftChanged(""))
                    }
                },
                // Disable while loading OR while the assistant is generating,
                // so users can't fire a second message mid-reply.
                enabled = !view.loading && !view.generating,
                generating = view.generating,
                onStop = { core.update(Event.CancelGeneration) },
                stopContentDescription = stringResource(R.string.chat_cd_stop),
                placeholder = stringResource(R.string.chat_input_placeholder),
                sendContentDescription = stringResource(R.string.chat_cd_send),
                modifier = Modifier.focusRequester(inputFocusRequester),
            )
        },
        snackbarHost = { DualSnackbarHost(errorHostState, infoHostState) },
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
                        onReconnect = {
                            sseBannerDismissed = false
                            core.reconnectSse()
                        },
                        onDismiss = { sseBannerDismissed = true },
                        reconnectLabel = stringResource(R.string.sse_reconnect),
                        dismissLabel = stringResource(R.string.sse_dismiss),
                    )
                } else if (sseState == SseState.Connecting && sessionId != null && !view.loading) {
                    // Brief "connecting" indicator while the SSE stream
                    // establishes — not an error, just a transitional state.
                    SseStatusBanner(
                        message = stringResource(R.string.sse_connecting),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    view.loading && view.messages.isEmpty() -> {
                        LoadingPlaceholder()
                    }
                    view.messages.isEmpty() -> {
                        EmptyState(message = chatEmptyHint)
                    }
                    else -> {
                        // Pull-to-refresh lets the user manually reload
                        // messages, e.g. when the SSE stream stalled and the
                        // banner was dismissed. Mirrors the Sessions screen.
                        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                            isRefreshing = view.loading,
                            onRefresh = { sessionId?.let { core.update(Event.LoadMessages(it)) } },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = Dimens.listHorizontalPadding),
                                verticalArrangement = Arrangement.spacedBy(Dimens.listItemSpacing),
                                contentPadding = PaddingValues(vertical = Dimens.listVerticalPadding),
                            ) {
                                val msgs = view.messages
                                for (i in msgs.indices) {
                                    val msg = msgs[i]
                                    // Insert a date separator before a message
                                    // when its calendar day differs from the
                                    // previous message's day (and it has a
                                    // valid timestamp).
                                    if (msg.time > 0uL) {
                                        val prevDay = if (i > 0) dayOfEpoch(msgs[i - 1].time) else Int.MIN_VALUE
                                        val curDay = dayOfEpoch(msg.time)
                                        if (curDay != prevDay) {
                                            item(key = "date_${msg.id}") {
                                                DateSeparator(epochSeconds = msg.time)
                                            }
                                        }
                                    }
                                    item(key = msg.id) {
                                        MessageBubble(
                                            message = msg,
                                            onCopied = { core.notifyCopied() },
                                        )
                                    }
                                }
                                // "Thinking" indicator shown after the user sends, while
                                // the assistant reply hasn't arrived yet. The indicator
                                // itself animates (pulsing dots), so we don't wrap it in
                                // AnimatedVisibility — an outer ColumnScope receiver from
                                // the chat Column would shadow the LazyItemScope here and
                                // break AnimatedVisibility overload resolution.
                                if (view.generating) {
                                    item(key = "__generating__") {
                                        GeneratingIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Scroll-to-bottom FAB: only when the user has scrolled up away
            // from the latest message and there's content to scroll to.
            // Animated so it scales in/out rather than popping.
            AnimatedVisibility(
                visible = view.messages.isNotEmpty() && !isAtBottom,
                enter = scaleIn(),
                exit = scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { scrollRequest++ },
                    icon = {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_cd_scroll_to_bottom),
                        )
                    },
                    text = { Text(stringResource(R.string.chat_scroll_to_latest)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(
                            end = Dimens.fabEndMargin,
                            bottom = Dimens.fabBottomMargin,
                        ),
                )
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
                MessageView("u1", "user", "Can you clean up parse_messages?", 1_700_000_000uL, MessageStatus.SENT),
                MessageView(
                    "a1",
                    "assistant",
                    "Sure — here's a **plan**:\n\n1. Extract helpers\n2. Add tests",
                    1_700_000_010uL,
                    MessageStatus.SENT,
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
            messages = listOf(MessageView("u1", "user", "Hello!", 1_700_000_000uL, MessageStatus.SENT)),
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
    val resolvedTitle = when {
        title.isNotEmpty() -> title
        sessionId != null -> {
            val prefix = sessionId.take(8)
            if (sessionId.length > prefix.length) "$prefix…" else prefix
        }
        else -> "Chat"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = resolvedTitle,
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
