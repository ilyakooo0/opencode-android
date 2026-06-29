package soy.iko.opencode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** A single line of a parsed unified diff. */
sealed interface DiffLine {
    val text: String

    data class Hunk(override val text: String) : DiffLine          // @@ ... @@
    data class FileHeader(override val text: String) : DiffLine    // --- / +++
    data class Context(override val text: String) : DiffLine
    data class Add(override val text: String) : DiffLine
    data class Remove(override val text: String) : DiffLine
}

/** Parse a unified diff string into typed [DiffLine]s. */
fun parseDiff(diff: String): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    for (raw in diff.split("\n")) {
        when {
            raw.startsWith("@@") -> result.add(DiffLine.Hunk(raw))
            raw.startsWith("---") || raw.startsWith("+++") -> result.add(DiffLine.FileHeader(raw))
            raw.startsWith("+") -> result.add(DiffLine.Add(raw.removePrefix("+")))
            raw.startsWith("-") -> result.add(DiffLine.Remove(raw.removePrefix("-")))
            raw.startsWith(" ") -> result.add(DiffLine.Context(raw.removePrefix(" ")))
            raw.startsWith("diff ") || raw.startsWith("index ") -> result.add(DiffLine.FileHeader(raw))
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

@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier) {
    val lines = remember(diff) { parseDiff(diff) }
    val addColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val removeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    val addText = MaterialTheme.colorScheme.primary
    val removeText = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        for (line in lines) {
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
                is DiffLine.Add -> DiffRow(line.text, "+", addColor, addText)
                is DiffLine.Remove -> DiffRow(line.text, "-", removeColor, removeText)
                is DiffLine.Context -> DiffRow(line.text, " ", Color.Transparent, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DiffRow(text: String, prefix: String, bg: Color, textColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().background(bg),
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
