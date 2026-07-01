package soy.iko.opencode.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Lightweight, dependency-free syntax highlighting for the file viewer.
 *
 * A full tokenizer (TreeSitter, Prism, etc.) would add a heavy native or generated
 * dependency that conflicts with the project's no-KSP / no-native-deps stance. This
 * heuristic highlighter instead colors comments, strings, numbers, and a per-language
 * keyword set via simple character/word scanning — enough to make a code file scannable
 * in the viewer without pulling in a parser.
 *
 * The highlighter is per-line (the viewer renders line-by-line), so multi-line block
 * comments and triple-quoted strings are not handled; that's an accepted trade-off for
 * a viewer (not an editor) where the goal is visual orientation, not correctness.
 */

/** Language families recognized by [highlightLine]. The set of keywords is shared
 *  across C-family languages (kotlin, java, js, ts, swift, rust, go, c, cpp) since the
 *  common keywords overlap heavily; python has its own set; shell has its own. */
private enum class Language {
    C_FAMILY, // kotlin, java, js, ts, swift, rust, go, c, cpp, csharp, scala
    PYTHON,
    SHELL, // sh, bash, zsh
    MARKUP, // html, xml, svg
    NONE,
}

private fun languageFor(filename: String): Language {
    val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx",
        "swift", "rs", "go", "c", "h", "cpp", "cc", "cxx", "hpp", "hh",
        "cs", "scala", "groovy", "dart", "gradle",
        -> Language.C_FAMILY
        "py", "pyw", "rb" -> Language.PYTHON
        "sh", "bash", "zsh", "fish" -> Language.SHELL
        "html", "htm", "xml", "svg", "vue" -> Language.MARKUP
        else -> Language.NONE
    }
}

private val cFamilyKeywords = setOf(
    "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
    "const", "continue", "default", "defer", "do", "else", "enum", "export",
    "extends", "external", "final", "finally", "fn", "for", "func", "fun", "go",
    "if", "impl", "implements", "import", "in", "init", "inline", "instanceof", "is",
    "let", "match", "mod", "module", "mut", "namespace", "new", "object", "of",
    "override", "package", "private", "protected", "public", "pub", "raise", "return",
    "sealed", "self", "static", "struct", "super", "suspend", "switch", "this", "throw",
    "throws", "trait", "try", "type", "typeof", "union", "unsafe", "val", "var", "when",
    "where", "while", "with", "yield", "true", "false", "null", "nil",
    "and", "or", "not", "lambda", "elif", "pass", "print",
)

private val pythonKeywords = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
    "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
    "raise", "return", "try", "while", "with", "yield", "self", "cls", "print",
)

private val shellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "case", "esac",
    "function", "in", "return", "exit", "echo", "export", "local", "readonly", "unset",
    "set", "shift", "source", "true", "false", "cd", "pwd", "ls", "cp", "mv", "rm",
)

/** Theme-derived colors for the highlighter. Captured in a @Composable so the styles
 *  re-resolve when the color scheme changes (e.g. dynamic color or a theme switch). */
data class HighlightPalette(
    val keyword: Color,
    val comment: Color,
    val string: Color,
    val number: Color,
    val annotation: Color,
    val tag: Color,
    val base: Color,
)

@Composable
fun rememberHighlightPalette(): HighlightPalette {
    // Use muted theme roles so the highlighting reads as part of the UI, not a foreign
    // palette. Keyword = primary, string = secondary, comment = outline (dimmed),
    // number = tertiary. Falls back gracefully when dynamic color is on (the roles
    // come from the wallpaper-derived scheme).
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        HighlightPalette(
            keyword = scheme.primary,
            comment = scheme.outline,
            string = scheme.secondary,
            number = scheme.tertiary,
            annotation = scheme.tertiary,
            tag = scheme.primary,
            base = scheme.onSurface,
        )
    }
}

/**
 * Produce a highlighted [AnnotatedString] for a single line of source code.
 *
 * The scanner walks the line character by character, classifying runs of chars as
 * comments, strings, numbers, identifiers (checked against the keyword set), or plain
 * text. It's O(n) per line and allocation-light (a single AnnotatedString.Builder),
 * which matters because the file viewer renders lazily but can scroll through
 * thousands of lines — a heavy highlighter would jank on fast scrolls.
 */
fun highlightLine(line: String, filename: String, palette: HighlightPalette): AnnotatedString {
    val lang = languageFor(filename)
    if (lang == Language.NONE) return AnnotatedString(line)
    val keywords = keywordsFor(lang)
    val builder = AnnotatedString.Builder(line.length + 8)
    val baseStyle = SpanStyle(color = palette.base, fontFamily = FontFamily.Monospace)
    val mono = FontFamily.Monospace
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        val emitted = emitToken(builder, line, i, lang, keywords, palette, baseStyle, mono)
        if (emitted.consumed) {
            i = emitted.nextIndex
        } else {
            builder.withStyle(baseStyle) { append(c) }
            i++
        }
    }
    return builder.toAnnotatedString()
}

/** Result of attempting to emit one token starting at [start]: the next scan index and
 *  whether a token was consumed (vs. falling through to the default plain-text emit). */
private data class EmitResult(val nextIndex: Int, val consumed: Boolean)

private fun emitToken(
    builder: AnnotatedString.Builder,
    line: String,
    start: Int,
    lang: Language,
    keywords: Set<String>,
    palette: HighlightPalette,
    baseStyle: SpanStyle,
    mono: FontFamily,
): EmitResult {
    val c = line[start]
    if (isLineCommentStart(c, line, start, lang)) {
        builder.withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic, fontFamily = mono)) {
            append(line.substring(start))
        }
        return EmitResult(line.length, true)
    }
    if (c == '"' || c == '\'') {
        val end = findStringEnd(line, start, c)
        builder.withStyle(SpanStyle(color = palette.string, fontFamily = mono)) { append(line.substring(start, end)) }
        return EmitResult(end, true)
    }
    if (isNumberStart(c, line, start)) {
        val end = findNumberEnd(line, start)
        builder.withStyle(SpanStyle(color = palette.number, fontFamily = mono)) { append(line.substring(start, end)) }
        return EmitResult(end, true)
    }
    if (c.isLetter() || c == '_' || c == '@') {
        val end = findIdentifierEnd(line, start)
        val word = line.substring(start, end)
        when {
            c == '@' -> builder.withStyle(SpanStyle(color = palette.annotation, fontFamily = mono)) { append(word) }
            word in keywords -> builder.withStyle(SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold, fontFamily = mono)) { append(word) }
            else -> builder.withStyle(baseStyle) { append(word) }
        }
        return EmitResult(end, true)
    }
    if (lang == Language.MARKUP && isMarkupDelim(c)) {
        builder.withStyle(SpanStyle(color = palette.tag, fontFamily = mono)) { append(c) }
        return EmitResult(start + 1, true)
    }
    return EmitResult(start + 1, false)
}

private fun keywordsFor(lang: Language): Set<String> = when (lang) {
    Language.C_FAMILY -> cFamilyKeywords
    Language.PYTHON -> pythonKeywords
    Language.SHELL -> shellKeywords
    Language.MARKUP -> emptySet()
    Language.NONE -> emptySet()
}

private fun isLineCommentStart(c: Char, line: String, i: Int, lang: Language): Boolean =
    (c == '/' && i + 1 < line.length && line[i + 1] == '/') ||
        (c == '#' && lang != Language.MARKUP)

private fun isMarkupDelim(c: Char): Boolean = c == '<' || c == '>' || c == '/' || c == '='

private fun isNumberStart(c: Char, line: String, i: Int): Boolean =
    c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit())

/** Scan forward to the end of a string literal starting at [start] (the opening quote
 *  is line[start]). Returns the index just past the closing quote (or line.length if
 *  the string is unterminated on this line). Honors backslash escapes. */
private fun findStringEnd(line: String, start: Int, quote: Char): Int {
    var i = start + 1
    val n = line.length
    while (i < n && line[i] != quote) {
        if (line[i] == '\\' && i + 1 < n) i += 2 else i++
    }
    return if (i < n) i + 1 else n
}

/** Scan forward to the end of a number literal starting at [start].
 *
 *  A small state machine so we don't over-consume neighbouring source: `5-3`, `1..10`
 *  and `123abc` must split rather than merge into one token.
 *   - `+`/`-` are consumed only immediately after an `e`/`E` (an exponent sign).
 *   - `e`/`E` continues a decimal number at most once, and never after a `0x` prefix.
 *   - hex digits `a-f`/`A-F` and the `x`/`X` marker are consumed only for a `0x`/`0X` literal.
 *   - a single `.` continues a decimal number; a second `.` stops the scan (so `1..10` splits).
 */
private fun findNumberEnd(line: String, start: Int): Int {
    val n = line.length
    // Detect a hex prefix ("0x"/"0X") right at the number start.
    val hex = start + 1 < n && line[start] == '0' && (line[start + 1] == 'x' || line[start + 1] == 'X')
    var i = if (hex) start + 2 else start + 1
    var seenDot = line[start] == '.'
    var seenExp = false
    while (i < n) {
        val c = line[i]
        val prev = line[i - 1]
        when {
            c.isDigit() || c == '_' -> i++
            hex && isHexDigit(c) -> i++
            c == '.' && !hex && !seenDot && !seenExp -> { seenDot = true; i++ }
            !hex && !seenExp && (c == 'e' || c == 'E') -> { seenExp = true; i++ }
            !hex && (c == '+' || c == '-') && (prev == 'e' || prev == 'E') -> i++
            else -> break
        }
    }
    return i
}

private fun isHexDigit(c: Char): Boolean = c in 'a'..'f' || c in 'A'..'F'

/** Scan forward to the end of an identifier starting at [start]. */
private fun findIdentifierEnd(line: String, start: Int): Int {
    var i = start
    if (line[i] == '@') i++
    val n = line.length
    while (i < n && isIdentifierPartChar(line[i])) i++
    return i
}

private fun isIdentifierPartChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '$'
