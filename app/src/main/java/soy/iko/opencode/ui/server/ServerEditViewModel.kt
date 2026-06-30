package soy.iko.opencode.ui.server

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.di.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import java.util.UUID

@Immutable
data class ServerEditState(
    val id: String? = null,
    val label: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** Whether the auth (username/password) fields are shown. */
    val authFieldsVisible: Boolean = false,
    /** Whether a connectivity/auth probe is in progress. */
    val probing: Boolean = false,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank() && isValidUrl(baseUrl)
    val isNew: Boolean get() = id == null
}

private fun isValidUrl(url: String): Boolean = try {
    val u = java.net.URI(url.trim())
    u.scheme != null && u.host != null && (u.scheme == "http" || u.scheme == "https")
} catch (_: Exception) {
    false
}

class ServerEditViewModel(
    private val container: AppContainer,
    private val profileId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditState(id = profileId))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (profileId != null) {
                // Wait for a non-empty profile list before searching, so DataStore
                // emitting an empty list during loading doesn't cause a false timeout.
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles
                        .first { it.isNotEmpty() || it.none { p -> p.id == profileId } }
                        .firstOrNull { it.id == profileId }
                }
                if (existing != null) {
                    _state.value = ServerEditState(
                        id = existing.id,
                        label = existing.label,
                        baseUrl = existing.baseUrl,
                        username = existing.username.orEmpty(),
                        password = existing.password.orEmpty(),
                        loaded = true,
                        // An existing profile that already has credentials saved should
                        // show the auth fields immediately — the user may want to edit them.
                        authFieldsVisible = existing.hasAuth,
                    )
                    return@launch
                }
                // Profile existed but timed out loading — show an error instead of
                // silently presenting an empty form that looks like "new server".
                _state.value = ServerEditState(
                    id = profileId,
                    loaded = true,
                    error = container.string(R.string.error_load_timeout),
                )
                return@launch
            }
            _state.update { it.copy(loaded = true) }
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        _state.update(transform)
    }

    /**
     * Probe the server at [baseUrl] to check reachability and detect whether
     * authentication is required. On success the auth fields are shown only if the
     * server returned an auth error (401/403); on failure the error is surfaced.
     * Editing the base URL after a probe resets the auth-visibility so the next
     * save re-probes against the new URL.
     */
    fun probe() {
        val s = _state.value
        if (!s.canSave || s.probing) return
        _state.update { it.copy(probing = true, error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { container.probeServer(s.baseUrl) }
            result.onSuccess { pr ->
                _state.update {
                    when (pr) {
                        is ProbeResult.Reachable -> it.copy(
                            probing = false,
                            authFieldsVisible = false,
                            error = null,
                        )
                        is ProbeResult.NeedsAuth -> it.copy(
                            probing = false,
                            authFieldsVisible = true,
                            error = null,
                        )
                        is ProbeResult.Unreachable -> it.copy(
                            probing = false,
                            authFieldsVisible = false,
                            error = pr.error,
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(probing = false, error = container.friendlyError(e)) }
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                // Preserve the existing lastUsed timestamp so saving an edit doesn't
                // reset the profile's sort position (profiles are sorted descending
                // by lastUsed). connect() is responsible for updating lastUsed on
                // actual server use. If loading the existing profile times out,
                // lastUsed=0 is passed — ProfileStore.save() preserves the existing
                // nonzero lastUsed in that case so the sort position isn't lost.
                val existingLastUsed = if (s.id != null) {
                    withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                        container.profileStore.profiles.first()
                            .firstOrNull { it.id == s.id }?.lastUsed
                    } ?: 0L
                } else 0L
                container.profileStore.save(
                    ServerProfile(
                        id = s.id ?: UUID.randomUUID().toString(),
                        label = s.label.trim(),
                        baseUrl = s.baseUrl.trim(),
                        username = s.username.trim().takeIf { it.isNotBlank() },
                        password = s.password.trim().takeIf { it.isNotEmpty() },
                        lastUsed = existingLastUsed,
                    ),
                )
            }
            result.onSuccess { onDone() }
                .onFailure { e -> _state.update { it.copy(error = container.friendlyError(e), saving = false) } }
        }
    }
}
