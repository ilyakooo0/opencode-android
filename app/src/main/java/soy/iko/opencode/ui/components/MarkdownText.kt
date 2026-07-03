package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
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
    @Suppress("UNUSED_PARAMETER") style: TextStyle = MaterialTheme.typography.bodyLarge,
    streaming: Boolean = false,
) {
    val scale = LocalChatTextScale.current
    if (scale == 1f) {
        MarkdownBody(markdown, modifier, streaming)
    } else {
        // Scale the entire markdown subtree (headings, lists, code, body) from the user's
        // chat text-size preference by nesting a MaterialTheme with a scaled typography:
        // the renderer derives its typography from MaterialTheme, so this reaches every
        // element without plumbing a scale through the library. colorScheme/shapes default
        // to the current theme's, so only sizes change. Memoized so a recomposition that
        // doesn't change the scale doesn't rebuild the 15-role Typography.
        val base = MaterialTheme.typography
        val scaled = remember(scale, base) { base.scaledBy(scale) }
        MaterialTheme(typography = scaled) {
            MarkdownBody(markdown, modifier, streaming)
        }
    }
}

@Composable
private fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val components = remember {
        markdownComponents(
            codeFence = { CodeWithCopy(it) },
            codeBlock = { CodeWithCopy(it) },
        )
    }
    if (streaming) {
        // Bridge the markdown parameter into snapshot state so snapshotFlow can observe it.
        val markdownState = rememberUpdatedState(markdown)
        var renderedContent by remember { mutableStateOf(markdown) }
        // A SINGLE long-lived effect (keyed on Unit), per this file's design. Keying on the
        // content — even a prefix like markdown.take(32) — cancels and restarts the effect as the
        // first characters stream in (the prefix changes on nearly every early token), so the
        // in-flight delay never completes and the throttle is defeated at the start of every
        // response. A streaming MarkdownBody instance only ever grows its content (a different
        // message is a fresh composition with its own remember), so no reset key is needed.
        // conflate() coalesces a burst of token updates into a single emission so the collector
        // sees only the latest value after the previous delay completes. Plain collect (not
        // collectLatest) is critical: collectLatest would cancel the delay on every new value,
        // reintroducing the same starvation a keyed effect has.
        LaunchedEffect(Unit) {
            snapshotFlow { markdownState.value }
                .conflate()
                .collect { md ->
                    // Only throttle when the content is growing incrementally (streaming): a
                    // shorter switch or initial render proceeds immediately. Checking length is
                    // O(1) vs. startsWith which is O(n) in the rendered content length — during a
                    // long streaming response this runs every throttle cycle. Within one streaming
                    // composition, growing length means appending (streaming), not a rewrite.
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
 * Renders a fenced or indented code block with a header toolbar: the fence's language
 * label (when present), a wrap toggle (soft-wrap long lines vs. horizontal scroll), and a
 * copy button. Self-contained so it doesn't depend on the library's internal code-block
 * composables. The wrap toggle starts from the user's [LocalCodeWrap] preference but can
 * be flipped per-block.
 */
@Composable
private fun CodeWithCopy(model: MarkdownComponentModel) {
    val context = LocalContext.current
    // Key on the content string + the AST node's offset range (stable ints) instead of
    // the model itself. MarkdownComponentModel is not @Immutable/@Stable (it holds an
    // ASTNode), so remembering on it re-executes extract* on every recomposition.
    val node = model.node
    val isFenced = node.type == MarkdownElementTypes.CODE_FENCE
    val raw = remember(model.content, node.startOffset, node.endOffset) {
        node.getTextInNode(model.content).toString()
    }
    val code = remember(raw, isFenced) { extractCodeText(raw, isFenced) }
    val language = remember(raw, isFenced) { extractFenceLanguage(raw, isFenced) }
    val codeStyle = model.typography.code.copy(fontFamily = FontFamily.Monospace)
    // Default per-block wrap from the global preference; re-seed if the preference changes
    // while this block is composed (rememberSaveable would over-persist across blocks).
    val defaultWrap = LocalCodeWrap.current
    var wrap by remember(defaultWrap) { mutableStateOf(defaultWrap) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language ?: context.getString(R.string.code),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val wrapLabel = context.getString(if (wrap) R.string.code_no_wrap else R.string.code_wrap)
            IconButton(onClick = { wrap = !wrap }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.WrapText,
                    contentDescription = wrapLabel,
                    modifier = Modifier.size(16.dp),
                    tint = if (wrap) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { copyToClipboard(context, "code", code) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = context.getString(R.string.copy),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Wrap → let the Text wrap naturally (no horizontal scroll). No-wrap → horizontal
        // scroll so long lines keep their formatting. Selectable so a portion can be copied.
        SelectionContainer {
            Text(
                text = code,
                style = codeStyle,
                modifier = if (wrap) {
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                } else {
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                },
            )
        }
    }
}

/**
 * Extract the fence language tag (e.g. "kotlin" from ```kotlin) so the code block can
 * label itself. Returns null for indented blocks or fences with no info string. Extracted
 * as a pure function for testability.
 */
internal fun extractFenceLanguage(raw: String, isFenced: Boolean): String? {
    if (!isFenced) return null
    val first = raw.lineSequence().firstOrNull()?.trim() ?: return null
    // Strip the whole opening fence run (CommonMark allows 3+ backticks or tildes, e.g.
    // ````kotlin), not a fixed 3 chars, before reading the info string.
    val info = first.trimStart('`', '~').trim()
    // A fence info string can carry more than the language (e.g. "```ts title=foo"); the
    // first whitespace-delimited token is the language. Split on ANY whitespace (CommonMark
    // ends the language at the first whitespace char), not just a space, so a tab-delimited
    // info string ("```ts\ttitle=foo") still yields "ts" rather than the whole run.
    return info.takeWhile { !it.isWhitespace() }.takeIf { it.isNotBlank() }
}

/**
 * Strip fence markers (```/~~~ and the language tag) from a raw code node text so
 * only the code content is returned. Extracted as a pure function for testability.
 *
 * @param raw the raw text of the code node (including fence markers for fenced blocks)
 * @param isFenced true for CODE_FENCE nodes, false for indented CODE_BLOCK nodes
 */
internal fun extractCodeText(raw: String, isFenced: Boolean): String {
    if (!isFenced) return raw.trimIndent()
    val body = raw.lines().drop(1).toMutableList()
    // Strip the closing fence. getTextInNode can include a trailing newline, making the true last
    // line "" — so locate the last NON-blank line and drop from there, rather than only checking
    // body.last() (which would miss the fence in that case and leak the closing ```/~~~ into the
    // displayed/copied code).
    val lastNonBlank = body.indexOfLast { it.isNotBlank() }
    // CommonMark allows the closing fence to be indented up to 3 spaces, and a fence nested inside
    // a list item keeps that indentation in the node's raw text — so trim leading whitespace before
    // the marker check. Without it, an indented closing ```/~~~ fails startsWith and leaks into the
    // displayed/copied code (the same leak a prior fix closed for the trailing-newline case).
    val closer = if (lastNonBlank >= 0) body[lastNonBlank].trimStart() else ""
    if (lastNonBlank >= 0 && (closer.startsWith("```") || closer.startsWith("~~~"))) {
        while (body.size > lastNonBlank) body.removeAt(body.lastIndex)
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
    lastToast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
}
