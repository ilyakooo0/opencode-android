package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import soy.iko.opencode.R
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

/**
 * Centered indeterminate loading indicator. Used when a screen is loading its
 * initial content and has nothing else to show. Marked as a polite live region
 * so TalkBack announces "loading" once.
 */
@Composable
fun LoadingPlaceholder(
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.loading),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Centered empty-state message. Used when a screen has no content yet (e.g.
 * no sessions, no messages).
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.screenPadding),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingPlaceholderPreview() {
    OpencodeTheme { LoadingPlaceholder() }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    OpencodeTheme { EmptyState(message = "No sessions yet") }
}
