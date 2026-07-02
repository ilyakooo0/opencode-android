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

    private val file: File? by lazy {
        appContext?.let { File(it.filesDir, "outbox.json") }
    }

    /** Read the persisted queue into [messages]. Call once at startup. Idempotent. */
    open suspend fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        val loaded = withContext(Dispatchers.IO) {
            runCatchingCancellable { json.decodeFromString<List<OutboxMessage>>(f.readText()) }
                .getOrDefault(emptyList())
        }
        // Don't clobber items enqueued while the load was in flight: merge by id, keeping
        // the in-memory version on conflict (it's newer).
        ioMutex.withLock {
            if (_messages.value.isEmpty()) {
                _messages.value = loaded.sortedBy { it.createdAt }
            } else {
                val known = _messages.value.mapTo(mutableSetOf()) { it.id }
                _messages.value = (loaded.filter { it.id !in known } + _messages.value).sortedBy { it.createdAt }
            }
        }
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
        val snapshot = ioMutex.withLock {
            val updated = transform(_messages.value).sortedBy { it.createdAt }
            _messages.value = updated
            updated
        }
        persist(snapshot)
    }

    private suspend fun persist(list: List<OutboxMessage>) {
        val f = file ?: return
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                if (list.isEmpty()) f.delete() else f.writeText(json.encodeToString(list))
            }.onFailure { Log.w("OutboxStore", "Failed to persist outbox", it) }
        }
    }
}
