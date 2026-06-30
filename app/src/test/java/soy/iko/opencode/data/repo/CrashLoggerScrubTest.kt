package soy.iko.opencode.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoggerScrubTest {

    @Test
    fun scrubUrls_replacesBareUrl() {
        assertEquals("see [url] for details", CrashLogger.scrubUrls("see http://host:4096/path for details"))
    }

    @Test
    fun scrubUrls_replacesUrlWithCredentials() {
        // Ktor/OkHttp exception messages embed the full request URL, which may carry
        // auth or internal paths — none of it may survive into a stored crash report.
        val scrubbed = CrashLogger.scrubUrls("Bad response: GET https://u:se%20cret@10.0.0.2:4096/session/s%201/message")
        assertFalse("password leaked: $scrubbed", scrubbed.contains("se%20cret"))
        assertFalse("host leaked: $scrubbed", scrubbed.contains("10.0.0.2"))
        assertTrue("redaction marker missing: $scrubbed", scrubbed.contains("[url]"))
    }

    @Test
    fun scrubUrls_replacesEveryOccurrenceIncludingCausedBy() {
        // printStackTrace re-emits the message on the top line and on each "Caused by:"
        // line; the scrub must catch all of them, not just the first.
        val report = """
            io.ktor.client.plugins.ClientRequestException: GET https://srv/event
                at Foo.bar(Foo.kt:1)
            Caused by: java.net.SocketTimeoutException: Request timeout https://srv/session
        """.trimIndent()
        val scrubbed = CrashLogger.scrubUrls(report)
        assertFalse(scrubbed.contains("https://"))
        // Non-URL content (class names, stack frames) is preserved.
        assertTrue(scrubbed.contains("ClientRequestException"))
        assertTrue(scrubbed.contains("Foo.kt:1"))
    }

    @Test
    fun scrubUrls_leavesPlainTextUntouched() {
        val text = "opencode-android crash report\nAndroid: 14 (API 34)\nno urls here"
        assertEquals(text, CrashLogger.scrubUrls(text))
    }
}
