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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.di.AppContainer
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
    val models by vm.models.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val pendingPermission by vm.pendingPermission.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val selectedAgent by vm.selectedAgent.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val failedDraft by vm.failedDraft.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showCommandPicker by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // Track whether the list is pinned to the bottom so streaming auto-scroll
    // doesn't fight a user who scrolled up to read earlier output.
    val isPinnedToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= messages.lastIndex || messages.isEmpty()
        }
    }

    // Keep the newest content in view as parts stream in — only if already at the bottom.
    LaunchedEffect(messages.size, messages.lastOrNull()?.parts?.size) {
        if (messages.isNotEmpty() && isPinnedToBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        val result = if (failedDraft != null) {
            snackbar.showSnackbar(message = msg, actionLabel = "Retry")
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
                    Column(modifier = Modifier.clickable(enabled = models.isNotEmpty()) { showModelPicker = true }) {
                        Text(sessionTitle ?: "Session")
                        Text(
                            buildString {
                                append(selectedModel?.modelLabel ?: "default model")
                                selectedAgent?.let { append("  •  $it") }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCommandPicker = true }, enabled = commands.isNotEmpty()) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Commands")
                    }
                    IconButton(onClick = { showAgentPicker = true }, enabled = agents.isNotEmpty()) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "Choose agent")
                    }
                    IconButton(onClick = { showModelPicker = true }, enabled = models.isNotEmpty()) {
                        Icon(Icons.Filled.Tune, contentDescription = "Choose model")
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
                Text(
                    "Not connected.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                ConnectionBanner(
                    state = connectionState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.info.id }) { message ->
                        MessageBubble(message, isRunning = running)
                    }
                    if (running) {
                        item(key = "__typing") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("  working…", style = MaterialTheme.typography.labelMedium)
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
                        icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                        text = { Text("Latest") },
                        modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Stop and exit?") },
            text = { Text("The agent is still working. Exit anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.abort()
                    onBack()
                }) { Text("Stop & exit") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Stay") }
            },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            options = models,
            selected = selectedModel,
            onSelect = { vm.selectModel(it) },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents,
            selected = selectedAgent,
            onSelect = { vm.selectAgent(it?.name) },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showCommandPicker) {
        CommandPickerSheet(
            commands = commands,
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
private fun ConnectionBanner(
    state: EventStreamClient.ConnectionState,
    modifier: Modifier = Modifier,
) {
    val text = when (state) {
        EventStreamClient.ConnectionState.Connecting -> "Connecting…"
        EventStreamClient.ConnectionState.Disconnected -> "Reconnecting…"
        EventStreamClient.ConnectionState.Connected -> null
    }
    if (text != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(
                    "  $text",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
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
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
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
                placeholder = { Text("Message opencode…") },
                enabled = enabled,
                maxLines = 6,
            )
            if (running) {
                IconButton(onClick = onAbort, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
