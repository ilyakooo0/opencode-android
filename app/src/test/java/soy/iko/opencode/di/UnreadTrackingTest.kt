package soy.iko.opencode.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessagePartRemoved
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.TextPart

class UnreadTrackingTest {

    private val session = "s1"

    @Test
    fun extractsSessionFromMessageUpdated() {
        val event = MessageUpdated(MessageUpdated.Props(AssistantMessage(id = "a1", sessionID = session)))
        assertEquals(session, sessionOfEvent(event))
    }

    @Test
    fun extractsSessionFromPartSessionId() {
        val event = MessagePartUpdated(
            MessagePartUpdated.Props(
                part = TextPart(id = "p1", messageID = "m1", sessionID = session, text = "hi"),
                sessionID = session,
                messageID = "m1",
            ),
        )
        assertEquals(session, sessionOfEvent(event))
    }

    @Test
    fun fallsBackToEnvelopeSessionIdWhenPartSessionIdMissing() {
        // Some events omit the session id on the nested part; the envelope value must
        // still badge the right session.
        val event = MessagePartUpdated(
            MessagePartUpdated.Props(
                part = TextPart(id = "p1", messageID = "m1", sessionID = null, text = "hi"),
                sessionID = session,
                messageID = "m1",
            ),
        )
        assertEquals(session, sessionOfEvent(event))
    }

    @Test
    fun returnsNullForNonMessageEvents() {
        // Idle/error/etc. events describe session lifecycle, not message activity, so
        // they must not badge a session.
        assertNull(sessionOfEvent(SessionIdle(SessionIdle.Props(sessionID = session))))
        assertNull(sessionOfEvent(MessagePartRemoved(MessagePartRemoved.Props(sessionID = session, messageID = "m1", partID = "p1"))))
    }
}
