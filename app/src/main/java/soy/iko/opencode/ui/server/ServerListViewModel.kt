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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A transient error surfaced as a snackbar, optionally paired with the profile that
 *  failed to connect so the snackbar can offer a Retry action. */
data class ConnectError(val message: String, val profile: ServerProfile?)

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
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
    private var pendingDelete: ServerProfile? = null

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

    /**
     * Mark [profile] for deferred deletion and emit an Undo event. The actual
     * [ProfileStore.delete] is delayed by [NetworkConfig.undoServerDeleteDelayMs]; if
     * [undoDelete] is called before it fires, the profile is restored. If the profile
     * is the active one, disconnect immediately (the user has already confirmed via the
     * dialog) so the UI reflects the disconnection right away — the profile row is
     * hidden by the optimistic removal, and the undo re-shows it without reconnecting.
     */
    fun delete(profile: ServerProfile) {
        pendingDelete = profile
        if (container.activeConnection.value?.profile?.id == profile.id) {
            // disconnect() is suspend; run it on the VM scope so the optimistic removal
            // and undo emission aren't blocked on the connection close completing.
            viewModelScope.launch { container.disconnect() }
        }
        _undoEvents.tryEmit(profile.id)
        viewModelScope.launch {
            delay(NetworkConfig.undoServerDeleteDelayMs)
            val toDelete = pendingDelete
            if (toDelete != null && toDelete.id == profile.id) {
                pendingDelete = null
                runCatchingCancellable { container.profileStore.delete(profile.id) }
                    .onFailure { _errorEvents.tryEmit(ConnectError(container.friendlyError(it), null)) }
            }
        }
    }

    /** Cancel a pending delete (Undo snackbar action). */
    fun undoDelete(profileId: String) {
        val pending = pendingDelete
        if (pending != null && pending.id == profileId) {
            pendingDelete = null
        }
    }
}
