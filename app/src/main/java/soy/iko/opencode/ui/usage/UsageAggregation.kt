package soy.iko.opencode.ui.usage

import androidx.compose.runtime.Immutable
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.Tokens

/** Aggregated usage for one model across the scanned sessions. */
@Immutable
data class ModelUsage(
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

private class MutableAgg(val model: String) {
    var messages = 0
    var cost = 0.0
    var tokens = Tokens()
}

/**
 * Aggregate cost/token usage across sessions. Each entry is (sessionId, title, messages).
 * Only [AssistantMessage]s that reported cost or tokens contribute — user prompts and
 * zero-usage messages are ignored so the counts reflect real model spend. Pure and
 * synchronous so it's unit-testable and can run off the main thread.
 */
fun aggregateUsage(sessions: List<Triple<String, String, List<MessageWithParts>>>): UsageReport {
    var totalCost = 0.0
    var totalTokens = Tokens()
    var messageCount = 0
    val byModel = LinkedHashMap<String, MutableAgg>()
    val bySession = ArrayList<SessionUsage>()

    for ((id, title, messages) in sessions) {
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
            // Key by provider+model so the same model id served by different providers
            // stays two rows instead of collapsing into one ambiguous total.
            val key = (info.providerID?.takeIf { it.isNotBlank() } ?: "unknown") + "/" + model
            val agg = byModel.getOrPut(key) { MutableAgg(model) }
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
            .map { ModelUsage(it.value.model, it.value.messages, it.value.cost, it.value.tokens) }
            .sortedByDescending { it.cost },
        bySession = bySession.sortedByDescending { it.cost },
    )
}
