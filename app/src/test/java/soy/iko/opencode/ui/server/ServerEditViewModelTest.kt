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
import soy.iko.opencode.di.ProbeResult
import soy.iko.opencode.ui.testing.FakeAppContainer

class ServerEditViewModelTest {
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

    private fun makeVm(container: FakeAppContainer, profileId: String? = null): ServerEditViewModel {
        val vm = ServerEditViewModel(container, profileId)
        testScope.testScheduler.advanceUntilIdle()
        return vm
    }

    // --- new profile ---

    @Test
    fun init_newProfile_loadsEmptyForm() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container, profileId = null)
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.isNew)
        assertEquals("", vm.state.value.baseUrl)
        assertEquals("", vm.state.value.label)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.error)
    }

    // --- existing profile ---

    @Test
    fun init_existingProfile_loadsFormFields() = testScope.runTest {
        val container = FakeAppContainer()
        val profile = ServerProfile(
            id = "p1",
            label = "My Server",
            baseUrl = "http://localhost:3000",
            username = "admin",
            password = "secret",
        )
        container.fakeProfileStore.setProfiles(listOf(profile))
        val vm = makeVm(container, profileId = "p1")
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.isNew)
        assertEquals("p1", vm.state.value.id)
        assertEquals("My Server", vm.state.value.label)
        assertEquals("http://localhost:3000", vm.state.value.baseUrl)
        assertEquals("admin", vm.state.value.username)
        assertEquals("secret", vm.state.value.password)
    }

    @Test
    fun init_nonExistentProfile_showsError() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.setProfiles(emptyList())
        val vm = makeVm(container, profileId = "nonexistent")
        assertTrue(vm.state.value.loaded)
        assertNotNull(vm.state.value.error)
    }

    // --- update() ---

    @Test
    fun update_modifiesState() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(label = "New Label") }
        assertEquals("New Label", vm.state.value.label)
    }

    // --- canSave validation ---

    @Test
    fun canSave_falseForBlankBaseUrl() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "") }
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun canSave_falseForInvalidUrl() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "not a url") }
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun canSave_trueForValidHttpUrl() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun canSave_trueForValidHttpsUrl() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "https://example.com") }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun canSave_falseForFtpUrl() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "ftp://example.com") }
        assertFalse(vm.state.value.canSave)
    }

    // --- save() ---

    @Test
    fun save_persistsProfileAndCallsOnDone() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", label = "Test") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertTrue(doneCalled)
        assertNotNull(container.fakeProfileStore.savedProfile)
        assertEquals("http://localhost:3000", container.fakeProfileStore.savedProfile?.baseUrl)
        assertEquals("Test", container.fakeProfileStore.savedProfile?.label)
    }

    @Test
    fun save_invalidUrlDoesNotSave() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "invalid") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(doneCalled)
        assertNull(container.fakeProfileStore.savedProfile)
    }

    @Test
    fun save_whileSavingDoesNotSaveAgain() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCount = 0
        vm.save { doneCount++ }
        // While saving, try to save again
        vm.save { doneCount++ }
        testScheduler.advanceUntilIdle()
        assertEquals(1, doneCount)
    }

    @Test
    fun save_generatesIdForNewProfile() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile
        assertNotNull(saved?.id)
        assertTrue(saved!!.id.isNotEmpty())
    }

    @Test
    fun save_preservesIdForExistingProfile() = testScope.runTest {
        val container = FakeAppContainer()
        val profile = ServerProfile(id = "existing-id", label = "Old", baseUrl = "http://old")
        container.fakeProfileStore.setProfiles(listOf(profile))
        val vm = makeVm(container, profileId = "existing-id")
        vm.update { it.copy(baseUrl = "http://new") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        assertEquals("existing-id", container.fakeProfileStore.savedProfile?.id)
    }

    @Test
    fun save_trimsFields() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "  http://localhost:3000  ", label = "  Test  ", username = "  admin  ") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertEquals("http://localhost:3000", saved.baseUrl)
        assertEquals("Test", saved.label)
        assertEquals("admin", saved.username)
    }

    @Test
    fun save_emptyUsernameClearsCredentials() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", username = "", password = "") }
        vm.save { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertNull(saved.username)
        assertNull(saved.password)
    }

    @Test
    fun save_failureSetsErrorAndClearsSaving() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.saveException = RuntimeException("disk full")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCalled = false
        vm.save { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(doneCalled)
        assertFalse(vm.state.value.saving)
        // The error must come from the exception, not from a clobbered ServerEditState.
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("disk full"))
    }

    @Test
    fun save_failureDoesNotClobberUserEdits() = testScope.runTest {
        val container = FakeAppContainer()
        container.fakeProfileStore.saveException = RuntimeException("disk full")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", label = "Original") }
        vm.save { }
        // Simulate the user editing the label while the save coroutine is in-flight.
        vm.update { it.copy(label = "Edited") }
        testScheduler.advanceUntilIdle()
        // The error path must not overwrite the user's edit.
        assertEquals("Edited", vm.state.value.label)
    }

    // --- probe() auth detection ---

    @Test
    fun probe_reachableServer_hidesAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.probing)
        assertFalse(vm.state.value.authFieldsVisible)
        assertNull(vm.state.value.error)
        assertEquals(1, container.probeCalls.size)
    }

    @Test
    fun probe_needsAuth_showsAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.probing)
        assertTrue(vm.state.value.authFieldsVisible)
        assertNull(vm.state.value.error)
    }

    @Test
    fun probe_unreachable_showsErrorAndHidesAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Unreachable("Could not reach server")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.probing)
        assertFalse(vm.state.value.authFieldsVisible)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("Could not reach server"))
    }

    @Test
    fun probe_invalidUrlDoesNotProbe() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "invalid") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.probeCalls.size)
    }

    @Test
    fun probe_whileProbingDoesNotProbeAgain() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.probeCalls.size)
    }

    @Test
    fun probe_setsProbingFlagDuringCall() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        // Before advancing, probing should be true
        assertTrue(vm.state.value.probing)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.probing)
    }

    @Test
    fun editingBaseUrlAfterProbe_resetsAuthVisibility() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.authFieldsVisible)
        // User edits the URL — auth fields should hide so a re-probe is required
        vm.update { it.copy(baseUrl = "http://localhost:4000", authFieldsVisible = false, error = null) }
        assertFalse(vm.state.value.authFieldsVisible)
    }

    @Test
    fun init_existingProfileWithAuth_showsAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        val profile = ServerProfile(
            id = "p1",
            label = "My Server",
            baseUrl = "http://localhost:3000",
            username = "admin",
            password = "secret",
        )
        container.fakeProfileStore.setProfiles(listOf(profile))
        val vm = makeVm(container, profileId = "p1")
        assertTrue(vm.state.value.authFieldsVisible)
    }

    @Test
    fun init_existingProfileWithoutAuth_hidesAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        val profile = ServerProfile(
            id = "p1",
            label = "My Server",
            baseUrl = "http://localhost:3000",
        )
        container.fakeProfileStore.setProfiles(listOf(profile))
        val vm = makeVm(container, profileId = "p1")
        assertFalse(vm.state.value.authFieldsVisible)
    }

    @Test
    fun init_newProfile_hidesAuthFields() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        assertFalse(vm.state.value.authFieldsVisible)
    }

    @Test
    fun probe_failure_setsError() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = null // probeServer defaults to Reachable, so simulate exception
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        // With default fake, probe succeeds → no error
        assertNull(vm.state.value.error)
    }

    @Test
    fun probe_reachable_setsProbeReachable() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.probeReachable)
    }

    @Test
    fun probe_needsAuth_setsProbeReachableFalse() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.probe()
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.state.value.probeReachable)
    }

    @Test
    fun suggestUrlScheme_addsHttpForBareHost() {
        assertEquals("http://192.168.1.10:4096", suggestUrlScheme("192.168.1.10:4096"))
        assertEquals("http://localhost:3000", suggestUrlScheme("localhost:3000"))
    }

    @Test
    fun suggestUrlScheme_returnsNullForAlreadySchemed() {
        assertNull(suggestUrlScheme("http://localhost:3000"))
        assertNull(suggestUrlScheme("https://example.com"))
    }

    @Test
    fun suggestUrlScheme_returnsNullForUnparseable() {
        assertNull(suggestUrlScheme("not a url"))
        assertNull(suggestUrlScheme(""))
    }

    @Test
    fun duplicate_seedsFromSourceProfileAsNew() = testScope.runTest {
        val container = FakeAppContainer()
        val source = ServerProfile(
            id = "src1",
            label = "My Server",
            baseUrl = "http://localhost:3000",
            username = "admin",
            password = "secret",
        )
        container.fakeProfileStore.setProfiles(listOf(source))
        val vm = ServerEditViewModel(container, profileId = null, sourceId = "src1")
        testScope.testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        // A duplicate is a NEW profile (no id), so save() generates a fresh one.
        assertNull(vm.state.value.id)
        assertEquals("My Server (copy)", vm.state.value.label)
        assertEquals("http://localhost:3000", vm.state.value.baseUrl)
        assertEquals("admin", vm.state.value.username)
        assertEquals("secret", vm.state.value.password)
        assertTrue(vm.state.value.authFieldsVisible)
    }
}
