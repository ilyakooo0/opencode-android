package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import com.mikepenz.markdown.model.DefaultMarkdownAnnotatorConfig
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.network.NetworkConfig

/** String-annotation tag stamped onto each inline-code (`CODE_SPAN`) range by the inline-code
 *  annotator wrapper, so [MarkdownTextNoAnim]'s `pointerInput` can resolve a long-press
 *  offset back to the code text and copy it. Mirrors the library's own `MARKDOWN_TAG_URL` pattern
 *  for link-tap hit-testing. */
private const val MARKDOWN_TAG_INLINE_CODE = "MARKDOWN_INLINE_CODE"

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
    imageContext: ImageLoadContext? = null,
) {
    val scale = LocalChatTextScale.current
    if (scale == 1f) {
        MarkdownBody(markdown, modifier, streaming, imageContext)
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
            MarkdownBody(markdown, modifier, streaming, imageContext)
        }
    }
}

@Composable
private fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    imageContext: ImageLoadContext? = null,
) {
    // Provide a custom image component when an ImageLoadContext is available so markdown
    // image tags (![alt](url)) route through RemoteImage (Basic auth + same-origin guard).
    // Without this the library's default image loader fetches with no auth and fails on
    // server-relative URLs. When no context is available, fall back to the library default
    // by not overriding the image component.
    // The `paragraph` and `heading` components are overridden with [MarkdownTextNoAnim] so a long-press
    // on a single-backtick inline code span copies its text — matching the copy affordance the
    // fenced/indented code blocks already have via [CodeWithCopy].
    val components = if (imageContext != null) {
        // Remember on imageContext so a recomposition that doesn't change the context (the
        // common case during streaming/scroll) doesn't rebuild the 21-field components
        // container + 4 lambdas. The lambdas capture `imageContext` (for the image component)
        // and the CodeWithCopy/MarkdownTextNoAnim factories (which only capture the
        // stable MarkdownComponentModel passed by the library at invoke time), so keying on
        // imageContext is sufficient for stability.
        remember(imageContext) {
            markdownComponents(
                codeFence = { CodeWithCopy(it) },
                codeBlock = { CodeWithCopy(it) },
                paragraph = { MarkdownTextNoAnim(it, it.typography.paragraph) },
                image = { MarkdownImage(it, imageContext) },
                heading1 = { MarkdownTextNoAnim(it, it.typography.h1, Modifier.semantics { heading() }) },
                heading2 = { MarkdownTextNoAnim(it, it.typography.h2, Modifier.semantics { heading() }) },
                heading3 = { MarkdownTextNoAnim(it, it.typography.h3, Modifier.semantics { heading() }) },
                heading4 = { MarkdownTextNoAnim(it, it.typography.h4, Modifier.semantics { heading() }) },
                heading5 = { MarkdownTextNoAnim(it, it.typography.h5, Modifier.semantics { heading() }) },
                heading6 = { MarkdownTextNoAnim(it, it.typography.h6, Modifier.semantics { heading() }) },
                setextHeading1 = { MarkdownTextNoAnim(it, it.typography.h1, Modifier.semantics { heading() }) },
                setextHeading2 = { MarkdownTextNoAnim(it, it.typography.h2, Modifier.semantics { heading() }) },
            )
        }
    } else {
        remember {
            markdownComponents(
                codeFence = { CodeWithCopy(it) },
                codeBlock = { CodeWithCopy(it) },
                paragraph = { MarkdownTextNoAnim(it, it.typography.paragraph) },
                heading1 = { MarkdownTextNoAnim(it, it.typography.h1, Modifier.semantics { heading() }) },
                heading2 = { MarkdownTextNoAnim(it, it.typography.h2, Modifier.semantics { heading() }) },
                heading3 = { MarkdownTextNoAnim(it, it.typography.h3, Modifier.semantics { heading() }) },
                heading4 = { MarkdownTextNoAnim(it, it.typography.h4, Modifier.semantics { heading() }) },
                heading5 = { MarkdownTextNoAnim(it, it.typography.h5, Modifier.semantics { heading() }) },
                heading6 = { MarkdownTextNoAnim(it, it.typography.h6, Modifier.semantics { heading() }) },
                setextHeading1 = { MarkdownTextNoAnim(it, it.typography.h1, Modifier.semantics { heading() }) },
                setextHeading2 = { MarkdownTextNoAnim(it, it.typography.h2, Modifier.semantics { heading() }) },
            )
        }
    }
    val context = LocalContext.current
    // Intercept link taps so the scheme can be validated before the library fires an
    // implicit ACTION_VIEW. A hallucinated javascript:/file:/intent: URL must not launch
    // an unexpected intent chooser. Taps are routed to a dialog offering Open (only for
    // http/https/mailto) and Copy link. The default handler is captured here and invoked
    // from the dialog's Open action.
    val defaultUriHandler = LocalUriHandler.current
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val safeUriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                pendingLink = uri
            }
        }
    }
    // In-conversation search highlighting: when LocalSearchHighlight carries a query,
    // wrap the default MarkdownAnnotator to add a translucent background span over each
    // case-insensitive match in the rendered text. Skipped while streaming so the
    // throttled re-render isn't invalidated on every token. Offsets are derived from the
    // builder's actual rendered output (not the raw markdown), so highlights land on the
    // right characters even inside formatted spans.
    val searchQuery = LocalSearchHighlight.current?.takeIf { it.isNotBlank() }
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer
    val baseAnnotator = LocalMarkdownAnnotator.current
    // Capture the inline-code typography/colors in the composable scope (the annotator lambda is
    // NOT a @Composable scope, so it can't read LocalMarkdownTypography.current itself). The
    // library's buildMarkdownAnnotatedString reads these in its own @Composable body for the
    // default CODE_SPAN branch; since we intercept CODE_SPAN in the annotator, we capture them
    // here and close over them in appendInlineCodeSpan.
    val inlineCodeTypography = LocalMarkdownTypography.current
    val inlineCodeColors = LocalMarkdownColors.current
    // Inline-code tag annotator: stamps a MARKDOWN_TAG_INLINE_CODE string annotation onto each
    // CODE_SPAN range so [MarkdownTextNoAnim] can resolve a long-press offset to the
    // code text and copy it. The library's default CODE_SPAN branch in buildMarkdownAnnotatedString
    // is bypassed (the annotator returns true) so the span is rendered exactly once, with the tag.
    // The CODE_SPAN rendering (padding spaces, inlineCode typography span, colors) is inlined here
    // rather than extracted to a helper to keep this file under detekt's TooManyFunctions threshold.
    val inlineCodeAnnotator = remember(baseAnnotator, inlineCodeTypography, inlineCodeColors) {
        val codeStyle = inlineCodeTypography.inlineCode.copy(
            color = inlineCodeTypography.inlineCode.color,
            background = inlineCodeColors.inlineCodeBackground,
        ).toSpanStyle()
        DefaultMarkdownAnnotator(
            annotate = { content, node ->
                if (node.type == MarkdownElementTypes.CODE_SPAN) {
                    pushStyle(codeStyle)
                    append(' ')
                    val codeStart = this.length
                    // Drop the opening/closing backtick children (matching the library's internal
                    // innerList(), which is inaccessible from here).
                    val codeText = StringBuilder()
                    for (child in node.children) {
                        if (child.type == org.intellij.markdown.MarkdownTokenTypes.BACKTICK) continue
                        val text = child.getTextInNode(content).toString()
                        codeText.append(text)
                        append(text)
                    }
                    val codeEnd = this.length
                    if (codeEnd > codeStart) {
                        pushStringAnnotation(MARKDOWN_TAG_INLINE_CODE, codeText.toString())
                        pop()
                    }
                    append(' ')
                    pop()
                    true
                } else {
                    baseAnnotator.annotate?.invoke(this, content, node) ?: false
                }
            },
            config = DefaultMarkdownAnnotatorConfig(),
        )
    }
    // Compose the inline-code tagger with the search-highlight wrapper when a query is active.
    // The highlight wrapper runs on top, so it sees the final (tagged) builder output and can
    // highlight matches inside inline code too.
    val effectiveAnnotator = if (searchQuery != null && !streaming) {
        remember(inlineCodeAnnotator, searchQuery, highlightColor) {
            val inner = inlineCodeAnnotator.annotate
            DefaultMarkdownAnnotator(
                annotate = { text, node ->
                    val startLen = this.length
                    val handled = inner?.invoke(this, text, node) ?: false
                    val endLen = this.length
                    if (endLen > startLen) {
                        addSearchHighlights(
                            this,
                            this.toString().substring(startLen, endLen),
                            startLen,
                            searchQuery,
                            highlightColor,
                        )
                    }
                    handled
                },
                config = DefaultMarkdownAnnotatorConfig(),
            )
        }
    } else {
        inlineCodeAnnotator
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
        // A polite live region announces streaming content to TalkBack users. The throttle
        // (50ms) plus TalkBack's own polite-queue coalescing keeps announcements from
        // interrupting on every token; the reader hears the reply grow in measured chunks.
        //
        // retainState = true is critical: without it the library flips the internal state
        // to Loading (an empty Box) on every content change, then back to Success once the
        // off-main parse completes. During streaming the throttle commits ~20x/sec, so the
        // whole rendered subtree would blink out and be rebuilt on every commit — visible
        // flicker, full recomposition of every paragraph/code block, and layout thrash as
        // the bubble height collapses then re-measures. retainState keeps the previous
        // Success visible while the new content parses, so the swap is Success→Success
        // (no empty-box frame). The library's own rememberMarkdownState already uses a
        // single LaunchedEffect(Unit) + snapshotFlow + conflate to feed updates without
        // re-launching, so this composes cleanly with the throttle above.
        CompositionLocalProvider(
            LocalUriHandler provides safeUriHandler,
        ) {
            val markdownState: MarkdownState = rememberMarkdownState(
                content = renderedContent,
                retainState = true,
            )
            Markdown(
                markdownState = markdownState,
                modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
                components = components,
                annotator = effectiveAnnotator,
            )
        }
        // A blinking caret at the end of a streaming reply gives a "live" typewriter feel.
        // Skipped under reduced motion (where blinking is discouraged). It's a tiny separate
        // element so the markdown AST isn't re-parsed to toggle it.
        if (!LocalReducedMotion.current) StreamingCaret()
    } else {
        // retainState = true avoids a one-frame Loading flash when the content changes
        // (e.g. an edit re-render, or a theme switch re-resolving colors). For static
        // content the first parse is fast and the Success is shown directly; retainState
        // only matters on subsequent content changes, where it keeps the old Success
        // visible until the new one is ready instead of flashing an empty Box.
        val content: @Composable () -> Unit = {
            SelectionContainer {
                val markdownState: MarkdownState = rememberMarkdownState(
                    content = markdown,
                    retainState = true,
                )
                Markdown(
                    markdownState = markdownState,
                    modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    components = components,
                    annotator = effectiveAnnotator,
                )
            }
        }
        CompositionLocalProvider(
            LocalUriHandler provides safeUriHandler,
        ) {
            content()
        }
    }
    // Open/copy dialog for the most recently tapped markdown link. Offer Open only for
    // schemes the app is willing to hand to the system (http/https/mailto); for anything
    // else, copy is the only escape so a model hallucination can't fire an unexpected intent.
    pendingLink?.let { url ->
        LinkActionDialog(
            url = url,
            canOpen = isSafeLinkScheme(url),
            onDismiss = { pendingLink = null },
            onOpen = {
                pendingLink = null
                runCatching { defaultUriHandler.openUri(url) }
            },
            onCopy = {
                pendingLink = null
                copyToClipboard(context, context.getString(R.string.copy_link), url)
            },
        )
    }
}

/**
 * Adds a translucent background span over each case-insensitive occurrence of [query] found
 * in [chunk], mapping the chunk-relative offsets into the builder via [baseOffset]. Called
 * from the search-highlight annotator wrapper; [chunk] is the text the default annotator
 * appended to the builder for a single markdown node, so the offsets line up with what the
 * user actually sees (markdown syntax already stripped).
 */
private fun addSearchHighlights(
    builder: AnnotatedString.Builder,
    chunk: String,
    baseOffset: Int,
    query: String,
    color: Color,
) {
    val q = query.trim()
    if (q.isEmpty()) return
    var idx = chunk.indexOf(q, ignoreCase = true)
    while (idx >= 0) {
        builder.addStyle(
            SpanStyle(background = color),
            baseOffset + idx,
            baseOffset + idx + q.length,
        )
        idx = chunk.indexOf(q, idx + q.length, ignoreCase = true)
    }
}

/** Schemes the app is willing to forward to the system's URI handler from a markdown link. */
private fun isSafeLinkScheme(uri: String): Boolean {
    val scheme = uri.substringBefore(':', "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}

/**
 * Dialog shown when the user taps a markdown link. For http/https/mailto URIs, offers Open
 * (via the platform default handler) and Copy link. For any other scheme, only Copy is
 * offered (with an "unsupported link type" note) so a hallucinated javascript:/file: URL
 * can't launch an unexpected intent.
 */
@Composable
private fun LinkActionDialog(
    url: String,
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        text = {
            if (!canOpen) {
                Text(
                    stringResource(R.string.link_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy_link)) }
                if (canOpen) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.open_link)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Custom markdown `paragraph` / `heading` component that renders via [BasicText] directly,
 * bypassing the library's [com.mikepenz.markdown.compose.elements.MarkdownText].
 *
 * The library's `MarkdownText` wraps every text segment in
 * [animateContentSize][androidx.compose.animation.animateContentSize], which triggers draw-phase
 * remeasures through the nested SubcomposeLayout (BasicText-with-links uses SubcomposeLayout for
 * link hit-testing). During streaming and during the Loading→Success state transition, these
 * remeasures crash with an NPE in the innermost MeasurePolicy.measure (an R8-rewritten frame
 * throwing NullPointerException in ChildData.isTarget). [BasicText] handles link taps natively
 * (via LayoutWithLinksAndInlineContent), so the library's wrapper adds nothing but the
 * crash-prone animateContentSize.
 *
 * Also adds a long-press-to-copy affordance for inline code spans (single-backtick `` `code` ``).
 * The library renders CODE_SPAN inline as a styled run within the paragraph's [AnnotatedString];
 * there's no discrete `inlineCode` component to override. Instead, [MarkdownBody] installs an
 * annotator that stamps each CODE_SPAN range with a [MARKDOWN_TAG_INLINE_CODE] string annotation;
 * this component builds the [AnnotatedString] via the library's [buildMarkdownAnnotatedString]
 * (so the annotator runs and the tags land), then renders it with a `pointerInput` that hit-tests
 * a long-press against the tagged ranges and copies the span's text on hit — mirroring the copy
 * affordance fenced/indented code blocks get via [CodeWithCopy], for the common case of short
 * inline commands/identifiers. A long-press inside a [SelectionContainer] is consumed by the
 * selection machinery first, so the copy fires from non-selectable contexts (e.g. while streaming,
 * where no SelectionContainer wraps the text); when selection is active the user can still copy
 * via the selection handles. Haptic feedback matches every other copy affordance.
 *
 * The color resolution mirrors MarkdownBasicText's logic (style.color →
 * LocalMarkdownColors.current.text). The [modifier] is applied to the BasicText (used by headings
 * to add `heading()` semantics). Used for both the `paragraph` component (style =
 * typography.paragraph, no modifier) and the `heading1`–`heading6` / `setextHeading` components
 * (style = typography.h1–h6, `heading()` semantics modifier).
 */
@Composable
private fun MarkdownTextNoAnim(
    model: MarkdownComponentModel,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val typography = LocalMarkdownTypography.current
    val annotator = LocalMarkdownAnnotator.current
    // buildMarkdownAnnotatedString needs an explicit AnnotatorSettings in 0.43+ (the old
    // overload that read LocalMarkdownAnnotator internally was removed). Build one from the
    // current composition locals so the inline-code tagger installed in MarkdownBody still
    // runs. The library's own MarkdownParagraph does the same.
    val settings = DefaultAnnotatorSettings(
        linkTextSpanStyle = typography.textLink,
        codeSpanStyle = typography.inlineCode.toSpanStyle(),
        annotator = annotator,
    )
    // Memoize the annotated string on its inputs so a recomposition that doesn't change
    // the paragraph's content/AST/style (the common case — e.g. a sibling paragraph
    // recomposing, a theme color resolving to the same values) doesn't re-run the
    // library's inline-markdown annotator pass (regex/string scanning + span allocation).
    // During streaming, the parent's retainState=true means only the changed paragraphs
    // recompose, so this caps re-annotation to genuinely-changed paragraphs.
    val styledText = remember(model.content, model.node, settings, style) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(model.content, model.node, settings)
            pop()
        }
    }
    // Capture the layout result so a long-press position can be mapped to a character offset,
    // then to a tagged range — the same hit-testing pattern the library uses for link taps.
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val baseColor = LocalMarkdownColors.current.text
    BasicText(
        text = styledText,
        modifier = modifier.pointerInput(styledText) {
            detectTapGestures(
                onLongPress = { pos ->
                    val result = layoutResult ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(pos)
                    val span = styledText.getStringAnnotations(MARKDOWN_TAG_INLINE_CODE, offset, offset)
                        .firstOrNull()
                        ?: return@detectTapGestures
                    val code = span.item
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copyToClipboard(context, context.getString(R.string.clip_label_code), code)
                },
            )
        },
        style = style,
        color = { baseColor },
        onTextLayout = { result -> layoutResult = result },
    )
}

/**
 * Renders a fenced or indented code block with a header toolbar: the fence's language
 * label (when present), a wrap toggle (soft-wrap long lines vs. horizontal scroll), and a
 * copy button. Self-contained so it doesn't depend on the library's internal code-block
 * composables. The wrap toggle starts from the user's [LocalCodeWrap] preference but can
 * be flipped per-block.
 *
 * To avoid composing thousands of [Text] nodes at once (which can ANR/OOM on a long
 * response), code blocks over [COLLAPSED_CODE_LINES] lines render only the head until the
 * user expands — mirroring the collapse already applied to tool output and diffs.
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
    // A long code fence can carry hundreds of lines; computing lineCount walks the string
    // once, memoized so a recomposition that doesn't change `code` (e.g. a wrap/expand flip)
    // doesn't re-walk it.
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val canCollapse = lineCount > NetworkConfig.collapsedCodeLineThreshold
    // Key expand state on the content so a growing (streaming) block re-seeds it. Collapsing
    // primarily benefits completed messages; a streaming block that crosses the cap may snap
    // to collapsed on the token that crosses it, which is acceptable (the head stays visible).
    var expanded by rememberSaveable(code) { mutableStateOf(false) }
    val displayCode = if (canCollapse && !expanded) {
        remember(code, expanded) {
            code.lineSequence().take(NetworkConfig.collapsedCodeLineThreshold).joinToString("\n")
        }
    } else {
        code
    }
    // Heuristic syntax highlighting, reusing the same highlighter the file viewer uses.
    // Resolved from the fence's info string (e.g. "kotlin"), not a filename. Memoized so a
    // scroll-induced recomposition doesn't re-tokenize; during streaming the MarkdownBody
    // throttle coalesces re-highlights. Falls back to plain text for unknown/missing tags
    // (NONE) so un-tagged fences render unchanged. Re-derived when displayCode changes so a
    // collapsed head and the full expanded block are both highlighted correctly.
    val palette = rememberHighlightPalette()
    val syntax = remember(language) { language?.let { syntaxForLanguageTag(it) } }
    // Off-main highlighting with a cross-message LRU cache. During streaming the throttle
    // coalesces re-highlights to ~20/sec, and the cache means a re-displayed block (scroll
    // recycle, theme switch) is a hash hit instead of a full re-tokenize. The first frame
    // uses a synchronous (cached or computed) result so the block doesn't flash plain-text.
    val highlighted = rememberHighlightedCode(displayCode, syntax, palette)
    // Default per-block wrap from the global preference; re-seed if the preference changes
    // while this block is composed (rememberSaveable would over-persist across blocks).
    val defaultWrap = LocalCodeWrap.current
    var wrap by remember(defaultWrap) { mutableStateOf(defaultWrap) }
    // Briefly flip the copy button to a checkmark after a successful copy so the user gets
    // visible confirmation even on Android 13+, where the copy toast is suppressed in favor of
    // the platform's tiny (and easy to miss) confirmation chip.
    var copied by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(copied) {
        if (copied) {
            delay(NetworkConfig.copyFeedbackMs.toLong())
            copied = false
        }
    }
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
            if (canCollapse) {
                val moreLines = lineCount - NetworkConfig.collapsedCodeLineThreshold
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) context.getString(R.string.show_less)
                            else context.resources.getQuantityString(R.plurals.show_more_lines, moreLines, moreLines),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val wrapLabel = context.getString(if (wrap) R.string.code_no_wrap else R.string.code_wrap)
            IconButton(onClick = { wrap = !wrap }) {
                Icon(
                    Icons.AutoMirrored.Filled.WrapText,
                    contentDescription = wrapLabel,
                    modifier = Modifier.size(18.dp),
                    tint = if (wrap) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    copyToClipboard(context, context.getString(R.string.clip_label_code), code)
                    copied = true
                    // Haptic to match every other copy affordance in the app (the code-fence
                    // copy is the most-used copy action in a coding assistant).
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                },
            ) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = if (copied) context.getString(R.string.copied)
                        else context.getString(R.string.copy),
                    modifier = Modifier.size(18.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Wrap → let the Text wrap naturally (no horizontal scroll). No-wrap → horizontal
        // scroll so long lines keep their formatting. Selectable so a portion can be copied.
        SelectionContainer {
            Text(
                text = highlighted,
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
        if (canCollapse && !expanded) {
            val hidden = lineCount - NetworkConfig.collapsedCodeLineThreshold
            TextButton(
                onClick = { expanded = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(bottom = 4.dp),
            ) {
                Text(pluralStringResource(R.plurals.show_more_lines, hidden, hidden))
            }
        }
    }
}

/**
 * Custom markdown image component that routes `![alt](url)` through [RemoteImage] so
 * server-relative image URLs get the same Basic auth + same-origin handling as image
 * attachments. The library's default image loader has no notion of the opencode server's
 * auth, so without this a `![diagram](/media/foo.png)` in an assistant reply would fail
 * to load. When the URL can't be extracted or doesn't resolve to a loadable model, the
 * alt text is shown as a muted placeholder so the image isn't silently invisible.
 */
@Composable
private fun MarkdownImage(model: MarkdownComponentModel, ctx: ImageLoadContext) {
    val node = model.node
    val content = model.content
    // Extract the URL from the IMAGE node's LINK_DESTINATION child, and the alt text from
    // the LINK_TEXT child. The AST structure for `![alt](url)` is:
    //   IMAGE -> [LINK_LABEL, LPAREN, LINK_DESTINATION, RPAREN]  (and LINK_TEXT nested)
    val (url, alt) = remember(node, content) { extractImageUrlAndAlt(node, content) }
    if (url.isNullOrBlank()) {
        // No URL — show the alt text (or a placeholder) so the image isn't invisible. The
        // earlier `"[$alt]".ifBlank { "[image]" }` was a bug: when alt is blank, "[$alt]" is
        // "[]" — a non-blank string — so the [image] fallback never fired and the user saw "[]".
        val placeholder = if (alt.isNotBlank()) alt else stringResource(R.string.markdown_image_placeholder)
        Text(
            placeholder,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    // Build a FilePart so RemoteImage's existing resolution (data URI, same-origin, auth)
    // handles the URL. The part carries the URL and alt text as the filename.
    val part = FilePart(url = url, filename = alt.ifBlank { null })
    RemoteImage(part = part, ctx = ctx, modifier = Modifier.fillMaxWidth())
}

/** Extract the URL and alt text from a markdown IMAGE AST node. Returns (url, alt) where
 *  url may be null when no LINK_DESTINATION child is present. */
private fun extractImageUrlAndAlt(node: ASTNode, content: String): Pair<String?, String> {
    var url: String? = null
    var alt = ""
    for (child in node.children) {
        when (child.type) {
            MarkdownElementTypes.LINK_DESTINATION -> {
                url = child.getTextInNode(content).toString().trim().ifBlank { null }
            }
            MarkdownElementTypes.LINK_TEXT -> {
                alt = child.getTextInNode(content).toString()
                    .removePrefix("[").removeSuffix("]").trim()
            }
        }
    }
    return url to alt
}

/**
 * A small blinking caret shown at the tail of a streaming assistant reply for a "live"
 * typewriter feel. Respects reduced motion (callers gate it). The blink is a gentle 1s alpha
 * pulse; the caret sits inline at the leading edge so it reads as the next character arriving.
 */
@Composable
private fun StreamingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(NetworkConfig.streamingCaretPeriodMs), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Text(
        "▋",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        modifier = Modifier.semantics { invisibleToUser() },
    )
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

/** Copy [text] to the system clipboard and show a confirmation toast (except on Android 13+,
 *  which renders its own system copy confirmation — a toast there would be a redundant double). */
internal fun copyToClipboard(context: Context, label: String, text: String = label) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label.take(40), text))
    showCopyToast(context, context.getString(R.string.copied))
}

/** Toast that self-suppresses on Android 13+ (Build.VERSION_CODES.TIRAMISU), where the platform
 *  already shows a copy confirmation. Use for clipboard-copy feedback so it isn't doubled. */
internal fun showCopyToast(context: Context, message: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    showToast(context, message)
}

// Self-suppressing toast: canceling the previous toast before showing the next so two rapid
// copy/share actions don't stack two toasts. Synchronized on a dedicated lock (lastToast is
// nullable, so it can't be the monitor itself) to close the TOCTOU window between cancel and
// assign — showToast is called from main-thread coroutine resumes and the unconfined save/share
// flows, so without the lock a second caller could read lastToast between the cancel and the
// assignment, leaving a toast uncancelled.
private val toastLock = Any()
private var lastToast: Toast? = null

internal fun showToast(context: Context, message: String) {
    synchronized(toastLock) {
        lastToast?.cancel()
        lastToast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
    }
}
