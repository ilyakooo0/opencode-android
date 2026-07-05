@file:Suppress("TooManyFunctions")

package soy.iko.opencode.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import soy.iko.opencode.util.runCatchingCancellable

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
 *  common keywords overlap heavily; python, ruby, shell, json each have their own. */
internal enum class Language {
    C_FAMILY, // kotlin, java, js, ts, swift, rust, go, c, cpp, csharp, scala
    PYTHON,
    SHELL, // sh, bash, zsh
    MARKUP, // html, xml, svg
    RUBY,
    JSON,
    NONE,
}

private fun languageFor(filename: String): Language {
    val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return languageForToken(ext)
}

/**
 * Resolve a language from a markdown fence info string (e.g. "kotlin" from ```kotlin).
 * Accepts both full language names and file extensions so a fence tagged either way
 * (` ```py ` or ` ```python `) highlights. Lowercased by the caller via [languageForToken].
 */
internal fun languageForTag(tag: String): Language = languageForToken(tag.trim().lowercase())

private fun languageForToken(token: String): Language = when (token) {
    "kt", "kotlin", "kts", "ktm", "java", "js", "javascript", "mjs", "cjs",
    "ts", "typescript", "tsx", "jsx",
    "swift", "rs", "rust", "go", "golang", "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hh",
    "cs", "csharp", "c#", "scala", "groovy", "gradle", "dart",
    -> Language.C_FAMILY
    "py", "python", "pyw" -> Language.PYTHON
    "rb", "ruby" -> Language.RUBY
    "sh", "bash", "zsh", "fish", "shell", "shellscript", "console" -> Language.SHELL
    "html", "htm", "xml", "svg", "vue" -> Language.MARKUP
    "json", "json5", "jsonc" -> Language.JSON
    else -> Language.NONE
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

private val rubyKeywords = setOf(
    "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?",
    "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next",
    "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then",
    "true", "undef", "unless", "until", "when", "while", "yield", "__FILE__", "__LINE__",
)

private val jsonKeywords = setOf("true", "false", "null")

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
fun highlightLine(line: String, filename: String, palette: HighlightPalette): AnnotatedString =
    highlightLine(line, syntaxFor(filename), palette)

/**
 * A file's language classification, resolved once from its name. Resolving this per file
 * (instead of re-parsing the extension inside [highlightLine] for every line) keeps the
 * hot per-line highlight path free of the `substringAfterLast`/`lowercase` work.
 */
class FileSyntax internal constructor(
    internal val lang: Language,
    internal val keywords: Set<String>,
)

/** Resolve the [FileSyntax] for a filename. Cheap; call once per file and reuse. */
fun syntaxFor(filename: String): FileSyntax {
    val lang = languageFor(filename)
    return FileSyntax(lang, keywordsFor(lang))
}

/**
 * Resolve the [FileSyntax] from a markdown fence language tag (e.g. "kotlin", "py",
 * "json"). Used by the chat code-block renderer, which only has the fence's info string
 * (not a filename). Returns a [FileSyntax] with [Language.NONE] for unknown tags so the
 * caller can short-circuit to plain text.
 */
fun syntaxForLanguageTag(tag: String): FileSyntax {
    val lang = languageForTag(tag)
    return FileSyntax(lang, keywordsFor(lang))
}

/**
 * Highlight a whole (possibly multi-line) code snippet into a single [AnnotatedString].
 * Each line is highlighted independently via [highlightLine], because the per-line
 * scanner carries no state across lines (so multi-line block comments/triple-quoted
 * strings are not handled — an accepted trade-off documented on [highlightLine]).
 *
 * Used by the chat code-block renderer, which renders the whole block in one [Text].
 * The file viewer renders line-by-line instead and calls [highlightLine] directly.
 */
fun highlightCode(code: String, syntax: FileSyntax, palette: HighlightPalette): AnnotatedString {
    if (syntax.lang == Language.NONE) return AnnotatedString(code)
    val builder = AnnotatedString.Builder(code.length + 16)
    val firstNewline = code.indexOf('\n')
    // Fast path for single-line code (common for short inline-ish fences): skip the
    // split + per-line append loop and the leading-newline bookkeeping entirely.
    if (firstNewline == -1) {
        builder.append(highlightLine(code, syntax, palette))
        return builder.toAnnotatedString()
    }
    var start = 0
    var first = true
    while (start <= code.length) {
        val end = code.indexOf('\n', start)
        val line = if (end == -1) code.substring(start) else code.substring(start, end)
        if (!first) builder.append("\n")
        builder.append(highlightLine(line, syntax, palette))
        first = false
        if (end == -1) break
        start = end + 1
    }
    return builder.toAnnotatedString()
}

/** Highlight a single line using a pre-resolved [FileSyntax]. See [FileSyntax]. */
fun highlightLine(line: String, syntax: FileSyntax, palette: HighlightPalette): AnnotatedString {
    val lang = syntax.lang
    if (lang == Language.NONE) return AnnotatedString(line)
    val keywords = syntax.keywords
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

/**
 * State carried across lines while highlighting a multi-line file, so block comments
 * (C-family slash-star, Ruby =begin/=end) and triple-quoted strings (Python/Kotlin)
 * are colored correctly on every line they span, not just the opening line.
 *
 * The per-line [highlightLine] carries no state and is still the right call for chat
 * code fences (which render a whole snippet in one Text). The file viewer renders
 * line-by-line and should use the stateful [highlightLine] overload instead so a
 * C-family file with a 50-line block comment doesn't lose highlighting on lines 2..49.
 */
class BlockCommentState {
    /** True when the previous line ended inside an unclosed C-family block comment. */
    internal var inBlockComment: Boolean = false
    /** True when the previous line ended inside an unclosed triple-quoted string. */
    internal var inTripleString: Boolean = false
}

/** Highlight a single line, carrying [state] across calls so multi-line block comments and
 *  triple-quoted strings stay colored on every line they span. Mutates [state] in place.
 *  Use this from line-by-line renderers (the file viewer); use [highlightLine] / [highlightCode]
 *  for self-contained snippets (chat code fences) where cross-line state isn't worth tracking. */
fun highlightLine(line: String, syntax: FileSyntax, palette: HighlightPalette, state: BlockCommentState): AnnotatedString {
    val lang = syntax.lang
    if (lang == Language.NONE) return AnnotatedString(line)
    val keywords = syntax.keywords
    val builder = AnnotatedString.Builder(line.length + 8)
    val baseStyle = SpanStyle(color = palette.base, fontFamily = FontFamily.Monospace)
    val mono = FontFamily.Monospace
    val commentStyle = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic, fontFamily = mono)
    val stringStyle = SpanStyle(color = palette.string, fontFamily = mono)

    // If we're continuing inside an open block comment, scan for the closing `*/` first;
    // everything before it is comment-colored. If none exists on this line, the whole line
    // is comment-colored and we stay in the block.
    if (state.inBlockComment) {
        val closeIdx = line.indexOf("*/")
        if (closeIdx < 0) {
            builder.withStyle(commentStyle) { append(line) }
            return builder.toAnnotatedString()
        }
        val end = closeIdx + 2
        builder.withStyle(commentStyle) { append(line.substring(0, end)) }
        state.inBlockComment = false
        // Continue highlighting the remainder of the line normally.
        var i = end
        val n = line.length
        while (i < n) {
            val c = line[i]
            // An opening `/*` inside the remainder re-enters the block.
            if (isCBlockCommentOpen(c, line, i, n, lang)) {
                val close = findBlockCommentEnd(line, i + 2)
                if (close < 0) {
                    builder.withStyle(commentStyle) { append(line.substring(i)) }
                    state.inBlockComment = true
                    return builder.toAnnotatedString()
                }
                builder.withStyle(commentStyle) { append(line.substring(i, close)) }
                i = close
                continue
            }
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

    // If we're continuing inside a triple-quoted string, scan for the closing `"""`.
    if (state.inTripleString) {
        val closeIdx = line.indexOf("\"\"\"")
        if (closeIdx < 0) {
            builder.withStyle(stringStyle) { append(line) }
            return builder.toAnnotatedString()
        }
        val end = closeIdx + 3
        builder.withStyle(stringStyle) { append(line.substring(0, end)) }
        state.inTripleString = false
        var i = end
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

    // Not currently in a block — run the normal per-line scanner, but intercept `/*` and `"""`
    // openings so we can flip state and color the rest of the line accordingly.
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        // Block comment open: C-family `/* ... */`. (Ruby's `=begin`/`=end` is handled below.)
        if (isCBlockCommentOpen(c, line, i, n, lang)) {
            val close = findBlockCommentEnd(line, i + 2)
            if (close < 0) {
                builder.withStyle(commentStyle) { append(line.substring(i)) }
                state.inBlockComment = true
                return builder.toAnnotatedString()
            }
            builder.withStyle(commentStyle) { append(line.substring(i, close)) }
            i = close
            continue
        }
        // Triple-quoted string open: Python/Kotlin `""" ... """`. Only track for languages
        // that actually have triple-quoted strings; in plain C/JS a `"` run is just adjacent
        // string literals.
        if (isTripleStringOpen(c, line, i, n, lang)) {
            val close = line.indexOf("\"\"\"", i + 3)
            if (close < 0) {
                builder.withStyle(stringStyle) { append(line.substring(i)) }
                state.inTripleString = true
                return builder.toAnnotatedString()
            }
            val end = close + 3
            builder.withStyle(stringStyle) { append(line.substring(i, end)) }
            i = end
            continue
        }
        // Ruby `=begin` … `=end` block comment (must be at the start of the line per Ruby
        // syntax; we check the leading-run here).
        if (isRubyBlockCommentOpen(c, line, i, lang)) {
            val close = line.indexOf("=end", i + 6)
            if (close < 0) {
                builder.withStyle(commentStyle) { append(line.substring(i)) }
                state.inBlockComment = true
                return builder.toAnnotatedString()
            }
            val end = close + 4
            builder.withStyle(commentStyle) { append(line.substring(i, end)) }
            i = end
            continue
        }
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

/** Index just past the closing block-comment delimiter for a block comment opened at [from],
 *  or -1 if the line ends before the block closes. */
private fun findBlockCommentEnd(line: String, from: Int): Int {
    val idx = line.indexOf("*/", from)
    return if (idx < 0) -1 else idx + 2
}

/** True when [line] at [i] opens a C-family block comment (`/` followed by `*`). */
private fun isCBlockCommentOpen(c: Char, line: String, i: Int, n: Int, lang: Language): Boolean =
    c == '/' && i + 1 < n && line[i + 1] == '*' && lang == Language.C_FAMILY

/** True when [line] at [i] opens a triple-quoted string (three double-quotes), for languages
 *  that support it (Python, Kotlin/C-family). */
private fun isTripleStringOpen(c: Char, line: String, i: Int, n: Int, lang: Language): Boolean =
    c == '"' && i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"' &&
        (lang == Language.PYTHON || lang == Language.C_FAMILY)

/** True when [line] at [i] opens a Ruby `=begin` block comment. Must be at the start of the
 *  line or preceded by whitespace per Ruby syntax. */
private fun isRubyBlockCommentOpen(c: Char, line: String, i: Int, lang: Language): Boolean =
    lang == Language.RUBY && c == '=' && line.startsWith("=begin", i) &&
        (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')

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
    Language.RUBY -> rubyKeywords
    Language.JSON -> jsonKeywords
    Language.MARKUP -> emptySet()
    Language.NONE -> emptySet()
}

private fun isLineCommentStart(c: Char, line: String, i: Int, lang: Language): Boolean =
    // `//` is a line comment only in C-family languages. Elsewhere it isn't: e.g. Python's
    // `a // b` is floor division, which must not be dimmed as a comment to end-of-line.
    (c == '/' && lang == Language.C_FAMILY && i + 1 < line.length && line[i + 1] == '/') ||
        // `#` is a line comment in Python, shell and Ruby. It is NOT one in C-family
        // (`#include`, `#define`, C# `#region`, JS/TS `this.#field`) or markup.
        (c == '#' && (lang == Language.PYTHON || lang == Language.SHELL || lang == Language.RUBY))

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

// --- Off-main highlighting with a cross-message LRU cache ---

/**
 * A small bounded LRU cache for highlighted [AnnotatedString]s, keyed by
 * `(language tag, code, palette identity hash)`. Shared across all code blocks in the app
 * (chat fences, file viewer), so two identical short fences don't re-tokenize, and a
 * scrolling viewer reusing the same lines doesn't re-highlight.
 *
 * The palette is hashed (not held by reference equality) because [HighlightPalette] is a
 * data class — its `equals`/`hashCode` cover all theme colors, so a palette instance is
 * cache-equivalent across recompositions that resolve the same color scheme. Hashing it
 * into the key string keeps the cache a flat `LinkedHashMap` with no nested map overhead.
 *
 * Bounded to [HIGHLIGHT_CACHE_MAX] entries; access-ordered so the least-recently-used
 * entry is evicted. Synchronized because the cache is touched from both the main thread
 * (cache lookup before the off-main fallback) and the `Dispatchers.Default` workers that
 * populate it. In practice contention is low (a lookup is a hash hit; a populate happens
 * once per unique block).
 */
private const val HIGHLIGHT_CACHE_MAX = 128
private val highlightCache = object : LinkedHashMap<String, AnnotatedString>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean =
        size > HIGHLIGHT_CACHE_MAX
}

private fun highlightCacheKey(code: String, syntax: FileSyntax?, palette: HighlightPalette): String =
    // Palette hashCode covers all 7 colors (data class auto-generated hashCode). The syntax
    // language enum + keyword set reference is stable for a given language tag (the sets are
    // module-level singletons), so its identity hashCode is a stable cache bucket.
    "${syntax?.lang ?: "none"}:${syntax?.keywords?.hashCode() ?: 0}:${palette.hashCode()}:${code.hashCode()}::${code.length}"

/**
 * Look up a cached highlight for `(code, syntax, palette)`, computing it on the calling
 * thread (synchronously) on miss. Used by the off-main worker to populate the cache and
 * by [rememberHighlightedCode] for a synchronous first-frame fallback.
 */
private fun highlightCached(code: String, syntax: FileSyntax?, palette: HighlightPalette): AnnotatedString {
    val key = highlightCacheKey(code, syntax, palette)
    synchronized(highlightCache) { highlightCache[key] }?.let { return it }
    val result = if (syntax != null) highlightCode(code, syntax, palette) else AnnotatedString(code)
    synchronized(highlightCache) { highlightCache[key] = result }
    return result
}

/**
 * Resolve syntax highlighting for [code] off the main thread, falling back to a cached or
 * synchronous result for the first frame so the block doesn't flash plain-text before the
 * highlighted version is ready.
 *
 * - On first composition (or when `code`/`syntax`/`palette` change), the synchronous
 *   [highlightCached] runs on the main thread to produce an immediate result. For a short
 *   fence this is fast (sub-millisecond); for a long fence the cache usually already has
 *   the result from a prior composition of the same block (e.g. scrolled away and back).
 * - A `LaunchedEffect` then re-computes on `Dispatchers.Default` and swaps in the result.
 *   If the synchronous result already matched (cache hit), the off-main pass is a no-op
 *   swap of an equal AnnotatedString — cheap.
 *
 * This replaces a direct `remember(code, syntax, palette) { highlightCode(...) }` which
 * ran the full tokenizer on the main thread on every cache miss — during streaming, a
 * growing code fence missed on every ~50ms throttle commit, re-tokenizing the whole
 * growing block each time (O(n²) over the stream). The off-main pass keeps the main
 * thread free for layout/draw, and the cross-message cache means a re-displayed block
 * (scroll recycle, theme switch) is a hash hit.
 *
 * For the streaming case, callers should still throttle how often `code` updates (the
 * markdown renderer's 50ms throttle does this); this function then bounds the per-update
 * main-thread cost to a cache lookup.
 */
@Composable
fun rememberHighlightedCode(code: String, syntax: FileSyntax?, palette: HighlightPalette): AnnotatedString {
    // Synchronous first-frame result (cache hit or compute). For a long block this can
    // take a few ms on the main thread the first time it's seen; subsequent compositions
    // of the same block (scroll recycle) are a pure cache hit.
    val initial = remember(code, syntax, palette) { highlightCached(code, syntax, palette) }
    var result by remember(code, syntax, palette) { mutableStateOf(initial) }
    // Recompute off-main; swaps in the (possibly identical) result when ready. Skipped
    // work: if the cache already held the exact entry, the off-main pass recomputes and
    // assigns an equal string — a cheap no-op for Compose (no recomposition since the
    // State value is structurally equal). The off-main pass still populates the cache for
    // future callers. For a cache hit on a very long block, we could skip the LaunchedEffect
    // entirely, but the cost of the off-main recompute is exactly what we want to keep off
    // the main thread, and the cache makes the main-thread lookup a hit next time.
    LaunchedEffect(code, syntax, palette) {
        val offMain = withContext(Dispatchers.Default) {
            runCatchingCancellable { highlightCached(code, syntax, palette) }.getOrDefault(initial)
        }
        result = offMain
    }
    return result
}
