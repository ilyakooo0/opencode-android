package soy.iko.opencode.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.toImageContext
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
) {
    val vm: ChatViewModel = viewModel(factory = vmFactory { ChatViewModel(container, sessionId) })
    val messages by vm.messages.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelsLoading by vm.modelsLoading.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val pendingPermission by vm.pendingPermission.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val agentsLoading by vm.agentsLoading.collectAsStateWithLifecycle()
    val selectedAgent by vm.selectedAgent.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val commandsLoading by vm.commandsLoading.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val failedDraft by vm.failedDraft.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shareContext = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    val defaultShareSubject = stringResource(R.string.share_subject)

    // Connection profile (with auth) for rendering inline image attachments.
    // Collect as state so a server switch recomposes the image context.
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val imageContext = remember(activeConnection?.profile) {
        activeConnection?.profile?.toImageContext()
    }

    // Mark this session as read while the user is viewing it; clear on leave so new
    // background activity can badge it again.
    DisposableEffect(sessionId) {
        container.setCurrentSession(sessionId)
        onDispose { if (container.currentSession.value == sessionId) container.setCurrentSession(null) }
    }

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    var showCommandPicker by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }

    // Keep the screen awake and hold a foreground priority while the agent is working,
    // so backgrounding mid-run doesn't let Doze choke the SSE stream.
    val currentView = LocalView.current
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(running) {
        currentView.keepScreenOn = running
        if (running) soy.iko.opencode.notification.RunForegroundService.start(appContext)
        onDispose {
            currentView.keepScreenOn = false
            soy.iko.opencode.notification.RunForegroundService.stop(appContext)
        }
    }

    // Track whether the list is pinned to the bottom so streaming auto-scroll
    // doesn't fight a user who scrolled up to read earlier output.
    val isPinnedToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= messages.lastIndex || messages.isEmpty()
        }
    }

    // Jump to the latest message when the conversation first loads so the user
    // lands on recent context, not the oldest.
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    // Keep the newest content in view as parts stream in — only if already at the bottom.
    // Include the growing text length so we follow streaming within a single part, not
    // only when a brand-new message or part is added.
    val lastPartLen = messages.lastOrNull()?.parts?.lastOrNull()?.let { part ->
        when (part) {
            is TextPart -> part.text.length
            is ReasoningPart -> part.text.length
            else -> 0
        }
    } ?: 0
    LaunchedEffect(messages.size, messages.lastOrNull()?.parts?.size, lastPartLen) {
        if (messages.isNotEmpty() && isPinnedToBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        val result = if (failedDraft != null) {
            snackbar.showSnackbar(message = msg, actionLabel = retryLabel)
        } else {
            snackbar.showSnackbar(msg)
        }
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.retryFailed()
        vm.clearError()
    }

    fun doSend() {
        if (vm.send(draft)) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
    }

    BackHandler(enabled = running) { showExitConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier
                        .clickable(enabled = models.isNotEmpty()) { showModelPicker = true }
                        .semantics { role = Role.Button }
                    ) {
                        Text(sessionTitle ?: stringResource(R.string.session))
                        Text(
                            buildString {
                                append(selectedModel?.modelLabel ?: stringResource(R.string.default_model))
                                selectedAgent?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showExitConfirm = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val md = buildConversationMarkdown(messages, sessionTitle)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, sessionTitle ?: defaultShareSubject)
                                putExtra(android.content.Intent.EXTRA_TEXT, md)
                            }
                            runCatching { shareContext.startActivity(android.content.Intent.createChooser(send, shareLabel)) }
                        },
                        enabled = messages.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_conversation))
                    }
                    IconButton(onClick = { showCommandPicker = true }, enabled = commands.isNotEmpty()) {
                        Icon(Icons.Filled.Terminal, contentDescription = stringResource(R.string.commands))
                    }
                    IconButton(onClick = { showAgentPicker = true }, enabled = agents.isNotEmpty()) {
                        Icon(Icons.Filled.SmartToy, contentDescription = stringResource(R.string.choose_agent))
                    }
                    IconButton(onClick = { showModelPicker = true }, enabled = models.isNotEmpty()) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.choose_model))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ChatInputBar(
                value = draft,
                onValueChange = vm::updateDraft,
                running = running,
                enabled = vm.connected,
                onSend = ::doSend,
                onAbort = { vm.abort() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!vm.connected) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.not_connected),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { vm.reconnect() }) {
                        Text(stringResource(R.string.reconnect))
                    }
                }
            } else {
                ConnectionBanner(
                    state = connectionState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (loading && messages.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (messages.isEmpty() && !running) {
                    EmptyConversation(
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.info.id }, contentType = { it.info::class }) { message ->
                        MessageBubble(message, isRunning = running, imageContext = imageContext)
                    }
                    if (running) {
                        item(key = "__typing") {
                            val workingText = stringResource(R.string.working)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.semantics {
                                    contentDescription = workingText
                                },
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(workingText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                // Jump-to-latest affordance when the user has scrolled away during a stream.
                AnimatedVisibility(
                    visible = !isPinnedToBottom && messages.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (messages.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                            }
                        },
                        icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.latest)) },
                        text = { Text(stringResource(R.string.latest)) },
                        modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                    )
                }
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.stop_and_exit_title)) },
            text = { Text(stringResource(R.string.stop_and_exit_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.abort()
                    onBack()
                }) { Text(stringResource(R.string.stop_and_exit)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(R.string.stay)) }
            },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            options = models,
            selected = selectedModel,
            loading = modelsLoading,
            onSelect = { vm.selectModel(it) },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents,
            selected = selectedAgent,
            loading = agentsLoading,
            onSelect = { vm.selectAgent(it?.name) },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showCommandPicker) {
        CommandPickerSheet(
            commands = commands,
            loading = commandsLoading,
            onSelect = { vm.runCommand(it) },
            onDismiss = { showCommandPicker = false },
        )
    }

    pendingPermission?.let { permission ->
        PermissionDialog(
            permission = permission,
            onRespond = { response -> vm.respondPermission(permission, response) },
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            !event.isShiftPressed &&
                            enabled && value.isNotBlank()
                        ) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(stringResource(R.string.message_placeholder)) },
                enabled = enabled,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (enabled && value.isNotBlank()) onSend() }),
            )
            if (running) {
                IconButton(onClick = onAbort, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.empty_chat_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
