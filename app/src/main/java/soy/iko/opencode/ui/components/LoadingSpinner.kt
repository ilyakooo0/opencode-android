package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import soy.iko.opencode.R

/**
 * The single source of truth for indeterminate loading spinners across the app. Always sets a
 * [contentDescription] so TalkBack announces progress (a bare [CircularProgressIndicator] is
 * announced only inconsistently, especially when inlined inside a Button or replacing an icon),
 * and uses a small set of canonical size/stroke combinations from [LoadingSize].
 */
@Composable
fun LoadingSpinner(
    size: LoadingSize = LoadingSize.Medium,
    modifier: Modifier = Modifier,
    color: Color = CircularProgressIndicatorDefaults.color,
) {
    val label = stringResource(R.string.loading)
    CircularProgressIndicator(
        modifier = modifier
            .size(size.sizeDp.dp)
            .semantics { contentDescription = label },
        strokeWidth = size.strokeDp.dp,
        color = color,
    )
}

private object CircularProgressIndicatorDefaults {
    val color: Color
        @Composable get() = MaterialTheme.colorScheme.primary
}
