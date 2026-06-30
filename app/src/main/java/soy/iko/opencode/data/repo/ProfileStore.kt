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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.dataStore by preferencesDataStore(name = "server_profiles")

/**
 * Persists server profiles. Non-secret fields go to DataStore (as a JSON list);
 * the Basic-auth password is stored separately in EncryptedSharedPreferences, keyed
 * by profile id, and merged back in on read.
 */
open class ProfileStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val profilesKey = stringPreferencesKey("profiles_json")

    /** Guards [migrateFallbackPasswords] so it runs at most once per process, not on every DataStore emission. */
    private val migrationDone = java.util.concurrent.atomic.AtomicBoolean(false)

    private val securePrefs: SharedPreferences? by lazy {
        val ctx = appContext ?: return@lazy null
        runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "server_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.e("ProfileStore", "Encrypted prefs unavailable; passwords will not be persisted", it)
            null
        }
    }

    /** True when EncryptedSharedPreferences could not be initialized. */
    open val securePrefsUnavailable: Boolean get() = securePrefs == null

    /** Plaintext fallback used only when encrypted prefs are unavailable, so the user
     *  can still connect — but passwords won't survive an app reinstall. */
    private val fallbackPrefs: SharedPreferences by lazy {
        appContext!!.getSharedPreferences("server_secrets_fallback", Context.MODE_PRIVATE)
    }

    private fun prefsForPasswords(): SharedPreferences =
        securePrefs ?: fallbackPrefs

    /** Migrate any plaintext passwords from the fallback prefs to secure prefs,
     *  then clear the fallback. Runs at most once per process — subsequent calls
     *  are no-ops — so DataStore re-emissions don't repeatedly scan SharedPreferences. */
    private suspend fun migrateFallbackPasswords() {
        if (!migrationDone.compareAndSet(false, true)) return
        val secure = securePrefs ?: return
        withContext(Dispatchers.IO) {
            val fallbackKeys = fallbackPrefs.all.keys.filter { it.startsWith("pw_") }
            if (fallbackKeys.isEmpty()) return@withContext
            // Write passwords to the secure store FIRST, then remove them from the
            // plaintext fallback. SharedPreferences.apply() persists to disk
            // asynchronously, so removing from fallback before the secure batch lands
            // could lose the password if the process dies between the two writes.
            // Writing secure first makes the worst case a harmless duplicate (both
            // stores hold it; the migration is idempotent) rather than data loss.
            secure.edit().apply {
                for (key in fallbackKeys) {
                    val pw = fallbackPrefs.getString(key, null) ?: continue
                    putString(key, pw)
                }
            }.apply()
            fallbackPrefs.edit().apply {
                for (key in fallbackKeys) remove(key)
            }.apply()
        }
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

    open val profiles: Flow<List<ServerProfile>> = appContext?.dataStore?.data?.map { prefs ->
        // Migrate any orphaned plaintext passwords to secure prefs on first load.
        migrateFallbackPasswords()
        val json = prefs[profilesKey] ?: return@map emptyList()
        runCatching {
            OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), json)
        }.onFailure { Log.w("ProfileStore", "Failed to decode stored profiles, ignoring", it) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.lastUsed }
            .map { it.toProfile() }
    } ?: kotlinx.coroutines.flow.flowOf(emptyList())

    private suspend fun StoredProfile.toProfile(): ServerProfile {
        val pw = username?.let { passwordFor(id) }
        return ServerProfile(
            id = id,
            label = label,
            baseUrl = baseUrl,
            username = username,
            password = pw,
            lastUsed = lastUsed,
        )
    }

    open suspend fun resolve(profile: ServerProfile): ServerProfile {
        val pw = if (profile.hasAuth) passwordFor(profile.id) else null
        return profile.copy(password = pw)
    }

    private suspend fun passwordFor(id: String): String? =
        withContext(Dispatchers.IO) { prefsForPasswords().getString(passwordKey(id), null) }

    open suspend fun save(profile: ServerProfile) {
        // Write the password first so that if the DataStore write fails, we're left
        // with an orphaned password (harmless, cleaned up on next save/delete) rather
        // than a profile with no password (which would break authentication).
        withContext(Dispatchers.IO) {
            prefsForPasswords().edit().apply {
                val pw = profile.password
                if (!profile.username.isNullOrBlank() && !pw.isNullOrEmpty()) {
                    putString(passwordKey(profile.id), pw)
                } else {
                    remove(passwordKey(profile.id))
                }
            }.apply()
        }
        appContext!!.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let {
                runCatching { OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            // If the incoming profile has lastUsed=0 (e.g. ServerEditViewModel.save()
            // couldn't read the existing value due to a DataStore timeout), preserve
            // the existing nonzero lastUsed so saving an edit doesn't reset the
            // profile's sort position in the server list.
            val preservedLastUsed = if (profile.lastUsed == 0L) {
                current.firstOrNull { it.id == profile.id }?.lastUsed ?: 0L
            } else {
                profile.lastUsed
            }
            val stored = StoredProfile(
                id = profile.id,
                label = profile.label,
                baseUrl = profile.baseUrl,
                username = profile.username?.takeIf { it.isNotBlank() },
                lastUsed = preservedLastUsed,
            )
            val updated = current.filterNot { it.id == profile.id } + stored
            prefs[profilesKey] = OpencodeJson.encodeToString(ListSerializer(StoredProfile.serializer()), updated)
        }
    }

    open suspend fun delete(id: String) {
        // Delete the profile from DataStore first, then the password. If the process
        // dies between the two, an orphaned password remains (harmless, cleaned up on
        // next save/delete) rather than a profile with no password.
        appContext!!.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let {
                runCatching { OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[profilesKey] = OpencodeJson.encodeToString(
                ListSerializer(StoredProfile.serializer()),
                current.filterNot { it.id == id },
            )
        }
        // Clean up the password in both secure and fallback prefs so no orphaned
        // plaintext password lingers if secure prefs were temporarily unavailable.
        withContext(Dispatchers.IO) {
            val key = passwordKey(id)
            securePrefs?.edit()?.remove(key)?.apply()
            fallbackPrefs.edit().remove(key).apply()
        }
    }

    private fun passwordKey(id: String) = "pw_$id"
}
