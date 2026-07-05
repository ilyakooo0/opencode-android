@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package soy.iko.opencode.ui.server

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.ui.testing.FakeAppContainer
import soy.iko.opencode.ui.testing.FakeOpencodeApiClient
import soy.iko.opencode.ui.testing.FakeOpencodeConnection
import soy.iko.opencode.ui.testing.FakeEventStreamClient
import soy.iko.opencode.ui.testing.FakeSessionRepository

class ServerSettingsViewModelTest {
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

    private fun makeVm(container: FakeAppContainer, profileId: String): ServerSettingsViewModel {
        val vm = ServerSettingsViewModel(container, profileId)
        testScope.testScheduler.advanceUntilIdle()
        return vm
    }

    private fun profile(
        id: String = "p1",
        label: String = "My Server",
        baseUrl: String = "http://localhost:3000",
        username: String? = null,
        password: String? = null,
        requireHttps: Boolean = false,
        certPin: String? = null,
    ): ServerProfile = ServerProfile(id, label, baseUrl, username, password, requireHttps = requireHttps, certPin = certPin)

    // --- init: load existing profile ---

    @Test
    fun init_loadsExistingProfileFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(label = "My Server", baseUrl = "http://localhost:3000")))
        val vm = makeVm(container, "p1")
        assertTrue(vm.state.value.loaded)
        assertEquals("p1", vm.state.value.id)
        assertEquals("My Server", vm.state.value.label)
        assertEquals("http://localhost:3000", vm.state.value.baseUrl)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun init_authedProfile_showsAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(username = "admin", password = "secret")))
        val vm = makeVm(container, "p1")
        assertTrue(vm.state.value.authFieldsVisible)
        assertEquals("admin", vm.state.value.username)
        assertEquals("secret", vm.state.value.password)
    }

    @Test
    fun init_nonExistentProfile_showsError() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(emptyList())
        val vm = makeVm(container, "nonexistent")
        assertTrue(vm.state.value.loaded)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun init_securitySettingsLoaded() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(
            listOf(profile(requireHttps = true, certPin = "sha256/abcdefg=")),
        )
        val vm = makeVm(container, "p1")
        assertTrue(vm.state.value.requireHttps)
        assertEquals("sha256/abcdefg=", vm.state.value.certPin)
    }

    // --- update / dirty detection ---

    @Test
    fun update_marksDirty() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(label = "Old")))
        val vm = makeVm(container, "p1")
        assertFalse(vm.state.value.isDirty)
        vm.update { it.copy(label = "New") }
        assertTrue(vm.state.value.isDirty)
    }

    @Test
    fun update_clearsError() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(emptyList())
        val vm = makeVm(container, "nonexistent")
        assertNotNull(vm.state.value.error)
        vm.update { it.copy(label = "x") }
        assertNull(vm.state.value.error)
    }

    // --- save() ---

    @Test
    fun save_persistsAndCallsOnDone() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(label = "Old")))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(label = "New") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertTrue(doneCalled)
        assertEquals("New", container.fakeProfileStore.savedProfile?.label)
    }

    @Test
    fun save_derivesLabelFromHostnameWhenBlank() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(label = "Old", baseUrl = "http://localhost:3000")))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(label = "") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        assertEquals("localhost:3000", container.fakeProfileStore.savedProfile?.label)
    }

    @Test
    fun save_normalizesBaseUrl() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(baseUrl = "http://old")))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(baseUrl = "localhost:4096") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        assertEquals("https://localhost:4096", container.fakeProfileStore.savedProfile?.baseUrl)
    }

    @Test
    fun save_preservesIdAndLastUsed() = testScope.runTest {
        val container = FakeAppContainer()
        val original = profile(id = "p1", baseUrl = "http://old").copy(lastUsed = 12345L)
        container.fakeProfileStore.setProfiles(listOf(original))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(baseUrl = "http://new") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        assertEquals("p1", container.fakeProfileStore.savedProfile?.id)
        assertEquals(12345L, container.fakeProfileStore.savedProfile?.lastUsed)
    }

    @Test
    fun save_reconnectsWhenActiveProfile() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(id = "p1")))
        val vm = makeVm(container, "p1")
        // Make p1 the active connection.
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val conn = FakeOpencodeConnection(
            api, events, FakeSessionRepository(api, events), profile(id = "p1"),
        )
        container.setActiveConnection(conn)
        vm.update { it.copy(label = "Renamed") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        // Saving an active profile reconnects (connect is called again with the saved profile).
        assertEquals(1, container.connectCalls.size)
        assertEquals("p1", container.connectCalls[0].id)
    }

    @Test
    fun save_doesNotReconnectWhenNotActive() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(id = "p1")))
        val vm = makeVm(container, "p1")
        // No active connection set.
        vm.update { it.copy(label = "Renamed") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.connectCalls.size)
        assertNotNull(container.fakeProfileStore.savedProfile)
    }

    @Test
    fun save_failureSetsErrorAndClearsSaving() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile()))
        container.fakeProfileStore.saveException = RuntimeException("disk full")
        val vm = makeVm(container, "p1")
        vm.update { it.copy(label = "New") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(doneCalled)
        assertFalse(vm.state.value.saving)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("disk full"))
    }

    @Test
    fun save_whileSavingDoesNotSaveAgain() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile()))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(label = "New") }
        var doneCount = 0
        vm.save { doneCount++ }
        vm.save { doneCount++ }
        testScheduler.advanceUntilIdle()
        assertEquals(1, doneCount)
    }

    @Test
    fun save_invalidUrlDoesNotSave() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(baseUrl = "http://localhost:3000")))
        val vm = makeVm(container, "p1")
        vm.update { it.copy(baseUrl = "not a url") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(doneCalled)
        // savedProfile stays null because save() never reached profileStore.save().
        assertNull(container.fakeProfileStore.savedProfile)
    }

    // --- delete() ---

    @Test
    fun delete_removesProfileAndCallsOnDone() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(id = "p1")))
        val vm = makeVm(container, "p1")
        var doneCalled = false
        vm.delete { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertTrue(doneCalled)
    }

    @Test
    fun delete_activeProfile_disconnects() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(id = "p1")))
        val vm = makeVm(container, "p1")
        val api = FakeOpencodeApiClient()
        val events = FakeEventStreamClient()
        val conn = FakeOpencodeConnection(
            api, events, FakeSessionRepository(api, events), profile(id = "p1"),
        )
        container.setActiveConnection(conn)
        vm.delete { }
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.disconnectCalls)
    }

    @Test
    fun delete_nonActiveProfile_doesNotDisconnect() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(listOf(profile(id = "p1")))
        val vm = makeVm(container, "p1")
        vm.delete { }
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.disconnectCalls)
    }
}
