package soy.iko.opencode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R

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
    return rest.startsWith("a/") || rest.startsWith("b/") || rest.startsWith("/")
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

private const val COLLAPSED_DIFF_LINES = 200

/**
 * Renders a parsed unified diff. Implemented as a plain (non-lazy) [Column] so it is
 * safe to embed inside another vertically scrolling container (e.g. the chat message
 * list) — a nested `LazyColumn` would crash with an unbounded-height constraint.
 * Callers that want the view to scroll on its own (e.g. the file viewer) should pass a
 * [Modifier.verticalScroll] in [modifier].
 *
 * To avoid composing thousands of [Text] nodes at once (which can ANR/OOM on large
 * diffs), only the first [COLLAPSED_DIFF_LINES] lines are rendered unless the user
 * expands the view.
 */
@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier) {
    val lines = remember(diff) { parseDiff(diff) }
    val addColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val removeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    val addText = MaterialTheme.colorScheme.primary
    val removeText = MaterialTheme.colorScheme.error
    val context = LocalContext.current
    val hScrollState = rememberScrollState()
    var expanded by rememberSaveable(diff) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = { copyToClipboard(context, "diff", diff) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val visibleLines = if (lines.size <= COLLAPSED_DIFF_LINES || expanded) lines
            else lines.subList(0, COLLAPSED_DIFF_LINES)
        // One horizontalScroll on the container instead of one per row: each modifier
        // adds a layout node + clip + offset pass, so a 200-line collapsed diff was
        // paying for 200 of them. The shared scroll state still synchronizes all rows.
        Column(modifier = Modifier.horizontalScroll(hScrollState)) {
            visibleLines.forEach { line ->
                Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                    when (line) {
                        is DiffLine.Hunk -> Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        is DiffLine.FileHeader -> Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                        is DiffLine.Meta -> Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                        is DiffLine.Add -> DiffRow(line.text, "+", addColor, addText)
                        is DiffLine.Remove -> DiffRow(line.text, "-", removeColor, removeText)
                        is DiffLine.Context -> DiffRow(line.text, " ", Color.Transparent, MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (lines.size > COLLAPSED_DIFF_LINES && !expanded) {
            TextButton(
                onClick = { expanded = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Text(stringResource(R.string.show_more))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DiffRow(text: String, prefix: String, bg: Color, textColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.background(bg),
    ) {
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
        )
    }
}
