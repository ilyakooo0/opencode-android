// Jetpack Security (androidx.security:security-crypto) was deprecated wholesale in 1.1.0 with
// no first-party drop-in replacement; we intentionally keep using EncryptedSharedPreferences /
// MasterKey for the encrypted password store (on-disk-compatible, still functional). Suppress
// the expected deprecation warnings here so the build log stays clean until a future migration.
@file:Suppress("DEPRECATION")

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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

    /** Guards [migrateFallbackPasswords] so it runs at most once per process, not on every
     *  DataStore emission. The mutex serializes concurrent callers so a second collector waits
     *  for the migration's writes to land (rather than skipping ahead and reading empty secure
     *  prefs mid-migration); [migrationDone] is only set true *after* the writes complete. */
    private val migrationMutex = Mutex()
    @Volatile private var migrationDone = false

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
     *  then clear the fallback. Also reaps orphaned password keys from BOTH stores whose
     *  `pw_<id>` matches no profile in [knownProfileIds] — a prior [delete] that raced a
     *  process death (DataStore write landed, password removal didn't) would otherwise leave
     *  the encrypted password stranded in `server_secrets` forever, since nothing else cleans
     *  it up. Runs at most once per process — subsequent calls are no-ops — so DataStore
     *  re-emissions don't repeatedly scan SharedPreferences. */
    private suspend fun migrateFallbackPasswords(knownProfileIds: Set<String>) {
        if (migrationDone) return
        migrationMutex.withLock {
            // Re-check inside the lock: a concurrent caller may have completed the migration
            // while we were waiting. Serializing here (rather than a bare compareAndSet that
            // marks done before doing the work) ensures a second collector doesn't read the
            // secure store before the first caller's write has populated it.
            if (migrationDone) return
            val secure = securePrefs
            withContext(Dispatchers.IO) {
                val fallbackKeys = fallbackPrefs.all.keys.filter { it.startsWith("pw_") }
                if (secure != null && fallbackKeys.isNotEmpty()) {
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
                // Reap orphaned password keys: a prior delete whose password removal raced a
                // process death leaves the encrypted entry stranded forever (nothing else
                // cleans it up, and the id may never be reused). Keyed by pw_<id>, so an orphan
                // is any key whose id isn't in the current profile set. Reap from both stores
                // since the fallback is written alongside secure in save().
                reapOrphanPasswords(secure, fallbackPrefs, knownProfileIds)
            }
            // Only mark done once the writes are in the secure store's in-memory cache (apply()
            // updates it synchronously), so the next collector past the mutex reads real values.
            migrationDone = true
        }
    }

    /** Remove `pw_<id>` keys from [secure] and [fallback] whose id matches no profile in
     *  [knownProfileIds]. A prior [delete] whose password removal raced a process death leaves
     *  the encrypted password stranded forever; this sweep (called once per process from
     *  [migrateFallbackPasswords]) reaps them so the secure/fallback stores don't accumulate
     *  orphaned credentials for deleted profiles. No-op when a store is null or has no orphans. */
    private fun reapOrphanPasswords(
        secure: SharedPreferences?,
        fallback: SharedPreferences,
        knownProfileIds: Set<String>,
    ) {
        val secureOrphans = secure?.all?.keys
            ?.filter { it.startsWith("pw_") && it.removePrefix("pw_") !in knownProfileIds }
            ?: emptyList()
        if (secureOrphans.isNotEmpty()) {
            secure!!.edit().apply { secureOrphans.forEach { remove(it) } }.apply()
        }
        val fallbackOrphans = fallback.all.keys
            .filter { it.startsWith("pw_") && it.removePrefix("pw_") !in knownProfileIds }
        if (fallbackOrphans.isNotEmpty()) {
            fallback.edit().apply { fallbackOrphans.forEach { remove(it) } }.apply()
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
        // Non-secret TLS options; default to prior behavior for profiles saved before these
        // fields existed (deserialization uses these defaults for missing keys).
        val requireHttps: Boolean = false,
        val certPin: String? = null,
    )

    open val profiles: Flow<List<ServerProfile>> = appContext?.dataStore?.data?.map { prefs ->
        // Decode the stored profiles first so the migration sweep can reap orphaned password
        // keys (whose pw_<id> matches no stored profile) in the same once-per-process pass.
        val json = prefs[profilesKey]
        val stored = json?.let {
            runCatching {
                OpencodeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), it)
            }.onFailure { Log.w("ProfileStore", "Failed to decode stored profiles, ignoring", it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        // Migrate any orphaned plaintext passwords to secure prefs, and reap any secure-pref
        // password keys whose profile was deleted while the process was gone (see
        // [migrateFallbackPasswords] for the sweep rationale).
        migrateFallbackPasswords(stored.mapTo(mutableSetOf()) { it.id })
        val sorted = stored.sortedByDescending { it.lastUsed }
        // Batch all password lookups into a single IO dispatch instead of one
        // withContext round-trip per profile (N dispatches → 1). DataStore already
        // runs this map block on IO, but each passwordFor() call still pays the
        // suspend + redispatch overhead.
        val pwPrefs = prefsForPasswords()
        sorted.map { it.toProfile(pwPrefs) }
    }
        // The .map above runs in the *collector's* context, and every consumer collects on
        // viewModelScope (Main). Without this, kotlinx JSON decode + EncryptedSharedPreferences
        // AES decryption would run on the UI thread on each emission. flowOn moves that work to IO.
        ?.flowOn(Dispatchers.IO)
        ?: kotlinx.coroutines.flow.flowOf(emptyList())

    private fun StoredProfile.toProfile(pwPrefs: SharedPreferences): ServerProfile {
        val pw = username?.let { pwPrefs.getString(passwordKey(id), null) }
        return ServerProfile(
            id = id,
            label = label,
            baseUrl = baseUrl,
            username = username,
            password = pw,
            lastUsed = lastUsed,
            requireHttps = requireHttps,
            certPin = certPin,
        )
    }

    open suspend fun resolve(profile: ServerProfile): ServerProfile {
        val pw = if (profile.hasAuth) {
            withContext(Dispatchers.IO) { prefsForPasswords().getString(passwordKey(profile.id), null) }
        } else {
            null
        }
        return profile.copy(password = pw)
    }

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
                requireHttps = profile.requireHttps,
                certPin = profile.certPin?.takeIf { it.isNotBlank() },
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
