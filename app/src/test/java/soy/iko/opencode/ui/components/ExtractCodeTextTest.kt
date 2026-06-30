package soy.iko.opencode.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractCodeTextTest {

    @Test
    fun fencedCodeStripsOpeningAndClosingFence() {
        val raw = "```kotlin\nfun main() {}\n```"
        assertEquals("fun main() {}", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun fencedCodeWithTildeFenceStripped() {
        val raw = "~~~python\nprint('hi')\n~~~"
        assertEquals("print('hi')", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun fencedCodePreservesBlankLines() {
        val raw = "```text\nline1\n\nline3\n```"
        assertEquals("line1\n\nline3", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun fencedCodeWithoutClosingFence() {
        // A streaming code block may not have a closing fence yet.
        val raw = "```kotlin\nfun main() {}"
        assertEquals("fun main() {}", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun fencedEmptyBody() {
        val raw = "```kotlin\n```"
        assertEquals("", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun fencedMultilineCode() {
        val raw = "```js\nconst a = 1;\nconst b = 2;\n```"
        assertEquals("const a = 1;\nconst b = 2;", extractCodeText(raw, isFenced = true))
    }

    @Test
    fun indentedCodeTrimIndent() {
        val raw = "    fun main() {\n        println()\n    }"
        assertEquals("fun main() {\n    println()\n}", extractCodeText(raw, isFenced = false))
    }

    @Test
    fun indentedCodeNoIndent() {
        val raw = "code without indent"
        assertEquals("code without indent", extractCodeText(raw, isFenced = false))
    }
}
