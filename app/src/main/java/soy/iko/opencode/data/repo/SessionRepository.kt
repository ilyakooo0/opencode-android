package soy.iko.opencode.data.repo

import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.MessagePartRemoved
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageRemoved
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelRef
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.SessionError
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.UnknownMessage
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.util.safeExceptionSummary
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Source of truth for a session's conversation. It seeds from `GET /session/:id/message`
 * and then reduces [BusEvent]s from the SSE stream into an ordered list of messages,
 * each keyed by message id with parts keyed by part id (idempotent upserts).
 */
class SessionRepository(
    private val api: OpencodeApiClient,
    private val eventStream: EventStreamClient,
) {
    suspend fun listSessions() = api.listSessions()
    suspend fun createSession(title: String? = null) = api.createSession(title)
    suspend fun deleteSession(id: String) = api.deleteSession(id)
    suspend fun abort(sessionId: String) = api.abort(sessionId)

    suspend fun sendPrompt(sessionId: String, text: String, model: ModelRef?, agent: String? = null) =
        api.sendPrompt(sessionId, text, model, agent)

    suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
    ) = api.runCommand(sessionId, command, arguments = arguments, agent = agent)

    /**
     * A live, ordered view of [sessionId]'s messages. Begins collecting the event
     * stream before the initial REST load so no streamed part is missed, then reduces
     * events into the in-memory state. The flow is conflated so a fast burst of
     * streaming tokens (each publishing a fresh snapshot) never back-pressures the
     * event reducer — a slow collector simply sees the most recent snapshot.
     */
    fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> = channelFlow {
        val store = MessageStore()
        val lock = Mutex()

        // Collect the snapshot under the lock, then publish outside it so a slow
        // downstream collector can't stall event processing.
        suspend fun publish() {
            val snapshot = lock.withLock { store.snapshot() }
            send(snapshot)
        }

        // Subscribe to events first so we don't miss early deltas during the initial load.
        val job = launch {
            eventStream.events.collect { event ->
                val changed = lock.withLock { store.reduce(sessionId, event) }
                if (changed) publish()
            }
        }

        // Re-seed from REST when the SSE stream reconnects after a drop. Events emitted
        // by the server during the disconnection gap are lost from the in-memory state;
        // re-fetching the current snapshot fills those holes. The seed merge logic
        // (see MessageStore.seed) handles interleaving with concurrently-arrived events.
        //
        // The REST fetch is performed *outside* the lock so events arriving via SSE
        // during the fetch are not blocked — holding the lock during a network call
        // would stall the event reducer and overflow the SharedFlow buffer (DROP_OLDEST),
        // silently losing events. The seed itself is under the lock; an interim seed
        // token is checked so a concurrent re-seed doesn't clobber a newer fetch.
        launch {
            var wasConnected = false
            eventStream.state.collect { state ->
                if (state == EventStreamClient.ConnectionState.Connected) {
                    if (wasConnected) {
                        val fresh = runCatchingCancellable { api.listMessages(sessionId) }
                            .onFailure { Log.w("SessionRepository", "Re-seed message load failed for $sessionId; relying on SSE: ${safeExceptionSummary(it)}") }
                            .getOrDefault(emptyList())
                        lock.withLock { store.seed(fresh, prune = true) }
                        publish()
                    }
                    wasConnected = true
                } else if (state == EventStreamClient.ConnectionState.Disconnected ||
                           state == EventStreamClient.ConnectionState.Failed) {
                    wasConnected = false
                }
            }
        }

        val initial = runCatchingCancellable { api.listMessages(sessionId) }
            .onFailure { Log.w("SessionRepository", "Initial message load failed for $sessionId; relying on SSE: ${safeExceptionSummary(it)}") }
            .getOrDefault(emptyList())
        lock.withLock { store.seed(initial) }
        publish()

        // Keep the flow alive until the collector cancels; the launched job is torn down with it.
        job.join()
    }.conflate()

    companion object {
        /** Convenience: is this event a run-completion signal for [sessionId]?
         *  A null sessionID is NOT treated as a wildcard — an idle event with no
         *  session id must not reset the running state of every open chat. */
        fun isIdle(event: BusEvent, sessionId: String): Boolean =
            event is SessionIdle && event.properties.sessionID == sessionId

        fun isError(event: BusEvent, sessionId: String): Boolean =
            event is SessionError && event.properties.sessionID == sessionId
    }
}

/**
 * In-memory reduction state for one observed session. Not thread-safe; guard with a Mutex.
 * Exposed as `internal` so the reducer logic can be unit-tested directly.
 */
internal class MessageStore {
    // messageId -> (info + parts), insertion-ordered.
    private val messages = LinkedHashMap<String, MessageWithParts>()

    /** Maximum number of messages to keep in memory; oldest are evicted when exceeded. */
    internal val maxMessages = NetworkConfig.maxInMemoryMessages

    fun snapshot(): List<MessageWithParts> = messages.values.toList()

    fun seed(initial: List<MessageWithParts>, prune: Boolean = false) {
        // On re-seed (after SSE reconnect), remove messages that are no longer in the
        // server snapshot (e.g. deleted during the disconnection gap). This keeps the
        // in-memory state in sync with the authoritative REST snapshot rather than
        // accumulating stale messages forever. Skipped on the initial seed to avoid
        // racing with just-arrived SSE events whose messages may not yet be in REST.
        if (prune) {
            val incomingIds = initial.mapTo(mutableSetOf()) { it.info.id }
            messages.keys.retainAll(incomingIds)
        }

        for (m in initial) {
            val existing = messages[m.info.id]
            if (existing == null) {
                messages[m.info.id] = m
            } else {
                // A part streamed in between subscribe and this initial load may already
                // have populated this message (see observeMessages). Merge instead of
                // overwriting so that newer streamed part isn't discarded: take the
                // snapshot's part order, swap in the streamed version where ids overlap,
                // append any streamed-only parts, and adopt the authoritative REST info.
                val streamedById = existing.parts.associateBy { it.id }
                val snapshotIds = m.parts.mapTo(mutableSetOf()) { it.id }
                val ordered = m.parts.map { p -> streamedById[p.id] ?: p }.toMutableList()
                for (p in existing.parts) {
                    if (p.id !in snapshotIds) ordered.add(p)
                }
                val info = if (existing.info is UnknownMessage) m.info else existing.info
                messages[m.info.id] = MessageWithParts(info = info, parts = ordered)
            }
        }
        evictOldMessages()
    }

    /** Returns true if the state changed (and a new snapshot should be published). */
    fun reduce(sessionId: String, event: BusEvent): Boolean {
        return when (event) {
            is MessageUpdated -> {
                val info = event.properties.info
                if (info.sessionID != sessionId) {
                    false
                } else {
                    // An UnknownMessage with an empty id (e.g. from an unrecognized
                    // server role with no id field) would collide with other such
                    // messages in the map. Generate a unique synthetic key so each
                    // unknown message gets its own entry instead of overwriting others.
                    val key = if (info.id.isEmpty() && info is UnknownMessage) {
                        "unknown-${System.nanoTime()}"
                    } else {
                        info.id
                    }
                    val existing = messages[key]
                    messages[key] = existing?.copy(info = info) ?: MessageWithParts(info)
                    if (existing == null) evictOldMessages()
                    true
                }
            }

            is MessagePartUpdated -> {
                val part = event.properties.part
                val messageId = part.messageID ?: event.properties.messageID
                val partSession = part.sessionID ?: event.properties.sessionID
                if (messageId == null || (partSession != null && partSession != sessionId)) {
                    false
                } else {
                    upsertPart(messageId, part)
                }
            }

            is MessagePartRemoved -> {
                val messageId = event.properties.messageID
                val partId = event.properties.partID
                if (messageId == null || partId == null) false else removePart(messageId, partId)
            }

            is MessageRemoved -> {
                val id = event.properties.messageID
                if (id == null) false else messages.remove(id) != null
            }

            else -> false
        }
    }

    private fun upsertPart(messageId: String, part: Part): Boolean {
        val current = messages[messageId]
        val parts = current?.parts.orEmpty()
        val idx = parts.indexOfFirst { it.id == part.id }
        if (idx >= 0 && parts[idx] == part) return false
        val newParts = if (idx >= 0) {
            parts.toMutableList().also { it[idx] = part }
        } else {
            parts + part
        }
        messages[messageId] = current?.copy(parts = newParts)
            ?: MessageWithParts(
                info = UnknownMessage(id = messageId, sessionID = part.sessionID ?: ""),
                parts = newParts,
            )
        if (current == null) evictOldMessages()
        return true
    }

    private fun removePart(messageId: String, partId: String): Boolean {
        val current = messages[messageId] ?: return false
        val newParts = current.parts.filterNot { it.id == partId }
        if (newParts.size == current.parts.size) return false
        messages[messageId] = current.copy(parts = newParts)
        return true
    }

    /** Evict the oldest messages when the store exceeds [maxMessages], keeping memory bounded. */
    private fun evictOldMessages() {
        while (messages.size > maxMessages) {
            val oldestKey = messages.keys.iterator().next()
            messages.remove(oldestKey)
        }
    }
}
