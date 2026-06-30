package soy.iko.opencode.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeBaseUrlTest {

    @Test
    fun addsHttpSchemeWhenAbsent() {
        assertEquals("https://192.168.1.10:4096/", HttpClientFactory.normalizeBaseUrl("192.168.1.10:4096"))
    }

    @Test
    fun preservesHttpScheme() {
        assertEquals("http://localhost:4096/", HttpClientFactory.normalizeBaseUrl("http://localhost:4096"))
    }

    @Test
    fun preservesHttpsScheme() {
        assertEquals("https://example.com/", HttpClientFactory.normalizeBaseUrl("https://example.com"))
    }

    @Test
    fun appendsTrailingSlash() {
        assertEquals("https://host/", HttpClientFactory.normalizeBaseUrl("host"))
    }

    @Test
    fun keepsSingleTrailingSlash() {
        assertEquals("http://host/", HttpClientFactory.normalizeBaseUrl("http://host/"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("http://host/", HttpClientFactory.normalizeBaseUrl("  http://host  "))
    }

    @Test
    fun keepsPathAfterHost() {
        assertEquals("https://host:4096/opencode/", HttpClientFactory.normalizeBaseUrl("host:4096/opencode"))
    }
}
