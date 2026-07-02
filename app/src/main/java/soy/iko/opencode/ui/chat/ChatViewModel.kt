package soy.iko.opencode.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.FilePromptPart
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A user-facing error to surface as a snackbar. [retryable] is true only for a failed
 * message *send* (whose draft is held in [ChatViewModel.failedDraft]); the snackbar attaches
 * a Retry action solely for those so an unrelated error (e.g. a message-load failure) can't
 * inherit a Retry that silently re-submits the last prompt.
 */
data class ChatError(val message: String, val retryable: Boolean = false)

/**
 * An attachment staged to send with the next prompt. [previewModel] is a Coil-loadable model
 * for the thumbnail (the source content Uri, as a string) for images, or null for non-image
 * files (rendered with a generic icon). [part] is the wire form (a base64 data URL) sent to
 * the server.
 */
@androidx.compose.runtime.Immutable
data class PendingAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val previewModel: Any?,
    val part: FilePromptPart,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class ChatViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {

    private val connection get() = container.activeConnection.value

    val connected: Boolean get() = connection != null

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Set when the messages flow failed to load (and the list is empty), so the UI
     *  can render a distinct error state instead of masquerading as an empty
     *  conversation. Cleared on a successful non-empty emission. */
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    /** Tracks whether the messages flow has ever emitted a non-empty list, so
     *  [messages]' retryWhen can decide whether a re-fetch failure should set
     *  [loadError] (nothing shown yet → error screen) or just emit a snackbar
     *  (already showing messages → don't replace the conversation with an error). */
    private var hasShownMessages = false

    /** True once we've surfaced a snackbar for the current message-load failure streak.
     *  Reset on any successful emission so a persistent failure doesn't spam a fresh
     *  snackbar every retry cycle (every few seconds). */
    private var loadErrorSnackbarShown = false

    /** Separate from [loading]: tracks the manual reconnect() flow so its spinner
     *  doesn't conflict with the messages flow's loading state. */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** When true, the debounced draft collector skips persisting an empty draft so
     *  [send]'s deliberate non-persistence of the cleared draft isn't undone by the
     *  debounce timer firing before the send completes. */
    private val suppressDraftPersist = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The draft value this VM last persisted, so the drafts observer can tell its own
     *  (debounced, possibly stale) echo apart from a genuine external write. Without this
     *  a debounced persist of an older value could echo back and overwrite newer text the
     *  user has since typed. */
    @Volatile private var lastPersistedDraft: String? = null

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
                // A value flowed through: the stream recovered, so allow the next distinct
                // failure to surface a fresh snackbar again.
                loadErrorSnackbarShown = false
                if (it.isNotEmpty()) {
                    hasShownMessages = true
                    _loadError.value = false
                }
            }
            .retryWhen { cause, _ ->
                _loading.value = false
                // Only surface the persistent error state when there's nothing to
                // show — if we already have messages, a transient re-fetch failure
                // is better surfaced as a snackbar (via errorEvents) than by
                // replacing the visible conversation with an error screen.
                if (!hasShownMessages) _loadError.value = true
                // Surface the snackbar only once per failure streak — retryWhen loops
                // every retryInitialDelayMs, so emitting here unconditionally would spam a
                // new snackbar every few seconds on a persistent load failure.
                if (!loadErrorSnackbarShown) {
                    _errorEvents.trySend(ChatError(container.friendlyError(cause)))
                    loadErrorSnackbarShown = true
                }
                // Delay before retrying to avoid a tight loop on persistent errors.
                delay(NetworkConfig.retryInitialDelayMs)
                true
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = emptyList(),
            )

    /** Whether the conversation has any messages. Derived separately so the top bar
     *  (share button enabled state) can observe this cheap boolean instead of the
     *  full messages list, avoiding per-token recomposition of the app bar during
     *  streaming. */
    val hasMessages: StateFlow<Boolean> = messages
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = false,
        )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** True while an abort REST call is in flight, so the Stop button can show a
     *  spinner and prevent double-taps from firing a second abort. */
    private val _aborting = MutableStateFlow(false)
    val aborting: StateFlow<Boolean> = _aborting.asStateFlow()

    /** A follow-up the user typed while a run was active. send() queues it here and
     *  auto-sends once the run completes (SessionIdle), so the user isn't blocked with
     *  no way to send and no indication why the Send button is gone. */
    private val _queuedFollowUp = MutableStateFlow<String?>(null)
    val queuedFollowUp: StateFlow<String?> = _queuedFollowUp.asStateFlow()

    /** One-shot error events surfaced as snackbars. A Channel (not SharedFlow) so an event
     *  emitted before the UI subscribes is buffered and still delivered — a SharedFlow with
     *  replay=0 would drop it (e.g. a VM-init catalog fetch failing before first
     *  composition). Each event is delivered exactly once and not replayed to a
     *  re-subscribing collector, so rotation doesn't re-show a stale snackbar. */
    private val _errorEvents = Channel<ChatError>(
        capacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: Flow<ChatError> = _errorEvents.receiveAsFlow()

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

    /** Attachments staged for the next prompt (images/files). In-memory only — a huge base64
     *  payload isn't worth persisting across process death, and the source Uris wouldn't
     *  survive anyway. Cleared on a successful send. */
    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments.asStateFlow()

    /** True while a revert checkpoint is active for this session (messages after it are hidden
     *  server-side). Drives the "reverted" banner with its Undo. */
    private val _reverted = MutableStateFlow(false)
    val reverted: StateFlow<Boolean> = _reverted.asStateFlow()

    /** The active public share URL for this session, or null when not shared. */
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** One-shot events carrying a freshly-created share URL so the UI can copy/share it. */
    private val _shareLinkEvents = Channel<String>(
        capacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val shareLinkEvents: Flow<String> = _shareLinkEvents.receiveAsFlow()

    init {
        // DraftStore loads SharedPreferences asynchronously; on a cold start the
        // synchronous get() above returns "" until the background load completes.
        // Observe the ready signal and re-seed the draft so it appears in the UI.
        viewModelScope.launch {
            container.draftStore.ready.collect { ready ->
                if (!ready) return@collect
                if (_draft.value.isEmpty()) {
                    _draft.value = container.draftStore.get(sessionId)
                }
                // Recover a follow-up queued before process death (its in-memory StateFlow
                // is gone). If the input is now free, drop it back there for review/send and
                // consume the stored copy; otherwise re-queue it so it auto-sends when the
                // current run next idles. Guarded on == null so it never clobbers a
                // follow-up the user queued after opening this session.
                val savedFollowUp = container.draftStore.getFollowUp(sessionId)
                if (savedFollowUp.isNotEmpty() && _queuedFollowUp.value == null) {
                    if (_draft.value.isEmpty()) {
                        _draft.value = savedFollowUp
                        container.draftStore.flushFollowUp(sessionId, "")
                    } else {
                        _queuedFollowUp.value = savedFollowUp
                    }
                }
            }
        }
        // Observe the drafts store for external mutations to this session's draft
        // (e.g. a share injected via draftStore.set in two-pane mode, or setImmediate
        // before navigation in single-pane mode). Without this, a share injected into
        // an already-open session never appears in the input field — the init-time
        // read and the ready-only re-seed miss it when the draft is non-empty.
        // The VM's own debounced persist writes the same value _draft already holds,
        // so those updates are no-ops here (storeValue == _draft.value). The
        // suppressDraftPersist guard prevents a stale store value from clobbering
        // the deliberate draft clear during a send (the store still holds the
        // pre-send draft until the send resolves).
        viewModelScope.launch {
            container.draftStore.drafts.collect { drafts ->
                // Ignore emissions until the initial disk load completes: the load itself
                // emits the persisted snapshot, and applying it here would clobber text the
                // user typed during the async load (the `ready` observer above seeds an
                // empty draft). After ready, emissions are external writes.
                if (!container.draftStore.ready.value) return@collect
                val storeValue = drafts[sessionId].orEmpty()
                // Ignore this VM's own (debounced, possibly stale) persistence echo — only a
                // genuine external write (e.g. a two-pane share injection) should overwrite
                // the live draft.
                if (storeValue == lastPersistedDraft) return@collect
                if (storeValue != _draft.value && !suppressDraftPersist.get()) {
                    _draft.value = storeValue
                }
            }
        }
    }

    fun selectModel(option: ModelOption) { _selectedModel.value = option }

    fun selectAgent(name: String?) { _selectedAgent.value = name }

    fun reloadModels() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateProvidersCache()
            _modelsReload.value++
        }
    }

    fun reloadAgents() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateAgentsCache()
            _agentsReload.value++
        }
    }

    fun reloadCommands() {
        val conn = connection ?: return
        viewModelScope.launch {
            conn.api.invalidateCommandsCache()
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
                lastPersistedDraft = text
                runCatchingCancellable { container.draftStore.set(sessionId, text) }
                    .onFailure { Log.w("ChatViewModel", "Failed to persist draft", it) }
            }
        }
        // Reset per-connection state when the active server changes so stale spinners,
        // permission dialogs, errors, and agent selections from the old server don't
        // persist into the new one.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) {
                    // The connection dropped (e.g. disconnect() mid-run). No SSE stream will
                    // arrive to deliver SessionIdle/SessionError, so reset the run state here —
                    // otherwise the working spinner sticks on, ChatScreen keeps keepScreenOn +
                    // the RunForegroundService alive, and the Stop button no-ops (abort() early-
                    // returns with no connection) until a later reconnect happens to clear it.
                    _running.value = false
                    _aborting.value = false
                    _pendingPermission.value = null
                    return@collectLatest
                }
                _running.value = false
                _pendingPermission.value = null
                _failedDraft.value = null
                // NOTE: deliberately not clearing _queuedFollowUp here. It's session-scoped
                // user intent that is now persisted; wiping it on every (re)connect — which
                // this collector does, including transient SSE drops and the cold-start
                // restore path — would drop a legitimately queued/recovered follow-up.
                _selectedAgent.value = null
                _sessionTitle.value = null
                try {
                    conn.events.events.collect { event ->
                        if (SessionRepository.isIdle(event, sessionId)) {
                            _running.value = false
                            // Auto-send a queued follow-up once the previous run finishes,
                            // so the user's drafted-while-running message isn't lost.
                            val queued = _queuedFollowUp.value
                            if (queued != null) {
                                setQueuedFollowUp(null)
                                send(queued, includeAttachments = false)
                            }
                        }
                        if (SessionRepository.isError(event, sessionId)) {
                            _running.value = false
                            // The run errored, so the queued follow-up won't auto-send.
                            // Restore it to the input (if free) so the user's typed text
                            // isn't silently lost; otherwise just clear the queue.
                            val queued = _queuedFollowUp.value
                            if (!queued.isNullOrEmpty() && _draft.value.isEmpty()) {
                                _draft.value = queued
                            }
                            setQueuedFollowUp(null)
                            _errorEvents.trySend(ChatError(container.string(R.string.error_agent_reported)))
                        }
                        when (event) {
                            is PermissionUpdated ->
                                if (event.properties.sessionID == sessionId) _pendingPermission.value = event.properties
                            is PermissionReplied ->
                                if (event.properties.sessionID == sessionId && event.properties.permissionID == _pendingPermission.value?.id) _pendingPermission.value = null
                            is SessionUpdated ->
                                if (event.properties.info.id == sessionId) {
                                    val info = event.properties.info
                                    _sessionTitle.value = info.displayTitle
                                    _reverted.value = info.isReverted
                                    _shareUrl.value = info.share?.url?.takeIf { it.isNotBlank() }
                                }
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
                // Only (re)apply the server default when there's no valid current
                // selection. On re-fetch/reconnect/Retry the user's chosen model must
                // survive — keep it as long as it's still present in the refreshed list.
                val current = _selectedModel.value
                if (current == null || options.none { it.ref == current.ref }) {
                    _selectedModel.value = resp.defaultOption(options)
                }
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
        // Skips the REST call when the SSE event collector already delivered the title
        // (SessionUpdated fires shortly after connect), avoiding a redundant full
        // session-list download.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                if (_sessionTitle.value != null) return@collectLatest
                runCatchingCancellable { conn.repository.listSessions() }
                    .getOrNull()
                    ?.firstOrNull { it.id == sessionId }
                    ?.let { session ->
                        _sessionTitle.value = session.displayTitle
                        _reverted.value = session.isReverted
                        _shareUrl.value = session.share?.url?.takeIf { it.isNotBlank() }
                    }
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

    /** Sends [text] together with any staged attachments; returns true on success so the
     *  caller can clear the draft only then. */
    fun send(text: String): Boolean = send(text, includeAttachments = true)

    /**
     * Core send. [includeAttachments] is false for the internal auto-send paths (a queued
     * follow-up, a retry) so staged attachments aren't silently re-sent with unrelated text.
     */
    private fun send(text: String, includeAttachments: Boolean): Boolean {
        val conn = connection ?: return false
        val trimmed = text.trim()
        val attachments = if (includeAttachments) _attachments.value else emptyList()
        // An image-only prompt (attachments, no text) is valid; a blank prompt with nothing
        // attached is not.
        if ((trimmed.isEmpty() && attachments.isEmpty()) || !_running.compareAndSet(false, true)) return false
        _failedDraft.value = null
        // Clear the staged attachments optimistically so the composer empties immediately;
        // restore them if the send fails (mirrors the draft handling below).
        if (attachments.isNotEmpty()) _attachments.value = emptyList()
        // Clear the in-memory draft for the UI immediately, but don't persist the clear
        // yet — if the send fails and the process dies before we restore, the draft
        // would be lost forever. The persisted draft is cleared only on success.
        // suppressDraftPersist prevents the debounced collector from persisting the
        // empty draft before the send resolves.
        //
        // Only clear when the text being sent IS the current draft. send() is also
        // invoked for auto-sent queued follow-ups (SessionIdle) and retryFailed(), where
        // the user may have typed a NEW draft since — wiping it would lose that text.
        if (_draft.value.trim() == trimmed) {
            suppressDraftPersist.set(true)
            _draft.value = ""
        }
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.sendPrompt(
                    sessionId,
                    trimmed,
                    attachments = attachments.map { it.part },
                    model = _selectedModel.value?.ref,
                    agent = _selectedAgent.value,
                )
            }.onFailure {
                suppressDraftPersist.set(false)
                _failedDraft.value = trimmed
                // Only restore the draft if the user hasn't typed anything new since.
                if (_draft.value.isBlank()) updateDraft(trimmed)
                // Restore the staged attachments too, so a failed send doesn't lose them —
                // but only if the user hasn't staged new ones in the meantime.
                if (attachments.isNotEmpty() && _attachments.value.isEmpty()) _attachments.value = attachments
                // Retryable: this is the failed send whose prompt Retry re-submits.
                _errorEvents.trySend(ChatError(container.friendlyError(it), retryable = true))
                _running.value = false
            }.onSuccess {
                suppressDraftPersist.set(false)
                // Send succeeded — persist the cleared draft, but only if the user hasn't
                // typed a new one while the send was in flight. Otherwise clearing the
                // store echoes back through the draft observer and wipes the in-progress
                // text (data loss). Mirrors the guard on the failure path above.
                if (_draft.value.isBlank()) {
                    lastPersistedDraft = ""
                    container.draftStore.set(sessionId, "")
                }
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
        // Attachments aren't re-sent on retry (they were optimistically restored to the
        // composer on the original failure, so they'll ride the next manual send).
        if (send(draft, includeAttachments = false)) {
            _failedDraft.value = null
        }
    }

    /**
     * Queue [text] to be sent automatically when the current run finishes, or clear any
     * queued follow-up when [text] is blank. Used when the user taps Send while a run
     * is already active — the Send button is replaced by Stop during a run, but the
     * input field stays enabled, so a follow-up typed mid-run would otherwise have
     * nowhere to go.
     */
    fun queueFollowUp(text: String) {
        val trimmed = text.trim()
        setQueuedFollowUp(trimmed.takeIf { it.isNotEmpty() })
        // The text now lives in the queued chip, so clear the input field just as a
        // normal send would. No-op for the cancel case (blank text), which must leave
        // whatever the user has since typed untouched.
        if (trimmed.isNotEmpty()) _draft.value = ""
    }

    /** Set or clear the queued follow-up and mirror it to persistence, so a follow-up
     *  queued mid-run survives process death (recovered on the next open of this session).
     *  Passing null / blank clears both the in-memory value and the persisted copy. */
    private fun setQueuedFollowUp(text: String?) {
        _queuedFollowUp.value = text
        container.draftStore.flushFollowUp(sessionId, text.orEmpty())
    }

    /** Transient flag set by [refreshMessages] so the top-bar refresh icon can show
     *  a brief spinner as immediate tap feedback. The SSE reconnect triggered by
     *  refreshMessages may not visibly change [connectionState] (it's already
     *  Connected), so without this the tap appears to do nothing. Clears after a
     *  short delay or when the connection state next becomes Connected. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Manually re-fetch messages by forcing the SSE stream to reconnect, which
     *  triggers the repository's re-seed-from-REST logic. Gives the user a recovery
     *  path when the SSE stream silently drops and the auto-reconnect re-seed is slow
     *  or fails — a pull-to-refresh or top-bar refresh forces a fresh fetch under the
     *  existing generation-guarded merge. Also clears [loadError] optimistically and
     *  sets [refreshing] for immediate tap feedback. */
    fun refreshMessages() {
        val conn = connection ?: return
        _loadError.value = false
        _refreshing.value = true
        conn.events.triggerReconnect()
        viewModelScope.launch {
            delay(NetworkConfig.refreshFeedbackMs)
            _refreshing.value = false
        }
    }

    /** Invoke a slash-command by name via the server's /command endpoint. */
    fun runCommand(command: Command) {
        val conn = connection ?: return
        // A run is already in flight — surface feedback instead of silently dropping the
        // command (the picker stays openable during a run and dismisses on select).
        if (!_running.compareAndSet(false, true)) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.runCommand(sessionId, command.name, agent = command.agent)
            }.onFailure {
                _errorEvents.trySend(ChatError(container.friendlyError(it)))
                _running.value = false
            }
        }
    }

    fun abort() {
        val conn = connection ?: return
        // Drop any queued follow-up so the SessionIdle that follows the abort doesn't
        // auto-send it — the user tapped Stop to halt work, not to trigger the next turn.
        setQueuedFollowUp(null)
        if (!_aborting.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable { conn.repository.abort(sessionId) }
                    .onSuccess { _running.value = false }
                    .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
            } finally {
                _aborting.value = false
            }
        }
    }

    fun addAttachment(attachment: PendingAttachment) {
        _attachments.update { it + attachment }
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    /** Revert the conversation to just before [messageId], hiding everything after it. The
     *  authoritative state also arrives via SessionUpdated; we flag it here for immediate
     *  feedback. */
    fun revertTo(messageId: String) {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.revert(sessionId, messageId) }
                .onSuccess { _reverted.value = it.isReverted }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Undo the active revert checkpoint, restoring the hidden messages. */
    fun unrevert() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.unrevert(sessionId) }
                .onSuccess { _reverted.value = it.isReverted }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Create (or fetch the existing) public share link. The URL is exposed via [shareUrl]
     *  and also emitted once via [shareLinkEvents] so the UI can copy it to the clipboard. */
    fun shareSession() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.shareSession(sessionId) }
                .onSuccess { session ->
                    val url = session.share?.url?.takeIf { it.isNotBlank() }
                    _shareUrl.value = url
                    if (url != null) _shareLinkEvents.trySend(url)
                }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Revoke the session's public share link. */
    fun unshareSession() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.unshareSession(sessionId) }
                .onSuccess { _shareUrl.value = it.share?.url?.takeIf { it.isNotBlank() } }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Compact the conversation via the summarize endpoint; the summary streams back via SSE.
     *  Uses the currently-selected model (required by the endpoint). */
    fun summarize() {
        val conn = connection ?: return
        val model = _selectedModel.value?.ref ?: run {
            _errorEvents.trySend(ChatError(container.string(R.string.needs_model)))
            return
        }
        if (!_running.compareAndSet(false, true)) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        viewModelScope.launch {
            runCatchingCancellable { conn.api.summarize(sessionId, model) }
                .onFailure {
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
                    _running.value = false
                }
        }
    }

    /** Analyze the project and (re)generate its AGENTS.md; the run streams back via SSE. */
    fun initProject() {
        val conn = connection ?: return
        if (!_running.compareAndSet(false, true)) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        viewModelScope.launch {
            runCatchingCancellable { conn.api.initSession(sessionId) }
                .onFailure {
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
                    _running.value = false
                }
        }
    }

    /** Run a one-off shell [command] in the session's worktree; output streams back via SSE.
     *  The server requires an agent to scope the run — use the selected one, else the primary. */
    fun runShell(command: String) {
        val conn = connection ?: return
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val agent = _selectedAgent.value
            ?: _agents.value.firstOrNull { it.isPrimary }?.name
            ?: _agents.value.firstOrNull()?.name
            ?: DEFAULT_SHELL_AGENT
        if (!_running.compareAndSet(false, true)) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        viewModelScope.launch {
            runCatchingCancellable { conn.api.shell(sessionId, cmd, agent, _selectedModel.value?.ref) }
                .onFailure {
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
                    _running.value = false
                }
        }
    }

    /** Rename the current session via PATCH /session/:id. On success updates [sessionTitle]
     *  so the top bar reflects the new name immediately. A failure surfaces as a snackbar;
     *  the caller keeps the dialog open so the user can retry without retyping. */
    fun renameSession(newTitle: String) {
        val conn = connection ?: return
        val title = newTitle.trim()
        if (title.isEmpty() || title == _sessionTitle.value) return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.updateSession(sessionId, title) }
                .onSuccess { _sessionTitle.value = it.displayTitle }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Delete the current session via DELETE /session/:id. Surfaces a toast (via the UI's
     *  sessionDeleted flow) and navigates away on success; a failure surfaces as a snackbar.
     *  Unlike [SessionListViewModel.deleteSession] there's no undo window — the user is
     *  already viewing the session, so a confirmation dialog guards the action instead. */
    fun deleteSession() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.repository.deleteSession(sessionId) }
                .onSuccess {
                    container.draftStore.remove(sessionId)
                    _sessionDeleted.value = true
                }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
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
                    _errorEvents.trySend(ChatError(container.string(R.string.no_servers_to_reconnect)))
                    return@launch
                }
                runCatchingCancellable {
                    val conn = container.connect(recent)
                    conn.api.ping()
                }.onFailure {
                    container.disconnect()
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
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
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
                    // Only restore if no new permission has arrived in the meantime.
                    if (_pendingPermission.value == null) _pendingPermission.value = permission
                }
        }
    }

    private companion object {
        /** Fallback agent for the shell endpoint when none is selected and no agent catalog
         *  loaded. opencode's default primary agent is "build". */
        const val DEFAULT_SHELL_AGENT = "build"
    }

    override fun onCleared() {
        super.onCleared()
        // Flush any pending debounced draft so it survives navigation. Uses an
        // asynchronous apply() (not a synchronous commit) so the main thread isn't
        // blocked on disk I/O — Android's SharedPreferences framework guarantees
        // pending apply() writes are flushed before the process exits.
        // Always flush — including an empty draft. Persistence is otherwise debounced, so if
        // the user cleared the input and navigated back within the debounce window, the
        // debounce coroutine was cancelled with viewModelScope and the prefs still hold the
        // previous non-empty draft. flushDraft() removes the key when the text is blank, so an
        // unconditional flush commits the clear instead of resurrecting the deleted text.
        container.draftStore.flushDraft(sessionId, _draft.value)
    }
}
