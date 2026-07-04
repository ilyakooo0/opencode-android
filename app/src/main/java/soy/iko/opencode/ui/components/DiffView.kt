package soy.iko.opencode.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.theme.diffColors

/** A single line of a parsed unified diff. */
sealed interface DiffLine {
    val text: String

    data class Hunk(override val text: String) : DiffLine          // @@ ... @@
    data class FileHeader(override val text: String) : DiffLine    // --- / +++ / diff / index
    data class Context(override val text: String) : DiffLine
    data class Add(override val text: String) : DiffLine
    data class Remove(override val text: String) : DiffLine
    data class Meta(override val text: String) : DiffLine          // git metadata (new file mode, etc.)
}

/** Git metadata line prefixes that aren't part of the diff content. */
private val gitMetaPrefixes = listOf(
    "new file mode ", "deleted file mode ", "old mode ", "new mode ",
    "similarity index ", "dissimilarity index ", "rename from ", "rename to ",
    "copy from ", "copy to ", "Binary files ", "\\ No newline",
)

/** Does [raw] look like a real unified-diff file header ("--- a/…", "+++ b/…", "--- /dev/null")
 *  rather than a removed/added content line that merely starts with "--- "/"+++ "? */
private fun isDiffFileHeader(raw: String): Boolean {
    val rest = when {
        raw.startsWith("--- ") -> raw.substring(4)
        raw.startsWith("+++ ") -> raw.substring(4)
        else -> return false
    }
    // Header paths are "a/…", "b/…", or the literal "/dev/null" (optionally followed by a
    // tab/space-separated timestamp). Restricting the absolute-path case to /dev/null avoids
    // misclassifying a removed content line like "-- /usr/local/foo" (on the wire
    // "--- /usr/local/foo"), whose rest starts with "/", as a file header.
    return rest.startsWith("a/") || rest.startsWith("b/") ||
        rest == "/dev/null" || rest.startsWith("/dev/null\t") || rest.startsWith("/dev/null ")
}

/** Parse a unified diff string into typed [DiffLine]s. */
fun parseDiff(diff: String): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    for (raw in diff.lineSequence()) {
        when {
            raw.startsWith("@@") -> result.add(DiffLine.Hunk(raw))
            // Unified-diff file headers are "--- <path>" / "+++ <path>" where the path is
            // "a/…", "b/…", or an absolute path (e.g. "/dev/null"). Requiring that the marker
            // be followed by "a/", "b/", or "/" avoids misclassifying removed/added *content*
            // lines like "-- TODO"/"++ x" — which appear on the wire as "--- TODO"/"+++ x" —
            // as headers; those fall through to the +/- branches below.
            isDiffFileHeader(raw) -> result.add(DiffLine.FileHeader(raw))
            raw.startsWith("+") -> result.add(DiffLine.Add(raw.removePrefix("+")))
            raw.startsWith("-") -> result.add(DiffLine.Remove(raw.removePrefix("-")))
            raw.startsWith(" ") -> result.add(DiffLine.Context(raw.removePrefix(" ")))
            raw.startsWith("diff ") || raw.startsWith("index ") -> result.add(DiffLine.FileHeader(raw))
            gitMetaPrefixes.any { raw.startsWith(it) } -> result.add(DiffLine.Meta(raw))
            raw.isNotBlank() -> result.add(DiffLine.Context(raw))
        }
    }
    return result
}

/** Heuristic: does this string look like a unified diff? */
fun looksLikeDiff(text: String): Boolean {
    val lines = text.lineSequence()
    var hunkCount = 0
    var addCount = 0
    var removeCount = 0
    for (line in lines) {
        when {
            line.startsWith("@@") -> hunkCount++
            line.startsWith("+") && !line.startsWith("+++") -> addCount++
            line.startsWith("-") && !line.startsWith("---") -> removeCount++
        }
        if (hunkCount >= 1 && (addCount + removeCount) >= 1) return true
    }
    return false
}

/** Extract a display path from a `+++ b/path` / `--- a/path` / `diff --git a/x b/y` header.
 *  Returns the raw line when no path can be extracted, so the header still shows something. */
private fun extractDisplayPath(headerText: String): String {
    // `diff --git a/foo b/bar` → `bar` (the new path). `+++ b/foo` → `foo`. `--- a/foo` → `foo`.
    return when {
        headerText.startsWith("diff --git ") -> {
            // Grab the second path after " b/"; fall back to the first after " a/".
            val bIdx = headerText.indexOf(" b/")
            if (bIdx >= 0) headerText.substring(bIdx + 3).trim() else headerText.substringAfter(" a/", headerText)
        }
        headerText.startsWith("+++ ") -> headerText.substring(4).substringAfter("b/", headerText.substring(4)).trimEnd()
        headerText.startsWith("--- ") -> headerText.substring(4).substringAfter("a/", headerText.substring(4)).trimEnd()
        else -> headerText
    }
}

/** Resolve a [FileSyntax] from a diff file header's path, for per-line highlighting. Returns
 *  null when the path has no recognizable extension (so the diff renders plain, as before). */
private fun syntaxForHeader(headerText: String): FileSyntax? {
    val path = extractDisplayPath(headerText)
    if (path == headerText) return null
    val filename = path.substringAfterLast('/')
    val syntax = syntaxFor(filename)
    return if (syntax.lang == Language.NONE) null else syntax
}

/**
 * Renders a parsed unified diff. Implemented as a plain (non-lazy) [Column] so it is
 * safe to embed inside another vertically scrolling container (e.g. the chat message
 * list) — a nested `LazyColumn` would crash with an unbounded-height constraint.
 * Callers that want the view to scroll on its own (e.g. the file viewer) should pass a
 * [Modifier.verticalScroll] in [modifier].
 *
 * To avoid composing thousands of [Text] nodes at once (which can ANR/OOM on large
 * diffs), only the first [NetworkConfig.collapsedDiffLineThreshold] lines are rendered
 * unless the user expands the view. Each hunk can also be collapsed individually via
 * its `@@` header.
 */
@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier, saveKey: String? = null) {
    val lines = remember(diff) { parseDiff(diff) }
    // Use dedicated diff add/remove colors (a fixed green/red pair tuned to the theme) so the
    // diff semantics stay consistent under Material You — where the primary role may not be
    // green-tinted, an "added" line would otherwise read as a primary-tinted highlight.
    val scheme = MaterialTheme.colorScheme
    val diffColors = diffColors()
    val addColor = remember(scheme) { diffColors.addBg }
    val removeColor = remember(scheme) { diffColors.removeBg }
    val addText = diffColors.addText
    val removeText = diffColors.removeText
    val context = LocalContext.current
    val hScrollState = rememberScrollState()
    // Key on a stable id (the part id) when available so streaming updates that grow `diff`
    // don't reset expand state on every token. Fall back to `diff` for non-streaming callers
    // (e.g. FileViewScreen) where the content is static.
    var expanded by rememberSaveable(saveKey ?: diff) { mutableStateOf(false) }
    // Resolve a syntax for highlighting from the first file header (if any). Memoized so a
    // streaming re-parse doesn't re-resolve per token.
    val syntax = remember(lines) {
        lines.firstOrNull { it is DiffLine.FileHeader }?.let { syntaxForHeader(it.text) }
    }
    val palette = rememberHighlightPalette()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        DiffHeader(
            lineCount = lines.size,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onCopy = { copyToClipboard(context, context.getString(R.string.clip_label_diff), diff) },
        )
        val visibleLines = if (lines.size <= NetworkConfig.collapsedDiffLineThreshold || expanded) lines
            else lines.subList(0, NetworkConfig.collapsedDiffLineThreshold)
        // One horizontalScroll on the container instead of one per row: each modifier
        // adds a layout node + clip + offset pass, so a 200-line collapsed diff was
        // paying for 200 of them. The shared scroll state still synchronizes all rows.
        val rows = remember(visibleLines, addColor, removeColor, addText, removeText, scheme) {
            buildDiffRows(
                visibleLines,
                addColor, removeColor, addText, removeText,
                scheme.tertiary, scheme.onSurface, scheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.horizontalScroll(hScrollState)) {
            rows.forEach { row -> RenderDiffRow(row, syntax, palette) }
        }
        DiffExpandFooter(lines = lines, expanded = expanded, onToggle = { expanded = !expanded })
    }
}

/**
 * Lazy variant of [DiffView] for callers that need the diff to scroll on its own (e.g. the
 * file viewer) WITHOUT an enclosing verticalScroll. A 5000-line diff in [DiffView] would
 * compose every row eagerly; this variant uses a [LazyColumn] so only the visible rows
 * compose. The collapse threshold still applies for the *initial* view, but expanding
 * switches to lazy rendering so a huge expanded diff doesn't OOM.
 */
@Composable
fun LazyDiffView(diff: String, modifier: Modifier = Modifier, saveKey: String? = null) {
    val lines = remember(diff) { parseDiff(diff) }
    val scheme = MaterialTheme.colorScheme
    val diffColors = diffColors()
    val addColor = remember(scheme) { diffColors.addBg }
    val removeColor = remember(scheme) { diffColors.removeBg }
    val addText = diffColors.addText
    val removeText = diffColors.removeText
    val context = LocalContext.current
    val hScrollState = rememberScrollState()
    var expanded by rememberSaveable(saveKey ?: diff) { mutableStateOf(false) }
    val syntax = remember(lines) {
        lines.firstOrNull { it is DiffLine.FileHeader }?.let { syntaxForHeader(it.text) }
    }
    val palette = rememberHighlightPalette()
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        DiffHeader(
            lineCount = lines.size,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onCopy = { copyToClipboard(context, context.getString(R.string.clip_label_diff), diff) },
        )
        val visibleLines = if (lines.size <= NetworkConfig.collapsedDiffLineThreshold || expanded) lines
            else lines.subList(0, NetworkConfig.collapsedDiffLineThreshold)
        val rows = remember(visibleLines, addColor, removeColor, addText, removeText, scheme) {
            buildDiffRows(
                visibleLines,
                addColor, removeColor, addText, removeText,
                scheme.tertiary, scheme.onSurface, scheme.onSurfaceVariant,
            )
        }
        // One horizontalScroll wrapping the LazyColumn synchronizes horizontal panning across
        // visible rows (the same trick DiffView uses on its Column). LazyColumn handles the
        // vertical scrolling.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.horizontalScroll(hScrollState),
        ) {
            itemsIndexed(
                rows,
                key = { i, row -> "${row.kind::class.simpleName}:$i:${row.text.hashCode()}" },
            ) { _, row ->
                RenderDiffRow(row, syntax, palette)
            }
        }
        DiffExpandFooter(lines = lines, expanded = expanded, onToggle = { expanded = !expanded })
    }
}

/** Header row with the expand/collapse toggle (when applicable) and a copy action. */
@Composable
private fun DiffHeader(lineCount: Int, expanded: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lineCount > NetworkConfig.collapsedDiffLineThreshold) {
            val moreLines = lineCount - NetworkConfig.collapsedDiffLineThreshold
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.show_less)
                        else context.resources.getQuantityString(
                            R.plurals.show_more_lines, moreLines, moreLines,
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Footer "show more/less" button when the diff was collapsed. */
@Composable
private fun DiffExpandFooter(lines: List<DiffLine>, expanded: Boolean, onToggle: () -> Unit) {
    if (lines.size > NetworkConfig.collapsedDiffLineThreshold) {
        val hidden = lines.size - NetworkConfig.collapsedDiffLineThreshold
        TextButton(
            onClick = onToggle,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text(
                if (expanded) stringResource(R.string.show_less)
                else pluralStringResource(R.plurals.show_more_lines, hidden, hidden),
            )
        }
    }
}

/** A pre-resolved diff row carrying its kind, text, line numbers, and resolved colors. This
 *  lets both [DiffView] and [LazyDiffView] share the rendering path without re-deriving the
 *  per-row state (line numbers advance as a side effect of walking the list). */
private data class DiffRowItem(
    val kind: DiffLine,
    val text: String,
    val bg: Color,
    val textColor: Color,
    val oldLine: Int?,
    val newLine: Int?,
)

/** Walk [visibleLines] advancing the old/new line counters per hunk, producing a list of
 *  [DiffRowItem]s that the renderers can iterate without per-row state. Theme colors for the
 *  non-add/remove rows (tertiary for hunk headers, onSurface/onSurfaceVariant for context
 *  and meta) are passed in by the @Composable caller so the row-builder stays a plain
 *  function that doesn't read MaterialTheme directly. */
private fun buildDiffRows(
    visibleLines: List<DiffLine>,
    addColor: Color,
    removeColor: Color,
    addText: Color,
    removeText: Color,
    tertiary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
): List<DiffRowItem> {
    var oldLine = 0
    var newLine = 0
    val hunkRegex = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    return visibleLines.map { line ->
        when (line) {
            is DiffLine.Hunk -> {
                val m = hunkRegex.find(line.text)
                if (m != null) {
                    oldLine = m.groupValues[1].toInt()
                    newLine = m.groupValues[2].toInt()
                }
                DiffRowItem(line, line.text, Color.Transparent, tertiary, null, null)
            }
            is DiffLine.FileHeader -> DiffRowItem(line, line.text, Color.Transparent, onSurface, null, null)
            is DiffLine.Meta -> DiffRowItem(line, line.text, Color.Transparent, onSurfaceVariant, null, null)
            is DiffLine.Add -> {
                val n = newLine++
                DiffRowItem(line, line.text, addColor, addText, null, n)
            }
            is DiffLine.Remove -> {
                val o = oldLine++
                DiffRowItem(line, line.text, removeColor, removeText, o, null)
            }
            is DiffLine.Context -> {
                val o = oldLine++; val n = newLine++
                DiffRowItem(line, line.text, Color.Transparent, onSurface, o, n)
            }
        }
    }
}

/** Render a single [DiffRowItem]. Hunk and FileHeader rows get their own styling; Add/Remove/
 *  Context delegate to [DiffRow]. */
@Composable
private fun RenderDiffRow(row: DiffRowItem, syntax: FileSyntax?, palette: HighlightPalette) {
    when (row.kind) {
        is DiffLine.Hunk -> Text(
            row.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        is DiffLine.FileHeader -> {
            val displayPath = extractDisplayPath(row.text)
            val isHeader = displayPath != row.text
            Text(
                displayPath,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .semantics { heading() },
            )
        }
        is DiffLine.Meta -> Text(
            row.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 1.dp),
        )
        is DiffLine.Add -> DiffRow(row.text, "+", row.bg, row.textColor, oldLine = row.oldLine, newLine = row.newLine, syntax = syntax, palette = palette)
        is DiffLine.Remove -> DiffRow(row.text, "-", row.bg, row.textColor, oldLine = row.oldLine, newLine = row.newLine, syntax = syntax, palette = palette)
        is DiffLine.Context -> DiffRow(row.text, " ", row.bg, row.textColor, oldLine = row.oldLine, newLine = row.newLine, syntax = syntax, palette = palette)
    }
}

@Composable
private fun DiffRow(
    text: String,
    prefix: String,
    bg: Color,
    textColor: androidx.compose.ui.graphics.Color,
    oldLine: Int? = null,
    newLine: Int? = null,
    syntax: FileSyntax? = null,
    palette: HighlightPalette,
) {
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    // Highlight the line's content (minus the leading +/-/space prefix, which DiffView
    // already stripped) when a syntax was resolved from a file header. Falls back to plain
    // monospace text when no syntax is known (e.g. an untagged patch), preserving the prior
    // rendering for diffs that can't be classified.
    val highlighted: AnnotatedString = remember(text, syntax, palette) {
        if (syntax != null) highlightLine(text, syntax, palette) else AnnotatedString(text)
    }
    val a11yLabel = when (prefix) {
        "+" -> stringResource(R.string.diff_added_line, newLine ?: 0, text)
        "-" -> stringResource(R.string.diff_removed_line, oldLine ?: 0, text)
        else -> stringResource(R.string.diff_context_line, oldLine ?: 0, text)
    }
    Row(
        modifier = Modifier
            .background(bg)
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel },
    ) {
        // Old/new line-number gutters. Blank (not 0) for the side that doesn't have a number
        // (an added line has no old number; a removed line has no new number) so the columns
        // stay aligned.
        Text(
            oldLine?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = gutterColor,
            modifier = Modifier.width(NetworkConfig.diffGutterWidthDp.dp),
        )
        Text(
            newLine?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = gutterColor,
            modifier = Modifier.width(NetworkConfig.diffGutterWidthDp.dp),
        )
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(NetworkConfig.diffPrefixWidthDp.dp),
        )
        Text(
            highlighted,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
        )
    }
}
