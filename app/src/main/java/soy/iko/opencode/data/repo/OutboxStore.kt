package soy.iko.opencode.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.iko.opencode.data.model.ModelRef
import soy.iko.opencode.util.runCatchingCancellable
import java.io.File

/**
 * One prompt composed while offline (or with no active connection), queued to send when
 * connectivity returns. Tagged with the [profileId] it was composed for so a reconnect to a
 * *different* server doesn't misfire it against a session id that only exists on the
 * original server. [attachments] carry their self-contained base64 data URLs, so a queued
 * image survives a process restart just like a staged attachment.
 */
@Serializable
data class OutboxMessage(
    val id: String,
    val profileId: String,
    val sessionId: String,
    val text: String,
    val attachments: List<PersistedAttachment> = emptyList(),
    val model: ModelRef? = null,
    val agent: String? = null,
    val createdAt: Long,
)

/**
 * A persisted send-queue: prompts the user composed while disconnected, flushed on
 * reconnect (see [soy.iko.opencode.di.AppContainer.observeOutbox]). Backed by one JSON file
 * under filesDir (not SharedPreferences) because a queued attachment's base64 payload can be
 * several megabytes. The in-memory [messages] StateFlow is the source of truth the UI and the
 * flusher observe; disk is written through under a mutex on every mutation.
 */
open class OutboxStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioMutex = Mutex()

    private val _messages = MutableStateFlow<List<OutboxMessage>>(emptyList())
    /** All queued messages across sessions/servers, in enqueue order. */
    open val messages: StateFlow<List<OutboxMessage>> = _messages.asStateFlow()

    /** Set once the on-disk queue has been read into [_messages] (under [ioMutex]). Until this
     *  is true, a [mutate] must read disk first — otherwise a mutation that beats [load] would
     *  persist from an empty in-memory snapshot and overwrite (or, for a removal, delete) the
     *  persisted queue, losing every message the user composed offline in a previous run. */
    private var loaded = false

    private val file: File? by lazy {
        appContext?.let { File(it.filesDir, "outbox.json") }
    }

    /** Read the persisted queue into [messages]. Call once at startup. Idempotent. */
    open suspend fun load() {
        ioMutex.withLock { loadLocked() }
    }

    /** Read the on-disk queue into memory. MUST be called while holding [ioMutex]. Idempotent
     *  (guarded by [loaded]) so a [mutate] that arrives before [load] can call it to guarantee
     *  disk is read before any write, then a later [load] is a no-op. Returns true once the queue
     *  is in memory (or the file is genuinely absent), false if an existing file couldn't be
     *  read — the caller MUST NOT persist on false or it would overwrite the still-valid queue. */
    private suspend fun loadLocked(): Boolean {
        if (loaded) return true
        val f = file?.takeIf { it.exists() }
        if (f == null) {
            // A genuinely absent file is a normal empty queue: mark loaded so mutations proceed.
            loaded = true
            return true
        }
        // Only mark [loaded] AFTER a successful read. A transient read/decode failure (or a
        // cancelled read) of an *existing* file must not clobber the still-valid on-disk queue:
        // leave loaded=false and report failure so the caller skips its write and a later
        // mutate/load retries the read, instead of persisting an empty snapshot over the user's
        // offline-composed messages.
        val diskList = withContext(Dispatchers.IO) {
            runCatchingCancellable { json.decodeFromString<List<OutboxMessage>>(f.readText()) }
        }.getOrElse { return false }
        // Don't clobber items enqueued while the load was in flight: merge by id, keeping
        // the in-memory version on conflict (it's newer).
        if (_messages.value.isEmpty()) {
            _messages.value = diskList.sortedBy { it.createdAt }
        } else {
            val known = _messages.value.mapTo(mutableSetOf()) { it.id }
            val merged = (diskList.filter { it.id !in known } + _messages.value).sortedBy { it.createdAt }
            _messages.value = merged
            // The merge reconciled items enqueued while this load was in flight with the
            // on-disk set; write the merged set back so a later process kill can't drop the
            // raced enqueue. persist() doesn't take ioMutex, so this won't deadlock.
            persist(merged)
        }
        loaded = true
        return true
    }

    open suspend fun enqueue(message: OutboxMessage) = mutate { current ->
        current.filterNot { it.id == message.id } + message
    }

    open suspend fun remove(id: String) = mutate { current -> current.filterNot { it.id == id } }

    /** Remove every queued message for [sessionId] (e.g. the user discarded them, or the
     *  session was deleted). */
    open suspend fun removeForSession(sessionId: String) =
        mutate { current -> current.filterNot { it.sessionId == sessionId } }

    private suspend fun mutate(transform: (List<OutboxMessage>) -> List<OutboxMessage>) {
        // Persist *under* the lock so concurrent mutations (e.g. the flusher removing sent
        // messages while the UI enqueues a new one) can't race their disk writes: without
        // this, the older snapshot could land last and drop/resurrect a queued prompt, or two
        // overlapping writeText/delete calls could tear the file. The lock serializes both the
        // in-memory update and the write, guaranteeing on-disk order matches memory order.
        ioMutex.withLock {
            // Ensure the persisted queue has been read into memory before mutating: a mutation
            // that races ahead of load() would otherwise transform an empty in-memory snapshot
            // and persist()/delete() the file, destroying messages queued in a previous run.
            val loadedOk = loadLocked()
            val updated = transform(_messages.value).sortedBy { it.createdAt }
            _messages.value = updated
            // Only write when the existing on-disk queue was actually read. If the read of an
            // existing file failed (loadedOk == false), _messages is missing whatever it held, so
            // persisting `updated` would overwrite the still-valid file with an incomplete
            // snapshot — losing the user's prior offline-composed messages. Keep the transform in
            // memory (so the UI reflects the new item) but leave disk untouched; loaded stays
            // false, so the next mutate/load retries the read and then merges + persists.
            if (loadedOk) persist(updated)
        }
    }

    private suspend fun persist(list: List<OutboxMessage>) {
        val f = file ?: return
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                if (list.isEmpty()) {
                    f.delete()
                } else {
                    // FIX: write atomically (temp file + rename) like RecentSessionsStore so a
                    // process kill mid-write can't leave a truncated file — a torn file fails to
                    // decode and load() would then drop ALL queued offline messages.
                    val encoded = json.encodeToString(list)
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    // One try/finally covers BOTH write paths: a throw from the initial
                    // tmp.writeText (disk full, permission) is cleaned up too — otherwise the
                    // inner finally below only ran after the rename failed, orphaning *.tmp
                    // files across failed first writes.
                    try {
                        tmp.writeText(encoded)
                        if (!tmp.renameTo(f)) {
                            f.writeText(encoded)
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }.onFailure { Log.w("OutboxStore", "Failed to persist outbox", it) }
        }
    }
}
