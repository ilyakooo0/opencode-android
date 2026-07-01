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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Source of truth for a session's conversation. It seeds from `GET /session/:id/message`
 * and then reduces [BusEvent]s from the SSE stream into an ordered list of messages,
 * each keyed by message id with parts keyed by part id (idempotent upserts).
 */
open class SessionRepository(
    private val api: OpencodeApiClient,
    private val eventStream: EventStreamClient,
) {
    open suspend fun listSessions() = api.listSessions()
    open suspend fun createSession(title: String? = null) = api.createSession(title)
    open suspend fun deleteSession(id: String) = api.deleteSession(id)
    open suspend fun abort(sessionId: String) = api.abort(sessionId)

    open suspend fun sendPrompt(sessionId: String, text: String, model: ModelRef?, agent: String? = null) =
        api.sendPrompt(sessionId, text, model, agent)

    open suspend fun runCommand(
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
    open fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> = channelFlow {
        val store = MessageStore()
        val lock = Mutex()
        // Conflated dirty signal: a burst of reduce()/seed() calls coalesces into a
        // single snapshot+send per demand cycle. Previously every changed event called
        // snapshot() — allocating an O(N) copy of the whole message list — and ~97% of
        // those copies were immediately discarded by the outer .conflate() during fast
        // streaming (hundreds of tokens/sec). Marking dirty and snapshotting in one
        // drain loop means N events in a burst allocate ~1 snapshot, not N.
        val dirty = Channel<Unit>(Channel.CONFLATED)

        // Mark the store dirty; the drain loop below snapshots and publishes. CONFLATED
        // collapses repeated marks into a single pending signal, so a tight burst of
        // events doesn't even allocate N marks.
        fun publish() { dirty.trySend(Unit) }

        // Subscribe to events first so we don't miss early deltas during the initial load.
        launch {
            eventStream.events.collect { event ->
                if (lock.withLock { store.reduce(sessionId, event) }) publish()
            }
        }

        // Re-seed from REST when the SSE stream reconnects after a drop. The REST fetch
        // is performed *outside* the lock so events arriving via SSE during the fetch are
        // not blocked. The seed itself is under the lock; a seed generation counter ensures
        // a stale fetch (from an earlier reconnect) doesn't clobber a newer one.
        launch {
            // Re-seed from REST when the SSE stream reconnects after a drop. The
            // hasConnectedBefore flag is NOT reset on disconnect — it stays true after
            // the first successful connection so a subsequent reconnect triggers the
            // re-seed. Resetting it on disconnect would make the condition always false
            // after the first cycle, turning the re-seed into dead code.
            //
            // A seed generation counter guards against a stale re-seed clobbering a
            // newer one: if two reconnects fire in quick succession, the older fetch
            // (which returns later) is discarded because its generation no longer matches.
            var hasConnectedBefore = false
            var seedGeneration = 0
            eventStream.state.collect { state ->
                if (state == EventStreamClient.ConnectionState.Connected) {
                    if (hasConnectedBefore) {
                        val generation = ++seedGeneration
                        // Pivot before the fetch: parts already in memory predate this
                        // snapshot and must yield to it, while parts that stream in during
                        // the fetch (added to streamedSincePivot via reduce) outrank it.
                        lock.withLock { store.beginReseed() }
                        val fresh = runCatchingCancellable { api.listMessages(sessionId) }
                            .onFailure { Log.w("SessionRepository", "Re-seed message load failed for $sessionId; relying on SSE: ${safeExceptionSummary(it)}") }
                            .getOrDefault(emptyList())
                        var seedChanged = false
                        lock.withLock {
                            if (generation == seedGeneration) {
                                // Merge without pruning: SSE events arriving during the
                                // REST fetch may have added messages not yet indexed by
                                // the REST endpoint. Pruning would silently delete them.
                                // Stale messages from deletions during the disconnect
                                // gap are eventually removed via MessageRemoved events
                                // or on the next app restart.
                                seedChanged = store.seed(fresh, prune = false)
                            }
                        }
                        if (seedChanged) publish()
                    }
                    hasConnectedBefore = true
                }
            }
        }

        val initial = runCatchingCancellable { api.listMessages(sessionId) }
            .onFailure { Log.w("SessionRepository", "Initial message load failed for $sessionId; relying on SSE: ${safeExceptionSummary(it)}") }
            .getOrDefault(emptyList())
        lock.withLock { store.seed(initial) }
        publish()

        // Single drain: one snapshot+send per conflated dirty signal. The snapshot is
        // taken under the lock so a reader never sees a half-reduced state; send() runs
        // outside the lock so a slow downstream collector can't stall the drain. This
        // loop also keeps the flow alive until the collector cancels; the launched jobs
        // above are children of this scope and are torn down with it.
        dirty.consumeAsFlow().collect {
            val snapshot = lock.withLock { store.snapshot() }
            send(snapshot)
        }
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

    /** Part ids upserted via [reduce] since the last reseed pivot ([beginReseed]) or seed.
     *  On a reconnect re-seed these are the parts that streamed in *during* the REST fetch,
     *  so they're newer than the snapshot and must win over it; every other part is taken
     *  from the authoritative REST snapshot. Without this distinction the merge kept every
     *  overlapping in-memory part, so a part that changed server-side during the disconnect
     *  (e.g. a tool going running -> completed) stayed stale until app restart. */
    private val streamedSincePivot = mutableSetOf<String>()

    /** Message ids whose `info` was updated via a live SSE [reduce] since the last reseed
     *  pivot ([beginReseed]) or seed. Parallels [streamedSincePivot] but for message info:
     *  on a reconnect re-seed these are the messages whose info changed *during* the REST
     *  fetch, so the in-memory info is newer than the snapshot and must win; every other
     *  message's info is taken from the authoritative REST snapshot. Without this the merge
     *  kept the in-memory info, so a run that finished server-side during the disconnect
     *  (e.g. cost/token totals, completion time) stayed stale until app restart. */
    private val messageInfoUpdatedSincePivot = mutableSetOf<String>()

    /** Mark the point a reconnect re-seed's REST fetch begins: parts/info streamed from here
     *  on are newer than the fetched snapshot. Call under the same lock as [seed]/[reduce]. */
    fun beginReseed() {
        streamedSincePivot.clear()
        messageInfoUpdatedSincePivot.clear()
    }

    /** Monotonic counter for synthetic UnknownMessage keys, avoiding nanoTime collisions. */
    private val unknownCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Maximum number of messages to keep in memory; oldest are evicted when exceeded. */
    internal val maxMessages = NetworkConfig.maxInMemoryMessages

    fun snapshot(): List<MessageWithParts> = messages.values.toList()

    fun seed(initial: List<MessageWithParts>, prune: Boolean = false): Boolean {
        var changed = false
        // On re-seed (after SSE reconnect), remove messages that are no longer in the
        // server snapshot (e.g. deleted during the disconnection gap). This keeps the
        // in-memory state in sync with the authoritative REST snapshot rather than
        // accumulating stale messages forever. Skipped on the initial seed to avoid
        // racing with just-arrived SSE events whose messages may not yet be in REST.
        if (prune) {
            val incomingIds = initial.mapTo(mutableSetOf()) { it.info.id }
            val before = messages.size
            messages.keys.retainAll(incomingIds)
            if (messages.size != before) changed = true
        }

        for (m in initial) {
            val existing = messages[m.info.id]
            if (existing == null) {
                messages[m.info.id] = m
                changed = true
            } else {
                // A part streamed in between subscribe and this initial load may already
                // have populated this message (see observeMessages). Merge instead of
                // overwriting so that newer streamed part isn't discarded: take the
                // snapshot's part order, swap in the streamed version where ids overlap,
                // append any streamed-only parts, and adopt the authoritative REST info.
                val streamedById = existing.parts.associateBy { it.id }
                val snapshotIds = m.parts.mapTo(mutableSetOf()) { it.id }
                // Prefer the in-memory version of an overlapping part ONLY if it streamed
                // in since the reseed pivot (i.e. during this fetch), so it's newer than
                // the snapshot. Otherwise the REST snapshot is authoritative and wins,
                // discarding an in-memory part that went stale during a disconnect.
                val ordered = m.parts.map { p ->
                    if (p.id in streamedSincePivot) streamedById[p.id] ?: p else p
                }.toMutableList()
                for (p in existing.parts) {
                    if (p.id !in snapshotIds) ordered.add(p)
                }
                // Adopt the authoritative REST info unless the in-memory info was itself
                // updated live since the pivot (i.e. during this fetch), in which case it's
                // newer than the snapshot. Otherwise the REST snapshot wins, discarding
                // in-memory info that went stale during a disconnect.
                val info = if (existing.info is UnknownMessage || m.info.id !in messageInfoUpdatedSincePivot) m.info else existing.info
                val merged = MessageWithParts(info = info, parts = ordered)
                if (merged != existing) {
                    messages[m.info.id] = merged
                    changed = true
                }
            }
        }
        if (evictOldMessages()) changed = true
        // Reset the pivot: everything just merged is now the baseline, so only parts/info
        // that stream in *after* this seed can outrank the next reconnect's snapshot.
        streamedSincePivot.clear()
        messageInfoUpdatedSincePivot.clear()
        return changed
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
                        "unknown-${unknownCounter.incrementAndGet()}"
                    } else {
                        info.id
                    }
                    val existing = messages[key]
                    messages[key] = existing?.copy(info = info) ?: MessageWithParts(info)
                    // Record that this message's info streamed in live, so a re-seed after a
                    // reconnect knows it's newer than the REST snapshot and keeps it (see
                    // seed()/beginReseed()).
                    messageInfoUpdatedSincePivot.add(key)
                    if (existing == null) evictOldMessages()
                    true
                }
            }

            is MessagePartUpdated -> {
                val part = event.properties.part
                val messageId = part.messageID ?: event.properties.messageID
                val partSession = part.sessionID ?: event.properties.sessionID
                when {
                    messageId == null -> false
                    partSession != null -> if (partSession == sessionId) upsertPart(messageId, part) else false
                    // No session id anywhere on the event: a null session is NOT a wildcard
                    // (mirroring isIdle/isError). Every open session's store sees the shared
                    // event stream, so accepting it unconditionally would leak the part into
                    // unrelated conversations (e.g. both panes in two-pane mode). Attribute it
                    // only if this store already holds the parent message — MessageUpdated,
                    // which always carries a session id, will have created it here first.
                    else -> if (messages.containsKey(messageId)) upsertPart(messageId, part) else false
                }
            }

            is MessagePartRemoved -> handlePartRemoved(sessionId, event)

            is MessageRemoved -> handleMessageRemoved(sessionId, event)

            else -> false
        }
    }

    /** Returns false if [eventSession] is non-null and doesn't match [sessionId]. */
    private fun matchesSession(eventSession: String?, sessionId: String): Boolean =
        eventSession == null || eventSession == sessionId

    private fun handlePartRemoved(sessionId: String, event: MessagePartRemoved): Boolean {
        val messageId = event.properties.messageID ?: return false
        val partId = event.properties.partID ?: return false
        if (!matchesSession(event.properties.sessionID, sessionId)) return false
        return removePart(messageId, partId)
    }

    private fun handleMessageRemoved(sessionId: String, event: MessageRemoved): Boolean {
        val id = event.properties.messageID ?: return false
        if (!matchesSession(event.properties.sessionID, sessionId)) return false
        return messages.remove(id) != null
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
        // Record that this part streamed in, so a re-seed after a reconnect knows it's
        // newer than the REST snapshot and keeps it (see seed()/beginReseed()).
        streamedSincePivot.add(part.id)
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

    /** Evict the oldest messages when the store exceeds [maxMessages], keeping memory bounded.
     *  Returns true if any messages were evicted. */
    private fun evictOldMessages(): Boolean {
        var evicted = false
        while (messages.size > maxMessages) {
            val oldestKey = messages.keys.iterator().next()
            messages.remove(oldestKey)
            evicted = true
        }
        return evicted
    }
}
