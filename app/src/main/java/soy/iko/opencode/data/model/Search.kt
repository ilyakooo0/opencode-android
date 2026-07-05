package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One ripgrep match from `GET /find?pattern=` (content search). [path] and [lines] are
 * wrapped `{ text }` objects on the wire; [submatches] carry the column offsets of each
 * hit within the line so the UI can highlight them.
 */
@Immutable
@Serializable
data class FindMatch(
    val path: TextField = TextField(),
    val lines: TextField = TextField(),
    @SerialName("line_number") val lineNumber: Int = 0,
    @SerialName("absolute_offset") val absoluteOffset: Long = 0,
    val submatches: List<SubMatch> = emptyList(),
) {
    @Immutable
    @Serializable
    data class TextField(val text: String = "")

    @Immutable
    @Serializable
    data class SubMatch(
        val start: Int = 0,
        val end: Int = 0,
    )

    /** The matched file's path (relative to the search root). */
    val filePath: String get() = path.text

    /** The matching line's text, trimmed of a trailing newline for display. */
    val lineText: String get() = lines.text.trimEnd('\n', '\r')
}

/**
 * One workspace symbol from `GET /find/symbol?query=`. [kind] is the LSP `SymbolKind`
 * integer; [location.uri] is a file URI and [location.range] the symbol's span.
 */
@Immutable
@Serializable
data class SymbolResult(
    val name: String = "",
    val kind: Int = 0,
    val location: Location = Location(),
) {
    @Immutable
    @Serializable
    data class Location(
        val uri: String = "",
        val range: Range = Range(),
    )

    @Immutable
    @Serializable
    data class Range(
        val start: Pos = Pos(),
        val end: Pos = Pos(),
    )

    @Immutable
    @Serializable
    data class Pos(
        val line: Int = 0,
        val character: Int = 0,
    )

    /** The file path portion of [Location.uri], stripping a `file://` scheme when present.
     *  Handles both empty-authority (`file:///abs/path`) and non-empty-authority
     *  (`file://host/abs/path`) URIs by parsing the URI and taking its path component,
     *  rather than a prefix strip that would leave the authority glued to the path. */
    val filePath: String
        get() {
            val uri = location.uri
            // Only parse file: URIs; for anything else, return as-is.
            if (!uri.startsWith("file:")) return uri
            return runCatching { java.net.URI(uri).path }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: uri.removePrefix("file://").ifBlank { uri }
        }

    /** 1-based line for display; the wire range is 0-based. */
    val displayLine: Int get() = location.range.start.line + 1
}

// LSP SymbolKind → label. A map lookup (rather than a large `when`) keeps the accessor
// trivial. Indices follow the LSP SymbolKind enum.
private val symbolKindLabels = mapOf(
    1 to "file", 2 to "module", 3 to "namespace", 4 to "package", 5 to "class",
    6 to "method", 7 to "property", 8 to "field", 9 to "constructor", 10 to "enum",
    11 to "interface", 12 to "function", 13 to "variable", 14 to "constant", 15 to "string",
    16 to "number", 17 to "boolean", 18 to "array", 19 to "object", 20 to "key",
    22 to "enum member", 23 to "struct", 26 to "type",
)

/** Human-readable label for an LSP [SymbolResult.kind]. */
fun symbolKindLabel(kind: Int): String = symbolKindLabels[kind] ?: "symbol"
