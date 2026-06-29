package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import soy.iko.opencode.R

/**
 * Renders markdown using [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
 * (commonmark-java under the hood). Keeps a local API so callers don't import the library directly.
 * Long-press copies the raw markdown to the system clipboard. Code blocks/fences get an
 * inline copy button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val context = LocalContext.current
    Markdown(
        content = markdown,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = markdown.take(500)
            role = Role.Button
        }.combinedClickable(
            onClick = {},
            onLongClick = { copyToClipboard(context, markdown) },
        ),
        components = markdownComponents(
            codeFence = { CodeWithCopy(it) },
            codeBlock = { CodeWithCopy(it) },
        ),
    )
}

/**
 * Renders a fenced or indented code block with a small copy button in the top-right corner.
 * Self-contained so it doesn't depend on the library's internal code-block composables.
 */
@Composable
private fun CodeWithCopy(model: MarkdownComponentModel) {
    val context = LocalContext.current
    val code = androidx.compose.runtime.remember(model) { extractCode(model.content, model.node) }
    val codeStyle = model.typography.code.copy(fontFamily = FontFamily.Monospace)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = code,
            style = codeStyle,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 40.dp, bottom = 12.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            IconButton(
                onClick = { copyToClipboard(context, "code", code) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = context.getString(R.string.copy),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Extract the raw code text from a markdown code node, stripping fence markers
 * (```/~~~ and the language tag) so only the code content is copied.
 */
private fun extractCode(content: String, node: ASTNode): String {
    val raw = node.getTextInNode(content).toString()
    val isOpenFence = node.type == MarkdownElementTypes.CODE_FENCE
    if (!isOpenFence) return raw.trimIndent()
    val lines = raw.lines()
    val body = lines.drop(1).toMutableList()
    if (body.isNotEmpty() && (body.last().startsWith("```") || body.last().startsWith("~~~"))) {
        body.removeAt(body.lastIndex)
    }
    return body.joinToString("\n").trimEnd()
}

/** Copy [text] to the system clipboard and show a confirmation toast. */
internal fun copyToClipboard(context: Context, label: String, text: String = label) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label.take(40), text))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}
