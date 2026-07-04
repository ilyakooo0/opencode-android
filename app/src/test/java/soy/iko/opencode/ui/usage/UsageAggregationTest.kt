package soy.iko.opencode.ui.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.TimeInfo
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.model.UserMessage

class UsageAggregationTest {

    private fun assistant(model: String, cost: Double, input: Long, output: Long) =
        MessageWithParts(
            info = AssistantMessage(
                id = "m",
                sessionID = "s",
                modelID = model,
                cost = cost,
                tokens = Tokens(input = input, output = output),
                time = TimeInfo(completed = 1L),
            ),
        )

    @Test
    fun aggregatesTotalsAndByModel() {
        val sessions = listOf(
            UsageSessionInput("s1", "One", null, listOf(assistant("gpt", 0.10, 100, 50), assistant("gpt", 0.20, 200, 100))),
            UsageSessionInput("s2", "Two", null, listOf(assistant("claude", 0.05, 10, 5), MessageWithParts(UserMessage("u", "s2")))),
        )
        val report = aggregateUsage(sessions)
        assertEquals(0.35, report.totalCost, 1e-9)
        assertEquals(3, report.messageCount)
        assertEquals(2, report.sessionCount)
        assertEquals(2, report.byModel.size)
        // Highest-cost model floats to the top.
        assertEquals("gpt", report.byModel.first().model)
        assertEquals(0.30, report.byModel.first().cost, 1e-9)
        assertEquals(450L, report.byModel.first().tokens.total)
    }

    @Test
    fun ignoresZeroUsageMessages() {
        val sessions = listOf(UsageSessionInput("s", "t", null, listOf(assistant("m", 0.0, 0, 0))))
        assertTrue(aggregateUsage(sessions).isEmpty)
    }

    @Test
    fun timeRangeCutoffExcludesOlderSessions() {
        val now = 10_000_000_000L
        val dayMs = 24L * 60 * 60 * 1000
        val recent = UsageSessionInput("recent", "Recent", now - 1000, listOf(assistant("gpt", 0.10, 100, 50)))
        val old = UsageSessionInput("old", "Old", now - 100_000_000, listOf(assistant("gpt", 0.90, 100, 50)))
        // A 24h window keeps the recent session only (the old one is ~27h ago).
        val cutoff = UsageTimeRange.TWENTY_FOUR_HOURS.cutoffMs(now)
        val report = aggregateUsage(listOf(recent, old), cutoff)
        assertEquals(0.10, report.totalCost, 1e-9)
        assertEquals(1, report.sessionCount)
        // All-time includes both.
        val all = aggregateUsage(listOf(recent, old), null)
        assertEquals(1.00, all.totalCost, 1e-9)
        assertEquals(2, all.sessionCount)
        // Sanity: the cutoff is exactly one day before now.
        assertEquals(now - dayMs, cutoff)
    }
}
