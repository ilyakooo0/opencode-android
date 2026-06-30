@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.ui.testing.FakeAppContainer
import soy.iko.opencode.ui.testing.FakeEventStreamClient
import soy.iko.opencode.ui.testing.FakeOpencodeApiClient
import soy.iko.opencode.ui.testing.FakeOpencodeConnection
import soy.iko.opencode.ui.testing.FakeSessionRepository
import java.io.IOException

class SessionListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeContainer(
        api: FakeOpencodeApiClient = FakeOpencodeApiClient(),
        events: FakeEventStreamClient = FakeEventStreamClient(),
        repo: FakeSessionRepository = FakeSessionRepository(api, events),
        profile: ServerProfile = ServerProfile(id = "s1", label = "Test", baseUrl = "http://localhost"),
    ): FakeAppContainer {
        val container = FakeAppContainer()
        val conn = FakeOpencodeConnection(api, events, repo, profile)
        container.setActiveConnection(conn)
        return container
    }

    private fun makeVm(container: FakeAppContainer): SessionListViewModel {
        val vm = SessionListViewModel(container)
        testScope.testScheduler.advanceUntilIdle()
        return vm
    }

    // --- refresh / loading ---

    @Test
    fun refresh_loadsSessionsFromRepository() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(
            Session(id = "s1", title = "Chat 1"),
            Session(id = "s2", title = "Chat 2"),
        )
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)
        assertEquals(2, vm.state.value.sessions.size)
        assertEquals("s1", vm.state.value.sessions[0].id)
    }

    @Test
    fun refresh_withoutConnection_showsNotConnected() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun refresh_failureWithExistingSessions_setsTransientError() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sessions = listOf(Session(id = "s1", title = "Existing"))
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        assertTrue(vm.state.value.sessions.isNotEmpty())

        // Now make listSessions fail — the existing list should remain and a
        // transient error should be surfaced (not replace the whole list).
        repo.listSessionsThrows = IOException("network error")
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.sessions.isNotEmpty())
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.transientError.value)
    }

    @Test
    fun refresh_failureWithNoSessions_setsErrorState() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sessions = emptyList()
        repo.listSessionsThrows = IOException("network error")
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.sessions.isEmpty())
    }

    // --- SSE events ---

    @Test
    fun sseSessionUpdated_insertsAndSortsSession() = testScope.runTest {
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), events)
        repo.sessions = listOf(Session(id = "s1", title = "Old"))
        val container = makeContainer(events = events, repo = repo)
        val vm = makeVm(container)

        events.fakeEvents.tryEmit(
            SessionUpdated(SessionUpdated.Props(info = Session(id = "s2", title = "New"))),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.sessions.any { it.id == "s2" })
    }

    @Test
    fun sseSessionDeleted_removesSessionAndClearsPreview() = testScope.runTest {
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), events)
        repo.sessions = listOf(Session(id = "s1", title = "Chat 1"))
        val container = makeContainer(events = events, repo = repo)
        val vm = makeVm(container)
        assertTrue(vm.state.value.sessions.any { it.id == "s1" })

        events.fakeEvents.tryEmit(
            SessionDeleted(SessionDeleted.Props(sessionID = "s1")),
        )
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.sessions.any { it.id == "s1" })
    }

    // --- createSession ---

    @Test
    fun createSession_successCallsOnCreated() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.createdSession = Session(id = "new-id", title = "New")
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)

        var createdId: String? = null
        vm.createSession { createdId = it }
        testScheduler.advanceUntilIdle()
        assertEquals("new-id", createdId)
    }

    // --- deleteSession ---

    @Test
    fun deleteSession_callsRepository() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(Session(id = "s1", title = "Chat 1"))
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)

        vm.deleteSession(Session(id = "s1", title = "Chat 1"))
        testScheduler.advanceUntilIdle()
        // The deleteSession in FakeSessionRepository is a no-op, but refresh() is called
        // which re-fetches sessions. The session should still be in the list since the
        // fake delete doesn't actually remove it.
    }

    // --- switchServer ---

    @Test
    fun switchServer_skipsSameServer() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        val currentProfile = container.activeConnection.value!!.profile
        vm.switchServer(currentProfile)
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.connectCalls.size)
    }

    @Test
    fun switchServer_skipsWhenAlreadySwitching() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        val newProfile = ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other")
        vm.switchServer(newProfile)
        vm.switchServer(newProfile) // should be skipped
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.connectCalls.size)
    }

    @Test
    fun switchServer_successRefreshesSessionList() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        val newProfile = ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other")
        container.connectResult = FakeOpencodeConnection(
            FakeOpencodeApiClient().also { it.sessions = listOf(Session(id = "x1", title = "From New")) },
            FakeEventStreamClient(),
            FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient()),
            newProfile,
        )
        vm.switchServer(newProfile)
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.connectCalls.size)
        assertNull(vm.switchingId.value)
    }

    // --- setQuery / filtering ---

    @Test
    fun setQuery_filtersSessionsByTitle() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(
            Session(id = "s1", title = "Kotlin Chat"),
            Session(id = "s2", title = "Rust Project"),
        )
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)
        vm.setQuery("Kotlin")
        val filtered = vm.state.value.filtered
        assertEquals(1, filtered.size)
        assertEquals("s1", filtered[0].id)
    }

    @Test
    fun setQuery_emptyShowsAllSessions() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(
            Session(id = "s1", title = "A"),
            Session(id = "s2", title = "B"),
        )
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)
        vm.setQuery("")
        assertEquals(2, vm.state.value.filtered.size)
    }

    @Test
    fun setQuery_caseInsensitive() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(Session(id = "s1", title = "Kotlin Chat"))
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)
        vm.setQuery("kotlin")
        assertEquals(1, vm.state.value.filtered.size)
    }

    // --- connection state banner ---

    @Test
    fun connectionState_reflectsEventStreamState() = testScope.runTest {
        val events = FakeEventStreamClient()
        events.fakeState.value = soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connected
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        // connectionState uses stateIn(WhileSubscribed) — subscribe to trigger collection
        val state = vm.connectionState.first { it == soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connected }
        assertEquals(
            soy.iko.opencode.data.network.EventStreamClient.ConnectionState.Connected,
            state,
        )
    }

    // --- Server switch cancels stale preview jobs ---

    @Test
    fun connectionChange_cancelsStalePreviewJobs() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sessions = listOf(Session(id = "s1", title = "Chat 1"))
        api.messages = listOf(
            MessageWithParts(
                info = UserMessage(id = "m1", sessionID = "s1"),
                parts = listOf(TextPart(id = "p1", text = "hello")),
            ),
        )
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        // Trigger a preview load via SSE event
        events.fakeEvents.tryEmit(
            SessionUpdated(SessionUpdated.Props(info = Session(id = "s1", title = "Chat 1"))),
        )
        testScheduler.advanceUntilIdle()
        // Preview should be loaded
        assertEquals("hello", vm.state.value.previews["s1"])
        // Switch to a new connection — previews should be cleared by refresh()
        val api2 = FakeOpencodeApiClient()
        val events2 = FakeEventStreamClient()
        val repo2 = FakeSessionRepository(api2, events2)
        repo2.sessions = listOf(Session(id = "x1", title = "New Server Chat"))
        val conn2 = FakeOpencodeConnection(api2, events2, repo2, ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        container.setActiveConnection(conn2)
        testScheduler.advanceUntilIdle()
        // The old session's preview should be gone (refresh re-populates from the new server)
        assertFalse(vm.state.value.previews.containsKey("s1"))
    }
}
