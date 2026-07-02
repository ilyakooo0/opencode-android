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

    private fun fileFor(sessionId: String): File? =
        dir?.let { File(it, idRegex.replace(sessionId, "_") + ".json") }

    /** Per-session write lock so concurrent save/remove/load on the same file (e.g. both
     *  panes of two-pane mode saving the same session, or a save racing a delete's remove)
     *  can't tear or half-write the cache file. Keyed by session id; the map grows only with
     *  the number of distinct sessions touched, and each [Mutex] is a few bytes. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lockFor(sessionId: String): Mutex = locks.getOrPut(sessionId) { Mutex() }

    /** Load the cached messages for [sessionId], or empty if none / unreadable. */
    open suspend fun load(sessionId: String): List<MessageWithParts> = lockFor(sessionId).withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(sessionId)?.takeIf { it.exists() } ?: return@withContext emptyList()
            runCatchingCancellable { OpencodeJson.decodeFromString(serializer, file.readText()) }
                .getOrDefault(emptyList())
        }
    }

    /** Persist [messages] for [sessionId] (an empty list deletes the file). */
    open suspend fun save(sessionId: String, messages: List<MessageWithParts>): Unit = lockFor(sessionId).withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(sessionId) ?: return@withContext
            runCatchingCancellable {
                if (messages.isEmpty()) file.delete()
                else file.writeText(OpencodeJson.encodeToString(serializer, messages))
            }.onFailure { Log.w("MessageCacheStore", "Failed to cache messages for $sessionId", it) }
            Unit
        }
    }

    /** Remove a session's cached messages (call on deletion). */
    open suspend fun remove(sessionId: String): Unit = lockFor(sessionId).withLock {
        withContext(Dispatchers.IO) {
            runCatchingCancellable { fileFor(sessionId)?.delete() }
            Unit
        }
    }
}
