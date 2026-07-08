package soy.iko.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureCoreFfiTest {

    private fun createCore(): PureCoreFfi = PureCoreFfi()

    private fun PureCoreFfi.currentView(): ViewModel =
        ViewModel.bincodeDeserialize(view())

    private fun PureCoreFfi.processEvent(event: Event): Requests {
        val effects = update(event.bincodeSerialize())
        return Requests.bincodeDeserialize(effects)
    }

    private fun PureCoreFfi.resolveHttp(
        requestId: UInt,
        status: Int = 200,
        body: String = "{}",
    ): Requests {
        val response = HttpResponse(
            status = status.toUShort(),
            headers = emptyList(),
            body = com.novi.serde.Bytes(body.toByteArray()),
        )
        val result = HttpResult.Ok(response)
        val effects = resolve(requestId, result.bincodeSerialize())
        return Requests.bincodeDeserialize(effects)
    }

    @Test
    fun `start event sets default server URL`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val view = core.currentView()
        assertEquals("http://localhost:4096", view.serverUrl)
        assertEquals(Screen.CONNECT, view.screen)
    }

    @Test
    fun `server URL change updates view`() {
        val core = createCore()
        core.processEvent(Event.Start)
        core.processEvent(Event.ServerUrlChanged("http://10.0.0.1:4096"))
        val view = core.currentView()
        assertEquals("http://10.0.0.1:4096", view.serverUrl)
    }

    @Test
    fun `connect emits HTTP effect`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val requests = core.processEvent(Event.Connect)
        assertEquals(1, requests.value.size)
        assertTrue(requests.value[0].effect is Effect.Http)
        val view = core.currentView()
        assertTrue(view.loading)
    }

    @Test
    fun `successful connect transitions to sessions screen`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val requests = core.processEvent(Event.Connect)
        val httpReq = requests.value[0]
        assertEquals(Effect.Http::class, httpReq.effect::class)

        // Simulate health check success — triggers LoadSessions
        val followUpRequests = core.resolveHttp(httpReq.id, body = """{"healthy":true}""")
        assertEquals(1, followUpRequests.value.size)

        // Simulate sessions loaded
        val sessionsBody = """[{"id":"s1","title":"My Session"}]"""
        val finalRequests = core.resolveHttp(followUpRequests.value[0].id, body = sessionsBody)
        assertEquals(0, finalRequests.value.size)

        val view = core.currentView()
        assertTrue(view.connected)
        assertEquals(Screen.SESSIONS, view.screen)
        assertEquals(1, view.sessions.size)
        assertEquals("s1", view.sessions[0].id)
        assertEquals("My Session", view.sessions[0].title)
    }

    @Test
    fun `failed connect shows error`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val requests = core.processEvent(Event.Connect)

        val errorResult = HttpResult.Err(HttpError.Io("Connection refused"))
        val effects = core.resolve(requests.value[0].id, errorResult.bincodeSerialize())
        Requests.bincodeDeserialize(effects)

        val view = core.currentView()
        assertNotNull(view.error)
        assertFalse(view.loading)
    }

    @Test
    fun `dismiss error clears error`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val requests = core.processEvent(Event.Connect)

        val errorResult = HttpResult.Err(HttpError.Io("Connection refused"))
        core.resolve(requests.value[0].id, errorResult.bincodeSerialize())

        core.processEvent(Event.DismissError)
        val view = core.currentView()
        assertNull(view.error)
    }

    @Test
    fun `select session loads messages and transitions to chat`() {
        val core = createCore()
        core.processEvent(Event.Start)
        val connectReqs = core.processEvent(Event.Connect)
        core.resolveHttp(connectReqs.value[0].id, body = """{"healthy":true}""")
        core.resolveHttp(0u, body = "[]") // won't find the id, but we can manually set sessions

        // Manually select a session
        val selectReqs = core.processEvent(Event.SelectSession("session-123"))
        assertTrue(selectReqs.value.isNotEmpty())

        val view = core.currentView()
        assertEquals("session-123", view.currentSessionId)
        assertEquals(Screen.CHAT, view.screen)
    }
}
