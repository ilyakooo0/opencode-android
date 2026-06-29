package soy.iko.opencode.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.OpencodeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.dataStore by preferencesDataStore(name = "server_profiles")

/**
 * Persists server profiles. Non-secret fields go to DataStore (as a JSON list);
 * the Basic-auth password is stored separately in EncryptedSharedPreferences, keyed
 * by profile id, and merged back in on read.
 */
class ProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val profilesKey = stringPreferencesKey("profiles_json")

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "server_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Stored shape on DataStore (everything except the secret password). */
    @Serializable
    private data class StoredProfile(
        val id: String,
        val label: String,
        val baseUrl: String,
        val username: String? = null,
        val lastUsed: Long = 0,
    )

    val profiles: Flow<List<ServerProfile>> = appContext.dataStore.data.map { prefs ->
        val json = prefs[profilesKey] ?: return@map emptyList()
        runCatching {
            OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), json)
        }.onFailure { Log.w("ProfileStore", "Failed to decode stored profiles, ignoring", it) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.lastUsed }
            .map { it.toProfile() }
    }

    private fun StoredProfile.toProfile() = ServerProfile(
        id = id,
        label = label,
        baseUrl = baseUrl,
        username = username,
        password = username?.let { passwordFor(id) },
        lastUsed = lastUsed,
    )

    /** Resolve a single profile with its password loaded, for opening a connection. */
    suspend fun resolve(profile: ServerProfile): ServerProfile =
        profile.copy(password = if (profile.hasAuth) passwordFor(profile.id) else null)

    fun passwordFor(id: String): String? = securePrefs.getString(passwordKey(id), null)

    suspend fun save(profile: ServerProfile) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let {
                runCatching { OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            val stored = StoredProfile(
                id = profile.id,
                label = profile.label,
                baseUrl = profile.baseUrl,
                username = profile.username?.takeIf { it.isNotBlank() },
                lastUsed = profile.lastUsed,
            )
            val updated = current.filterNot { it.id == profile.id } + stored
            prefs[profilesKey] = OpencodeJson.encodeToString(ListSerializer(StoredProfile.serializer()), updated)
        }
        // Secret goes to encrypted storage (or is cleared when auth removed).
        securePrefs.edit().apply {
            val pw = profile.password
            if (!profile.username.isNullOrBlank() && !pw.isNullOrEmpty()) {
                putString(passwordKey(profile.id), pw)
            } else {
                remove(passwordKey(profile.id))
            }
        }.apply()
    }

    suspend fun delete(id: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let {
                runCatching { OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[profilesKey] = OpencodeJson.encodeToString(
                ListSerializer(StoredProfile.serializer()),
                current.filterNot { it.id == id },
            )
        }
        securePrefs.edit().remove(passwordKey(id)).apply()
    }

    private fun passwordKey(id: String) = "pw_$id"
}
