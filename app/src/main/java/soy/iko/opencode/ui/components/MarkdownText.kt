package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.conflate
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
 * Code blocks/fences get an inline copy button.
 *
 * The rendered text is wrapped in a [SelectionContainer] so the user can select and
 * copy a portion of the response (e.g. a single code snippet or paragraph) instead of
 * the all-or-nothing long-press copy. The per-message copy button in [MessageBubble]
 * copies all TextParts; this complements it with partial selection.
 *
 * During streaming, the full markdown is re-parsed on every token (the library re-parses
 * whenever the content string changes). To avoid O(n²) work during long responses, the
 * rendered content is throttled — the latest [markdown] is committed to the renderer at
 * most once every ~50ms, so a burst of tokens coalesces into a single re-parse.
 *
 * The throttle uses a single long-lived `LaunchedEffect(Unit)` that observes the markdown
 * parameter via `rememberUpdatedState` + `snapshotFlow` + `conflate` + `collect`. A keyed
 * effect (`LaunchedEffect(markdown)`) cancels and restarts on every token; if tokens arrive
 * faster than the throttle delay, the in-flight `delay` is cancelled before it completes,
 * starving the render and showing stale content until the stream pauses. The conflated
 * `collect` lets the delay run to completion, then picks up the most recent buffered value,
 * so rendering always progresses even under continuous fast streaming.
 *
 * When [streaming] is false (the common case: every non-active message in the chat list),
 * the throttle pipeline is skipped entirely — no `LaunchedEffect`, no `snapshotFlow`, no
 * coroutine. This eliminates per-item coroutine churn as messages scroll in and out of view.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    val components = remember {
        markdownComponents(
            codeFence = { CodeWithCopy(it) },
            codeBlock = { CodeWithCopy(it) },
        )
    }
    if (streaming) {
        // Bridge the markdown parameter into snapshot state so snapshotFlow can observe it.
        val markdownState = rememberUpdatedState(markdown)
        // Keyed to a content-prefix so switching to a different message resets
        // immediately instead of showing the old message for one frame.
        val prefix = markdown.take(32)
        var renderedContent by remember(prefix) { mutableStateOf(markdown) }
        // conflate() coalesces a burst of token updates into a single emission so the
        // collector sees only the latest value after the previous delay completes. Plain
        // collect (not collectLatest) is critical: collectLatest would cancel the delay
        // on every new value, reintroducing the same starvation the keyed effect had.
        LaunchedEffect(prefix) {
            snapshotFlow { markdownState.value }
                .conflate()
                .collect { md ->
                    // Only throttle when the content is growing incrementally (streaming):
                    // a content switch or initial render proceeds immediately. Checking
                    // length is O(1) vs. startsWith which is O(n) in the rendered content
                    // length — during a long streaming response this runs every throttle
                    // cycle. The keyed LaunchedEffect(markdown.take(32)) already handles
                    // content switches; within one keyed cycle, growing length means
                    // appending (streaming), not a rewrite.
                    if (renderedContent.isNotEmpty() &&
                        md.length > renderedContent.length
                    ) {
                        delay(NetworkConfig.streamingThrottleMs)
                    }
                    renderedContent = md
                }
        }
        // SelectionContainer is intentionally omitted during streaming: the content is
        // changing every ~50ms, and an active selection would be invalidated (and the
        // selection handles would flicker) on each throttle commit. Partial selection
        // becomes available once the stream finishes; the per-message Copy button in
        // MessageBubble copies all TextParts at any time.
        Markdown(
            content = renderedContent,
            modifier = modifier,
            components = components,
        )
    } else {
        SelectionContainer {
            Markdown(
                content = markdown,
                modifier = modifier,
                components = components,
            )
        }
    }
}

/**
 * Renders a fenced or indented code block with a small copy button in the top-right corner.
 * Self-contained so it doesn't depend on the library's internal code-block composables.
 */
@Composable
private fun CodeWithCopy(model: MarkdownComponentModel) {
    val context = LocalContext.current
    // Key on the content string + the AST node's offset range (stable ints) instead of
    // the model itself. MarkdownComponentModel is not @Immutable/@Stable (it holds an
    // ASTNode), so remembering on it re-executes extractCode on every recomposition.
    val node = model.node
    val code = remember(model.content, node.startOffset, node.endOffset) {
        extractCode(model.content, node)
    }
    val codeStyle = model.typography.code.copy(fontFamily = FontFamily.Monospace)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
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
            shape = MaterialTheme.shapes.extraSmall,
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
private fun extractCode(content: String, node: ASTNode): String =
    extractCodeText(node.getTextInNode(content).toString(), node.type == MarkdownElementTypes.CODE_FENCE)

/**
 * Strip fence markers (```/~~~ and the language tag) from a raw code node text so
 * only the code content is returned. Extracted as a pure function for testability.
 *
 * @param raw the raw text of the code node (including fence markers for fenced blocks)
 * @param isFenced true for CODE_FENCE nodes, false for indented CODE_BLOCK nodes
 */
internal fun extractCodeText(raw: String, isFenced: Boolean): String {
    if (!isFenced) return raw.trimIndent()
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
    showToast(context, context.getString(R.string.copied))
}

private var lastToast: Toast? = null

internal fun showToast(context: Context, message: String) {
    lastToast?.cancel()
    lastToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
}
