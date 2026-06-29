package soy.iko.opencode.data.repo

import soy.iko.opencode.data.model.AssistantMessage
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
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.OpencodeApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

    /**
     * A live, ordered view of [sessionId]'s messages. Begins collecting the event
     * stream before the initial REST load so no streamed part is missed, then reduces
     * events into the in-memory state.
     */
    fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> = channelFlow {
        val store = MessageStore()
        val lock = Mutex()

        suspend fun publish() = send(store.snapshot())

        // Subscribe to events first so we don't miss early deltas during the initial load.
        val job = launch {
            eventStream.events.collect { event ->
                lock.withLock {
                    if (store.reduce(sessionId, event)) publish()
                }
            }
        }

        val initial = runCatching { api.listMessages(sessionId) }.getOrDefault(emptyList())
        lock.withLock {
            store.seed(initial)
            publish()
        }

        // Keep the flow alive until the collector cancels; the launched job is torn down with it.
        job.join()
    }

    /** In-memory reduction state for one observed session. Not thread-safe; guard with a Mutex. */
    private class MessageStore {
        // messageId -> (info + parts), insertion-ordered.
        private val messages = LinkedHashMap<String, MessageWithParts>()

        fun snapshot(): List<MessageWithParts> = messages.values.toList()

        fun seed(initial: List<MessageWithParts>) {
            for (m in initial) messages[m.info.id] = m
        }

        /** Returns true if the state changed (and a new snapshot should be published). */
        fun reduce(sessionId: String, event: BusEvent): Boolean {
            return when (event) {
                is MessageUpdated -> {
                    val info = event.properties.info
                    if (info.sessionID != sessionId) {
                        false
                    } else {
                        val existing = messages[info.id]
                        messages[info.id] = existing?.copy(info = info) ?: MessageWithParts(info)
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
            val newParts = if (idx >= 0) {
                parts.toMutableList().also { it[idx] = part }
            } else {
                parts + part
            }
            messages[messageId] = current?.copy(parts = newParts)
                ?: MessageWithParts(
                    info = AssistantMessage(id = messageId, sessionID = part.sessionID ?: ""),
                    parts = newParts,
                )
            return true
        }

        private fun removePart(messageId: String, partId: String): Boolean {
            val current = messages[messageId] ?: return false
            val newParts = current.parts.filterNot { it.id == partId }
            if (newParts.size == current.parts.size) return false
            messages[messageId] = current.copy(parts = newParts)
            return true
        }
    }

    companion object {
        /** Convenience: is this event a run-completion signal for [sessionId]? */
        fun isIdle(event: BusEvent, sessionId: String): Boolean =
            event is SessionIdle && (event.properties.sessionID == null || event.properties.sessionID == sessionId)

        fun isError(event: BusEvent, sessionId: String): Boolean =
            event is SessionError && (event.properties.sessionID == null || event.properties.sessionID == sessionId)
    }
}
