package soy.iko.opencode.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun setGetRemoveRoundTrip() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = DraftStore(context, scope)

        // Use a unique session ID so it doesn't collide with other tests.
        val sid = "test-${System.nanoTime()}"

        store.set(sid, "hello world")
        assertEquals("hello world", store.get(sid))

        // Overwrite
        store.set(sid, "updated")
        assertEquals("updated", store.get(sid))

        // Blank clears the entry
        store.set(sid, "   ")
        assertEquals("", store.get(sid))

        // Explicit remove
        store.set(sid, "text")
        store.remove(sid)
        assertEquals("", store.get(sid))

        scope.cancel()
    }

    @Test
    fun getReturnsEmptyForUnknownSession() {
        val store = DraftStore(context, CoroutineScope(SupervisorJob()))
        assertEquals("", store.get("never-set-${System.nanoTime()}"))
    }
}
