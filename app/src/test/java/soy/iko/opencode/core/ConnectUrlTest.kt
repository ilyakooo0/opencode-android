package soy.iko.opencode.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Covers two related connect-screen bugs:
 *  - the URL field must stay editable — normalizing per keystroke made it
 *    "impossible to input" (it ate the "://" as you typed);
 *  - a scheme-less URL must still be normalized to http:// on connect, and the
 *    HTTP client must never crash on a malformed URL.
 */
class ConnectUrlTest {
    private fun core() = OpencodeCore(CoroutineScope(Dispatchers.Unconfined))

    /** A client that never touches the network, so Connect's probe can't hang. */
    private fun offlineCore(): OpencodeCore {
        val offline = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("offline in test") })
            .build()
        return OpencodeCore(CoroutineScope(Dispatchers.Unconfined), HttpClient(offline))
    }

    @Test
    fun `typing preserves raw input verbatim`() {
        val core = core()
        // Mid-scheme text the user is still typing must not be rewritten.
        core.dispatch(Event.ServerUrlChanged("http:/"))
        assertEquals("http:/", core.view.value.serverUrl)
    }

    @Test
    fun `connect normalizes a scheme-less url to http`() {
        val core = offlineCore()
        core.dispatch(Event.ServerUrlChanged("192.168.1.10:4096"))
        assertEquals("192.168.1.10:4096", core.view.value.serverUrl) // raw while typing
        core.dispatch(Event.Connect)
        assertEquals("http://192.168.1.10:4096", core.view.value.serverUrl) // normalized on connect
    }

    @Test
    fun `connect trims and keeps an explicit scheme`() {
        val core = offlineCore()
        core.dispatch(Event.ServerUrlChanged("  https://host:4096/  "))
        core.dispatch(Event.Connect)
        assertEquals("https://host:4096", core.view.value.serverUrl)
    }

    @Test
    fun `connect on a blank url reports an error`() {
        val core = core()
        core.dispatch(Event.ServerUrlChanged("   "))
        core.dispatch(Event.Connect)
        assertEquals("Enter a server URL", core.view.value.error)
    }

    @Test
    fun `http client returns failure rather than throwing on an unparseable url`() = runTest {
        val result = HttpClient().get("not a valid url", auth = null)
        assertTrue("expected a Result.failure, got $result", result.isFailure)
    }
}
