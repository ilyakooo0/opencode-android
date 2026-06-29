package soy.iko.opencode.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessagePartRemoved
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageRemoved
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.SessionError
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.UserMessage

class MessageStoreTest {

    private val session = "s1"

    private fun msgUpdated(id: String, sid: String = session) =
        MessageUpdated(MessageUpdated.Props(AssistantMessage(id = id, sessionID = sid)))

    private fun partUpdated(messageId: String, partId: String, text: String, sid: String = session) =
        MessagePartUpdated(
            MessagePartUpdated.Props(
                part = TextPart(id = partId, messageID = messageId, sessionID = sid, text = text),
                sessionID = sid,
                messageID = messageId,
            ),
        )

    private fun partRemoved(messageId: String, partId: String, sid: String = session) =
        MessagePartRemoved(
            MessagePartRemoved.Props(sessionID = sid, messageID = messageId, partID = partId),
        )

    private fun messageRemoved(messageId: String, sid: String = session) =
        MessageRemoved(MessageRemoved.Props(sessionID = sid, messageID = messageId))

    // --- seed ---

    @Test
    fun seedPopulatesSnapshot() {
        val store = MessageStore()
        store.seed(
            listOf(
                MessageWithParts(UserMessage("u1", session)),
                MessageWithParts(AssistantMessage("a1", session)),
            ),
        )
        assertEquals(listOf("u1", "a1"), store.snapshot().map { it.info.id })
    }

    // --- MessageUpdated ---

    @Test
    fun messageUpdatedInsertsNewMessage() {
        val store = MessageStore()
        val changed = store.reduce(session, msgUpdated("m1"))
        assertTrue(changed)
        assertEquals("m1", store.snapshot().single().info.id)
    }

    @Test
    fun messageUpdatedOverwritesExistingInfo() {
        val store = MessageStore()
        store.reduce(session, msgUpdated("m1"))
        val changed = store.reduce(session, msgUpdated("m1"))
        assertTrue(changed)
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun messageUpdatedIgnoredForOtherSession() {
        val store = MessageStore()
        val changed = store.reduce(session, msgUpdated("m1", sid = "other"))
        assertFalse(changed)
        assertTrue(store.snapshot().isEmpty())
    }

    // --- MessagePartUpdated ---

    @Test
    fun partUpdatedAppendsToExistingMessage() {
        val store = MessageStore()
        store.reduce(session, msgUpdated("m1"))
        val changed = store.reduce(session, partUpdated("m1", "p1", "hello"))
        assertTrue(changed)
        val parts = store.snapshot().single().parts
        assertEquals(1, parts.size)
        assertEquals("hello", (parts[0] as TextPart).text)
    }

    @Test
    fun partUpdatedCreatesSyntheticMessageWhenAbsent() {
        val store = MessageStore()
        store.reduce(session, partUpdated("m1", "p1", "hello"))
        val msg = store.snapshot().single()
        assertEquals("m1", msg.info.id)
        assertEquals("hello", (msg.parts.single() as TextPart).text)
    }

    @Test
    fun partUpdatedReplacesPartWithSameId() {
        val store = MessageStore()
        store.reduce(session, partUpdated("m1", "p1", "hello"))
        store.reduce(session, partUpdated("m1", "p1", "hello world"))
        val parts = store.snapshot().single().parts
        assertEquals(1, parts.size)
        assertEquals("hello world", (parts[0] as TextPart).text)
    }

    @Test
    fun partUpdatedPreservesInsertionOrderAcrossParts() {
        val store = MessageStore()
        store.reduce(session, partUpdated("m1", "p1", "one"))
        store.reduce(session, partUpdated("m1", "p2", "two"))
        store.reduce(session, partUpdated("m1", "p3", "three"))
        val parts = store.snapshot().single().parts
        assertEquals(listOf("p1", "p2", "p3"), parts.map { it.id })
    }

    @Test
    fun partUpdatedIgnoredForOtherSession() {
        val store = MessageStore()
        val changed = store.reduce(session, partUpdated("m1", "p1", "x", sid = "other"))
        assertFalse(changed)
        assertTrue(store.snapshot().isEmpty())
    }

    // --- MessagePartRemoved ---

    @Test
    fun partRemovedDeletesMatchingPart() {
        val store = MessageStore()
        store.reduce(session, partUpdated("m1", "p1", "one"))
        store.reduce(session, partUpdated("m1", "p2", "two"))
        val changed = store.reduce(session, partRemoved("m1", "p1"))
        assertTrue(changed)
        val parts = store.snapshot().single().parts
        assertEquals(listOf("p2"), parts.map { it.id })
    }

    @Test
    fun partRemovedReturnsFalseWhenPartNotFound() {
        val store = MessageStore()
        store.reduce(session, partUpdated("m1", "p1", "one"))
        val changed = store.reduce(session, partRemoved("m1", "p2"))
        assertFalse(changed)
    }

    // --- MessageRemoved ---

    @Test
    fun messageRemovedDeletesWholeMessage() {
        val store = MessageStore()
        store.reduce(session, msgUpdated("m1"))
        store.reduce(session, msgUpdated("m2"))
        val changed = store.reduce(session, messageRemoved("m1"))
        assertTrue(changed)
        assertEquals(listOf("m2"), store.snapshot().map { it.info.id })
    }

    @Test
    fun messageRemovedReturnsFalseWhenMissing() {
        val store = MessageStore()
        val changed = store.reduce(session, messageRemoved("nope"))
        assertFalse(changed)
    }

    // --- unrelated events ---

    @Test
    fun unrelatedEventsDoNotMutateState() {
        val store = MessageStore()
        store.reduce(session, msgUpdated("m1"))
        val idle = store.reduce(session, SessionIdle(SessionIdle.Props(sessionID = session)))
        val error = store.reduce(session, SessionError(SessionError.Props(sessionID = session)))
        assertFalse(idle)
        assertFalse(error)
        assertEquals(listOf("m1"), store.snapshot().map { it.info.id })
    }

    // --- end-to-end ordering ---

    @Test
    fun fullReductionProducesOrderedMessagesWithParts() {
        val store = MessageStore()
        store.reduce(session, msgUpdated("u1"))
        store.reduce(session, msgUpdated("a1"))
        store.reduce(session, partUpdated("a1", "t1", "draft"))
        store.reduce(session, partUpdated("a1", "t1", "final"))
        store.reduce(session, partUpdated("a1", "t2", "second"))

        val snapshot = store.snapshot()
        assertEquals(listOf("u1", "a1"), snapshot.map { it.info.id })
        val a1 = snapshot[1]
        assertEquals(listOf("t1", "t2"), a1.parts.map { it.id })
        assertEquals("final", (a1.parts[0] as TextPart).text)
    }
}
