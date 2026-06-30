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
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.repo.SessionRepository
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.util.safeExceptionSummary
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class ChatViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {

    private val connection get() = container.activeConnection.value

    val connected: Boolean get() = connection != null

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Separate from [loading]: tracks the manual reconnect() flow so its spinner
     *  doesn't conflict with the messages flow's loading state. */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** When true, the debounced draft collector skips persisting an empty draft so
     *  [send]'s deliberate non-persistence of the cleared draft isn't undone by the
     *  debounce timer firing before the send completes. */
    private val suppressDraftPersist = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Per-catalog reload triggers: incrementing one causes [observeCatalog]'s
     *  collectLatest to cancel any in-flight fetch and start a fresh one, so a
     *  manual reload supersedes a stale observeCatalog fetch. */
    private val _modelsReload = MutableStateFlow(0)
    private val _agentsReload = MutableStateFlow(0)
    private val _commandsReload = MutableStateFlow(0)

    val messages: StateFlow<List<MessageWithParts>> =
        container.activeConnection
            .flatMapLatest { conn ->
                conn?.repository?.observeMessages(sessionId) ?: flowOf(emptyList())
            }
            .onEach {
                _loading.value = false
            }
            .retryWhen { cause, _ ->
                _loading.value = false
                _errorEvents.tryEmit(container.friendlyError(cause))
                // Delay before retrying to avoid a tight loop on persistent errors.
                delay(NetworkConfig.retryInitialDelayMs)
                true
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = emptyList(),
            )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** One-shot error events surfaced as snackbars. A SharedFlow (not StateFlow) so each
     *  emission is delivered independently — two rapid failures both get a snackbar
     *  instead of the second silently overwriting the first. */
    private val _errorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _modelsLoading = MutableStateFlow(true)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _modelsError = MutableStateFlow(false)
    val modelsError: StateFlow<Boolean> = _modelsError.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelOption?>(null)
    val selectedModel: StateFlow<ModelOption?> = _selectedModel.asStateFlow()

    val connectionState: StateFlow<EventStreamClient.ConnectionState> =
        container.activeConnection
            .flatMapLatest { it?.events?.state ?: flowOf(EventStreamClient.ConnectionState.Disconnected) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = EventStreamClient.ConnectionState.Disconnected,
            )

    private val _pendingPermission = MutableStateFlow<Permission?>(null)
    val pendingPermission: StateFlow<Permission?> = _pendingPermission.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _agentsLoading = MutableStateFlow(true)
    val agentsLoading: StateFlow<Boolean> = _agentsLoading.asStateFlow()

    private val _agentsError = MutableStateFlow(false)
    val agentsError: StateFlow<Boolean> = _agentsError.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    private val _commands = MutableStateFlow<List<Command>>(emptyList())
    val commands: StateFlow<List<Command>> = _commands.asStateFlow()

    private val _commandsLoading = MutableStateFlow(true)
    val commandsLoading: StateFlow<Boolean> = _commandsLoading.asStateFlow()

    private val _commandsError = MutableStateFlow(false)
    val commandsError: StateFlow<Boolean> = _commandsError.asStateFlow()

    private val _sessionTitle = MutableStateFlow<String?>(null)
    val sessionTitle: StateFlow<String?> = _sessionTitle.asStateFlow()

    /** Set when the current session is deleted via SSE, so the UI can navigate away. */
    private val _sessionDeleted = MutableStateFlow(false)
    val sessionDeleted: StateFlow<Boolean> = _sessionDeleted.asStateFlow()

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

    fun selectModel(option: ModelOption) { _selectedModel.value = option }

    fun selectAgent(name: String?) { _selectedAgent.value = name }

    fun reloadModels() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateCache()
            _modelsReload.value++
        }
    }

    fun reloadAgents() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateCache()
            _agentsReload.value++
        }
    }

    fun reloadCommands() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateCache()
            _commandsReload.value++
        }
    }

    /**
     * Shared helper for init-block catalog observers: re-fetches whenever the active
     * connection or the catalog's reload trigger changes, invoking [onNull] to clear
     * state on null so stale data from the old server doesn't persist. The
     * [reloadTrigger] is merged with [activeConnection] so a manual reload (via
     * reloadModels/reloadAgents/reloadCommands) cancels any in-flight observe fetch
     * via collectLatest, preventing a stale observe result from overwriting a fresh
     * reload result. Failure is non-fatal — the error flag is surfaced to the UI.
     */
    private fun <T> observeCatalog(
        tag: String,
        loading: MutableStateFlow<Boolean>,
        error: MutableStateFlow<Boolean>,
        fetch: suspend (soy.iko.opencode.data.network.OpencodeApiClient) -> T,
        onSuccess: (T) -> Unit,
        onNull: () -> Unit,
        reloadTrigger: StateFlow<Int>,
    ) {
        viewModelScope.launch {
            merge(container.activeConnection, reloadTrigger).collectLatest { _ ->
                val conn = container.activeConnection.value
                if (conn == null) { onNull(); loading.value = false; error.value = false; return@collectLatest }
                loading.value = true
                error.value = false
                runCatchingCancellable { fetch(conn.api) }
                    // fetch() does HTTP via withRetry, so the failure can be a
                    // ClientRequestException whose message embeds the full request URL
                    // (may contain auth/paths). Log only a scrubbed summary.
                    .onFailure { Log.w("ChatViewModel", "Failed to load $tag: ${safeExceptionSummary(it)}"); error.value = true }
                    .getOrNull()?.let(onSuccess)
                loading.value = false
            }
        }
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    init {
        // Debounce draft persistence so we don't write to disk on every keystroke.
        // Skip persisting empty drafts that were set by send() (suppressed via
        // suppressDraftPersist) — send() clears the in-memory draft immediately for
        // UI feedback but deliberately doesn't persist the clear until the send
        // succeeds, so a failed send can restore the draft.
        viewModelScope.launch {
            _draft.drop(1).debounce(NetworkConfig.draftDebounceMs).collect { text ->
                if (text.isBlank() && suppressDraftPersist.get()) return@collect
                runCatchingCancellable { container.draftStore.set(sessionId, text) }
                    .onFailure { Log.w("ChatViewModel", "Failed to persist draft", it) }
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
                _failedDraft.value = null
                _selectedAgent.value = null
                _sessionTitle.value = null
                try {
                    conn.events.events.collect { event ->
                        if (SessionRepository.isIdle(event, sessionId)) _running.value = false
                        if (SessionRepository.isError(event, sessionId)) {
                            _running.value = false
                            _errorEvents.tryEmit(container.string(R.string.error_agent_reported))
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
                                    _sessionDeleted.value = true
                                }
                            else -> {}
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "SSE event collector error", e)
                }
            }
        }
        // Load the model catalog; preselect the server default. Failure is non-fatal —
        // sending with no model just uses the server's default agent/model.
        observeCatalog(
            tag = "model catalog",
            loading = _modelsLoading,
            error = _modelsError,
            fetch = { it.providers() },
            onSuccess = { resp ->
                val options = resp.toOptions()
                _models.value = options
                _selectedModel.value = resp.defaultOption(options)
            },
            onNull = { _models.value = emptyList(); _selectedModel.value = null },
            reloadTrigger = _modelsReload,
        )
        // Load the agent catalog (non-fatal).
        observeCatalog(
            tag = "agent catalog",
            loading = _agentsLoading,
            error = _agentsError,
            fetch = { it.agents() },
            onSuccess = { _agents.value = it },
            onNull = { _agents.value = emptyList() },
            reloadTrigger = _agentsReload,
        )
        // Load the command catalog (non-fatal).
        observeCatalog(
            tag = "command catalog",
            loading = _commandsLoading,
            error = _commandsError,
            fetch = { it.commands() },
            onSuccess = { _commands.value = it },
            onNull = { _commands.value = emptyList() },
            reloadTrigger = _commandsReload,
        )
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
                try {
                    conn.events.state.collect { state ->
                        if ((state == EventStreamClient.ConnectionState.Disconnected ||
                             state == EventStreamClient.ConnectionState.Failed) && _running.value) {
                            _running.value = false
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "SSE state collector error", e)
                }
            }
        }
    }

    /** Sends [text]; returns true on success so the caller can clear the draft only then. */
    fun send(text: String): Boolean {
        val conn = connection ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_running.compareAndSet(false, true)) return false
        _failedDraft.value = null
        // Clear the in-memory draft for the UI immediately, but don't persist the clear
        // yet — if the send fails and the process dies before we restore, the draft
        // would be lost forever. The persisted draft is cleared only on success.
        // suppressDraftPersist prevents the debounced collector from persisting the
        // empty draft before the send resolves.
        suppressDraftPersist.set(true)
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
                suppressDraftPersist.set(false)
                _failedDraft.value = trimmed
                // Only restore the draft if the user hasn't typed anything new since.
                if (_draft.value.isBlank()) updateDraft(trimmed)
                _errorEvents.tryEmit(container.friendlyError(it))
                _running.value = false
            }.onSuccess {
                suppressDraftPersist.set(false)
                // Send succeeded — now it's safe to persist the empty draft.
                container.draftStore.set(sessionId, "")
                // Don't reset _running here: the agent continues streaming via SSE.
                // _running is cleared on SessionIdle/SessionError (see event collector)
                // or when the SSE stream drops (see connection state watcher below).
            }
        }
        return true
    }

    /** Re-send the last draft whose send failed, if any. */
    fun retryFailed() {
        val draft = _failedDraft.value ?: return
        // Don't clear _failedDraft until send() accepts the text — if _running is
        // already true, send() returns false and the draft would be lost forever.
        if (send(draft)) {
            _failedDraft.value = null
        }
    }

    /** Invoke a slash-command by name via the server's /command endpoint. */
    fun runCommand(command: Command) {
        val conn = connection ?: return
        if (!_running.compareAndSet(false, true)) return
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.runCommand(sessionId, command.name, agent = command.agent)
            }.onFailure {
                _errorEvents.tryEmit(container.friendlyError(it))
                _running.value = false
            }
        }
    }

    fun abort() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.repository.abort(sessionId) }
                .onSuccess { _running.value = false }
                .onFailure { _errorEvents.tryEmit(container.friendlyError(it)) }
        }
    }

    /** Reconnect to the most recently used server profile (used when the connection is gone). */
    fun reconnect() {
        if (container.activeConnection.value != null) return
        // Guard against concurrent reconnect calls: the user can tap the button
        // multiple times while _reconnecting is true, which would launch parallel
        // connect() coroutines that race to set the active connection, leaking the
        // intermediate connections.
        if (!_reconnecting.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val recent = runCatchingCancellable { container.profileStore.profiles.first() }
                    .getOrDefault(emptyList())
                    .firstOrNull()
                if (recent == null) {
                    _errorEvents.tryEmit(container.string(R.string.no_servers_to_reconnect))
                    return@launch
                }
                runCatchingCancellable {
                    val conn = container.connect(recent)
                    conn.api.ping()
                }.onFailure {
                    container.disconnect()
                    _errorEvents.tryEmit(container.friendlyError(it))
                }
            } finally {
                _reconnecting.value = false
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
                    _errorEvents.tryEmit(container.friendlyError(it))
                    // Only restore if no new permission has arrived in the meantime.
                    if (_pendingPermission.value == null) _pendingPermission.value = permission
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush any pending debounced draft so it survives navigation. Uses an
        // asynchronous apply() (not a synchronous commit) so the main thread isn't
        // blocked on disk I/O — Android's SharedPreferences framework guarantees
        // pending apply() writes are flushed before the process exits.
        val pending = _draft.value
        if (pending.isNotEmpty()) {
            container.draftStore.flushDraft(sessionId, pending)
        }
    }
}
