package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.m3.Markdown

/**
 * Renders markdown using [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
 * (commonmark-java under the hood). Keeps a local API so callers don't import the library directly.
 * Long-press copies the raw markdown to the system clipboard.
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
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { copyToClipboard(context, markdown) },
        ),
    )
}

/** Copy [text] to the system clipboard and show a confirmation toast. */
internal fun copyToClipboard(context: Context, label: String, text: String = label) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label.take(40), text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
