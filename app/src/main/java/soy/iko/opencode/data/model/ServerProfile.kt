package soy.iko.opencode.data.model

/**
 * A saved opencode server connection. The [password] for HTTP Basic auth is resolved
 * separately from EncryptedSharedPreferences and is null when not loaded/needed.
 */
data class ServerProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val lastUsed: Long = 0,
) {
    val hasAuth: Boolean get() = !username.isNullOrBlank()

    val displayLabel: String get() = label.takeIf { it.isNotBlank() } ?: baseUrl
}
