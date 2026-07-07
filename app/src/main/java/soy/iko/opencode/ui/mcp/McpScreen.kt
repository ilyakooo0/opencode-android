package soy.iko.opencode.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.AppTopBar
import soy.iko.opencode.ui.components.ConnectionBannerFor
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.SkeletonRow
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: McpViewModel = viewModel(factory = vmFactory { McpViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val addedMsg = stringResource(R.string.mcp_added)
    val addFailedMsg = stringResource(R.string.mcp_add_failed)
    val notConnectedMsg = stringResource(R.string.not_connected)

    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            val text = when (msg) {
                McpMessage.ADDED -> addedMsg
                McpMessage.ADD_FAILED -> addFailedMsg
                McpMessage.NOT_CONNECTED -> notConnectedMsg
            }
            // Dismiss the Add dialog only on success (the reload makes the new server appear).
            // On failure keep it open with adding=false so the user can adjust and retry without
            // re-typing their input — the dialog's own spinner/disabled-state machinery was
            // designed for this; dismissing synchronously after the (async) POST would lose the
            // entered name/command/URL/env on every transient failure.
            if (msg == McpMessage.ADDED) showAddDialog = false
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.mcp_servers),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Register a new MCP server dynamically (POST /mcp). Hidden until a connection is
            // established — adding requires an active server. Extended FAB (icon + label) matches
            // the session list ("New session") and server list ("Add server") so a first-time
            // user isn't left guessing what the + does.
            if (state !is McpViewModel.State.Disconnected) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.mcp_add_server)) },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load() },
            modifier = Modifier.fillMaxSize().imePadding().padding(padding),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().widthIn(max = 600.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConnectionBannerFor(container)
                // Crossfade between content states so transitions read as a smooth fade instead
                // of an instant snap. Matches the session list's Crossfade pattern; reduced
                // motion is honored by Crossfade's default spec. The content lambda branches
                // on its target-state parameter (not the captured `state`) so the outgoing
                // layer keeps rendering the OLD state type while it fades out — reading `state`
                // directly would recompose both layers to the latest content and defeat the
                // crossfade into an instant snap.
                //
                // Include the Error message in the key so a transition between two distinct
                // errors (e.g. "not connected" -> "auth failed") crossfades instead of snapping:
                // the class-name-only key stays constant across Error variants, so Crossfade
                // treats them as the same target and doesn't animate. The other states carry no
                // data, so their class name alone is a stable key.
                val stateKey = state.let { s ->
                    when (s) {
                        is McpViewModel.State.Error -> "Error:${s.message}"
                        else -> s::class.simpleName
                    }
                }
                    Crossfade(
                        targetState = stateKey,
                        animationSpec = tween(soy.iko.opencode.data.network.NetworkConfig.motionFadeDurationMs.toInt()),
                        label = "mcp_state",
                    ) { key ->
                    when (key) {
                    "Loading" -> Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(6) { SkeletonRow() }
                    }
                    "Disconnected" -> EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.not_connected),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    "Ready" ->
                        (state as? McpViewModel.State.Ready)?.let { s ->
                            if (s.servers.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Filled.Hub,
                                    title = stringResource(R.string.mcp_empty),
                                    modifier = Modifier.align(Alignment.Center),
                                    actionIcon = Icons.Filled.Add,
                                    actionLabel = stringResource(R.string.mcp_add_server),
                                    onAction = { showAddDialog = true },
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(
                                        start = 16.dp, end = 16.dp, top = 16.dp, bottom = soy.iko.opencode.data.network.NetworkConfig.listFabInsetDp.dp,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(s.servers, key = { it.name }) {
                                        McpServerCard(it, modifier = Modifier.then(reducedMotionAnimateItem()))
                                    }
                                }
                            }
                        }
                    else -> if (key?.startsWith("Error") == true) EmptyState(
                        icon = Icons.Filled.ErrorOutline,
                        title = stringResource(R.string.mcp_failed),
                        description = (state as? McpViewModel.State.Error)?.message,
                        modifier = Modifier.align(Alignment.Center),
                        actionLabel = stringResource(R.string.retry),
                        onAction = { vm.load() },
                    )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpDialog(
            adding = adding,
            onDismiss = { if (!adding) showAddDialog = false },
            onAdd = { name, kind, target, env ->
                vm.addServer(name, kind, target, env)
                // Don't dismiss synchronously: addServer launches a coroutine, so the POST is
                // still in flight. The dialog stays open showing the spinner (adding=true);
                // the messages collector above dismisses on ADDED, or re-enables the fields
                // on ADD_FAILED so the user can retry without re-typing.
            },
        )
    }
}

@Composable
private fun McpServerCard(server: McpServerInfo, modifier: Modifier = Modifier) {
    val remote = server.type.equals("remote", ignoreCase = true)
    val connected = server.connected
    // Build a single merged content description so TalkBack reads the card as one node
    // (type, name, target, enabled, status) instead of ~5 separate stops.
    val typeLabel = stringResource(if (remote) R.string.mcp_remote else R.string.mcp_local)
    val stateLabel = if (server.enabled) {
        stringResource(R.string.mcp_enabled)
    } else {
        stringResource(R.string.mcp_disabled)
    }
    val statusLabel = when {
        server.error != null && connected != true -> server.error
        connected == true -> stringResource(R.string.mcp_connected)
        connected == false -> stringResource(R.string.mcp_disconnected)
        else -> null
    }
    val cardDesc = buildString {
        append(typeLabel).append(", ")
        append(server.name)
        server.target?.let { append(", ").append(it) }
        append(", ").append(stateLabel)
        statusLabel?.let { append(", ").append(it) }
    }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mcp_card")
            .semantics(mergeDescendants = true) { contentDescription = cardDesc },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (remote) Icons.Filled.CloudQueue else Icons.Filled.Computer,
                    contentDescription = typeLabel,
                    tint = if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(server.name, style = MaterialTheme.typography.titleSmall)
                        // Small type label next to the name so a user can tell local from
                        // remote without recognizing the cloud/computer icon.
                        Text(
                            typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    server.target?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (server.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A "Remove" item would go here once the opencode server exposes DELETE /mcp
                // (or a config PATCH to drop an entry). Only GET and POST /mcp exist today, so
                // removing a server isn't reachable from the client; the mcp_remove* strings are
                // already declared for when it is.
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.more_options_for, server.name),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mcp_view_tools)) },
                            onClick = { menuExpanded = false; showTools = true },
                        )
                    }
                }
            }
            // Live status row: connected (with tool count) / error / unknown. Only shown when the
            // server reported a runtime state via /mcp, so a static config-only view isn't
            // cluttered with "unknown" chips.
            if (connected != null || server.error != null) {
                Spacer(Modifier.size(10.dp))
                McpStatusRow(server = server, connected = connected)
            }
        }
    }
    if (showTools) {
        McpToolsDialog(server = server, onDismiss = { showTools = false })
    }
}

@Composable
private fun McpToolsDialog(server: McpServerInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_view_tools)) },
        text = {
            val tools = server.tools
            if (tools.isNullOrEmpty()) {
                Text(stringResource(R.string.mcp_no_tools))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.mcp_tools_count, tools.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    tools.forEach { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** Live status row for an MCP server card: connected (with tool count) / error / disconnected.
 *  Extracted from McpServerCard to keep its complexity under detekt's threshold. */
@Composable
private fun McpStatusRow(server: McpServerInfo, connected: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (server.error != null && connected != true) {
            Icon(
                Icons.Filled.Error,
                contentDescription = stringResource(R.string.mcp_failed_state),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                server.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
        } else if (connected == true) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.mcp_connected),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            val connectedLabel = server.toolCount?.let {
                stringResource(R.string.mcp_connected) + " · " +
                    stringResource(R.string.mcp_tools_count, it)
            } ?: stringResource(R.string.mcp_connected)
            Text(
                connectedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        } else {
            Text(
                stringResource(R.string.mcp_disconnected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp),
            )
        }
    }
}

/** Dialog collecting the bits needed to register an MCP server via POST /mcp: a name, whether
 *  it's a local command or a remote URL, the command line / URL, and optional env vars. */
@Composable
private fun AddMcpDialog(
    adding: Boolean,
    onDismiss: () -> Unit,
    onAdd: (name: String, kind: McpKind, target: String, env: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(McpKind.LOCAL) }
    var target by rememberSaveable { mutableStateOf("") }
    var env by rememberSaveable { mutableStateOf("") }
    val nameValid = name.trim().isNotEmpty()
    val targetValid = target.trim().isNotEmpty()
    val canAdd = !adding && nameValid && targetValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_add)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    label = { Text(stringResource(R.string.mcp_add_name)) },
                    singleLine = true,
                    enabled = !adding,
                    isError = !nameValid,
                    supportingText = if (!nameValid) {
                        { Text(stringResource(R.string.mcp_add_name_required)) }
                    } else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == McpKind.LOCAL,
                        onClick = { kind = McpKind.LOCAL },
                        label = { Text(stringResource(R.string.mcp_kind_local)) },
                        enabled = !adding,
                    )
                    FilterChip(
                        selected = kind == McpKind.REMOTE,
                        onClick = { kind = McpKind.REMOTE },
                        label = { Text(stringResource(R.string.mcp_kind_remote)) },
                        enabled = !adding,
                    )
                }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = if (kind == McpKind.LOCAL) 4.dp else 12.dp),
                    label = { Text(if (kind == McpKind.LOCAL) stringResource(R.string.mcp_add_command) else stringResource(R.string.mcp_add_url)) },
                    placeholder = {
                        Text(if (kind == McpKind.LOCAL) stringResource(R.string.mcp_add_command_hint) else stringResource(R.string.mcp_add_url_hint))
                    },
                    singleLine = true,
                    enabled = !adding,
                    isError = !targetValid,
                    supportingText = if (!targetValid) {
                        { Text(stringResource(R.string.mcp_add_target_required)) }
                    } else null,
                )
                // Warn that the local command is split on whitespace and quoted args aren't
                // supported, so a user entering `"my program" --arg` isn't surprised when it's
                // split into 4 tokens. Directs them to config.mcp for complex commands.
                if (kind == McpKind.LOCAL) {
                    Text(
                        stringResource(R.string.mcp_command_split_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.mcp_add_env)) },
                    placeholder = { Text(stringResource(R.string.mcp_add_env_hint)) },
                    supportingText = { Text(stringResource(R.string.mcp_env_hint)) },
                    enabled = !adding,
                    minLines = 2,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, kind, target, env) }, enabled = canAdd) {
                if (adding) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.mcp_add_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text(stringResource(R.string.cancel)) }
        },
    )
}
