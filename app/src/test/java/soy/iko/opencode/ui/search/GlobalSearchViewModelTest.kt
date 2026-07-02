@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.ui.testing.FakeAppContainer
import soy.iko.opencode.ui.testing.FakeEventStreamClient
import soy.iko.opencode.ui.testing.FakeOpencodeApiClient
import soy.iko.opencode.ui.testing.FakeOpencodeConnection
import soy.iko.opencode.ui.testing.FakeSessionRepository

class GlobalSearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeContainer(
        sessions: List<Session>,
        messages: List<MessageWithParts>,
    ): FakeAppContainer {
        val api = FakeOpencodeApiClient().apply { this.messages = messages }
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events).apply { this.sessions = sessions }
        val container = FakeAppContainer()
        container.setActiveConnection(
            FakeOpencodeConnection(api, events, repo, ServerProfile(id = "s1", label = "T", baseUrl = "http://localhost")),
        )
        return container
    }

    private fun textMessage(id: String, text: String) = MessageWithParts(
        info = UserMessage(id = id, sessionID = "s1"),
        parts = listOf(TextPart(id = "p-$id", text = text)),
    )

    @Test
    fun query_matchesMessageText_andReturnsSnippet() = testScope.runTest {
        val container = makeContainer(
            sessions = listOf(Session(id = "s1", title = "Chat 1")),
            messages = listOf(textMessage("m1", "the quick brown fox jumps")),
        )
        val vm = GlobalSearchViewModel(container)

        vm.setQuery("brown")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.hasSearched)
        assertEquals(1, state.results.size)
        assertEquals("s1", state.results[0].session.id)
        assertTrue("snippet should contain the match", state.results[0].snippet.contains("brown"))
    }

    @Test
    fun query_belowMinLength_doesNotSearch() = testScope.runTest {
        val container = makeContainer(
            sessions = listOf(Session(id = "s1", title = "Chat 1")),
            messages = listOf(textMessage("m1", "hello world")),
        )
        val vm = GlobalSearchViewModel(container)

        vm.setQuery("h")
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.hasSearched)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun query_noMatch_setsHasSearchedWithEmptyResults() = testScope.runTest {
        val container = makeContainer(
            sessions = listOf(Session(id = "s1", title = "Chat 1")),
            messages = listOf(textMessage("m1", "hello world")),
        )
        val vm = GlobalSearchViewModel(container)

        vm.setQuery("zzzznomatch")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.hasSearched)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun query_matchesSessionTitle_evenWithoutMessageMatch() = testScope.runTest {
        val container = makeContainer(
            sessions = listOf(Session(id = "s1", title = "Refactor auth")),
            messages = listOf(textMessage("m1", "unrelated content")),
        )
        val vm = GlobalSearchViewModel(container)

        vm.setQuery("auth")
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.results.size)
        assertEquals("s1", vm.state.value.results[0].session.id)
    }
}
