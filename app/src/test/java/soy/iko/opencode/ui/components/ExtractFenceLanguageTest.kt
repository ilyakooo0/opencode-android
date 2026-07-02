package soy.iko.opencode.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractFenceLanguageTest {

    @Test
    fun extractsLanguageTag() {
        assertEquals("kotlin", extractFenceLanguage("```kotlin\nval x = 1\n```", isFenced = true))
    }

    @Test
    fun takesFirstTokenOfInfoString() {
        assertEquals("ts", extractFenceLanguage("```ts title=foo.ts\ncode\n```", isFenced = true))
    }

    @Test
    fun nullForIndentedBlock() {
        assertNull(extractFenceLanguage("    indented code", isFenced = false))
    }

    @Test
    fun nullForBareFence() {
        assertNull(extractFenceLanguage("```\nplain\n```", isFenced = true))
    }
}
