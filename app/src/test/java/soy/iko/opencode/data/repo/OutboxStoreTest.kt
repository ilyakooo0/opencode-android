package soy.iko.opencode.data.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxStoreTest {

    /** The protected no-arg constructor runs with no Context, so disk ops are skipped and the
     *  in-memory queue can be exercised directly. */
    private class TestOutbox : OutboxStore()

    private fun msg(id: String, sessionId: String, text: String, createdAt: Long) =
        OutboxMessage(id = id, profileId = "p", sessionId = sessionId, text = text, createdAt = createdAt)

    @Test
    fun enqueueOrdersByCreatedAtAndDedupesById() = runBlocking {
        val store = TestOutbox()
        store.enqueue(msg("b", "s1", "B", createdAt = 2))
        store.enqueue(msg("a", "s1", "A", createdAt = 1))
        // Same id replaces the prior entry rather than adding a duplicate.
        store.enqueue(msg("a", "s1", "A2", createdAt = 1))
        assertEquals(listOf("a", "b"), store.messages.value.map { it.id })
        assertEquals("A2", store.messages.value.first { it.id == "a" }.text)
    }

    @Test
    fun removeForSessionDropsOnlyThatSession() = runBlocking {
        val store = TestOutbox()
        store.enqueue(msg("1", "s1", "x", createdAt = 1))
        store.enqueue(msg("2", "s2", "y", createdAt = 2))
        store.removeForSession("s1")
        assertEquals(listOf("2"), store.messages.value.map { it.id })
    }

    @Test
    fun removeDropsSingleMessage() = runBlocking {
        val store = TestOutbox()
        store.enqueue(msg("1", "s1", "x", createdAt = 1))
        store.enqueue(msg("2", "s1", "y", createdAt = 2))
        store.remove("1")
        assertEquals(listOf("2"), store.messages.value.map { it.id })
    }
}
