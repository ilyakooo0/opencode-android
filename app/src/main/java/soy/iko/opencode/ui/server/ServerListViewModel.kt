package soy.iko.opencode.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.di.OpencodeConnection
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A transient error surfaced as a snackbar, optionally paired with the profile that
 *  failed to connect so the snackbar can offer a Retry action. */
data class ConnectError(val message: String, val profile: ServerProfile?)

/** Result of a "Test connection" probe: latency in ms on success, or a user-facing message
 *  on failure. Carried by [ServerListViewModel.probeEvents] so the server list can surface
 *  it as a snackbar without navigating away. */
sealed class ProbeTestResult {
    data class Success(val latencyMs: Long) : ProbeTestResult()
    data class Failed(val message: String) : ProbeTestResult()
}

/** Sort order for the server list. RECENT (by lastUsed, the default) or NAME (A→Z). */
enum class ServerSortMode { RECENT, NAME }

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    /** Ids optimistically hidden while their deferred delete's undo window is open, so a
     *  "deleted" row disappears immediately instead of lingering (tappable) for the whole
     *  undo window. Mirrors [SessionListViewModel]'s optimistic-removal pattern. */
    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    /** Sort order for the list. A simple recent/name toggle (no persistence — the list is
     *  short enough that re-picking is cheap, matching the screen's other ephemeral state). */
    private val _sortMode = MutableStateFlow(ServerSortMode.RECENT)
    val sortMode: StateFlow<ServerSortMode> = _sortMode.asStateFlow()

    fun setSortMode(mode: ServerSortMode) {
        if (_sortMode.value != mode) _sortMode.value = mode
    }

    val profiles: StateFlow<List<ServerProfile>> =
        combine(container.profileStore.profiles, _hiddenIds, _sortMode) { profiles, hidden, sort ->
            val visible = profiles.filterNot { it.id in hidden }
            when (sort) {
                ServerSortMode.RECENT -> visible.sortedByDescending { it.lastUsed }
                ServerSortMode.NAME -> visible.sortedWith(
                    compareBy<ServerProfile, String>(String.CASE_INSENSITIVE_ORDER) { it.displayLabel }
                        .thenByDescending { it.lastUsed },
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = emptyList(),
        )

    private val _connecting = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connecting.asStateFlow()

    /** True until the first batch of profiles lands from DataStore, so the screen can render a
     *  loading spinner instead of flashing the empty state (the [profiles] flow's initial value
     *  is an empty list, which is indistinguishable from "loaded, no servers" without this). */
    val loading: StateFlow<Boolean> =
        container.profileStore.profiles.map { false }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = true,
            )

    /** One-shot error events surfaced as snackbars. A SharedFlow (not StateFlow) so each
     *  emission is delivered independently. */
    private val _errorEvents = MutableSharedFlow<ConnectError>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<ConnectError> = _errorEvents.asSharedFlow()

    /** One-shot events carrying the id of a server profile marked for deferred deletion,
     *  so the UI can show an Undo snackbar. Mirrors the session list's undo pattern. */
    private val _undoEvents = MutableSharedFlow<String>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val undoEvents: SharedFlow<String> = _undoEvents.asSharedFlow()

    /** One-shot "Test connection" results surfaced as snackbars. A SharedFlow (not StateFlow)
     *  so each emission is delivered independently. */
    private val _probeEvents = MutableSharedFlow<ProbeTestResult>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val probeEvents: SharedFlow<ProbeTestResult> = _probeEvents.asSharedFlow()

    /** Id of the profile currently being probed by [testConnection], so its row can show a
     *  spinner while the probe is in flight (mirrors [connectingId] for a full connect). */
    private val _probing = MutableStateFlow<String?>(null)
    val probingId: StateFlow<String?> = _probing.asStateFlow()

    /** Probe a server without establishing a long-lived connection or navigating away.
     *  Measures the round-trip latency of an authenticated ping and emits the result via
     *  [probeEvents]. Uses [AppContainer.probeWithCredentials] so the probe validates the
     *  same effective URL + auth the real connection would use, without replacing the
     *  active connection. Capped at one in-flight probe at a time so rapid taps don't
     *  multiply. */
    fun testConnection(profile: ServerProfile) {
        if (_probing.value != null) return
        _probing.value = profile.id
        viewModelScope.launch {
            try {
                val start = System.currentTimeMillis()
                val result = runCatchingCancellable {
                    withTimeoutOrNull(NetworkConfig.testCredentialsTimeoutMs) {
                        container.probeWithCredentials(
                            baseUrl = profile.baseUrl,
                            username = profile.username.orEmpty(),
                            password = profile.password.orEmpty(),
                            requireHttps = profile.requireHttps,
                            certPin = profile.certPin,
                        )
                    } ?: throw java.net.SocketTimeoutException("Probe timed out")
                }
                val latency = System.currentTimeMillis() - start
                _probeEvents.tryEmit(
                    result.fold(
                        onSuccess = { ok ->
                            // probeWithCredentials returns false on 401/403: the server was
                            // reached but auth failed. Distinguish "server requires auth but
                            // no credentials were sent" from "credentials were sent and
                            // rejected" so the user gets actionable feedback (enter creds vs
                            // fix creds) instead of a single ambiguous "check credentials".
                            if (ok) {
                                ProbeTestResult.Success(latency)
                            } else {
                                val hasCreds = !profile.username.isNullOrBlank() || !profile.password.isNullOrEmpty()
                                val msg = if (hasCreds) {
                                    container.string(R.string.credentials_incorrect)
                                } else {
                                    container.string(R.string.server_requires_auth)
                                }
                                ProbeTestResult.Failed(msg)
                            }
                        },
                        onFailure = { ProbeTestResult.Failed(container.friendlyError(it)) },
                    ),
                )
            } finally {
                _probing.value = null
            }
        }
    }

    fun connect(profile: ServerProfile, onConnected: () -> Unit) {
        if (_connecting.value != null) return
        _connecting.value = profile.id
        viewModelScope.launch {
            try {
                var conn: OpencodeConnection? = null
                // Wrap the connect + ping in a timeout so a hung/unresponsive server
                // doesn't leave the per-row spinner spinning indefinitely. The ping itself
                // also has a REST-level timeout, but the connection establishment path
                // (HttpClientFactory.create) can hang on DNS/TCP before any timeout applies.
                val result = runCatchingCancellable {
                    conn = container.connect(profile)
                    withTimeoutOrNull(NetworkConfig.testCredentialsTimeoutMs) { conn!!.api.ping() }
                        ?: throw java.net.SocketTimeoutException("Connect timed out")
                }
                result.onSuccess { onConnected() }
                    .onFailure {
                        // Only tear down the connection we created: a concurrent connect (or the
                        // cold-start auto-connect) may have replaced the active one, and
                        // disconnect() closes whatever is active — which would drop that one.
                        if (conn != null && container.activeConnection.value === conn) container.disconnect()
                        _errorEvents.tryEmit(ConnectError(container.friendlyError(it), profile))
                    }
            } finally {
                _connecting.value = null
            }
        }
    }

    /** Retry connecting to the most recently used profile — the pull-to-refresh action on
     *  the server list. Gives the user a familiar gesture to retry a failed auto-connect
     *  instead of having to tap a server card. Targets the profile with the highest
     *  lastUsed timestamp (the most natural "retry the one I was just on"), falling back
     *  to the first profile if none has been used yet. */
    fun refresh(onConnected: () -> Unit) {
        if (_connecting.value != null || container.reconnecting.value) return
        val target = profiles.value.maxByOrNull { it.lastUsed }
            ?: profiles.value.firstOrNull() ?: return
        connect(target, onConnected)
    }

    /**
     * Mark [profile] for deferred deletion and emit an Undo event. The actual
     * [ProfileStore.delete] is delayed by [NetworkConfig.undoServerDeleteDelayMs]; if
     * [undoDelete] is called before it fires, the profile is restored. If the profile
     * is the active one, disconnect immediately (the user has already confirmed via the
     * dialog) so the UI reflects the disconnection right away — the profile row is
     * hidden by the optimistic removal, and the undo re-shows it without reconnecting.
     */
    fun delete(profile: ServerProfile) {
        // Optimistically hide the row so the UI feels instant; undo re-shows it.
        _hiddenIds.update { it + profile.id }
        if (container.activeConnection.value?.profile?.id == profile.id) {
            // disconnect() is suspend; run it on the VM scope so the optimistic removal
            // and undo emission aren't blocked on the connection close completing.
            viewModelScope.launch { container.disconnect() }
        }
        _undoEvents.tryEmit(profile.id)
        // Run the deferred delete on the container's process-lived scope, not viewModelScope:
        // if the user navigates away from the server list within the undo window, a
        // viewModelScope job would be cancelled and the profile would never be deleted
        // (reappearing on return). The container owns the timer and the undo cancellation.
        container.scheduleProfileDelete(
            id = profile.id,
            delayMs = NetworkConfig.undoServerDeleteDelayMs,
            onDeleted = {
                // Drop the optimistic hide only after the store has emitted the
                // removal, so the row doesn't flash back before the list updates.
                _hiddenIds.update { it - profile.id }
            },
            onError = {
                // Delete failed: re-show the row so it isn't hidden forever.
                _hiddenIds.update { it - profile.id }
                _errorEvents.tryEmit(ConnectError(container.friendlyError(it), null))
            },
        )
    }

    /** Cancel a pending delete (Undo snackbar action) and re-show the hidden row. */
    fun undoDelete(profileId: String) {
        // If the deferred delete already fired (cancel returns false), the delete is
        // committing/committed — don't re-show the row, or we'd briefly resurrect a profile
        // that's on its way out (the onDeleted/onError callbacks un-hide it once the store
        // settles). Mirrors SessionListViewModel.undoDelete.
        if (!container.cancelProfileDelete(profileId)) return
        _hiddenIds.update { it - profileId }
    }

    /** Save a new profile decoded from a scanned/imported QR payload, returning the created
     *  profile so the caller can connect or route to the editor. Does not connect. */
    suspend fun importFromQr(qr: soy.iko.opencode.ui.components.ServerProfileQr): ServerProfile {
        val profile = ServerProfile(
            id = java.util.UUID.randomUUID().toString(),
            label = qr.label,
            baseUrl = qr.baseUrl,
            username = qr.username,
            password = qr.password,
            requireHttps = qr.requireHttps,
            certPin = qr.certPin,
        )
        container.profileStore.save(profile)
        return profile
    }
}
