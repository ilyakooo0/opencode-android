package soy.iko.opencode.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "app crashes after submitting a URL" bug: a
 * scheme-less server URL (e.g. "192.168.1.10:4096") used to reach OkHttp's URL
 * parser and throw, crashing the connect coroutine. It must now (a) be
 * normalized to an http:// URL by the core, and (b) never crash the HTTP client
 * even if something un-parseable slips through.
 */
class ConnectUrlTest {
    private fun core() = OpencodeCore(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `scheme-less url defaults to http`() {
        val core = core()
        core.dispatch(Event.ServerUrlChanged("192.168.1.10:4096"))
        assertEquals("http://192.168.1.10:4096", core.view.value.serverUrl)
    }

    @Test
    fun `url with scheme is preserved and trimmed`() {
        val core = core()
        core.dispatch(Event.ServerUrlChanged("  https://host:4096/  "))
        assertEquals("https://host:4096", core.view.value.serverUrl)
    }

    @Test
    fun `blank url normalizes to empty`() {
        val core = core()
        core.dispatch(Event.ServerUrlChanged("   "))
        assertEquals("", core.view.value.serverUrl)
    }

    @Test
    fun `http client returns failure rather than throwing on an unparseable url`() = runTest {
        val result = HttpClient().get("not a valid url", auth = null)
        assertTrue("expected a Result.failure, got $result", result.isFailure)
    }
}
