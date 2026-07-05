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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /** Upgrade cleartext http:// to https:// for this server. */
    val requireHttps: Boolean = false,
    /** Optional OkHttp certificate pin(s), "sha256/<base64>" (whitespace/comma separated). */
    val certPin: String = "",
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** Whether the auth (username/password) fields are shown. */
    val authFieldsVisible: Boolean = false,
    /** Snapshot of the profile as loaded (for dirty detection). Null until loaded. */
    val initial: InitialProfile? = null,
    /** Whether a credential test is in progress. */
    val testingCredentials: Boolean = false,
    /** Non-null when the last credential test succeeded (true) or failed (false). */
    val credentialsResult: Boolean? = null,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank() && isValidUrl(baseUrl) && certPinValid
    val isNew: Boolean get() = id == null
    /** A blank pin is fine (feature off); otherwise every entry must be a valid OkHttp pin. */
    val certPinValid: Boolean get() = isValidCertPin(certPin)
    /** True if any field differs from its loaded value. */
    val isDirty: Boolean
        get() {
            val init = initial ?: return false
            return label.trim() != init.label ||
                baseUrl.trim() != init.baseUrl ||
                username.trim() != init.username ||
                password.trim() != init.password ||
                requireHttps != init.requireHttps ||
                certPin.trim() != init.certPin
        }
}

/** Snapshot of the profile loaded into the editor, normalized the same way save() stores it. */
data class InitialProfile(
    val label: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val requireHttps: Boolean = false,
    val certPin: String = "",
)

/** True when the certificate-pin field is empty (feature off) or every whitespace/comma
 *  separated entry is a well-formed OkHttp "sha256/<base64>" (or legacy "sha1/<base64>") pin. */
fun isValidCertPin(raw: String): Boolean {
    val entries = raw.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.isEmpty()) return true
    // Require a well-formed sha256/ or sha1/ <base64>= pin (at least one base64 char before the
    // padding). This rejects obviously malformed entries like "sha256/=" or "sha256/===" and
    // unsupported algorithms like "md5/…". sha1 is accepted because OkHttp's CertificatePinner
    // accepts it too — the validator must match what the runtime accepts, or a well-formed
    // sha1 pin would be un-saveable. A real pin is 43 base64 chars + '=', but we accept any
    // length for demo/test pins.
    val pinRegex = Regex("(sha256|sha1)/[A-Za-z0-9+/]+=")
    return entries.all { it.matches(pinRegex) }
}

/**
 * Return [input] with an `https://` scheme prepended when it has no scheme, mirroring
 * [HttpClientFactory.normalizeBaseUrl]. Lets the user type a bare host ("example.com",
 * "192.168.1.10:4096") without manually prefixing a protocol; we always assume HTTPS.
 * Pass through unchanged when [input] already has a scheme.
 */
fun normalizeForSave(input: String): String {
    val trimmed = input.trim()
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

fun isValidUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return false
    // Accept any URL OkHttp can parse, *or* a schemeless bare host that we can prefix with
    // https:// ourselves. This lets the user type "example.com" or "192.168.1.10:4096"
    // without a protocol: canSave flips true and Save/Connect prepend https:// via
    // [normalizeForSave]. A non-http scheme like "ftp://…" is rejected explicitly so we
    // don't accidentally accept "ftp://example.com" by prepending https:// (which would
    // parse as host="ftp", path="//example.com"). Truly invalid input ("not a url") still
    // fails here so canSave stays false.
    if (trimmed.toHttpUrlOrNull() != null) return true
    if ("://" in trimmed) return false
    return "https://$trimmed".toHttpUrlOrNull() != null
}

class ServerEditViewModel(
    private val container: AppContainer,
    private val profileId: String?,
    private val sourceId: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditState(id = profileId))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (profileId != null) {
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles
                        .first { list -> list.any { p -> p.id == profileId } }
                        .firstOrNull { it.id == profileId }
                }
                if (existing != null) {
                    // FIX 21(a): normalize (trim) the dirty-snapshot fields the same way
                    // save() stores them, so a stored value with surrounding whitespace
                    // doesn't make the freshly-opened editor spuriously dirty (isDirty
                    // compares field.trim() against these).
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
                return@launch
            }
            // Duplicate: seed the form from an existing profile but as a NEW profile
            // (id stays null so save() generates a fresh one). Appends " (copy)" to the
            // label so the user can tell the duplicate from the original at a glance.
            if (sourceId != null) {
                val source = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles
                        .first { list -> list.any { p -> p.id == sourceId } }
                        .firstOrNull { it.id == sourceId }
                }
                if (source != null) {
                    val dupLabel = if (source.label.isBlank()) source.baseUrl + " (copy)" else "${source.label} (copy)"
                    _state.value = ServerEditState(
                        id = null,
                        label = dupLabel,
                        baseUrl = source.baseUrl,
                        username = source.username.orEmpty(),
                        password = source.password.orEmpty(),
                        requireHttps = source.requireHttps,
                        certPin = source.certPin.orEmpty(),
                        loaded = true,
                        authFieldsVisible = source.hasAuth,
                        // Snapshot the seeded (copy) values so an untouched duplicate isn't
                        // immediately dirty — an empty initial would make backing out of a
                        // freshly opened duplicate always trigger the discard-changes dialog.
                        initial = InitialProfile(
                            label = dupLabel.trim(),
                            baseUrl = source.baseUrl.trim(),
                            username = source.username.orEmpty().trim(),
                            password = source.password.orEmpty().trim(),
                            requireHttps = source.requireHttps,
                            certPin = source.certPin.orEmpty().trim(),
                        ),
                    )
                    return@launch
                }
                // Source not found — fall through to a blank new-profile form.
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
        // Clear both the inline credential note and the bottom-of-form error when any field
        // changes: once the user starts editing (e.g. correcting a rejected password), a stale
        // "Credentials rejected" / test-failure message no longer describes the current input.
        _state.update { transform(it).copy(credentialsResult = null, error = null) }
    }

    /**
     * Primary action: save the profile and connect to it. When the server needs auth and the
     * user hasn't supplied credentials yet, a connectivity probe reveals the username/password
     * fields instead of connecting with none — folding the former separate "Check connectivity"
     * step into this single action so the common case is one tap.
     */
    fun connect(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        // Credentials already known (an authed profile is loaded, or the user typed some): skip
        // the probe and go straight to save + connect. A wrong password then surfaces as a
        // connect error rather than silently re-opening the auth fields.
        if (s.authFieldsVisible || s.username.isNotBlank() || s.password.isNotBlank()) {
            saveInternal(connectAfter = true, onDone = onDone)
            return
        }
        _state.update { it.copy(saving = true, error = null, credentialsResult = null) }
        // FIX 21(b)/FIX 11: probe the trimmed URL save() would store, and remember it so a stale
        // result is dropped if the user edited the base URL away while the probe was in flight.
        // normalizeForSave applies the same scheme prefix save() would persist, so a bare host
        // typed without http:// is probed at the URL it'll actually be saved as.
        val probedUrl = normalizeForSave(s.baseUrl)
        val probedRequireHttps = s.requireHttps
        val probedCertPin = s.certPin.trim().takeIf { it.isNotBlank() }
        viewModelScope.launch {
            val result = runCatchingCancellable { container.probeServer(probedUrl, probedRequireHttps, probedCertPin) }
            result.onSuccess { pr ->
                // Stale result (URL edited away mid-probe): clear the spinner and stop, else
                // `saving` latches true and blocks re-connecting.
                if (normalizeForSave(_state.value.baseUrl) != probedUrl) {
                    _state.update { it.copy(saving = false) }
                    return@onSuccess
                }
                when (pr) {
                    is ProbeResult.NeedsAuth ->
                        _state.update { it.copy(saving = false, authFieldsVisible = true, error = null) }
                    is ProbeResult.Unreachable ->
                        _state.update { it.copy(saving = false, error = pr.error) }
                    is ProbeResult.Reachable -> {
                        // Reachable without auth: clear the probe-owned saving flag and hand off
                        // to the normal save+connect path (saveInternal re-sets saving itself).
                        _state.update { it.copy(saving = false) }
                        saveInternal(connectAfter = true, onDone = onDone)
                    }
                }
            }.onFailure { e ->
                _state.update {
                    if (normalizeForSave(it.baseUrl) != probedUrl) it.copy(saving = false)
                    else it.copy(saving = false, error = container.friendlyError(e))
                }
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
        // FIX 21(b): test the trimmed URL/credentials that save() would actually store.
        // FIX 11: remember the probed URL so a stale result is dropped if the user
        // edited the base URL away while the test was in flight.
        val probedUrl = normalizeForSave(s.baseUrl)
        val probedUser = s.username.trim()
        val probedPass = s.password.trim()
        val probedRequireHttps = s.requireHttps
        val probedCertPin = s.certPin.trim().takeIf { it.isNotBlank() }
        viewModelScope.launch {
            // Bound the probe so a hung/unresponsive server doesn't leave the "Testing…"
            // spinner spinning indefinitely. A timeout surfaces as a failure (the probe
            // didn't confirm credentials), not a silent hang.
            val result = runCatchingCancellable {
                withTimeoutOrNull(NetworkConfig.testCredentialsTimeoutMs) {
                    container.probeWithCredentials(probedUrl, probedUser, probedPass, probedRequireHttps, probedCertPin)
                }
            }
            result.onSuccess { ok ->
                _state.update {
                    // Stale result: clear the spinner but drop the verdict (see probe()).
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
     * Delete the profile being edited (only valid for an existing profile, not a new one).
     * Fires [onDone] on success so the screen pops back to the list. The active profile can't
     * be deleted here (the list enforces the same guard); surface an error instead.
     */
    fun delete(onDone: () -> Unit) {
        val s = _state.value
        val id = s.id ?: return
        if (s.saving || s.testingCredentials) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { container.profileStore.delete(id) }
            result.onSuccess {
                // If this was the active profile, disconnect so the app doesn't hold a stale
                // connection to a deleted profile. The list screen also guards against deleting
                // the active profile, but the edit screen reaches here via its own overflow.
                if (container.activeConnection.value?.profile?.id == id) {
                    runCatchingCancellable { container.disconnect() }
                }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = container.friendlyError(e)) }
            }
        }
    }

    private fun saveInternal(connectAfter: Boolean, onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        _state.update { it.copy(saving = true) }
        // Prepend https:// to a bare host so the saved profile is immediately connectable
        // and re-opening the editor doesn't re-show an "add scheme" hint. The user can still
        // opt into cleartext by typing http:// explicitly.
        val savedBaseUrl = normalizeForSave(s.baseUrl)
        viewModelScope.launch {
            val result = runCatchingCancellable {
                // Duplicate detection: warn when a different profile with the same URL
                // and label already exists, so the user doesn't silently create a second
                // entry identical to an existing one. The check excludes the profile
                // being edited (by id) so re-saving an existing profile doesn't trigger.
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles.first()
                } ?: emptyList()
                val duplicate = existing.any { it.id != s.id && it.baseUrl == savedBaseUrl && it.label == s.label.trim() }
                if (duplicate) {
                    throw java.io.IOException(container.string(R.string.duplicate_server_warning))
                }
                // Preserve the existing profile's lastUsed so re-saving doesn't reset its
                // sort order. When the timeout fired (existing is empty) and this is an
                // edit of an existing profile (s.id != null), we can't know the prior
                // lastUsed — fall back to the current wall-clock time rather than 0 (which
                // would silently drop the user's most-recently-used server to the bottom of
                // the server list). A fresh save bumping lastUsed to "now" is the same
                // behavior connectLocked applies on a real connect, so this is consistent.
                val existingLastUsed = if (s.id != null) {
                    existing.firstOrNull { it.id == s.id }?.lastUsed ?: System.currentTimeMillis()
                } else 0L
                val saved = ServerProfile(
                    id = s.id ?: UUID.randomUUID().toString(),
                    label = s.label.trim(),
                    baseUrl = savedBaseUrl,
                    username = s.username.trim().takeIf { it.isNotBlank() },
                    password = s.password.trim().takeIf { it.isNotEmpty() },
                    lastUsed = existingLastUsed,
                    requireHttps = s.requireHttps,
                    certPin = s.certPin.trim().takeIf { it.isNotBlank() },
                )
                container.profileStore.save(saved)
                // FIX 3: persist the (possibly freshly-generated) id and the normalized
                // values back into state right after the save succeeds — before the
                // connect/ping that may throw. Otherwise a retry after a connect failure
                // would see id == null again, mint a new UUID and create a DUPLICATE
                // profile instead of upserting the same row. Also refresh the dirty
                // snapshot so the just-saved values no longer read as unsaved changes.
                // The base URL is updated to the schemed form too, so a freshly-saved
                // bare-host entry doesn't read as dirty against its own saved value.
                _state.update {
                    it.copy(
                        id = saved.id,
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
                if (connectAfter) {
                    container.connect(saved)
                    // Await the ping and treat a failure as a failed save: without this, a server
                    // that accepts the connect but is half-up (wrong path, unresponsive /info) would
                    // navigate to the session list as if everything succeeded, leaving the user
                    // stranded on an empty screen with no error.
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
}
