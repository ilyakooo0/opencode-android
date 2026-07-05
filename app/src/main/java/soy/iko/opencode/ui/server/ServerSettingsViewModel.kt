package soy.iko.opencode.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
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

/**
 * ViewModel for the Server Settings screen, which edits an *existing* saved server profile.
 * Holds the fields that don't appear on the add-server form: the display label, the advanced
 * security options (require-HTTPS + certificate pin), plus the URL and credentials. Saving
 * persists the profile and, when it's the active connection, reconnects to it so the edit
 * takes effect immediately. Deleting disconnects first when the profile is active.
 */
class ServerSettingsViewModel(
    private val container: AppContainer,
    private val profileId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditState(id = profileId))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                container.profileStore.profiles
                    .first { list -> list.any { p -> p.id == profileId } }
                    .firstOrNull { it.id == profileId }
            }
            if (existing != null) {
                // Normalize (trim) the dirty-snapshot fields the same way save() stores them,
                // so a stored value with surrounding whitespace doesn't make the freshly-opened
                // settings screen spuriously dirty (isDirty compares field.trim() against these).
                val init = InitialProfile(
                    label = existing.label.trim(),
                    baseUrl = existing.baseUrl.trim(),
                    username = existing.username.orEmpty().trim(),
                    password = existing.password.orEmpty().trim(),
                    requireHttps = existing.requireHttps,
                    certPin = existing.certPin.orEmpty().trim(),
                )
                _state.value = ServerEditState(
                    id = existing.id,
                    label = existing.label,
                    baseUrl = existing.baseUrl,
                    username = existing.username.orEmpty(),
                    password = existing.password.orEmpty(),
                    requireHttps = existing.requireHttps,
                    certPin = existing.certPin.orEmpty(),
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
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        // Clear both the inline credential note and the bottom-of-form error when any field
        // changes: once the user starts editing (e.g. correcting a rejected password), a stale
        // "Credentials rejected" message no longer describes the current input.
        _state.update { transform(it).copy(credentialsResult = null, error = null) }
    }

    /**
     * Probe the server *with* the entered credentials to validate them before saving. Only
     * meaningful once auth fields are visible (the server requires auth). Surfaces
     * [ServerEditState.credentialsResult] so the UI can show success/failure without
     * dismissing the form.
     */
    fun testCredentials() {
        val s = _state.value
        if (!s.canSave || s.testingCredentials) return
        if (s.username.isBlank() && s.password.isBlank()) return
        _state.update { it.copy(testingCredentials = true, credentialsResult = null, error = null) }
        // Test the trimmed URL/credentials that save() would actually store, and remember the
        // probed URL so a stale result is dropped if the user edited the base URL away while
        // the test was in flight.
        val probedUrl = normalizeForSave(s.baseUrl)
        val probedUser = s.username.trim()
        val probedPass = s.password.trim()
        val probedRequireHttps = s.requireHttps
        val probedCertPin = s.certPin.trim().takeIf { it.isNotBlank() }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                withTimeoutOrNull(NetworkConfig.testCredentialsTimeoutMs) {
                    container.probeWithCredentials(probedUrl, probedUser, probedPass, probedRequireHttps, probedCertPin)
                }
            }
            result.onSuccess { ok ->
                _state.update {
                    if (normalizeForSave(it.baseUrl) != probedUrl) return@update it.copy(testingCredentials = false)
                    it.copy(
                        testingCredentials = false,
                        credentialsResult = ok,
                        error = if (ok == true) null else container.string(R.string.credentials_rejected),
                    )
                }
            }.onFailure { e ->
                _state.update {
                    if (normalizeForSave(it.baseUrl) != probedUrl) return@update it.copy(testingCredentials = false)
                    it.copy(
                        testingCredentials = false,
                        credentialsResult = false,
                        error = container.friendlyError(e),
                    )
                }
            }
        }
    }

    /**
     * Persist the edited profile. When it's the active connection, reconnect so the change
     * (URL, credentials, security) takes effect immediately. Fires [onDone] on success so the
     * screen pops back.
     */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving || s.testingCredentials) return
        _state.update { it.copy(saving = true, error = null) }
        val savedBaseUrl = normalizeForSave(s.baseUrl)
        viewModelScope.launch {
            val result = runCatchingCancellable {
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles.first()
                } ?: emptyList()
                // Duplicate detection: warn when a *different* profile with the same URL and
                // label already exists. Excludes the profile being edited (by id) so re-saving
                // the same profile doesn't trigger.
                val savedLabel = s.label.trim().ifBlank { hostFromBaseUrl(savedBaseUrl) }
                val duplicate = existing.any { it.id != s.id && it.baseUrl == savedBaseUrl && it.label == savedLabel }
                if (duplicate) {
                    throw java.io.IOException(container.string(R.string.duplicate_server_warning))
                }
                // Preserve lastUsed so re-saving doesn't reset the profile's sort order. When
                // the load timed out (existing is empty) fall back to now rather than 0, which
                // would silently drop the user's most-recently-used server to the list bottom.
                val existingLastUsed = existing.firstOrNull { it.id == s.id }?.lastUsed ?: System.currentTimeMillis()
                val saved = ServerProfile(
                    id = s.id ?: UUID.randomUUID().toString(),
                    label = savedLabel,
                    baseUrl = savedBaseUrl,
                    username = s.username.trim().takeIf { it.isNotBlank() },
                    password = s.password.trim().takeIf { it.isNotEmpty() },
                    lastUsed = existingLastUsed,
                    requireHttps = s.requireHttps,
                    certPin = s.certPin.trim().takeIf { it.isNotBlank() },
                )
                container.profileStore.save(saved)
                // Persist the normalized values back into state and refresh the dirty snapshot
                // so the just-saved values no longer read as unsaved changes.
                _state.update {
                    it.copy(
                        id = saved.id,
                        label = saved.label,
                        baseUrl = saved.baseUrl,
                        initial = InitialProfile(
                            label = saved.label,
                            baseUrl = saved.baseUrl,
                            username = saved.username.orEmpty(),
                            password = saved.password.orEmpty(),
                            requireHttps = saved.requireHttps,
                            certPin = saved.certPin.orEmpty(),
                        ),
                    )
                }
                // If this is the active profile, reconnect so the edit (URL/creds/security)
                // takes effect immediately. A non-active profile just persists.
                if (container.activeConnection.value?.profile?.id == saved.id) {
                    container.connect(saved)
                    val pingOk = runCatchingCancellable { container.activeConnection.value?.api?.ping() }.isSuccess
                    if (!pingOk) {
                        throw java.io.IOException(container.string(R.string.error_ping_failed))
                    }
                }
            }
            result.onSuccess { onDone() }
                .onFailure { e -> _state.update { it.copy(error = container.friendlyError(e), saving = false) } }
        }
    }

    /**
     * Delete the profile being edited. Fires [onDone] on success so the screen pops back. The
     * active profile is disconnected first so the app doesn't hold a stale connection to a
     * deleted profile.
     */
    fun delete(onDone: () -> Unit) {
        val s = _state.value
        val id = s.id ?: return
        if (s.saving || s.testingCredentials) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { container.profileStore.delete(id) }
            result.onSuccess {
                if (container.activeConnection.value?.profile?.id == id) {
                    runCatchingCancellable { container.disconnect() }
                }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = container.friendlyError(e)) }
            }
        }
    }
}
