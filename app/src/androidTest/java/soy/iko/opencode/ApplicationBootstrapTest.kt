package soy.iko.opencode

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the [OpencodeApp] Application bootstrap: the container and crash logger
 * are initialized on startup, and the notification channels are created.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationBootstrapTest {

    @Test
    fun containerIsInitialized() {
        val app = ApplicationProvider.getApplicationContext<OpencodeApp>()
        assertNotNull(app.container)
        assertNotNull(app.container.settingsStore)
    }

    @Test
    fun notificationChannelsExist() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Touching the app instance runs Application.onCreate, which creates the channels.
        ApplicationProvider.getApplicationContext<OpencodeApp>()
        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val ids = nm.notificationChannels.map { it.id }
        assertTrue("status channel should exist, got $ids", ids.contains("run_status"))
        assertTrue("completed channel should exist, got $ids", ids.contains("session_completed"))
    }
}
