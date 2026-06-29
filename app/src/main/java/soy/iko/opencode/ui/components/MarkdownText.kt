package soy.iko.opencode.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.m3.Markdown

/**
 * Renders markdown using [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
 * (commonmark-java under the hood). Keeps a local API so callers don't import the library directly.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Markdown(
        content = markdown,
        modifier = modifier,
    )
}
