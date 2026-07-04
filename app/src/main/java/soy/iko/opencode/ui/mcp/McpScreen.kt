package soy.iko.opencode.ui.mcp

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            val text = when (msg) {
                McpMessage.ADDED -> addedMsg
                McpMessage.ADD_FAILED -> addFailedMsg
                McpMessage.NOT_CONNECTED -> notConnectedMsg
            }
            snackbar.showSnackbar(text)
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }

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
            // established — adding requires an active server.
            if (state !is McpViewModel.State.Disconnected) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.mcp_add))
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ConnectionBannerFor(container)
                val loadingLabel = stringResource(R.string.loading)
                when (val s = state) {
                    is McpViewModel.State.Loading -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            Modifier.semantics { contentDescription = loadingLabel },
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(loadingLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is McpViewModel.State.Disconnected -> EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.not_connected),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    is McpViewModel.State.Error -> EmptyState(
                        icon = Icons.Filled.ErrorOutline,
                        title = stringResource(R.string.mcp_failed),
                        description = s.message,
                        modifier = Modifier.align(Alignment.Center),
                        actionLabel = stringResource(R.string.retry),
                        onAction = { vm.load() },
                    )
                    is McpViewModel.State.Ready ->
                        if (s.servers.isEmpty()) {
                            EmptyState(
                                icon = Icons.Filled.Hub,
                                title = stringResource(R.string.mcp_empty),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(s.servers, key = { it.name }) { McpServerCard(it) }
                            }
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
                // Close immediately; the snackbar reports the outcome and a successful add
                // triggers a reload so the new server appears in the list.
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun McpServerCard(server: McpServerInfo) {
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
