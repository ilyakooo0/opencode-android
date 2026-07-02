@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.file

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.ui.testing.FakeAppContainer
import soy.iko.opencode.ui.testing.FakeEventStreamClient
import soy.iko.opencode.ui.testing.FakeOpencodeApiClient
import soy.iko.opencode.ui.testing.FakeOpencodeConnection
import soy.iko.opencode.ui.testing.FakeSessionRepository

class FileViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeContainer(api: FakeOpencodeApiClient): FakeAppContainer {
        val events = FakeEventStreamClient()
        val repo = FakeSessionRepository(api, events)
        val container = FakeAppContainer()
        container.setActiveConnection(
            FakeOpencodeConnection(api, events, repo, ServerProfile(id = "s1", label = "T", baseUrl = "http://localhost")),
        )
        return container
    }

    @Test
    fun load_success_populatesContent() = testScope.runTest {
        val api = FakeOpencodeApiClient().apply { fileContent = FileContent(content = "hello source") }
        val vm = FileViewModel(makeContainer(api), "src/Main.kt")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.content)
        assertEquals("hello source", state.content?.content)
        assertNull(state.error)
    }

    @Test
    fun load_withoutConnection_showsNotConnected() = testScope.runTest {
        val vm = FileViewModel(FakeAppContainer(), "src/Main.kt")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.error)
        assertNull(state.content)
    }
}
