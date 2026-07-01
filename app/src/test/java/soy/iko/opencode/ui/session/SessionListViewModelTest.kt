@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        val errors = mutableListOf<String>()
        val collectJob = launch { vm.transientErrors.toList(errors) }
        repo.listSessionsThrows = IOException("network error")
        vm.refresh()
        testScheduler.advanceUntilIdle()
        collectJob.cancel()
        assertTrue(vm.state.value.sessions.isNotEmpty())
        assertFalse(vm.state.value.loading)
        assertTrue(errors.isNotEmpty())
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

    @Test
    fun refresh_whileInFlight_doesNotClearSpinnerPrematurely() = testScope.runTest {
        // Regression: a refresh() that cancels an in-flight refresh must not have the
        // cancelled job's `finally` clobber _refreshing back to false while the newer
        // refresh is still running (which would hide the pull-to-refresh spinner).
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(Session(id = "s1", title = "Chat"))
        val container = makeContainer(repo = repo)
        val vm = makeVm(container) // init completes with no gate; sessions loaded
        assertFalse(vm.refreshing.value)

        // Hold subsequent listSessions() calls in flight via a gate.
        val gate = CompletableDeferred<Unit>()
        repo.listSessionsGate = gate
        vm.refresh() // #A: hangs on the gate
        testScheduler.advanceUntilIdle()
        assertTrue(vm.refreshing.value)

        vm.refresh() // #B: cancels #A, also hangs on the gate
        testScheduler.advanceUntilIdle() // #A's finally runs here
        // #A's cancellation must NOT clear the spinner while #B is still in flight.
        assertTrue("spinner cleared while a refresh is still in flight", vm.refreshing.value)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.refreshing.value)
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

    @Test
    fun switchServer_failure_restoresPreviousAndShowsTransientError() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(Session(id = "s1-chat", title = "Original"))
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)

        // connect() succeeds but the new server's ping fails.
        val newApi = FakeOpencodeApiClient().apply { pingThrows = IOException("switch failed") }
        val newRepo = FakeSessionRepository(newApi, FakeEventStreamClient())
        newRepo.sessions = listOf(Session(id = "restored", title = "Restored"))
        container.connectResult = FakeOpencodeConnection(
            newApi, FakeEventStreamClient(), newRepo,
            ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"),
        )

        // The switch failure is surfaced as a transient error, not a list-replacing error.
        val errors = mutableListOf<String>()
        val collectJob = launch { vm.transientErrors.toList(errors) }
        vm.switchServer(ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        testScheduler.advanceUntilIdle()
        collectJob.cancel()

        // connect attempted for the new server, then again to restore the previous one.
        assertEquals(2, container.connectCalls.size)
        assertEquals("s2", container.connectCalls[0].id)
        assertEquals("s1", container.connectCalls[1].id)
        assertTrue(errors.isNotEmpty())
        assertNull(vm.state.value.error)
        assertEquals(1, container.disconnectCalls)
        assertNull(vm.switchingId.value)
    }

    @Test
    fun switchServer_failure_whenNoPreviousShowsErrorState() = testScope.runTest {
        // No active connection — there's no previous profile to restore to.
        val container = FakeAppContainer()
        val vm = makeVm(container)

        val newApi = FakeOpencodeApiClient().apply { pingThrows = IOException("nope") }
        container.connectResult = FakeOpencodeConnection(
            newApi, FakeEventStreamClient(),
            FakeSessionRepository(newApi, FakeEventStreamClient()),
            ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"),
        )

        vm.switchServer(ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        testScheduler.advanceUntilIdle()

        // No previous profile to restore — the error replaces the list state.
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.switchingId.value)
    }

    @Test
    fun switchServer_failure_restoreAlsoFailsShowsCombinedError() = testScope.runTest {
        val repo = FakeSessionRepository(FakeOpencodeApiClient(), FakeEventStreamClient())
        repo.sessions = listOf(Session(id = "s1-chat", title = "Original"))
        val container = makeContainer(repo = repo)
        val vm = makeVm(container)

        // Both the new connect and the restore connect fail.
        container.connectException = IOException("unreachable")

        vm.switchServer(ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        testScheduler.advanceUntilIdle()

        // connect attempted for the new server, then again to restore the previous one.
        assertEquals(2, container.connectCalls.size)
        // Both failed — the combined error replaces the list state.
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
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

    // --- Preview caching ---

    @Test
    fun refresh_skipsPreviewsAlreadyCached() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sessions = listOf(
            Session(id = "s1", title = "Chat 1"),
            Session(id = "s2", title = "Chat 2"),
        )
        api.messages = listOf(
            MessageWithParts(
                info = UserMessage(id = "m1", sessionID = "s1"),
                parts = listOf(TextPart(id = "p1", text = "hello")),
            ),
        )
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        // Initial load fetches previews for both sessions.
        val initialCalls = api.listMessagesCalls.size
        assertTrue("initial load should fetch previews", initialCalls > 0)

        // A second refresh should NOT re-fetch previews already cached.
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(
            "refresh should skip cached previews",
            initialCalls,
            api.listMessagesCalls.size,
        )
    }
}
