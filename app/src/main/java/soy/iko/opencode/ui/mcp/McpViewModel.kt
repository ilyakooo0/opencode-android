package soy.iko.opencode.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable

/** The shape of an MCP server the user is registering from the Add dialog. */
enum class McpKind { LOCAL, REMOTE }

/** User-facing outcomes of an Add-server attempt; the screen maps these to localized strings. */
enum class McpMessage { ADDED, ADD_FAILED, NOT_CONNECTED }

/** Loads the configured MCP servers from the active connection's `config`, enriched with live
 *  status from `GET /mcp`, and supports dynamically registering a new server via `POST /mcp`. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Disconnected : State
        data object Error : State
        data class Ready(val servers: List<McpServerInfo>) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while a reload runs on top of already-loaded data, so the list stays visible and the
     *  pull-to-refresh spinner (not the full-screen one) shows. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** True while an Add-server POST is in flight, so the dialog can show a spinner and block a
     *  second concurrent submit. */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /** One-shot user-facing messages (add success/failure) for the snackbar host. */
    private val _messages = MutableSharedFlow<McpMessage>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<McpMessage> = _messages.asSharedFlow()

    /** Bumped by [load] (refresh/retry) to re-run without waiting for a connection change. */
    private val _reload = MutableStateFlow(0)

    init {
        // Observe the active connection so the servers load (or reload) once a connection is
        // available — including when the screen opens during a reconnect window where
        // activeConnection.value is momentarily null (else it would latch Disconnected until a
        // manual refresh). collectLatest cancels an in-flight load when the connection changes or
        // a manual reload arrives.
        viewModelScope.launch {
            merge(container.activeConnection, _reload).collectLatest { doLoad() }
        }
    }

    fun load() { _reload.value++ }

    private suspend fun doLoad() {
        val conn = container.activeConnection.value ?: run {
            _state.value = State.Disconnected
            return
        }
        // Keep already-loaded servers on screen during a reload; only show the full-screen
        // spinner on the very first load.
        if (_state.value is State.Ready) _refreshing.value = true else _state.value = State.Loading
        runCatchingCancellable {
            val config = conn.api.config()
            // Fetch live status separately so a missing/unstable /mcp endpoint (older server
            // builds, transient error) never blocks the config-driven view — it just omits the
            // connected/error indicators for that load.
            val status = runCatchingCancellable { conn.api.mcpStatus() }.getOrNull()
            withContext(Dispatchers.Default) {
                mergeMcpStatus(parseMcpServers(config), status)
            }
        }
            .onSuccess { _state.value = State.Ready(it) }
            .onFailure { _state.value = State.Error }
        _refreshing.value = false
    }

    /** Register a new MCP server via `POST /mcp`. Builds the server config object from the
     *  kind/target the user entered. A name collision is left for the server to reject (it
     *  overwrites/validates server-side); the result surfaces as a snackbar. */
    fun addServer(name: String, kind: McpKind, target: String, env: String) {
        if (_adding.value) return
        val conn = container.activeConnection.value ?: run {
            viewModelScope.launch { _messages.tryEmit(McpMessage.NOT_CONNECTED) }
            return
        }
        val cleanName = name.trim()
        val cleanTarget = target.trim()
        if (cleanName.isEmpty() || cleanTarget.isEmpty()) return
        val config = buildMcpConfig(kind, cleanTarget, env)
        viewModelScope.launch {
            _adding.value = true
            val ok = runCatchingCancellable { conn.api.addMcp(cleanName, config) }.isSuccess
            _adding.value = false
            if (ok) {
                _messages.tryEmit(McpMessage.ADDED)
                load()
            } else {
                _messages.tryEmit(McpMessage.ADD_FAILED)
            }
        }
    }
}

/** Build a `config.mcp`-shaped object for a new server. Local servers carry a `command` token
 *  array; remote servers carry a `url`. Env vars (one KEY=VALUE per line) are parsed tolerantly. */
private fun buildMcpConfig(kind: McpKind, target: String, env: String): JsonObject = buildJsonObject {
    when (kind) {
        McpKind.LOCAL -> {
            put("type", "local")
            putJsonArray("command") {
                // Split the command line into argv the way a shell roughly would: on whitespace.
                // This intentionally doesn't handle quoting — MCP command fields are normally a
                // simple program + args, and over-engineering a parser here would surprise more
                // than it helps. Users needing quotes can edit config.mcp directly.
                target.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(it) }
            }
        }
        McpKind.REMOTE -> {
            put("type", "remote")
            put("url", target)
        }
    }
    val envPairs = env.lineSequence()
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
        .filter { it.first.isNotEmpty() }
        .toList()
    if (envPairs.isNotEmpty()) {
        putJsonObject("env") { envPairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
    }
}
