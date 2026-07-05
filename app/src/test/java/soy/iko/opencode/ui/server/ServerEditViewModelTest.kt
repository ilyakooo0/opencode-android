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

    private fun makeVm(container: FakeAppContainer): ServerEditViewModel {
        val vm = ServerEditViewModel(container)
        testScope.testScheduler.advanceUntilIdle()
        return vm
    }

    // --- new profile ---

    @Test
    fun init_newProfile_loadsEmptyForm() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.isNew)
        assertEquals("", vm.state.value.baseUrl)
        assertEquals("", vm.state.value.label)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.error)
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

    @Test
    fun canSave_trueForBareHostWithoutScheme() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        // A bare host:port is schemed by normalizeForSave at save time, so canSave must
        // accept it — the user shouldn't have to type a protocol to enable Save/Connect.
        vm.update { it.copy(baseUrl = "localhost:3000") }
        assertTrue(vm.state.value.canSave)
        vm.update { it.copy(baseUrl = "192.168.1.10:4096") }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun canSave_trueForBarePublicDomain() = testScope.runTest {
        val container = FakeAppContainer()
        val vm = makeVm(container)
        // A public-looking domain with no port is schemed to https:// by normalizeForSave,
        // which OkHttp accepts, so canSave must be true.
        vm.update { it.copy(baseUrl = "example.com") }
        assertTrue(vm.state.value.canSave)
    }

    // --- hostFromBaseUrl (default label derivation) ---

    @Test
    fun hostFromBaseUrl_extractsHostAndPort() {
        assertEquals("localhost:3000", hostFromBaseUrl("http://localhost:3000"))
        assertEquals("192.168.1.10:4096", hostFromBaseUrl("192.168.1.10:4096"))
        assertEquals("example.com", hostFromBaseUrl("https://example.com"))
        assertEquals("example.com", hostFromBaseUrl("example.com"))
        // Default port (443 for https) is omitted.
        assertEquals("example.com", hostFromBaseUrl("https://example.com:443"))
    }

    // --- connect() save path (probe Reachable → save + connect + ping) ---
    // The add-server form has no label field, so the saved label is derived from the hostname
    // via hostFromBaseUrl. Connect is the single primary action and its probe IS the
    // connection test.

    @Test
    fun connect_persistsProfileAndCallsOnDone() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertTrue(doneCalled)
        assertNotNull(container.fakeProfileStore.savedProfile)
        assertEquals("http://localhost:3000", container.fakeProfileStore.savedProfile?.baseUrl)
    }

    @Test
    fun connect_derivesLabelFromHostnameWhenLabelBlank() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "192.168.1.10:4096") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertEquals("192.168.1.10:4096", saved.label)
    }

    @Test
    fun connect_preservesExplicitLabelWhenProvided() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        // The add form has no label field, but the VM still honors a label set via update()
        // (e.g. by a future caller) — only a blank label is derived.
        vm.update { it.copy(baseUrl = "http://localhost:3000", label = "My Server") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        assertEquals("My Server", container.fakeProfileStore.savedProfile?.label)
    }

    @Test
    fun connect_generatesIdForNewProfile() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile
        assertNotNull(saved?.id)
        assertTrue(saved!!.id.isNotEmpty())
    }

    @Test
    fun connect_trimsFields() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "  http://localhost:3000  ", username = "  admin  ") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertEquals("http://localhost:3000", saved.baseUrl)
        assertEquals("admin", saved.username)
    }

    @Test
    fun connect_appliesHttpsSchemeToBareLanHost() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "localhost:3000") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        // A bare host is always schemed to https:// (the safe default). The user can still
        // opt into cleartext by typing http:// explicitly.
        assertEquals("https://localhost:3000", saved.baseUrl)
        // The editor's own field is refreshed to the schemed form so a just-saved bare-host
        // entry doesn't read as dirty against its own stored value on re-open.
        assertEquals("https://localhost:3000", vm.state.value.baseUrl)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun connect_appliesHttpsSchemeToBarePublicDomain() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "example.com") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertEquals("https://example.com", saved.baseUrl)
    }

    @Test
    fun connect_preservesExplicitHttpSchemeForPublicDomain() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        // The user typed the scheme explicitly — don't upgrade a public domain to https://
        // behind their back; preserve their explicit http:// choice.
        vm.update { it.copy(baseUrl = "http://example.com") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertEquals("http://example.com", saved.baseUrl)
    }

    @Test
    fun connect_emptyUsernameClearsCredentials() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", username = "", password = "") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        val saved = container.fakeProfileStore.savedProfile!!
        assertNull(saved.username)
        assertNull(saved.password)
    }

    @Test
    fun connect_saveFailureSetsErrorAndClearsSaving() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        container.fakeProfileStore.saveException = RuntimeException("disk full")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(doneCalled)
        assertFalse(vm.state.value.saving)
        // The error must come from the exception, not from a clobbered ServerEditState.
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("disk full"))
    }

    @Test
    fun connect_saveFailureDoesNotClobberUserEdits() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        container.fakeProfileStore.saveException = RuntimeException("disk full")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", label = "Original") }
        vm.connect { }
        // Simulate the user editing the label while the save coroutine is in-flight.
        vm.update { it.copy(label = "Edited") }
        testScheduler.advanceUntilIdle()
        // The error path must not overwrite the user's edit.
        assertEquals("Edited", vm.state.value.label)
    }

    // --- connect(): probe folded into the primary action ---

    @Test
    fun connect_reachableServer_savesAndConnects() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.authFieldsVisible)
        assertNull(vm.state.value.error)
        assertEquals(1, container.probeCalls.size)
        // Reachable → the profile is saved and a connection is opened.
        assertNotNull(container.fakeProfileStore.savedProfile)
        assertEquals(1, container.connectCalls.size)
        assertTrue(doneCalled)
    }

    @Test
    fun connect_needsAuth_showsAuthFieldsWithoutSaving() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.authFieldsVisible)
        assertNull(vm.state.value.error)
        // Auth needed: reveal the fields; nothing is saved or connected yet.
        assertNull(container.fakeProfileStore.savedProfile)
        assertEquals(0, container.connectCalls.size)
        assertFalse(doneCalled)
    }

    @Test
    fun connect_unreachable_showsErrorAndDoesNotSave() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Unreachable("Could not reach server")
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.authFieldsVisible)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.error!!.contains("Could not reach server"))
        assertNull(container.fakeProfileStore.savedProfile)
    }

    @Test
    fun connect_withCredentials_skipsProbeAndSaves() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000", username = "admin", password = "secret") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        // Credentials present: no probe, straight to save + connect.
        assertEquals(0, container.probeCalls.size)
        assertNotNull(container.fakeProfileStore.savedProfile)
        assertEquals(1, container.connectCalls.size)
        assertTrue(doneCalled)
    }

    @Test
    fun connect_authFieldsVisible_skipsProbe() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        // Simulate a first Connect that revealed auth, then a second Connect with creds typed.
        vm.update { it.copy(baseUrl = "http://localhost:3000", authFieldsVisible = true) }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.probeCalls.size)
        assertNotNull(container.fakeProfileStore.savedProfile)
    }

    @Test
    fun connect_invalidUrlDoesNothing() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "not a url") }
        var doneCalled = false
        vm.connect { doneCalled = true }
        testScheduler.advanceUntilIdle()
        assertEquals(0, container.probeCalls.size)
        assertNull(container.fakeProfileStore.savedProfile)
        assertFalse(doneCalled)
    }

    @Test
    fun connect_whileSavingDoesNotConnectAgain() = testScope.runTest {
        val container = FakeAppContainer()
        container.probeResult = ProbeResult.Reachable
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.connect { }
        vm.connect { }
        testScheduler.advanceUntilIdle()
        assertEquals(1, container.probeCalls.size)
    }

    @Test
    fun connect_setsSavingFlagDuringProbeThenClearsOnAuth() = testScope.runTest {
        val container = FakeAppContainer()
        // NeedsAuth ends the flow (reveals auth) rather than navigating away, so saving
        // returns to false and is observable — the reachable path stays saving until onDone.
        container.probeResult = ProbeResult.NeedsAuth
        val vm = makeVm(container)
        vm.update { it.copy(baseUrl = "http://localhost:3000") }
        vm.connect { }
        // Before advancing, the probe is in flight → saving is true.
        assertTrue(vm.state.value.saving)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.saving)
    }
}
