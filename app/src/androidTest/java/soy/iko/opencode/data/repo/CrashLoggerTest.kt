package soy.iko.opencode.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for [CrashLogger]. Runs on a device/emulator so it can exercise the
 * real app-private filesystem: a simulated crash is written, then read back, listed, and
 * deleted through the public API.
 */
@RunWith(AndroidJUnit4::class)
class CrashLoggerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val logger = CrashLogger.get(context)

    @Test
    fun writeReadDeleteRoundTrip() {
        logger.clearAll()

        // Simulate an uncaught exception by invoking the report path directly.
        val report = writeFakeReport()

        logger.refresh()
        assertEquals(1, logger.reportCount())

        val content = logger.readReport(report)
        assertNotNull(content)
        assertTrue("report should contain the stack trace", content!!.contains("IllegalStateException"))
        assertTrue("report should contain device metadata", content.contains("Android:"))

        logger.deleteReport(report)
        assertEquals(0, logger.reportCount())
        assertNull(logger.readReport(report))
    }

    @Test
    fun clearAllRemovesEveryReport() {
        writeFakeReport("a")
        writeFakeReport("b")
        logger.refresh()
        assertTrue(logger.reportCount() >= 2)

        logger.clearAll()
        assertEquals(0, logger.reportCount())
    }

    private fun writeFakeReport(tag: String = "test"): String {
        val dir = java.io.File(context.filesDir, "crashes").apply { mkdirs() }
        val name = "crash-test-${tag}-${System.nanoTime()}.txt"
        java.io.File(dir, name).writeText(
            """
            opencode-android crash report
            Android: test
            java.lang.IllegalStateException: boom $tag
                at Crasher.crash(Crasher.kt:1)
            """.trimIndent(),
        )
        return name
    }
}
