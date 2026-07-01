package soy.iko.opencode.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A transient error surfaced as a snackbar, optionally paired with the profile that
 *  failed to connect so the snackbar can offer a Retry action. */
data class ConnectError(val message: String, val profile: ServerProfile?)

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    /** Ids optimistically hidden while their deferred delete's undo window is open, so a
     *  "deleted" row disappears immediately instead of lingering (tappable) for the whole
     *  undo window. Mirrors [SessionListViewModel]'s optimistic-removal pattern. */
    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    val profiles: StateFlow<List<ServerProfile>> =
        combine(container.profileStore.profiles, _hiddenIds) { profiles, hidden ->
            profiles.filterNot { it.id in hidden }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = emptyList(),
        )

    private val _connecting = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connecting.asStateFlow()

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

    /** Profile awaiting the undo window to expire before its REST delete fires. */
    // Keyed by profile id, not a single field: several deletes can overlap within the
    // undo window, and a single field would let the second overwrite the first — leaving
    // the first profile in the store forever while it's hidden from the list.
    private val pendingDeletes = mutableMapOf<String, ServerProfile>()

    fun connect(profile: ServerProfile, onConnected: () -> Unit) {
        if (_connecting.value != null) return
        _connecting.value = profile.id
        viewModelScope.launch {
            try {
                val result = runCatchingCancellable {
                    val conn = container.connect(profile)
                    conn.api.ping()
                }
                result.onSuccess { onConnected() }
                    .onFailure {
                        container.disconnect()
                        _errorEvents.tryEmit(ConnectError(container.friendlyError(it), profile))
                    }
            } finally {
                _connecting.value = null
            }
        }
    }

    /** Retry connecting to the most recently used profile — the pull-to-refresh action on
     *  the server list. Gives the user a familiar gesture to retry a failed auto-connect
     *  instead of having to tap a server card. */
    fun refresh(onConnected: () -> Unit) {
        if (_connecting.value != null || container.reconnecting.value) return
        val target = profiles.value.firstOrNull() ?: return
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
        pendingDeletes[profile.id] = profile
        // Optimistically hide the row so the UI feels instant; undo re-shows it.
        _hiddenIds.update { it + profile.id }
        if (container.activeConnection.value?.profile?.id == profile.id) {
            // disconnect() is suspend; run it on the VM scope so the optimistic removal
            // and undo emission aren't blocked on the connection close completing.
            viewModelScope.launch { container.disconnect() }
        }
        _undoEvents.tryEmit(profile.id)
        viewModelScope.launch {
            delay(NetworkConfig.undoServerDeleteDelayMs)
            if (pendingDeletes.remove(profile.id) != null) {
                runCatchingCancellable { container.profileStore.delete(profile.id) }
                    .onSuccess {
                        // Drop the optimistic hide only after the store has emitted the
                        // removal, so the row doesn't flash back before the list updates.
                        _hiddenIds.update { it - profile.id }
                    }
                    .onFailure {
                        // Delete failed: re-show the row so it isn't hidden forever.
                        _hiddenIds.update { it - profile.id }
                        _errorEvents.tryEmit(ConnectError(container.friendlyError(it), null))
                    }
            }
        }
    }

    /** Cancel a pending delete (Undo snackbar action) and re-show the hidden row. */
    fun undoDelete(profileId: String) {
        pendingDeletes.remove(profileId)
        _hiddenIds.update { it - profileId }
    }
}
