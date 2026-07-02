@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package soy.iko.opencode.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
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
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelInfo
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.Provider
import soy.iko.opencode.data.model.ProvidersResponse
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionError
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.ui.testing.FakeAppContainer
import soy.iko.opencode.ui.testing.FakeEventStreamClient
import soy.iko.opencode.ui.testing.FakeOpencodeApiClient
import soy.iko.opencode.ui.testing.FakeOpencodeConnection
import soy.iko.opencode.ui.testing.FakeSessionRepository
import java.io.IOException

class ChatViewModelTest {
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

    /** Creates a ViewModel and lets its init coroutines start before returning. */
    private fun makeVm(container: FakeAppContainer, sessionId: String = "session1"): ChatViewModel {
        val vm = ChatViewModel(container, sessionId)
        testScope.testScheduler.advanceUntilIdle()
        return vm
    }

    // --- send() ---

    @Test
    fun send_returnsFalseWhenNotConnected() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        assertFalse(vm.send("hello"))
    }

    @Test
    fun send_returnsFalseForEmptyText() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        assertFalse(vm.send(""))
        assertFalse(vm.send("   "))
    }

    @Test
    fun send_returnsFalseWhenAlreadyRunning() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        assertTrue(vm.send("first"))
        assertFalse(vm.send("second"))
    }

    @Test
    fun send_clearsDraftImmediatelyAndSetsRunning() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.updateDraft("hello world")
        assertTrue(vm.send("hello world"))
        assertEquals("", vm.draft.value)
        assertTrue(vm.running.value)
    }

    @Test
    fun send_successPersistsEmptyDraft() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.updateDraft("hello")
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("", container.fakeDraftStore.get("session1"))
        assertNull(vm.failedDraft.value)
    }

    @Test
    fun send_failureRestoresDraftAndClearsRunning() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sendPromptThrows = IOException("network error")
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        vm.updateDraft("hello")
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("hello", vm.draft.value)
        assertEquals("hello", vm.failedDraft.value)
        assertFalse(vm.running.value)
        val errors = mutableListOf<ChatError>()
        val collectJob = launch { vm.errorEvents.toList(errors) }
        assertTrue(vm.send("hello"))
        testScheduler.advanceUntilIdle()
        collectJob.cancel()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun send_failureDoesNotRestoreDraftIfUserTypedNewText() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sendPromptThrows = IOException("network error")
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        vm.updateDraft("original")
        assertTrue(vm.send("original"))
        vm.updateDraft("new text")
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("new text", vm.draft.value)
    }

    // --- retryFailed() ---

    @Test
    fun retryFailed_noopWhenNoFailedDraft() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.retryFailed()
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
    }

    @Test
    fun retryFailed_resendsFailedDraft() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sendPromptThrows = IOException("network error")
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        vm.updateDraft("hello")
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("hello", vm.failedDraft.value)

        repo.sendPromptThrows = null
        vm.retryFailed()
        testScope.testScheduler.advanceUntilIdle()
        assertNull(vm.failedDraft.value)
    }

    // --- runCommand() ---

    @Test
    fun runCommand_setsRunning() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.runCommand(Command(name = "compact"))
        assertTrue(vm.running.value)
    }

    @Test
    fun runCommand_rejectedWhenAlreadyRunning() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        assertFalse(vm.running.value.not()) // running is true
        vm.runCommand(Command(name = "compact"))
        // Still running from the first send; runCommand should not override
        assertTrue(vm.running.value)
    }

    // --- abort() ---

    @Test
    fun abort_clearsRunning() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        vm.abort()
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
    }

    // --- respondPermission() ---

    @Test
    fun respondPermission_clearsPendingOptimistically() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        val perm = Permission(id = "p1", sessionID = "session1")

        events.fakeEvents.tryEmit(PermissionUpdated(perm))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("p1", vm.pendingPermission.value?.id)

        vm.respondPermission(perm, PermissionResponse.ONCE)
        assertNull(vm.pendingPermission.value)
    }

    @Test
    fun respondPermission_restoresPendingOnFailure() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        api.respondPermissionThrows = IOException("network error")
        val container = makeContainer(api = api)
        val vm = makeVm(container)
        val perm = Permission(id = "p1", sessionID = "session1")

        val events = (container.activeConnection.value!!.events as FakeEventStreamClient)
        events.fakeEvents.tryEmit(PermissionUpdated(perm))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("p1", vm.pendingPermission.value?.id)

        val errors = mutableListOf<ChatError>()
        val collectJob = launch { vm.errorEvents.toList(errors) }
        vm.respondPermission(perm, PermissionResponse.ONCE)
        assertNull(vm.pendingPermission.value)
        testScope.testScheduler.advanceUntilIdle()
        collectJob.cancel()
        assertEquals("p1", vm.pendingPermission.value?.id)
        assertTrue(errors.isNotEmpty())
    }

    // --- SSE event handling ---

    @Test
    fun sseSessionIdle_resetsRunning() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        events.fakeEvents.tryEmit(SessionIdle(SessionIdle.Props(sessionID = "session1")))
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
    }

    @Test
    fun sseSessionError_resetsRunningAndEmitsError() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        val errors = mutableListOf<ChatError>()
        val collectJob = launch { vm.errorEvents.toList(errors) }
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        events.fakeEvents.tryEmit(SessionError(SessionError.Props(sessionID = "session1")))
        testScope.testScheduler.advanceUntilIdle()
        collectJob.cancel()
        assertFalse(vm.running.value)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun sseSessionIdle_ignoresOtherSession() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        events.fakeEvents.tryEmit(SessionIdle(SessionIdle.Props(sessionID = "other")))
        testScope.testScheduler.advanceUntilIdle()
        assertTrue(vm.running.value)
    }

    @Test
    fun sseSessionUpdated_updatesTitle() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        events.fakeEvents.tryEmit(
            SessionUpdated(SessionUpdated.Props(info = Session(id = "session1", title = "My Chat"))),
        )
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("My Chat", vm.sessionTitle.value)
    }

    @Test
    fun sseSessionDeleted_setsDeletedFlag() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        events.fakeEvents.tryEmit(
            SessionDeleted(SessionDeleted.Props(sessionID = "session1")),
        )
        testScope.testScheduler.advanceUntilIdle()
        assertTrue(vm.sessionDeleted.value)
    }

    // --- Connection state ---

    @Test
    fun sseDisconnected_resetsRunning() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        events.fakeState.value = EventStreamClient.ConnectionState.Disconnected
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
    }

    @Test
    fun sseFailed_resetsRunning() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        events.fakeState.value = EventStreamClient.ConnectionState.Failed
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
    }

    // --- Draft persistence ---

    @Test
    fun updateDraft_doesNotPersistImmediately() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.updateDraft("typing")
        assertEquals("", container.fakeDraftStore.get("session1"))
        assertEquals("typing", vm.draft.value)
    }

    @Test
    fun updateDraft_persistsAfterDebounce() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.updateDraft("typing")
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("typing", container.fakeDraftStore.get("session1"))
    }

    @Test
    fun onCleared_flushesPendingDraft() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.updateDraft("unsent draft")
        testScope.testScheduler.advanceUntilIdle()
        // onCleared() is protected — invoke via reflection
        val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)
        assertEquals("unsent draft", container.fakeDraftStore.lastFlushed?.second)
    }

    // --- Catalog loading ---

    @Test
    fun catalogs_loadFromApi() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        api.agentsList = listOf(soy.iko.opencode.data.model.Agent(name = "code"))
        api.commandsList = listOf(Command(name = "compact"))
        val container = makeContainer(api = api)
        val vm = makeVm(container)
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(1, vm.agents.value.size)
        assertEquals("code", vm.agents.value[0].name)
        assertEquals(1, vm.commands.value.size)
    }

    @Test
    fun reloadModels_invalidatesCacheAndRefetches() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val container = makeContainer(api = api)
        val vm = makeVm(container)
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(api.providersCacheInvalidated)

        api.providersResponse = ProvidersResponse(
            providers = listOf(
                Provider(id = "p1", name = "Provider 1", models = mapOf("m1" to ModelInfo(id = "m1", name = "Model 1"))),
            ),
        )
        vm.reloadModels()
        testScope.testScheduler.advanceUntilIdle()
        assertTrue(api.providersCacheInvalidated)
        assertTrue(vm.models.value.isNotEmpty())
    }

    // --- reconnect() ---

    @Test
    fun reconnect_connectsToMostRecentProfile() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(
            listOf(ServerProfile(id = "p1", label = "Server 1", baseUrl = "http://localhost")),
        )
        val vm = makeVm(container)
        vm.reconnect()
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(1, container.connectCalls.size)
        assertEquals("p1", container.connectCalls[0].id)
    }

    @Test
    fun reconnect_withoutProfilesDoesNothing() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.reconnect()
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(0, container.connectCalls.size)
    }

    @Test
    fun reconnect_skipsWhenAlreadyConnected() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.reconnect()
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(0, container.connectCalls.size)
    }

    // --- Connection reset on server switch ---

    @Test
    fun connectionChange_resetsRunningAndErrorAndPermission() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        assertTrue(vm.send("hello"))
        testScope.testScheduler.advanceUntilIdle()
        // Switch to a new connection
        val api2 = FakeOpencodeApiClient()
        val events2 = FakeEventStreamClient()
        val repo2 = FakeSessionRepository(api2, events2)
        val conn2 = FakeOpencodeConnection(api2, events2, repo2, ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        container.setActiveConnection(conn2)
        testScope.testScheduler.advanceUntilIdle()
        assertFalse(vm.running.value)
        assertNull(vm.pendingPermission.value)
    }

    // --- rapid successive errors ---

    @Test
    fun rapidSuccessiveErrors_bothDeliveredAsEvents() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        repo.sendPromptThrows = IOException("err")
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)

        val errors = mutableListOf<ChatError>()
        val collectJob = launch { vm.errorEvents.toList(errors) }

        // Two rapid sends that both fail — both should produce independent error
        // events, not just the last one (the bug with StateFlow-keyed effects).
        assertTrue(vm.send("first"))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.send("second"))
        testScheduler.advanceUntilIdle()

        collectJob.cancel()
        assertEquals(2, errors.size)
    }

    @Test
    fun messagesFlowRetry_emitsErrorOnFailure() = testScope.runTest {
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        // Use a controllable flow so we can simulate a re-emission after an error.
        val messagesFlow = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(MessageWithParts(info = UserMessage(id = "m1", sessionID = "session1")))
        )
        val repo = FakeSessionRepository(api, events)
        repo.observeMessagesOverride = messagesFlow
        val container = makeContainer(api = api, events = events, repo = repo)
        val vm = makeVm(container)
        val errors = mutableListOf<ChatError>()
        val collectJob = launch { vm.errorEvents.toList(errors) }
        // Subscribe to messages so the flow's retryWhen is active.
        val collector = launch { vm.messages.collect { /* keep subscribed */ } }
        testScope.testScheduler.advanceUntilIdle()
        // Set an error via SSE
        events.fakeEvents.tryEmit(SessionError(SessionError.Props(sessionID = "session1")))
        testScope.testScheduler.advanceUntilIdle()
        collectJob.cancel()
        collector.cancel()
        assertTrue(errors.isNotEmpty())
    }

    // --- Connection switch clears session title ---

    @Test
    fun connectionChange_clearsSessionTitle() = testScope.runTest {
        val events = FakeEventStreamClient()
        val container = makeContainer(events = events)
        val vm = makeVm(container)
        // Set the title via SSE event
        events.fakeEvents.tryEmit(
            SessionUpdated(SessionUpdated.Props(info = Session(id = "session1", title = "Old Title"))),
        )
        testScope.testScheduler.advanceUntilIdle()
        assertEquals("Old Title", vm.sessionTitle.value)
        // Switch to a new connection
        val api2 = FakeOpencodeApiClient()
        val events2 = FakeEventStreamClient()
        val repo2 = FakeSessionRepository(api2, events2)
        val conn2 = FakeOpencodeConnection(api2, events2, repo2, ServerProfile(id = "s2", label = "Server 2", baseUrl = "http://other"))
        container.setActiveConnection(conn2)
        testScope.testScheduler.advanceUntilIdle()
        assertNull(vm.sessionTitle.value)
    }

    // --- editMessage ---

    @Test
    fun editMessage_prefillsDraftWithText() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.editMessage("m1", "the original prompt")
        testScheduler.advanceUntilIdle()
        assertEquals("the original prompt", vm.draft.value)
    }

    // --- attachment persistence ---

    private fun makeAttachment(id: String) = PendingAttachment(
        id = id,
        name = "$id.txt",
        mime = "text/plain",
        previewModel = null,
        part = soy.iko.opencode.data.model.FilePromptPart(
            mime = "text/plain",
            url = "data:text/plain;base64,QUJD",
            filename = "$id.txt",
        ),
    )

    @Test
    fun addAttachment_persistsToStore() = testScope.runTest {
        val container = makeContainer()
        val vm = makeVm(container)
        vm.addAttachment(makeAttachment("a1"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.fakeAttachmentDraftStore.load("session1").size)
    }

    @Test
    fun attachments_restoredFromStoreOnInit() = testScope.runTest {
        val container = makeContainer()
        // Pre-populate the store as if a prior compose was interrupted by process death.
        val att = makeAttachment("a1")
        container.fakeAttachmentDraftStore.save(
            "session1",
            listOf(soy.iko.opencode.data.repo.PersistedAttachment(att.id, att.name, att.mime, att.part.url, att.part.filename)),
        )
        val vm = makeVm(container)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.attachments.value.size)
        assertEquals("a1", vm.attachments.value[0].id)
    }
}
