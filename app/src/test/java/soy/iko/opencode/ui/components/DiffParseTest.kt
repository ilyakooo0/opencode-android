package soy.iko.opencode.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffParseTest {

    @Test
    fun parsesAddedRemovedContextAndHunks() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@
             context line
            -removed line
            +added line
        """.trimIndent()

        val lines = parseDiff(diff)
        assertEquals(6, lines.size)
        assertTrue(lines[0] is DiffLine.FileHeader) // ---
        assertTrue(lines[1] is DiffLine.FileHeader) // +++
        assertTrue(lines[2] is DiffLine.Hunk)       // @@
        assertTrue(lines[3] is DiffLine.Context)
        assertTrue(lines[4] is DiffLine.Remove)
        assertEquals("removed line", (lines[4] as DiffLine.Remove).text)
        assertTrue(lines[5] is DiffLine.Add)
        assertEquals("added line", (lines[5] as DiffLine.Add).text)
    }

    @Test
    fun stripsPrefixFromAddAndRemove() {
        val lines = parseDiff("-gone\n+new")
        assertEquals("gone", (lines[0] as DiffLine.Remove).text)
        assertEquals("new", (lines[1] as DiffLine.Add).text)
    }

    @Test
    fun looksLikeDiffTrueForUnifiedDiff() {
        assertTrue(looksLikeDiff("@@ -1,1 +1,1 @@\n-old\n+new"))
    }

    @Test
    fun looksLikeDiffFalseForPlainText() {
        assertFalse(looksLikeDiff("This is just some prose.\nNo diff here."))
        assertFalse(looksLikeDiff(""))
    }

    @Test
    fun emptyInputParsesToEmpty() {
        assertTrue(parseDiff("").isEmpty())
    }
}
