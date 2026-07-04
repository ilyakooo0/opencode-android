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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R

/**
 * A single pulse-animated placeholder row mirroring the session list's skeleton pattern: a
 * leading avatar/box and two text bars (title + subtitle). Used by the server/MCP/usage/file
 * browser screens' initial-load states so a structured preview of the eventual list reads more
 * polished than a bare centered [androidx.compose.material3.CircularProgressIndicator], and
 * matches the chat/session/file-viewer skeletons.
 *
 * Pulses 0.35↔0.7 alpha over 900ms; under [LocalReducedMotion] the alpha is a static 0.5 so the
 * placeholder is still visible but doesn't animate. The whole row is one merged semantics node
 * (TalkBack announces "Loading…" once per row, not three times for each bar).
 *
 * @param circleSize diameter of the leading placeholder (40dp matches the session avatar).
 * @param barWidths fractional widths of the title and subtitle bars (0.7 to 0.45 by default).
 */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    circleSize: Dp = 40.dp,
    barWidths: Pair<Float, Float> = 0.7f to 0.45f,
) {
    val loadingLabel = stringResource(R.string.loading)
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton_row")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton_row_alpha",
    )
    val skeletonAlpha = if (reducedMotion) 0.5f else pulse
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = loadingLabel },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(circleSize)
                .clip(RoundedCornerShape(circleSize / 2))
                .background(skeletonColor)
                .alpha(skeletonAlpha),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(barWidths.first)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
                    .alpha(skeletonAlpha),
            )
            Box(
                Modifier
                    .fillMaxWidth(barWidths.second)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
                    .alpha(skeletonAlpha),
            )
        }
    }
}
