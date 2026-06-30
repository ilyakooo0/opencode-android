package soy.iko.opencode.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionReplied
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.defaultOption
import soy.iko.opencode.data.model.toOptions
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.repo.SessionRepository
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {

    private val connection get() = container.activeConnection.value

    val connected: Boolean get() = connection != null

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val messages: StateFlow<List<MessageWithParts>> =
        container.activeConnection
            .flatMapLatest { conn ->
                conn?.repository?.observeMessages(sessionId) ?: flowOf(emptyList())
            }
            .onEach { _loading.value = false }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _modelsLoading = MutableStateFlow(true)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)
    val selectedModel: StateFlow<ModelOption?> = _selectedModel.asStateFlow()

    val connectionState: StateFlow<EventStreamClient.ConnectionState> =
        container.activeConnection
            .flatMapLatest { it?.events?.state ?: flowOf(EventStreamClient.ConnectionState.Disconnected) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EventStreamClient.ConnectionState.Disconnected,
            )

    private val _pendingPermission = MutableStateFlow<Permission?>(null)
    val pendingPermission: StateFlow<Permission?> = _pendingPermission.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _agentsLoading = MutableStateFlow(true)
    val agentsLoading: StateFlow<Boolean> = _agentsLoading.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    private val _commands = MutableStateFlow<List<Command>>(emptyList())
    val commands: StateFlow<List<Command>> = _commands.asStateFlow()

    private val _commandsLoading = MutableStateFlow(true)
    val commandsLoading: StateFlow<Boolean> = _commandsLoading.asStateFlow()

    private val _sessionTitle = MutableStateFlow<String?>(null)
    val sessionTitle: StateFlow<String?> = _sessionTitle.asStateFlow()

    /** The text of the last send that failed, surfaced so the UI can offer a retry. */
    private val _failedDraft = MutableStateFlow<String?>(null)
    val failedDraft: StateFlow<String?> = _failedDraft.asStateFlow()

    /** Per-session draft, persisted so it survives navigation/process death. */
    private val _draft = MutableStateFlow(container.draftStore.get(sessionId))
    val draft: StateFlow<String> = _draft.asStateFlow()

    init {
        // DraftStore loads SharedPreferences asynchronously; on a cold start the
        // synchronous get() above returns "" until the background load completes.
        // Observe the ready signal and re-seed the draft so it appears in the UI.
        viewModelScope.launch {
            container.draftStore.ready.collect { ready ->
                if (ready && _draft.value.isEmpty()) {
                    _draft.value = container.draftStore.get(sessionId)
                }
            }
        }
    }

    fun clearError() { _error.value = null }

    fun selectModel(option: ModelOption) { _selectedModel.value = option }

    fun selectAgent(name: String?) { _selectedAgent.value = name }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    init {
        // Debounce draft persistence so we don't write to disk on every keystroke.
        viewModelScope.launch {
            _draft.drop(1).debounce(500).collect { text ->
                container.draftStore.set(sessionId, text)
            }
        }
        // Reset per-connection state when the active server changes so stale spinners,
        // permission dialogs, errors, and agent selections from the old server don't
        // persist into the new one.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                _running.value = false
                _pendingPermission.value = null
                _error.value = null
                _failedDraft.value = null
                _selectedAgent.value = null
                conn.events.events.collect { event ->
                    if (SessionRepository.isIdle(event, sessionId)) _running.value = false
                    if (SessionRepository.isError(event, sessionId)) {
                        _running.value = false
                        _error.value = container.string(R.string.error_agent_reported)
                    }
                    when (event) {
                        is PermissionUpdated ->
                            if (event.properties.sessionID == sessionId) _pendingPermission.value = event.properties
                        is PermissionReplied ->
                            if (event.properties.sessionID == sessionId && event.properties.permissionID == _pendingPermission.value?.id) _pendingPermission.value = null
                        is SessionUpdated ->
                            if (event.properties.info.id == sessionId) _sessionTitle.value = event.properties.info.displayTitle
                        is SessionDeleted ->
                            if (event.properties.info?.id == sessionId || event.properties.sessionID == sessionId) {
                                _sessionTitle.value = event.properties.info?.displayTitle
                            }
                        else -> {}
                    }
                }
            }
        }
        // Load the model catalog; preselect the server default. Failure is non-fatal —
        // sending with no model just uses the server's default agent/model.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) { _models.value = emptyList(); _selectedModel.value = null; _modelsLoading.value = false; return@collectLatest }
                _modelsLoading.value = true
                runCatchingCancellable { conn.api.providers() }
                    .onFailure { Log.w("ChatViewModel", "Failed to load model catalog", it) }
                    .getOrNull()?.let { resp ->
                    val options = resp.toOptions()
                    _models.value = options
                    _selectedModel.value = resp.defaultOption(options)
                }
                _modelsLoading.value = false
            }
        }
        // Load the agent catalog (non-fatal).
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) { _agents.value = emptyList(); _agentsLoading.value = false; return@collectLatest }
                _agentsLoading.value = true
                runCatchingCancellable { conn.api.agents() }
                    .onFailure { Log.w("ChatViewModel", "Failed to load agent catalog", it) }
                    .getOrNull()?.let { _agents.value = it }
                _agentsLoading.value = false
            }
        }
        // Load the command catalog (non-fatal).
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) { _commands.value = emptyList(); _commandsLoading.value = false; return@collectLatest }
                _commandsLoading.value = true
                runCatchingCancellable { conn.api.commands() }
                    .onFailure { Log.w("ChatViewModel", "Failed to load command catalog", it) }
                    .getOrNull()?.let { _commands.value = it }
                _commandsLoading.value = false
            }
        }
        // Resolve the human-readable session title for the app bar (non-fatal).
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                runCatchingCancellable { conn.repository.listSessions() }
                    .getOrNull()
                    ?.firstOrNull { it.id == sessionId }
                    ?.let { _sessionTitle.value = it.displayTitle }
            }
        }
        // If the SSE stream drops mid-run, the run indicator would spin forever;
        // reset it so the UI doesn't look stuck while the banner shows "Reconnecting…".
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                conn.events.state.collect { state ->
                    if ((state == EventStreamClient.ConnectionState.Disconnected ||
                         state == EventStreamClient.ConnectionState.Failed) && _running.value) {
                        _running.value = false
                    }
                }
            }
        }
    }

    /** Sends [text]; returns true on success so the caller can clear the draft only then. */
    fun send(text: String): Boolean {
        val conn = connection ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_running.compareAndSet(false, true)) return false
        _error.value = null
        _failedDraft.value = null
        // Clear the in-memory draft for the UI immediately, but don't persist the clear
        // yet — if the send fails and the process dies before we restore, the draft
        // would be lost forever. The persisted draft is cleared only on success.
        _draft.value = ""
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.sendPrompt(
                    sessionId,
                    trimmed,
                    model = _selectedModel.value?.ref,
                    agent = _selectedAgent.value,
                )
            }.onFailure {
                _failedDraft.value = trimmed
                // Only restore the draft if the user hasn't typed anything new since.
                if (_draft.value.isBlank()) updateDraft(trimmed)
                _error.value = container.friendlyError(it)
            }.onSuccess {
                // Send succeeded — now it's safe to persist the empty draft.
                container.draftStore.set(sessionId, "")
            }
            _running.value = false
        }
        return true
    }

    /** Re-send the last draft whose send failed, if any. */
    fun retryFailed() {
        val draft = _failedDraft.value ?: return
        _failedDraft.value = null
        send(draft)
    }

    /** Invoke a slash-command by name via the server's /command endpoint. */
    fun runCommand(command: Command) {
        val conn = connection ?: return
        if (!_running.compareAndSet(false, true)) return
        _error.value = null
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.runCommand(sessionId, command.name, agent = command.agent)
            }.onFailure { _error.value = container.friendlyError(it) }
            _running.value = false
        }
    }

    fun abort() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.repository.abort(sessionId) }
                .onSuccess { _running.value = false }
                .onFailure { _error.value = container.friendlyError(it) }
        }
    }

    /** Reconnect to the most recently used server profile (used when the connection is gone). */
    fun reconnect() {
        if (container.activeConnection.value != null) return
        viewModelScope.launch {
            _loading.value = true
            val recent = runCatching { container.profileStore.profiles.first() }
                .getOrDefault(emptyList())
                .firstOrNull() ?: run { _loading.value = false; return@launch }
            runCatchingCancellable {
                val conn = container.connect(recent)
                conn.api.ping()
            }.onSuccess {
                _loading.value = false
            }.onFailure {
                container.disconnect()
                _error.value = container.friendlyError(it)
                _loading.value = false
            }
        }
    }

    fun respondPermission(permission: Permission, response: PermissionResponse) {
        val conn = connection ?: return
        // Clear optimistically; permission.replied will confirm. If the call fails we
        // restore the pending permission so the user can retry instead of being stuck
        // with a dismissed dialog and a paused tool run.
        _pendingPermission.value = null
        viewModelScope.launch {
            runCatchingCancellable { conn.api.respondPermission(sessionId, permission.id, response) }
                .onFailure {
                    _error.value = container.friendlyError(it)
                    // Only restore if no new permission has arrived in the meantime.
                    if (_pendingPermission.value == null) _pendingPermission.value = permission
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush any pending debounced draft so it survives navigation. Use a synchronous
        // commit instead of runBlocking so the main thread isn't held hostage by disk I/O.
        val pending = _draft.value
        if (pending.isNotEmpty()) {
            container.draftStore.flushDraft(sessionId, pending)
        }
    }
}
