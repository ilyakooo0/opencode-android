package soy.iko.opencode.ui.chat

import soy.iko.opencode.data.model.Tokens

// NumberFormat.getNumberInstance performs an expensive ICU locale lookup + object
// construction on every call. Reuse a thread-local instance so repeated calls (e.g.
// when the message list re-seeds after a reconnect and every visible assistant bubble
// recomposes at once) don't each pay that cost. Thread-local because NumberFormat is
// not thread-safe.
internal val tokenNumberFormat: ThreadLocal<java.text.NumberFormat> = ThreadLocal.withInitial {
    java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
}

internal fun formatTokens(tokens: Tokens, format: String): String {
    val nf = tokenNumberFormat.get()!!
    return format.format(nf.format(tokens.input), nf.format(tokens.output))
}

internal fun formatCost(cost: Double, shortFormat: String, longFormat: String): String =
    // Locale.US so the formatting is stable regardless of device locale (avoids
    // non-ASCII digits or comma decimal separators in a dollar amount). The format
    // string itself is localized via strings.xml so the currency symbol can be
    // adapted by translators.
    if (cost < 0.01) String.format(java.util.Locale.US, longFormat, cost)
    else String.format(java.util.Locale.US, shortFormat, cost)

/** Assemble the single-line cost/tokens summary for an assistant bubble. Returns null when the
 *  message isn't complete, when neither cost nor tokens were reported, or when every figure is
 *  zero (so the bubble doesn't show a meaningless "0 in · 0 out • $0.0000"). Extracted from
 *  [AssistantBlock] to keep that function's cyclomatic complexity under the detekt threshold.
 *  Reasoning and cache segments are added only when non-zero. */
internal fun buildCostSummary(
    isComplete: Boolean,
    tokens: Tokens?,
    cost: Double?,
    tokenFormat: String,
    reasoningFormat: String,
    cacheReadFormat: String,
    cacheWriteFormat: String,
    costShort: String,
    costLong: String,
): String? {
    if (!isComplete || (cost == null && tokens == null)) return null
    val nf = tokenNumberFormat.get()!!
    return buildList {
        // Skip an all-zero token count (e.g. a completed message that reported no usage) so
        // the bubble doesn't show a meaningless "0 in · 0 out".
        tokens?.takeIf { it.input > 0 || it.output > 0 }?.let { add(formatTokens(it, tokenFormat)) }
        tokens?.takeIf { it.reasoning > 0 }?.let {
            add(reasoningFormat.format(nf.format(it.reasoning)))
        }
        tokens?.cache?.takeIf { it.read > 0 }?.let {
            add(cacheReadFormat.format(nf.format(it.read)))
        }
        tokens?.cache?.takeIf { it.write > 0 }?.let {
            add(cacheWriteFormat.format(nf.format(it.write)))
        }
        cost?.takeIf { it > 0 }?.let { add(formatCost(it, costShort, costLong)) }
    }.takeIf { it.isNotEmpty() }?.joinToString("  •  ")
}
