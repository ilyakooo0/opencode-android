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
    /** Snapshot of the profile as loaded (for dirty detection). Null until loaded. */
    val initial: InitialProfile? = null,
    /** Whether a credential test is in progress. */
    val testingCredentials: Boolean = false,
    /** Non-null when the last credential test succeeded (true) or failed (false). */
    val credentialsResult: Boolean? = null,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank() && isValidUrl(baseUrl)
    val isNew: Boolean get() = id == null
    /** True if any field differs from its loaded value. */
    val isDirty: Boolean
        get() {
            val init = initial ?: return false
            return label.trim() != init.label ||
                baseUrl.trim() != init.baseUrl ||
                username.trim() != init.username ||
                password.trim() != init.password
        }
}

/** Snapshot of the profile loaded into the editor, normalized the same way save() stores it. */
data class InitialProfile(
    val label: String,
    val baseUrl: String,
    val username: String,
    val password: String,
)

fun isValidUrl(url: String): Boolean = try {
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
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles
                        .first { it.isNotEmpty() || it.none { p -> p.id == profileId } }
                        .firstOrNull { it.id == profileId }
                }
                if (existing != null) {
                    val init = InitialProfile(
                        label = existing.label,
                        baseUrl = existing.baseUrl,
                        username = existing.username.orEmpty(),
                        password = existing.password.orEmpty(),
                    )
                    _state.value = ServerEditState(
                        id = existing.id,
                        label = existing.label,
                        baseUrl = existing.baseUrl,
                        username = existing.username.orEmpty(),
                        password = existing.password.orEmpty(),
                        loaded = true,
                        authFieldsVisible = existing.hasAuth,
                        initial = init,
                    )
                    return@launch
                }
                _state.value = ServerEditState(
                    id = profileId,
                    loaded = true,
                    error = container.string(R.string.error_load_timeout),
                )
                return@launch
            }
            // New-profile form: seed an initial snapshot of empty values so isDirty
            // becomes true the moment the user types anything.
            _state.update {
                it.copy(
                    loaded = true,
                    initial = InitialProfile("", "", "", ""),
                )
            }
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        _state.update { transform(it).copy(credentialsResult = null) }
    }

    fun probe() {
        val s = _state.value
        if (!s.canSave || s.probing) return
        _state.update { it.copy(probing = true, error = null, credentialsResult = null) }
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

    /**
     * Probe the server *with* the entered credentials to validate them before saving.
     * Only meaningful once auth fields are visible (the server requires auth); calling
     * it when no auth is needed is a no-op. Surfaces [ServerEditState.credentialsResult]
     * so the UI can show success/failure without dismissing the form.
     */
    fun testCredentials() {
        val s = _state.value
        if (!s.canSave || s.testingCredentials) return
        if (s.username.isBlank() && s.password.isBlank()) return
        _state.update { it.copy(testingCredentials = true, credentialsResult = null, error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { container.probeWithCredentials(s.baseUrl, s.username, s.password) }
            result.onSuccess { ok ->
                _state.update {
                    it.copy(
                        testingCredentials = false,
                        credentialsResult = ok,
                        error = if (ok) null else container.string(R.string.credentials_rejected),
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        testingCredentials = false,
                        credentialsResult = false,
                        error = container.friendlyError(e),
                    )
                }
            }
        }
    }

    fun save(onDone: () -> Unit) {
        saveInternal(connectAfter = false, onDone = onDone)
    }

    /** Save and then connect to the saved profile in one step. */
    fun saveAndConnect(onDone: () -> Unit) {
        saveInternal(connectAfter = true, onDone = onDone)
    }

    private fun saveInternal(connectAfter: Boolean, onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                val existingLastUsed = if (s.id != null) {
                    withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                        container.profileStore.profiles.first()
                            .firstOrNull { it.id == s.id }?.lastUsed
                    } ?: 0L
                } else 0L
                val saved = ServerProfile(
                    id = s.id ?: UUID.randomUUID().toString(),
                    label = s.label.trim(),
                    baseUrl = s.baseUrl.trim(),
                    username = s.username.trim().takeIf { it.isNotBlank() },
                    password = s.password.trim().takeIf { it.isNotEmpty() },
                    lastUsed = existingLastUsed,
                )
                container.profileStore.save(saved)
                if (connectAfter) {
                    container.connect(saved)
                    container.activeConnection.value?.api?.ping()
                }
            }
            result.onSuccess { onDone() }
                .onFailure { e -> _state.update { it.copy(error = container.friendlyError(e), saving = false) } }
        }
    }
}
