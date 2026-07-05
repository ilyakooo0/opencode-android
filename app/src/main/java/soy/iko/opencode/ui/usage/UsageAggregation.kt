package soy.iko.opencode.ui.usage

import androidx.compose.runtime.Immutable
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.Tokens

/** Aggregated usage for one model across the scanned sessions. */
@Immutable
data class ModelUsage(
    val provider: String,
    val model: String,
    val messages: Int,
    val cost: Double,
    val tokens: Tokens,
)

/** Aggregated usage for one session. */
@Immutable
data class SessionUsage(
    val sessionId: String,
    val title: String,
    val messages: Int,
    val cost: Double,
    val tokens: Tokens,
)

/** One session's inputs to [aggregateUsage]: its id, title, last-modified timestamp (epoch
 *  millis, or null when unknown), and messages. The timestamp lets a [UsageTimeRange] filter
 *  exclude sessions outside the window before they contribute to the totals. */
@Immutable
data class UsageSessionInput(
    val sessionId: String,
    val title: String,
    val modifiedAt: Long?,
    val messages: List<MessageWithParts>,
)

/** Time-range filter for the usage dashboard. [cutoffMs] returns the epoch-millis threshold
 *  before which a session is excluded (or null for "all time"), computed against [now]. */
enum class UsageTimeRange {
    TWENTY_FOUR_HOURS,
    SEVEN_DAYS,
    THIRTY_DAYS,
    ALL_TIME;

    fun cutoffMs(now: Long = System.currentTimeMillis()): Long? = when (this) {
        TWENTY_FOUR_HOURS -> now - 24L * 60 * 60 * 1000
        SEVEN_DAYS -> now - 7L * 24 * 60 * 60 * 1000
        THIRTY_DAYS -> now - 30L * 24 * 60 * 60 * 1000
        ALL_TIME -> null
    }
}

/** A full usage report: overall totals plus per-model and per-session breakdowns. */
@Immutable
data class UsageReport(
    val totalCost: Double = 0.0,
    val totalTokens: Tokens = Tokens(),
    val messageCount: Int = 0,
    val sessionCount: Int = 0,
    val byModel: List<ModelUsage> = emptyList(),
    val bySession: List<SessionUsage> = emptyList(),
) {
    val isEmpty: Boolean get() = messageCount == 0
}

/** Sum of two token counters (including the cache read/write sub-counters). */
internal operator fun Tokens.plus(other: Tokens): Tokens = Tokens(
    input = input + other.input,
    output = output + other.output,
    reasoning = reasoning + other.reasoning,
    cache = Tokens.Cache(read = cache.read + other.cache.read, write = cache.write + other.cache.write),
)

/** Total tokens across all sub-counters, for a single "N tokens" headline. */
internal val Tokens.total: Long get() = input + output + reasoning + cache.read + cache.write

private class MutableAgg(val provider: String, val model: String) {
    var messages = 0
    var cost = 0.0
    var tokens = Tokens()
}

/**
 * Aggregate cost/token usage across sessions. Each entry carries its messages plus an optional
 * last-modified timestamp; when [cutoffMs] is non-null, sessions whose [UsageSessionInput.modifiedAt]
 * is older than the cutoff (or unknown) are skipped, so a time-range filter narrows the totals
 * AND the per-model/per-session breakdowns rather than just the session list.
 *
 * Only [AssistantMessage]s that reported cost or tokens contribute — user prompts and
 * zero-usage messages are ignored so the counts reflect real model spend. Pure and
 * synchronous so it's unit-testable and can run off the main thread.
 */
fun aggregateUsage(
    sessions: List<UsageSessionInput>,
    cutoffMs: Long? = null,
): UsageReport {
    var totalCost = 0.0
    var totalTokens = Tokens()
    var messageCount = 0
    val byModel = LinkedHashMap<Pair<String, String>, MutableAgg>()
    val bySession = ArrayList<SessionUsage>()

    for (entry in sessions) {
        // Apply the time-range filter at the session level: a session modified before the
        // cutoff (or with no known timestamp under a finite range) doesn't contribute.
        if (cutoffMs != null && (entry.modifiedAt == null || entry.modifiedAt < cutoffMs)) continue
        val id = entry.sessionId
        val title = entry.title
        val messages = entry.messages
        var sessionCost = 0.0
        var sessionTokens = Tokens()
        var sessionMessages = 0
        for (m in messages) {
            val info = m.info as? AssistantMessage ?: continue
            val cost = info.cost ?: 0.0
            val tokens = info.tokens ?: Tokens()
            if (cost == 0.0 && tokens.total == 0L) continue
            messageCount++
            sessionMessages++
            totalCost += cost
            totalTokens += tokens
            sessionCost += cost
            sessionTokens += tokens
            val model = info.modelID?.takeIf { it.isNotBlank() } ?: "unknown"
            val provider = info.providerID?.takeIf { it.isNotBlank() } ?: "unknown"
            // Key by (provider, model) pair so a model id containing '/' (e.g. "org/model")
            // can't collide with a different provider/model split that would produce the same
            // concatenated "$provider/$model" string.
            val key = provider to model
            val agg = byModel.getOrPut(key) { MutableAgg(provider, model) }
            agg.messages++
            agg.cost += cost
            agg.tokens += tokens
        }
        if (sessionMessages > 0) {
            bySession.add(SessionUsage(id, title, sessionMessages, sessionCost, sessionTokens))
        }
    }

    return UsageReport(
        totalCost = totalCost,
        totalTokens = totalTokens,
        messageCount = messageCount,
        sessionCount = bySession.size,
        byModel = byModel.entries
            .map { ModelUsage(it.value.provider, it.value.model, it.value.messages, it.value.cost, it.value.tokens) }
            .sortedByDescending { it.cost },
        bySession = bySession.sortedByDescending { it.cost },
    )
}
