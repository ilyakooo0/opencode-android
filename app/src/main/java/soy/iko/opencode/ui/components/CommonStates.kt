package soy.iko.opencode.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R

/**
 * Shared search/filter text field: a leading [Search] icon, a trailing clear-× (shown when the
 * query is non-empty), and [ImeAction.Search] + keyboard-hide on the search action. Extracted
 * from the ~9 near-identical search fields across Session/Server/File/Settings/Diagnostics/Usage
 * /GlobalSearch/FileViewer/Chat-search so future screens stay consistent.
 *
 * @param query the current query text.
 * @param onQueryChange called when the query changes (typing, clear, paste).
 * @param placeholder the placeholder label (e.g. "Search sessions").
 * @param modifier optional [Modifier] (the field fills its max width by default).
 * @param testTagSuffix appended to the standard `"search_field"` testTag for per-screen uniqueness.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    testTagSuffix: String = "",
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag("search_field$testTagSuffix"),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

/**
 * Shared centered error state: a large error icon, an optional message, and a Retry button.
 * Extracted from the ~4 near-identical error states across FileBrowser/FileView/SessionList/
 * Diagnostics so every screen's "load failed" state reads and behaves the same. Mirrors
 * [EmptyState]'s centered layout.
 *
 * @param message the error detail to show under the icon.
 * @param onRetry invoked when the user taps Retry. When null, the button is hidden (for
 *  non-retryable errors).
 * @param icon the error icon (defaults to [androidx.compose.material.icons.Icons.Filled.ErrorOutline]
 *  at the callsite — kept as a param so callers can pass a context-specific icon).
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.ErrorOutline,
    retryLabel: String = "",
) {
    val retry = if (retryLabel.isBlank()) "" else retryLabel
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null && retry.isNotBlank()) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("error_state_retry")) {
                Text(retry)
            }
        }
    }
}

/**
 * A pulse-animated placeholder box used to build skeletons. Reuses the same pulse pattern as
 * [SkeletonRow] (0.35↔0.7 alpha over 900ms, static 0.5 under [LocalReducedMotion]) so all
 * skeletons across the app animate in unison. Extracted so the chat/session/file-viewer
 * skeletons can compose from this primitive instead of re-implementing the pulse.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    cornerRadius: Dp = 4.dp,
) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton_box")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            tween(900),
            RepeatMode.Reverse,
        ),
        label = "skeleton_box_alpha",
    )
    val skeletonAlpha = if (reducedMotion) 0.5f else pulse
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(skeletonColor)
            .alpha(skeletonAlpha),
    )
}

/** Resolves the standard "Loading…" label for skeletons that need a merged a11y description. */
@Composable
fun skeletonLoadingLabel(): String = androidx.compose.ui.res.stringResource(R.string.loading)
