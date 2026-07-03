package soy.iko.opencode.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterLanguageTest {

    @Test
    fun tagResolvesByNameOrExtension() {
        assertEquals(Language.C_FAMILY, syntaxForLanguageTag("kotlin").lang)
        assertEquals(Language.C_FAMILY, syntaxForLanguageTag("typescript").lang)
        assertEquals(Language.C_FAMILY, syntaxForLanguageTag("ts").lang)
        assertEquals(Language.PYTHON, syntaxForLanguageTag("python").lang)
        assertEquals(Language.PYTHON, syntaxForLanguageTag("py").lang)
        assertEquals(Language.SHELL, syntaxForLanguageTag("bash").lang)
        assertEquals(Language.RUBY, syntaxForLanguageTag("ruby").lang)
        assertEquals(Language.RUBY, syntaxForLanguageTag("rb").lang)
        assertEquals(Language.JSON, syntaxForLanguageTag("json").lang)
        assertEquals(Language.MARKUP, syntaxForLanguageTag("html").lang)
        assertEquals(Language.NONE, syntaxForLanguageTag("brainfuck").lang)
    }

    @Test
    fun tagIsCaseInsensitiveAndTrimmed() {
        assertEquals(Language.C_FAMILY, syntaxForLanguageTag("  Kotlin  ").lang)
    }

    private val palette = HighlightPalette(
        keyword = Color.White,
        comment = Color.Gray,
        string = Color.Green,
        number = Color.Blue,
        annotation = Color.Cyan,
        tag = Color.Red,
        base = Color.Black,
    )

    @Test
    fun noneLanguageReturnsPlainString() {
        val code = "val x = 1"
        val out = highlightCode(code, syntaxForLanguageTag("txt"), palette)
        assertEquals(code, out.toString())
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun multilineCodePreservesTextAndAppliesSpans() {
        val code = "val a = 1\nval b = 2"
        val out = highlightCode(code, syntaxForLanguageTag("kotlin"), palette)
        // Highlighting must not alter the characters, only attach spans.
        assertEquals(code, out.toString())
        // 'val' is a C-family keyword and appears on both lines, so at least two spans exist.
        assertTrue("expected spans for two keyword lines", out.spanStyles.size >= 2)
    }

    @Test
    fun rubyNotMisclassifiedAsPython() {
        // Regression guard: `.rb` previously fell into the Python keyword set.
        assertEquals(Language.RUBY, syntaxForLanguageTag("rb").lang)
        val out = highlightCode("def foo\nend", syntaxForLanguageTag("ruby"), palette)
        assertEquals("def foo\nend", out.toString())
        assertTrue(out.spanStyles.isNotEmpty())
    }
}
