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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig

/**
 * Renders markdown using [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
 * (commonmark-java under the hood). Keeps a local API so callers don't import the library directly.
 * Long-press copies the raw markdown to the system clipboard. Code blocks/fences get an
 * inline copy button.
 *
 * During streaming, the full markdown is re-parsed on every token (the library re-parses
 * whenever the content string changes). To avoid O(n²) work during long responses, the
 * rendered content is throttled — the latest [markdown] is committed to the renderer at
 * most once per frame (~16ms), so a burst of tokens coalesces into a single re-parse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val context = LocalContext.current
    // Throttle the rendered content so a rapid burst of streaming tokens coalesces
    // into a single markdown re-parse per frame instead of one per token. The delay
    // is only applied when the content is growing incrementally (streaming) — a
    // content switch or initial render proceeds immediately.
    // Keyed to a content-prefix so switching to a different message resets
    // immediately instead of showing the old message for one frame.
    var renderedContent by remember(markdown.take(32)) { mutableStateOf(markdown) }
    LaunchedEffect(markdown) {
        if (renderedContent.isNotEmpty() &&
            markdown.startsWith(renderedContent) &&
            markdown != renderedContent
        ) {
            delay(NetworkConfig.streamingThrottleMs)
        }
        renderedContent = markdown
    }
    Markdown(
        content = renderedContent,
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { copyToClipboard(context, markdown) },
            role = Role.Button,
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
                modifier = Modifier.size(48.dp),
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
