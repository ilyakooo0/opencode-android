package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable

/**
 * A saved opencode server connection. The [password] for HTTP Basic auth is resolved
 * separately from EncryptedSharedPreferences and is null when not loaded/needed.
 */
@Immutable
data class ServerProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val lastUsed: Long = 0,
    // When true, the client upgrades a cleartext http:// URL to https:// so credentials and
    // traffic are never sent over the wire in the clear for this server.
    val requireHttps: Boolean = false,
    // Optional OkHttp certificate pin(s) ("sha256/<base64>", whitespace/comma-separated for
    // multiple) applied to this server's TLS connection for defense against a rogue CA.
    val certPin: String? = null,
) {
    val hasAuth: Boolean get() = !username.isNullOrBlank()

    val displayLabel: String get() = label.takeIf { it.isNotBlank() } ?: baseUrl
}
