package soy.iko.opencode.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.network.OpencodeJson
import soy.iko.opencode.util.runCatchingCancellable
import java.io.File

/**
 * On-disk cache of a session's reduced message list, so a conversation shows instantly on
 * open (and remains readable while offline / before the network responds) instead of a blank
 * screen until `GET /session/:id/message` returns.
 *
 * One JSON file per session under filesDir, encoded with [OpencodeJson] so the same sealed
 * polymorphism used on the wire round-trips (unknown parts/messages are concrete
 * `__unknown` variants, so they encode too). Every operation is best-effort — a cache
 * miss or a write failure just falls back to the network — so problems never surface to
 * the user. No annotation processing (Room/KSP) per the project's manual-DI convention.
 */
open class MessageCacheStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val serializer = ListSerializer(MessageWithParts.serializer())

    /** Session ids are validated tokens upstream, but sanitize anyway so a stray id can't
     *  escape the directory. */
    private val idRegex = Regex("[^A-Za-z0-9_-]")

    private val dir: File? by lazy {
        appContext?.let { File(it.filesDir, "message_cache").apply { mkdirs() } }
    }

    /** Per-profile subdirectory: two servers with a colliding session id must not share a cache
     *  file, so entries are namespaced by profile id under [dir]. */
    private fun profileDir(profileId: String): File? =
        dir?.let { File(it, idRegex.replace(profileId, "_")).apply { mkdirs() } }

    private fun fileFor(profileId: String, sessionId: String): File? =
        profileDir(profileId)?.let { File(it, idRegex.replace(sessionId, "_") + ".json") }

    /** Per-session write lock so concurrent save/remove/load on the same file (e.g. both
     *  panes of two-pane mode saving the same session, or a save racing a delete's remove)
     *  can't tear or half-write the cache file. Keyed by session id; the map grows only with
     *  the number of distinct sessions touched, and each [Mutex] is a few bytes. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lockFor(sessionId: String): Mutex = locks.getOrPut(sessionId) { Mutex() }

    /** Sessions whose cache file has been removed by [remove]. A teardown flush of an
     *  observer (CacheWriter.flushOnTeardown) runs under NonCancellable and can race with
     *  remove(): both serialize on [lockFor], but their relative order is nondeterministic.
     *  If the flush's save lands after remove, it re-creates the deleted session's cache
     *  file, defeating the deletion (a privacy/disk-leak with no background cleanup). The
     *  tombstone lets a post-remove save detect the deletion and skip. Keyed by
     *  "$profileId/$sessionId" — a bare sessionId would suppress a *different* server's
     *  saves if two servers share a colliding session id (the very case the per-profile
     *  namespacing at [profileDir] exists to defend against). Cleared by [load] so
     *  re-opening a session re-enables saving. Bounded by distinct deleted sessions. */
    private val tombstones: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun tombstoneKey(profileId: String, sessionId: String): String = "$profileId/$sessionId"

    /** Load the cached messages for [sessionId] under [profileId], or empty if none / unreadable. */
    open suspend fun load(profileId: String, sessionId: String): List<MessageWithParts> = lockFor(sessionId).withLock {
        // Re-opening a session re-enables saving: clear any tombstone so a fresh conversation
        // write isn't suppressed by a prior deletion of the same (reused) id.
        tombstones.remove(tombstoneKey(profileId, sessionId))
        withContext(Dispatchers.IO) {
            val file = fileFor(profileId, sessionId)?.takeIf { it.exists() } ?: return@withContext emptyList()
            runCatchingCancellable { OpencodeJson.decodeFromString(serializer, file.readText()) }
                .getOrDefault(emptyList())
        }
    }

    /** Persist [messages] for [sessionId] under [profileId] (an empty list deletes the file). */
    open suspend fun save(profileId: String, sessionId: String, messages: List<MessageWithParts>): Unit = lockFor(sessionId).withLock {
        // Skip if this session was deleted since the observer started: a teardown flush racing
        // after remove() must not re-create the file. (Held under the same per-session lock as
        // remove, so the tombstone is visible to a save that lost the race.)
        if (tombstoneKey(profileId, sessionId) in tombstones) return@withLock
        withContext(Dispatchers.IO) {
            val file = fileFor(profileId, sessionId) ?: return@withContext
            runCatchingCancellable {
                if (messages.isEmpty()) file.delete()
                else {
                    // FIX: write atomically (temp file + rename) like RecentSessionsStore so a
                    // process kill mid-write can't leave a truncated file that fails to decode,
                    // wiping the cached conversation on the next open.
                    val encoded = OpencodeJson.encodeToString(serializer, messages)
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(encoded)
                    if (!tmp.renameTo(file)) {
                        file.writeText(encoded)
                        tmp.delete()
                    }
                }
            }.onFailure { Log.w("MessageCacheStore", "Failed to cache messages for $sessionId", it) }
            Unit
        }
    }

    /** Remove a session's cached messages (call on deletion). Clears the entry in every profile
     *  namespace: session ids are unique per server and deletion is terminal, so the caller
     *  needn't know which profile owned it. The per-session lock is intentionally NOT evicted —
     *  a concurrent teardown save() for this id must serialize against the same [Mutex] instance,
     *  and the map is bounded by the number of distinct sessions touched.
     *  [profileId] namespaces the tombstone so a delete on one server doesn't suppress another
     *  server's saves for a colliding session id. */
    open suspend fun remove(profileId: String, sessionId: String) {
        lockFor(sessionId).withLock {
            // Set the tombstone before deleting so a concurrent/loser save sees it and skips,
            // instead of re-creating the file we're about to delete.
            tombstones.add(tombstoneKey(profileId, sessionId))
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val name = idRegex.replace(sessionId, "_") + ".json"
                    dir?.listFiles()?.forEach { entry ->
                        if (entry.isDirectory) File(entry, name).delete()
                    }
                    // Back-compat: pre-namespacing caches were written flat under [dir].
                    dir?.let { File(it, name).delete() }
                }
                Unit
            }
        }
    }
}
