package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared empty/placeholder state: a centered icon, a title, an optional supporting line,
 * and an optional call-to-action button. Extracted so every screen's "nothing here yet"
 * state reads and behaves the same (see EmptySessions/EmptyServers/etc. which predate this).
 *
 * An optional [secondaryActionLabel] / [onSecondaryAction] pair renders a secondary
 * OutlinedButton below the primary CTA, for cases where two recovery paths are equally
 * valid (e.g. "Add server" vs "Import from QR image" on a first-run server list).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionIcon: ImageVector? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
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
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("empty_state_action"),
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(actionLabel, modifier = Modifier.padding(start = 6.dp))
                } else {
                    Text(actionLabel)
                }
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            OutlinedButton(
                onClick = onSecondaryAction,
                modifier = Modifier.testTag("empty_state_secondary_action"),
            ) {
                if (secondaryActionIcon != null) {
                    Icon(secondaryActionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(secondaryActionLabel, modifier = Modifier.padding(start = 6.dp))
                } else {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}
