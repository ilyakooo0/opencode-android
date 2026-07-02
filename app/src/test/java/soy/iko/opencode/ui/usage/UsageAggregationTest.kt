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
            Triple("s1", "One", listOf(assistant("gpt", 0.10, 100, 50), assistant("gpt", 0.20, 200, 100))),
            Triple("s2", "Two", listOf(assistant("claude", 0.05, 10, 5), MessageWithParts(UserMessage("u", "s2")))),
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
        val sessions = listOf(Triple("s", "t", listOf(assistant("m", 0.0, 0, 0))))
        assertTrue(aggregateUsage(sessions).isEmpty)
    }
}
