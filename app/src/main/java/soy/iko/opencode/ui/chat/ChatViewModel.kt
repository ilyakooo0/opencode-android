package soy.iko.opencode.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.FilePromptPart
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionReplied
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.TodoItem
import soy.iko.opencode.data.model.TodoPlanCache
import soy.iko.opencode.data.model.currentTodoPlan
import soy.iko.opencode.data.model.defaultOption
import soy.iko.opencode.data.model.toOptions
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.repo.OutboxMessage
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A user-facing error to surface as a snackbar. [retryable] is true only for a failed
 * message *send* (whose draft is held in [ChatViewModel.failedDraft]); the snackbar attaches
 * a Retry action solely for those so an unrelated error (e.g. a message-load failure) can't
 * inherit a Retry that silently re-submits the last prompt.
 */
data class ChatError(val message: String, val retryable: Boolean = false)

/**
 * Progress of the current permission within a burst of requests. [total] is the max number
 * simultaneously pending since the queue was last empty; [position] advances as the user clears
 * the backlog. A [total] of 0/1 means there's no meaningful backlog to surface.
 */
@androidx.compose.runtime.Immutable
data class PermissionProgress(val position: Int, val total: Int)

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

/** Reduce a staged attachment to its persistable form (the self-contained data URL). */
private fun PendingAttachment.toPersisted() =
    soy.iko.opencode.data.repo.PersistedAttachment(id, name, mime, part.url, part.filename)

/** Rebuild a staged attachment from persistence. The base64 data URL doubles as the Coil
 *  preview model for images (the original content Uri didn't survive the process restart). */
private fun soy.iko.opencode.data.repo.PersistedAttachment.toPending() = PendingAttachment(
    id = id,
    name = name,
    mime = mime,
    previewModel = if (mime.startsWith("image/")) url else null,
    part = FilePromptPart(mime = mime, url = url, filename = filename),
)

// Large by design: this VM owns the whole chat surface (streaming, drafts, attachments,
// catalogs, permissions, revert/edit, sharing, summarize/init/shell, TTS wiring). It's
// already grandfathered for TooManyFunctions in the detekt baseline; the class-size rule is
// suppressed for the same reason. A future split into sub-controllers is tracked separately.
@Suppress("LargeClass")
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

    /** Set when a re-fetch fails mid-conversation (messages already visible). Distinct
     *  from [loadError] (which replaces the whole content with an error screen): this
     *  surfaces as a persistent inline banner above the message list so a persistent
     *  outage doesn't go silent after the one-shot snackbar. Cleared on the next
     *  successful emission. */
    private val _loadErrorInline = MutableStateFlow(false)
    val loadErrorInline: StateFlow<Boolean> = _loadErrorInline.asStateFlow()

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

    /** An optimistically-injected user message shown immediately on send, before the server
     *  echoes the real message back via SSE. Removed once a matching UserMessage arrives. */
    data class OptimisticEntry(val tempId: String, val text: String, val timestamp: Long, val failed: Boolean)

    private val _optimisticMessages = MutableStateFlow<List<OptimisticEntry>>(emptyList())

    /** SSE-driven message stream with retry/backoff and loading/error side effects. Combined
     *  with [_optimisticMessages] by [messages] so the user sees their outgoing prompt instantly. */
    private val sseMessages: Flow<List<MessageWithParts>> =
        container.activeConnection
            .flatMapLatest { conn ->
                conn?.repository?.observeMessages(sessionId) ?: flowOf(emptyList())
            }
            .onEach {
                _loading.value = false
                // A value flowed through: the stream recovered, so allow the next distinct
                // failure to surface a fresh snackbar again.
                loadErrorSnackbarShown = false
                // Clear the error on ANY successful emission, including an empty list. An empty
                // session that hit one transient error would otherwise stay latched on the
                // "Failed to load / Retry" screen forever, because the later successful EMPTY
                // emission couldn't clear the flag. hasShownMessages stays gated on non-empty
                // (an empty session has genuinely shown nothing yet).
                _loadError.value = false
                _loadErrorInline.value = false
                if (it.isNotEmpty()) {
                    hasShownMessages = true
                }
                // Reconcile optimistic messages: drop any whose text now matches a real SSE
                // UserMessage (the server echoed the prompt back). Matching by trimmed text,
                // removing the oldest match first so duplicate texts are handled in order.
                reconcileOptimistic(it)
            }
            .retryWhen { cause, attempt ->
                _loading.value = false
                // Only surface the persistent error state when there's nothing to
                // show — if we already have messages, a transient re-fetch failure
                // is better surfaced as a snackbar (via errorEvents) than by
                // replacing the visible conversation with an error screen.
                if (!hasShownMessages) _loadError.value = true
                // Mid-conversation failure: surface a persistent inline banner so
                // the user knows the stream is broken (the one-shot snackbar alone
                // reads as "recovered" once it dismisses). Cleared on recovery.
                if (hasShownMessages) _loadErrorInline.value = true
                // Surface the snackbar only once per failure streak — retryWhen loops on a
                // backoff, so emitting here unconditionally would spam a new snackbar every
                // retry on a persistent load failure.
                if (!loadErrorSnackbarShown) {
                    _errorEvents.trySend(ChatError(container.friendlyError(cause)))
                    loadErrorSnackbarShown = true
                }
                // Exponential backoff capped at retryMaxDelayMs so a persistently failing
                // server is retried with growing delays (retryInitialDelayMs, then doubling)
                // instead of hammered at a fixed interval forever. Clamp the shift so a long
                // failure streak can't overflow the Long delay.
                val backoffMs = (NetworkConfig.retryInitialDelayMs shl attempt.coerceAtMost(16L).toInt())
                    .coerceAtMost(NetworkConfig.retryMaxDelayMs)
                delay(backoffMs)
                true
            }

    val messages: StateFlow<List<MessageWithParts>> =
        combine(sseMessages, _optimisticMessages) { sse, optimistic ->
            mergeOptimistic(sse, optimistic)
        }.stateIn(
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

    /** Map of optimistic message tempId → failed flag, so the UI can render a "Sending…"
     *  or "Failed to send" indicator on the corresponding message bubble. */
    val optimisticStatuses: StateFlow<Map<String, Boolean>> = _optimisticMessages
        .map { entries -> entries.associate { it.tempId to it.failed } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = emptyMap(),
        )

    /** Merge SSE-driven messages with optimistic entries. An optimistic entry whose trimmed
     *  text matches a real [UserMessage] in [sse] is dropped (the server echoed it back).
     *  Remaining entries are appended as synthetic [UserMessage]s at the end. Failed entries
     *  are kept so the user sees the failed indicator until they retry or dismiss. */
    private fun mergeOptimistic(
        sse: List<MessageWithParts>,
        optimistic: List<OptimisticEntry>,
    ): List<MessageWithParts> {
        if (optimistic.isEmpty()) return sse
        // Collect the text of real user messages so optimistic entries can be matched.
        val realUserTexts = mutableSetOf<String>()
        for (msg in sse) {
            if (msg.info !is UserMessage) continue
            val text = msg.parts.filterIsInstance<TextPart>()
                .joinToString("\n\n") { it.text }
                .trim()
            if (text.isNotEmpty()) realUserTexts.add(text)
        }
        val surviving = optimistic.filter { it.text.trim() !in realUserTexts }
        if (surviving.isEmpty()) return sse
        return sse + surviving.map { it.toMessageWithParts() }
    }

    /** Remove optimistic entries whose text now matches a real user message delivered by SSE.
     *  Called from the SSE flow's onEach so stale optimistic entries are cleaned up promptly. */
    private fun reconcileOptimistic(sse: List<MessageWithParts>) {
        if (_optimisticMessages.value.isEmpty()) return
        val realUserTexts = mutableSetOf<String>()
        for (msg in sse) {
            if (msg.info !is UserMessage) continue
            val text = msg.parts.filterIsInstance<TextPart>()
                .joinToString("\n\n") { it.text }
                .trim()
            if (text.isNotEmpty()) realUserTexts.add(text)
        }
        if (realUserTexts.isEmpty()) return
        _optimisticMessages.update { entries ->
            entries.filterNot { it.text.trim() in realUserTexts }
        }
    }

    /** Convert an optimistic entry to a [MessageWithParts] for display. */
    private fun OptimisticEntry.toMessageWithParts(): MessageWithParts = MessageWithParts(
        info = UserMessage(
            id = tempId,
            sessionID = sessionId,
            time = soy.iko.opencode.data.model.TimeInfo(created = timestamp),
        ),
        parts = listOf(TextPart(id = "$tempId-text", text = text)),
    )

    /** The agent's current task plan (the latest `todowrite`), surfaced so the chat can pin a
     *  live progress checklist above the composer. distinctUntilChanged so a per-token messages
     *  emission that doesn't change the plan doesn't recompose the bar.
     *
     *  A [TodoPlanCache] memoizes the scan against the input list's identity, so a `combine`
     *  re-evaluation that re-emits the same list instance (e.g. when the optimistic-messages
     *  StateFlow updates without an SSE change) skips the O(messages×parts) re-scan. */
    private val todoPlanCache = TodoPlanCache()
    val todoPlan: StateFlow<List<TodoItem>> = messages
        .map { todoPlanCache.plan(it) }
        // Run the scan off the main thread: it's an O(messages×parts) scan (worst case
        // when the conversation has no todowrite) and runs on every conflated messages emission.
        // distinctUntilChanged is downstream so it dedupes the result but doesn't prevent the
        // scan — without flowOn this scans on Main.immediate per streamed token → jank.
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = emptyList(),
        )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * True when the SSE stream dropped mid-run (a transient [Disconnected] while a run is
     * active). The UI uses this to switch the working indicator to a "stream interrupted /
     * reconnecting" visual instead of clearing [_running] outright — the run is still active
     * on the server, so showing it as finished would mislead the user into typing a follow-up
     * or navigating away. Cleared when the stream reconnects ([Connected]) or when a hard
     * failure ([Failed]/[AuthFailed]) genuinely clears [_running]. See the state watcher in
     * [init]. */
    private val _streamInterrupted = MutableStateFlow(false)
    val streamInterrupted: StateFlow<Boolean> = _streamInterrupted.asStateFlow()

    /**
     * Wall-clock millis at which the current run started (0L when no run is active). Drives
     * the typing row's elapsed timer so it survives LazyColumn recycling — a local `remember`
     * in the row resets to 0:00 when the row is disposed and scrolled back into view. Stamped
     * only on a real false→[beginRun] transition (send/run/init), not on SSE-reconnect relight
     * (which continues an existing run and should keep its original elapsed time).
     */
    private val _runStartMs = MutableStateFlow(0L)
    val runStartMs: StateFlow<Long> = _runStartMs.asStateFlow()

    /**
     * Atomically transition `_running` false→true and stamp the run-start timestamp. Returns
     * false (with no state change) when a run is already in flight, so callers can surface a
     * "busy" message instead of silently dropping the action. Centralizes the transition so
     * every run-start path captures [runStartMs] consistently.
     */
    private fun beginRun(): Boolean {
        if (!_running.compareAndSet(false, true)) return false
        _runStartMs.value = System.currentTimeMillis()
        return true
    }

    /** True once the current run ended via SessionIdle/SessionError, until the next run starts
     *  (a send) or an SSE reconnect. Gates the run-indicator re-light below: isRunActivity
     *  returns true for ANY message.part.updated, so without this a trailing/replayed part
     *  arriving after a legitimate SessionIdle would re-light the "working" indicator with no
     *  further idle to clear it — a stuck spinner + foreground service. Cleared on reconnect so
     *  genuine reconnect-recovery re-lighting (its actual purpose) still works. */
    @Volatile private var runEndedByIdle = false

    /** The in-flight REST title resolution for the current connection. Launched from — and
     *  cancelled by — the per-connection reset block so it can't observe or restore a previous
     *  server's stale title. */
    private var sessionTitleJob: Job? = null

    /** True while an abort REST call is in flight, so the Stop button can show a
     *  spinner and prevent double-taps from firing a second abort. */
    private val _aborting = MutableStateFlow(false)
    val aborting: StateFlow<Boolean> = _aborting.asStateFlow()

    /** A follow-up the user typed while a run was active. send() queues it here and
     *  auto-sends once the run completes (SessionIdle), so the user isn't blocked with
     *  no way to send and no indication why the Send button is gone. */
    private val _queuedFollowUp = MutableStateFlow<String?>(null)
    val queuedFollowUp: StateFlow<String?> = _queuedFollowUp.asStateFlow()

    /** The text of the most recently-sent user prompt (captured in [send]), so an undo-able
     *  Stop can re-send it. Only text-bearing prompts are tracked — an image-only prompt has
     *  nothing to re-send text-wise. Cleared when a new run starts with different text. */
    private var lastSentPrompt: String? = null

    /** One-shot event signals for an undoable Stop. The UI collects this and shows an
     *  "Stopped — Undo" snackbar; tapping Undo calls [resendLastPrompt]. A Channel so an
     *  emit before the UI subscribes is still delivered. */
    private val _stopUndoEvents = Channel<String?>(Channel.CONFLATED)
    val stopUndoEvents: Flow<String?> = _stopUndoEvents.receiveAsFlow()

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

    /** One-shot events signaling a user-initiated delete was scheduled and is undoable in-place.
     *  The ChatScreen collects these to show an Undo snackbar over the conversation the user is
     *  already viewing, instead of navigating away first and surfacing Undo on the session list. */
    private val _deleteUndoEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deleteUndoEvents: SharedFlow<Unit> = _deleteUndoEvents.asSharedFlow()

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

    /** SSE reconnect-attempt count for the active connection, surfaced so the chat's
     *  connection banner can show "Reconnecting (attempt N)…" during a sustained outage. */
    val reconnectAttempts: StateFlow<Int> =
        container.activeConnection
            .flatMapLatest { it?.events?.reconnectAttempts ?: flowOf(0) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = 0,
            )

    private val _pendingPermission = MutableStateFlow<Permission?>(null)
    val pendingPermission: StateFlow<Permission?> = _pendingPermission.asStateFlow()

    /** Backlog indicator for the permission dialog (see [PermissionProgress]). */
    private val _permissionProgress = MutableStateFlow(PermissionProgress(0, 0))
    val permissionProgress: StateFlow<PermissionProgress> = _permissionProgress.asStateFlow()

    /** Max number of permissions simultaneously pending since the queue was last empty. Reset
     *  to 0 when the queue drains, so a fresh burst starts its count over. */
    private var maxPendingSeen = 0

    // Unanswered permission requests keyed by id, insertion-ordered. The dialog shows the most
    // recent; when one is answered/replied — or a response fails — we fall back to the next so a
    // request that arrived while another was in flight isn't silently dropped (its tool run would
    // otherwise stay paused server-side with no UI affordance). Only touched on the VM's main
    // dispatcher (event collector + viewModelScope launches), so no extra synchronization is needed.
    private val pendingPermissions = LinkedHashMap<String, Permission>()

    // Session-scoped permission grants (Allow-for-this-session). Each entry is a (type, patternText)
    // pair the user approved with PermissionResponse.SESSION. Subsequent matching requests are
    // auto-responded ONCE without showing the dialog, so e.g. a user granting "read" for one path
    // isn't re-prompted for every other file read in the same conversation. Cleared on connection
    // change (see the activeConnection collector) so it never leaks across servers. The server only
    // knows once/always/reject; SESSION is a client-side scope that maps to ONCE on the wire.
    private val sessionAllowed = mutableSetOf<Pair<String, String>>()

    private fun enqueuePermission(permission: Permission) {
        // Auto-respond to a permission whose (type, pattern) the user previously granted for this
        // session, instead of showing the dialog again. Resolved immediately (not via the queue) so
        // the tool run isn't paused waiting for a dialog the user will never see.
        val type = permission.type.orEmpty()
        val pattern = permission.patternText.orEmpty()
        if (type.isNotEmpty() && sessionAllowed.contains(type to pattern)) {
            respondPermission(permission, PermissionResponse.ONCE)
            return
        }
        pendingPermissions[permission.id] = permission
        _pendingPermission.value = pendingPermissions.values.lastOrNull()
        updatePermissionProgress()
    }

    private fun resolvePermission(id: String) {
        pendingPermissions.remove(id)
        _pendingPermission.value = pendingPermissions.values.lastOrNull()
        updatePermissionProgress()
    }

    private fun clearPermissions() {
        pendingPermissions.clear()
        _pendingPermission.value = null
        updatePermissionProgress()
    }

    /** Recompute the "N of M" backlog indicator. [maxPendingSeen] grows with the queue and
     *  resets only when it drains, so position = total - remaining + 1 advances as requests are
     *  answered. */
    private fun updatePermissionProgress() {
        val remaining = pendingPermissions.size
        if (remaining == 0) {
            maxPendingSeen = 0
            _permissionProgress.value = PermissionProgress(0, 0)
            return
        }
        if (remaining > maxPendingSeen) maxPendingSeen = remaining
        _permissionProgress.value = PermissionProgress(
            position = maxPendingSeen - remaining + 1,
            total = maxPendingSeen,
        )
    }

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

    /** The idempotency key of the last failed online send, stashed alongside [_failedDraft] so
     *  [retryFailed] re-submits with the SAME key. A retry after a lost response is then
     *  deduplicated server-side instead of starting a duplicate agent run. */
    @Volatile private var failedIdempotencyKey: String? = null

    /** Per-session draft, persisted so it survives navigation/process death. */
    private val _draft = MutableStateFlow(container.draftStore.get(sessionId))
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** A pending quote-reply preview (the raw text the user quoted), shown as a dismissible
     *  card above the composer so the user can see and cancel the quote before sending. Cleared
     *  on send or via [cancelQuoteReply]. The actual `>` prefixed lines are still folded into
     *  the draft for the server; this state is purely for the visual preview chip. */
    private val _pendingQuote = MutableStateFlow<String?>(null)
    val pendingQuote: StateFlow<String?> = _pendingQuote.asStateFlow()

    /** Messages composed for this session while offline/disconnected, queued in the outbox and
     *  awaiting an automatic flush on reconnect. Surfaced so the composer can show a "queued"
     *  chip with a discard/send-now affordance. */
    val outbox: StateFlow<List<OutboxMessage>> =
        container.outboxStore.messages
            .map { list -> list.filter { it.sessionId == sessionId } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = emptyList(),
            )

    /** Attachments staged for the next prompt (images/files). Persisted per-session (as their
     *  self-contained base64 data URLs) via [AttachmentDraftStore] so an interrupted compose
     *  survives process death — the source content Uris wouldn't survive, but the data URLs
     *  do. Cleared on a successful send. */
    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments.asStateFlow()

    /** True while a revert checkpoint is active for this session (messages after it are hidden
     *  server-side). Drives the "reverted" banner with its Undo. */
    private val _reverted = MutableStateFlow(false)
    val reverted: StateFlow<Boolean> = _reverted.asStateFlow()

    /** True when the revert was triggered by an Edit action (vs a bare Revert). Drives the
     *  banner's copy so it reads "Editing message" rather than "Reverted", making the composer's
     *  prefilled text unambiguous. Cleared on unrevert and on send. */
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    /** The diff of the active revert checkpoint, when the server includes one. Shown in the
     *  revert banner so the user can see what changed before deciding to undo. Null when the
     *  session isn't reverted or the server didn't send a diff. */
    private val _revertDiff = MutableStateFlow<String?>(null)
    val revertDiff: StateFlow<String?> = _revertDiff.asStateFlow()

    /** The active public share URL for this session, or null when not shared. */
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** One-shot events carrying a freshly-created share URL so the UI can copy/share it. */
    private val _shareLinkEvents = Channel<String>(
        capacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val shareLinkEvents: Flow<String> = _shareLinkEvents.receiveAsFlow()

    /** One-shot events carrying the id of a session freshly branched from a message, so the
     *  UI can navigate to it. */
    private val _branchEvents = Channel<String>(
        capacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val branchEvents: Flow<String> = _branchEvents.receiveAsFlow()

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

    init {
        // Restore attachments staged before process death. They were persisted as
        // self-contained data URLs, so they re-send and (for images) re-preview intact.
        // Guarded on empty so it never clobbers attachments the user staged after opening.
        viewModelScope.launch {
            val restored = runCatchingCancellable { container.attachmentDraftStore.load(sessionId) }
                .getOrDefault(emptyList())
            if (restored.isNotEmpty() && _attachments.value.isEmpty()) {
                _attachments.value = restored.map { it.toPending() }
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
        onSuccess: suspend (T) -> Unit,
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
                    .getOrNull()?.let { onSuccess(it) }
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
                _streamInterrupted.value = false
                _aborting.value = false
                clearPermissions()
                sessionAllowed.clear()
                sessionTitleJob?.cancel()
                _revertDiff.value = null
                return@collectLatest
                }
                _running.value = false
                _streamInterrupted.value = false
                // Allow the re-light below to recover a still-running run after this (re)connect;
                // a run that actually finished during the outage emits no live parts, so this
                // won't spuriously re-light.
                runEndedByIdle = false
                clearPermissions()
                sessionAllowed.clear()
                _failedDraft.value = null
                _revertDiff.value = null
                // NOTE: deliberately not clearing _queuedFollowUp here. It's session-scoped
                // user intent that is now persisted; wiping it on every (re)connect — which
                // this collector does, including transient SSE drops and the cold-start
                // restore path — would drop a legitimately queued/recovered follow-up.
                _selectedAgent.value = null
                // Apply the user's persisted preferred-agent for this new session/load, so a
                // user who always runs a specific agent doesn't have to pick it every session.
                // No-op when the preference is empty (the server default applies). Read here
                // (not in the agent-catalog onSuccess) because the catalog loads once per
                // connection while this reset fires per-session-open; setting it once on
                // connect is enough and avoids re-applying it on every catalog refresh.
                runCatchingCancellable {
                    val pref = container.settingsStore.preferredAgentName.first()
                    if (pref.isNotEmpty()) _selectedAgent.value = pref
                }
                _sessionTitle.value = null
                // Resolve the app-bar title via REST for THIS connection. Launched from the same
                // block that just nulled _sessionTitle (and cancelled the previous fetch) so it can
                // never observe or restore the previous server's stale title — the old separate
                // collector could, racing that reset. The assignment is guarded on a still-null
                // title so a fresher SSE SessionUpdated (from the event collector below) wins.
                sessionTitleJob?.cancel()
                sessionTitleJob = viewModelScope.launch {
                    runCatchingCancellable { conn.repository.listSessions() }
                        .getOrNull()
                        ?.firstOrNull { it.id == sessionId }
                        ?.let { session ->
                            if (_sessionTitle.value == null) {
                                _sessionTitle.value = session.displayTitle
                                _reverted.value = session.isReverted
                                _revertDiff.value = session.revert?.diff?.takeIf { it.isNotBlank() }
                                _shareUrl.value = session.share?.url?.takeIf { it.isNotBlank() }
                            }
                        }
                }
                try {
                    // Loop the inner collect so a transient non-cancellation exception (a
                    // malformed SSE event decoding error, an unexpected channel close)
                    // doesn't terminate the event collector for this connection. Without
                    // the loop, the catch returns the collectLatest lambda normally, so
                    // collectLatest considers this connection's block complete and never
                    // re-runs it — the chat would go permanently silent (no streaming,
                    // no permission prompts, no title updates) until a server switch.
                    while (currentCoroutineContext().isActive) {
                        try {
                            conn.events.events.collect { event ->
                                if (SessionRepository.isIdle(event, sessionId)) {
                                    _running.value = false
                                    runEndedByIdle = true
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
                                    runEndedByIdle = true
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
                                // Restore the run indicator if a live streaming event arrives while it's
                                // cleared — e.g. an SSE reconnect mid-run reset _running to false, but the
                                // agent is still working. Don't revive it once the user has hit Stop.
                                val relightRunIndicator = SessionRepository.isRunActivity(event, sessionId) &&
                                    !_aborting.value && !_running.value && !runEndedByIdle
                                if (relightRunIndicator) {
                                    _running.value = true
                                }
                                when (event) {
                                    is PermissionUpdated ->
                                        if (event.properties.sessionID == sessionId) enqueuePermission(event.properties)
                                    is PermissionReplied ->
                                        if (event.properties.sessionID == sessionId) event.properties.permissionID?.let { resolvePermission(it) }
                                    is SessionUpdated ->
                                        if (event.properties.info.id == sessionId) {
                                            val info = event.properties.info
                                            _sessionTitle.value = info.displayTitle
                                            _reverted.value = info.isReverted
                                            _revertDiff.value = info.revert?.diff?.takeIf { it.isNotBlank() }
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
                            Log.w("ChatViewModel", "SSE event collector error, restarting", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "SSE event collector loop error", e)
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
                // Only (re)apply the default when there's no valid current selection. On
                // re-fetch/reconnect/Retry the user's chosen model must survive — keep it as
                // long as it's still present in the refreshed list. When there's no current
                // selection, prefer the user's persisted default-model preference (if it's in
                // the list) over the server default, so a user who always picks a specific
                // model doesn't have to pick it every new session.
                val current = _selectedModel.value
                if (current == null || options.none { it.ref == current.ref }) {
                    val prefId = runCatchingCancellable {
                        container.settingsStore.preferredModelId.first()
                    }.getOrDefault("")
                    val preferredOption = if (prefId.isNotEmpty()) {
                        options.firstOrNull { it.modelID == prefId }
                    } else null
                    _selectedModel.value = preferredOption ?: resp.defaultOption(options)
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
        // If the SSE stream drops mid-run, the run indicator would spin forever;
        // reset it so the UI doesn't look stuck while the banner shows "Reconnecting…".
        // On a *transient* drop (Disconnected) we don't clear _running — the run is still
        // active on the server, and showing it as finished would mislead the user. Instead
        // we set _streamInterrupted so the working row switches to a "reconnecting" visual.
        // On a *hard* failure (Failed/AuthFailed) the run genuinely can't continue without
        // user action, so _running is cleared. _streamInterrupted is cleared on Connected.
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                try {
                    // Loop so a transient non-cancellation exception doesn't terminate the
                    // state watcher for this connection. Without the loop, a single thrown
                    // error returns the collectLatest lambda and the _running reset on
                    // Disconnected/Failed never fires again for this connection — a dropped
                    // stream leaves the working spinner (and the RunForegroundService) stuck.
                    while (currentCoroutineContext().isActive) {
                        try {
                            conn.events.state.collect { state ->
                                when (state) {
                                    EventStreamClient.ConnectionState.Connected -> {
                                        _streamInterrupted.value = false
                                    }
                                    EventStreamClient.ConnectionState.Disconnected -> {
                                        // Transient: the stream dropped but the system is
                                        // reconnecting. Flag interrupted so the working row
                                        // shows a "reconnecting" state instead of vanishing.
                                        if (_running.value) _streamInterrupted.value = true
                                    }
                                    EventStreamClient.ConnectionState.Failed,
                                    EventStreamClient.ConnectionState.AuthFailed -> {
                                        // Hard failure: the run can't continue without user
                                        // action (fix credentials / server). Clear running and
                                        // the interrupted flag so the UI doesn't look stuck.
                                        _streamInterrupted.value = false
                                        if (_running.value) _running.value = false
                                    }
                                    EventStreamClient.ConnectionState.Connecting -> {
                                        // Initial connect or reconnect attempt; leave running
                                        // as-is — interrupted (if set) stays until Connected.
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("ChatViewModel", "SSE state collector error, restarting", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "SSE state collector loop error", e)
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
    private fun send(text: String, includeAttachments: Boolean, idempotencyKey: String? = null): Boolean {
        val trimmed = text.trim()
        val attachments = if (includeAttachments) _attachments.value else emptyList()
        // An image-only prompt (attachments, no text) is valid; a blank prompt with nothing
        // attached is not.
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        // Offline, or no active connection: queue the prompt to the outbox (flushed on
        // reconnect) instead of failing the send. Device-offline is caught even when a stale
        // connection object lingers, so a phone that lost signal mid-conversation still
        // captures the message rather than bouncing it off a dead socket.
        val conn = connection
        if (conn == null || !container.isOnline.value) {
            return enqueueOffline(trimmed, attachments)
        }
        if (!beginRun()) return false
        // A new run is starting: reopen the re-light gate so this run's streamed parts keep the
        // indicator lit (and a later SessionIdle re-closes it).
        runEndedByIdle = false
        // A plain manual Send of the auto-restored failed draft must reuse the stashed key too
        // (retryFailed passes it explicitly; the composer's Send does not), so a lost-response
        // re-send stays deduplicated server-side instead of starting a duplicate agent run.
        // Capture the match BEFORE clearing _failedDraft just below.
        val reuseKey = if (idempotencyKey == null && trimmed == _failedDraft.value?.trim()) {
            failedIdempotencyKey
        } else {
            null
        }
        _failedDraft.value = null
        // Stable idempotency key for THIS online attempt: a brand-new message gets a fresh key,
        // while retryFailed() (or a manual re-send of the restored failed draft) re-submits with the
        // key it stashed on the original failure — so a retry after a lost response is deduplicated
        // server-side (no duplicate agent run).
        val key = idempotencyKey ?: reuseKey ?: java.util.UUID.randomUUID().toString()
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
            _pendingQuote.value = null
            _editing.value = false
        }
        // Inject an optimistic user message so the outgoing prompt is visible immediately,
        // before the server echoes it back via SSE. Removed by reconcileOptimistic once the
        // real UserMessage arrives. Only for text-bearing prompts (an image-only prompt has
        // no text to show).
        val optimisticId = if (trimmed.isNotEmpty()) "__optimistic_${System.nanoTime()}" else null
        if (optimisticId != null) {
            // Clear any prior failed optimistic entry with the same text (a retry re-sends).
            _optimisticMessages.update { entries -> entries.filterNot { it.failed && it.text.trim() == trimmed } }
            _optimisticMessages.update { it + OptimisticEntry(optimisticId, trimmed, System.currentTimeMillis(), failed = false) }
        }
        // Remember the sent text so an undoable Stop can re-send it. Overwrite unconditionally:
        // an image-only prompt has nothing to re-send, and leaving a prior text prompt tracked
        // would make Stop-Undo silently re-send that unrelated earlier message.
        lastSentPrompt = trimmed.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            runCatchingCancellable {
                conn.repository.sendPrompt(
                    sessionId,
                    trimmed,
                    attachments = attachments.map { it.part },
                    model = _selectedModel.value?.ref,
                    agent = _selectedAgent.value,
                    idempotencyKey = key,
                )
            }.onFailure {
                // Mark the optimistic message as failed so the user sees a failure indicator
                // instead of a perpetual "sending" state.
                if (optimisticId != null) {
                    _optimisticMessages.update { entries ->
                        entries.map { if (it.tempId == optimisticId) it.copy(failed = true) else it }
                    }
                }
                suppressDraftPersist.set(false)
                _failedDraft.value = trimmed
                failedIdempotencyKey = key
                // Only restore the draft if the user hasn't typed anything new since.
                if (_draft.value.isBlank()) updateDraft(trimmed)
                // Restore the staged attachments too, so a failed send doesn't lose them —
                // but only if the user hasn't staged new ones in the meantime.
                if (attachments.isNotEmpty() && _attachments.value.isEmpty()) _attachments.value = attachments
                // Re-persist so the restored attachments survive process death after a failure.
                persistAttachments()
                // Retryable: this is the failed send whose prompt Retry re-submits.
                _errorEvents.trySend(ChatError(container.friendlyError(it), retryable = true))
                _running.value = false
            }.onSuccess {
                suppressDraftPersist.set(false)
                // This attempt reached the server, so the stashed retry key is spent — drop it so a
                // later unrelated send can't reuse it (retryFailed clears it on success too).
                failedIdempotencyKey = null
                // Send succeeded — the attachments rode along, so clear their persisted copy
                // (or keep any the user staged after the optimistic clear).
                persistAttachments()
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

    /**
     * Queue [trimmed] (+ [attachments]) to the offline outbox, clearing the composer
     * optimistically like a real send. AppContainer flushes the outbox automatically on
     * reconnect. The prompt is attributed to the active (or most-recent) server profile so a
     * reconnect to a different server doesn't misfire it. On any failure the draft/attachments
     * are restored so nothing is lost.
     */
    private fun enqueueOffline(trimmed: String, attachments: List<PendingAttachment>): Boolean {
        _failedDraft.value = null
        if (attachments.isNotEmpty()) _attachments.value = emptyList()
        val clearDraft = _draft.value.trim() == trimmed
        if (clearDraft) {
            suppressDraftPersist.set(true)
            _draft.value = ""
        }
        viewModelScope.launch {
            val profileId = connection?.profile?.id
                ?: runCatchingCancellable { container.profileStore.profiles.first().firstOrNull()?.id }.getOrNull()
            if (profileId == null) {
                // No server to attribute the message to — restore the composer and report it
                // rather than silently dropping the prompt.
                suppressDraftPersist.set(false)
                if (_draft.value.isBlank()) updateDraft(trimmed)
                if (attachments.isNotEmpty() && _attachments.value.isEmpty()) _attachments.value = attachments
                persistAttachments()
                _errorEvents.trySend(ChatError(container.string(R.string.no_servers_to_reconnect)))
                return@launch
            }
            runCatchingCancellable {
                container.outboxStore.enqueue(
                    OutboxMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        profileId = profileId,
                        sessionId = sessionId,
                        text = trimmed,
                        attachments = attachments.map { it.toPersisted() },
                        model = _selectedModel.value?.ref,
                        agent = _selectedAgent.value,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onSuccess {
                suppressDraftPersist.set(false)
                // The staged attachments now live in the outbox item; clear their draft copy.
                persistAttachments()
                if (_draft.value.isBlank()) {
                    lastPersistedDraft = ""
                    container.draftStore.set(sessionId, "")
                }
                // Nudge a flush in case we're actually online with a live connection.
                container.flushOutbox()
                _errorEvents.trySend(ChatError(container.string(R.string.outbox_queued)))
            }.onFailure {
                suppressDraftPersist.set(false)
                if (_draft.value.isBlank()) updateDraft(trimmed)
                if (attachments.isNotEmpty() && _attachments.value.isEmpty()) _attachments.value = attachments
                persistAttachments()
                _errorEvents.trySend(ChatError(container.friendlyError(it)))
            }
        }
        return true
    }

    /** Discard a single queued (offline) message. */
    fun discardQueued(id: String) {
        viewModelScope.launch { runCatchingCancellable { container.outboxStore.remove(id) } }
    }

    /** Discard every queued (offline) message for this session. */
    fun discardAllQueued() {
        viewModelScope.launch { runCatchingCancellable { container.outboxStore.removeForSession(sessionId) } }
    }

    /** Attempt to flush the outbox now (e.g. the user tapped "Send now"). */
    fun flushQueued() { container.flushOutbox() }

    /** Re-send the last draft whose send failed, if any. */
    fun retryFailed() {
        val draft = _failedDraft.value ?: return
        // Reuse the failed send's idempotency key so a retry after a lost response is
        // deduplicated server-side rather than starting a duplicate run.
        val key = failedIdempotencyKey
        // Don't clear _failedDraft until send() accepts the text — if _running is
        // already true, send() returns false and the draft would be lost forever.
        // Attachments aren't re-sent on retry (they were optimistically restored to the
        // composer on the original failure, so they'll ride the next manual send).
        // send() accepts the text synchronously, but its onSuccess/onFailure callbacks run
        // asynchronously. Clearing failedIdempotencyKey here would open a window between this
        // synchronous clear and the async onFailure (which re-stashes the key) during which a
        // manual re-send of the same text gets a fresh UUID — defeating server-side deduplication.
        // Let send()'s own callbacks own the key's lifecycle, as they do for non-retry paths.
        if (send(draft, includeAttachments = false, idempotencyKey = key)) {
            _failedDraft.value = null
        }
    }

    /** Dismiss a single failed optimistic message by its tempId so it no longer lingers in the
     *  list. Without this there's no recovery path for a failed message the user chose to abandon
     *  (its text won't ever match a real server message, so reconcileOptimistic never clears it). */
    fun dismissOptimistic(tempId: String) {
        _optimisticMessages.update { entries -> entries.filterNot { it.tempId == tempId } }
    }

    /** Re-send a specific failed optimistic message by its tempId. Loads its text into the
     *  composer and fires a send, mirroring the snackbar's Retry path. Returns false (and is a
     *  no-op) if the entry can't be found or a run is already active. */
    fun retryOptimisticMessage(tempId: String): Boolean {
        val entry = _optimisticMessages.value.firstOrNull { it.tempId == tempId && it.failed } ?: return false
        return send(entry.text, includeAttachments = false)
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
        if (trimmed.isNotEmpty()) {
            _draft.value = ""
            // Persist the cleared draft IMMEDIATELY, not just in memory: the follow-up was already
            // persisted synchronously above, so if the process is killed before the debounced
            // draft-persist fires, the same text would remain under BOTH the draft and follow-up
            // keys — and cold-start recovery would restore it to the composer AND re-queue it (a
            // duplicate). Mirror send()'s lastPersistedDraft update so the drafts observer treats
            // this store change as our own echo and doesn't write the cleared value back.
            lastPersistedDraft = ""
            container.draftStore.flushDraft(sessionId, "")
        }
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
        _loadErrorInline.value = false
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
        if (!beginRun()) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        // A new run is starting: reopen the re-light gate (as send() does) so a mid-run SSE
        // reconnect keeps the working indicator lit instead of leaving it stuck off.
        runEndedByIdle = false
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
                // Run the abort POST under NonCancellable so it survives this scope being
                // cancelled. The "Stop and exit" path calls abort() then navigates back
                // immediately, which pops this ViewModel's back-stack entry → onCleared() →
                // viewModelScope.cancel(); without this the in-flight abort is cancelled (often
                // before the request even reaches the server) and the run keeps burning tokens
                // server-side despite the user explicitly stopping it.
                runCatchingCancellable { withContext(NonCancellable) { conn.repository.abort(sessionId) } }
                    .onSuccess {
                        _running.value = false
                        // Close the re-light gate so trailing SSE events in flight between the
                        // abort POST's ack and the server's SessionIdle don't re-light the run
                        // indicator (and re-arm the FGS / keepScreenOn) after the user hit Stop.
                        // send()/runCommand()/continueRun() all open this gate on beginRun();
                        // abort() is the one run-ending path that previously missed it.
                        runEndedByIdle = true
                        // Surface an undo opportunity so an accidental Stop is recoverable.
                        // The UI shows a "Stopped — Undo" snackbar; tapping Undo re-sends the
                        // last prompt. Only when there's a tracked prompt to undo back to.
                        val prompt = lastSentPrompt
                        if (prompt != null) _stopUndoEvents.trySend(prompt)
                    }
                    .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
            } finally {
                _aborting.value = false
            }
        }
    }

    /** Re-send the most recently-sent user prompt, used by the Stop-undo snackbar so an
     *  accidental Stop can be recovered. No-op if no prompt is tracked (e.g. the run was
     *  started by a command, not a text prompt). Clears the tracked prompt so a second Undo
     *  tap doesn't double-send. */
    fun resendLastPrompt() {
        val prompt = lastSentPrompt ?: return
        lastSentPrompt = null
        send(prompt, includeAttachments = false)
    }

    fun addAttachment(attachment: PendingAttachment) {
        // Enforce the cumulative size cap across the whole staged set (the per-file size and the
        // count caps are checked upstream). The base64 data URLs are held in memory and
        // re-serialized on every add/remove and again when the request body is built, so an
        // unbounded set OOMs. Reject rather than add, mirroring the other attachment limits'
        // snackbar via errorEvents. Size is the raw (pre-base64) byte count, matching the
        // per-file check, and includes attachments already staged (incl. restored ones).
        val staged = _attachments.value.sumOf { base64DataUrlByteSize(it.part.url) }
        if (staged + base64DataUrlByteSize(attachment.part.url) > NetworkConfig.maxTotalAttachmentBytes) {
            _errorEvents.trySend(ChatError(container.string(R.string.attachment_limit)))
            return
        }
        _attachments.update { it + attachment }
        persistAttachments()
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
        persistAttachments()
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
        persistAttachments()
    }

    /** Persist the current staged attachments so an interrupted compose survives process
     *  death (mirrors draft persistence). Fire-and-forget on [viewModelScope]. */
    private fun persistAttachments() {
        val snapshot = _attachments.value
        viewModelScope.launch {
            runCatchingCancellable { container.attachmentDraftStore.save(sessionId, snapshot.map { it.toPersisted() }) }
                .onFailure { Log.w("ChatViewModel", "Failed to persist attachments", it) }
        }
    }

    /** Revert the conversation to just before [messageId], hiding everything after it. The
     *  authoritative state also arrives via SessionUpdated; we flag it here for immediate
     *  feedback. */
    fun revertTo(messageId: String) {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.revert(sessionId, messageId) }
                .onSuccess {
                    _reverted.value = it.isReverted
                    _revertDiff.value = it.revert?.diff?.takeIf { diff -> diff.isNotBlank() }
                    _editing.value = false
                }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /**
     * Regenerate an assistant reply: revert the conversation to that assistant message (removing
     * it and everything after), then re-send the user prompt that immediately preceded it. A
     * one-tap "try again" mirroring ChatGPT/Claude, instead of the two-step revert + re-Send.
     *
     * Captures the preceding user text *before* the revert (the messages list updates
     * asynchronously via SSE once the revert lands). No-op if there's no preceding user prompt
     * or the assistant message isn't found.
     */
    fun regenerate(assistantMessageId: String) {
        val conn = connection ?: return
        val msgs = messages.value
        val idx = msgs.indexOfFirst { it.info.id == assistantMessageId }
        if (idx < 0) return
        val precedingUserText = (idx - 1 downTo 0)
            .asSequence()
            .map { msgs[it] }
            .firstOrNull { it.info is UserMessage }
            ?.parts
            ?.filterIsInstance<TextPart>()
            ?.joinToString("\n\n") { it.text }
            ?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.revert(sessionId, assistantMessageId) }
                .onSuccess { send(precedingUserText, includeAttachments = false) }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Continue a partially-generated assistant reply by sending a "continue" prompt
     *  without reverting (unlike [regenerate], which re-runs from the preceding user
     *  prompt and loses the partial). The partial reply stays as context so the model
     *  can pick up where it left off. This is the standard chat-app "continue" pattern
     *  for a reply that was aborted mid-stream. */
    fun continueRun(@Suppress("UNUSED_PARAMETER") assistantMessageId: String) {
        val conn = connection ?: return
        if (running.value || aborting.value) return
        if (!beginRun()) return
        // Reopen the relight gate so this run's streamed parts keep the indicator lit (and a
        // later SessionIdle re-closes it). Without this, runEndedByIdle stays true from the
        // previous run's SessionIdle and the relight guard at the event collector would suppress
        // every streamed part of the continuation, leaving the working indicator / Stop button /
        // foreground service off for the whole run.
        runEndedByIdle = false
        viewModelScope.launch {
            runCatchingCancellable {
                conn.api.sendPrompt(sessionId, text = "continue")
            }.onFailure {
                _running.value = false
                _errorEvents.trySend(ChatError(container.friendlyError(it)))
            }
        }
    }

    /** Load a previously-sent user prompt back into the composer to edit and re-send.
     *  Reverts the conversation to just before [messageId] (hiding it and everything after)
     *  and prefills the draft with [text]. The next Send continues from that checkpoint,
     *  effectively replacing the edited message; the reverted banner's Undo restores it.
     *  Overwrites the current draft — this is an explicit edit action on that message. */
    fun editMessage(messageId: String, text: String) {
        val conn = connection ?: return
        _draft.value = text.take(NetworkConfig.maxDraftLengthChars)
        _pendingQuote.value = null
        // Do NOT delegate to revertTo(): revertTo's onSuccess sets _editing = false, which races
        // this synchronous _editing = true (the async revert completes after editMessage returns
        // and clobbers the banner). Inline the revert so editMessage owns the _editing lifecycle:
        // set it true on success, matching the documented contract ("Cleared on unrevert and on
        // send"). revertTo() (the manual revert button) still clears _editing itself.
        viewModelScope.launch {
            runCatchingCancellable { conn.api.revert(sessionId, messageId) }
                .onSuccess {
                    _reverted.value = it.isReverted
                    _revertDiff.value = it.revert?.diff?.takeIf { diff -> diff.isNotBlank() }
                    _editing.value = true
                }
                .onFailure { _errorEvents.trySend(ChatError(container.friendlyError(it))) }
        }
    }

    /** Prefill the composer with [text] as a Markdown blockquote so the user can respond to a
     *  specific message inline. Appends to any existing draft (with a blank line) rather than
     *  overwriting, so quoting doesn't discard text already being typed. Also stashes the raw
     *  quoted text in [pendingQuote] so the composer can show a dismissible preview card. */
    fun quoteReply(text: String) {
        val quoted = text.trim().lineSequence().joinToString("\n") { "> $it" }
        if (quoted.isBlank()) return
        val current = _draft.value
        val combined = if (current.isBlank()) "$quoted\n\n" else "$current\n\n$quoted\n\n"
        // Cap like the composer's typed/pasted/dictated input so quoting onto a near-full draft
        // can't push it past the limit (negative remaining counter + an over-cap prompt sent).
        _draft.value = combined.take(NetworkConfig.maxDraftLengthChars)
        _pendingQuote.value = text.trim().takeIf { it.isNotBlank() }
    }

    /** Remove the pending quote-reply preview and strip the `>`-prefixed blockquote lines from
     *  the draft so canceling the quote card actually removes the quoted text, not just the chip.
     *  Strips by leading `> ` prefix (structural match) rather than by exact full-text replace,
     *  so a user who edited any quoted line is still cleaned up — the exact-text replaces would
     *  silently no-op on an edit, leaving orphan `>` lines in the draft after the chip vanished. */
    fun cancelQuoteReply() {
        val removed = _pendingQuote.value ?: return
        _pendingQuote.value = null
        val quoteLineCount = removed.trim().lineSequence().count()
        // Walk the draft and drop the first contiguous run of `> `-prefixed lines that matches
        // the stashed quote's line count (the block quoteReply inserted). Lines the user edited
        // still begin with `> ` (quoteReply prefixed every line), so a prefix match finds them
        // even when their trailing content diverges from the stashed original.
        val lines = _draft.value.split("\n").toMutableList()
        var runStart = -1
        var runLen = 0
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("> ") || lines[i] == ">") {
                if (runStart < 0) runStart = i
                runLen++
                if (runLen == quoteLineCount) {
                    // Strip the run plus a single trailing blank line that separated the quote
                    // from the reply (quoteReply appends "\n\n" after the block).
                    val stripUntil = i + 1
                    if (stripUntil < lines.size && lines[stripUntil].isEmpty()) {
                        lines.subList(runStart, stripUntil + 1).clear()
                    } else {
                        lines.subList(runStart, stripUntil).clear()
                    }
                    break
                }
                i++
            } else {
                runStart = -1
                runLen = 0
                i++
            }
        }
        _draft.value = lines.joinToString("\n").trimStart()
    }

    /** Fork the conversation by creating a brand-new session seeded with [text] as its first
     *  prompt (using the currently-selected model/agent). Emits the new session id via
     *  [branchEvents] so the UI can navigate to it. There's no server-side "branch" endpoint,
     *  so this starts a fresh session from the chosen prompt rather than copying history. */
    fun branchFrom(text: String) {
        val conn = connection ?: return
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        viewModelScope.launch {
            var createdId: String? = null
            runCatchingCancellable {
                val session = conn.repository.createSession(title = null)
                createdId = session.id
                conn.repository.sendPrompt(
                    session.id,
                    prompt,
                    model = _selectedModel.value?.ref,
                    agent = _selectedAgent.value,
                )
                session.id
            }
                .onSuccess { newId -> _branchEvents.trySend(newId) }
                .onFailure {
                    // If the session was created but the prompt failed, delete the orphaned
                    // empty session so failed branches don't accumulate on the server.
                    val id = createdId
                    if (id != null) {
                        runCatchingCancellable { conn.repository.deleteSession(id) }
                    }
                    _errorEvents.trySend(ChatError(container.string(R.string.branch_failed)))
                }
        }
    }

    /** Undo the active revert checkpoint, restoring the hidden messages. */
    fun unrevert() {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.unrevert(sessionId) }
                .onSuccess {
                    _reverted.value = it.isReverted
                    _revertDiff.value = it.revert?.diff?.takeIf { diff -> diff.isNotBlank() }
                    _editing.value = false
                }
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
        if (!beginRun()) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        // A new run is starting: reopen the re-light gate (as send() does) so a mid-run SSE
        // reconnect keeps the working indicator lit instead of leaving it stuck off.
        runEndedByIdle = false
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
        if (!beginRun()) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        // A new run is starting: reopen the re-light gate (as send() does) so a mid-run SSE
        // reconnect keeps the working indicator lit instead of leaving it stuck off.
        runEndedByIdle = false
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
        if (!beginRun()) {
            _errorEvents.trySend(ChatError(container.string(R.string.command_busy)))
            return
        }
        // A new run is starting: reopen the re-light gate (as send() does) so a mid-run SSE
        // reconnect keeps the working indicator lit instead of leaving it stuck off.
        runEndedByIdle = false
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

    /** Delete the current session via DELETE /session/:id. Schedules a deferred delete with an
     *  undo window and signals [deleteUndoEvents] so the ChatScreen shows an Undo snackbar
     *  *over the conversation the user is viewing* — they can cancel without first being
     *  navigated back to the session list. The actual REST delete runs after
     *  [NetworkConfig.undoDeleteDelayMs]; cancel via [cancelSessionDelete]. If the window
     *  expires, the scheduled delete's onDeleted sets [sessionDeleted] so the UI navigates away.
     *  A failure surfaces as a snackbar. */
    fun deleteSession() {
        val conn = connection ?: return
        container.scheduleSessionDelete(
            id = sessionId,
            delayMs = NetworkConfig.undoDeleteDelayMs,
            // Navigate away only once the deferred delete actually commits — so an in-place Undo
            // before the window expires keeps the user on this conversation.
            onDeleted = { _sessionDeleted.value = true },
            onError = { _errorEvents.trySend(ChatError(container.friendlyError(it))) },
        )
        _deleteUndoEvents.tryEmit(Unit)
    }

    /** Cancel a delete scheduled by [deleteSession] (the in-chat Undo action). Returns true if the
     *  delete was still pending (Undo succeeded). */
    fun cancelSessionDelete(): Boolean = container.cancelSessionDelete(sessionId)

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
        // Record a session-scoped grant so subsequent matching requests are auto-answered.
        // SESSION maps to ONCE on the wire (the server has no session-scope concept); the
        // client-side set is what gives it the "for this session" semantics.
        if (response == PermissionResponse.SESSION) {
            val type = permission.type.orEmpty()
            val pattern = permission.patternText.orEmpty()
            if (type.isNotEmpty()) sessionAllowed.add(type to pattern)
        }
        // Dismiss optimistically (revealing any queued request); permission.replied will confirm.
        // If the call fails we re-surface this request so the user can retry instead of being stuck
        // with a dismissed dialog and a tool run that's still paused server-side.
        resolvePermission(permission.id)
        viewModelScope.launch {
            runCatchingCancellable { conn.api.respondPermission(sessionId, permission.id, response) }
                .onFailure {
                    _errorEvents.trySend(ChatError(container.friendlyError(it)))
                    // Revoke the session-scoped grant on failure: enqueuePermission() auto-responds
                    // when (type, pattern) is in sessionAllowed, so leaving the entry in would loop
                    // respondPermission -> fail -> enqueuePermission -> respondPermission ... forever,
                    // spamming the dead server and the error snackbar without ever re-prompting the
                    // user. Removing it makes enqueuePermission fall through to re-queueing the
                    // request for the dialog so the user can retry.
                    if (response == PermissionResponse.SESSION) {
                        val type = permission.type.orEmpty()
                        val pattern = permission.patternText.orEmpty()
                        if (type.isNotEmpty()) sessionAllowed.remove(type to pattern)
                    }
                    enqueuePermission(permission)
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
        // Flush the pending draft — including an empty one. Persistence is otherwise debounced,
        // so if the user cleared the input and navigated back within the debounce window, the
        // debounce coroutine was cancelled with viewModelScope and the prefs still hold the
        // previous non-empty draft. flushDraft() removes the key when the text is blank, so
        // flushing commits the clear instead of resurrecting the deleted text.
        //
        // BUT respect the send-in-flight guard: send() optimistically clears _draft to "" WITHOUT
        // persisting the clear so a failed send can restore the text. If the user taps Send then
        // immediately navigates back (cancelling the in-flight send), flushing the blank _draft here
        // would remove the persisted key and erase the pre-send draft with no recovery. Skip the
        // flush while a send is in flight so the pre-send draft stays persisted (recoverable).
        if (!suppressDraftPersist.get()) container.draftStore.flushDraft(sessionId, _draft.value)
    }
}
