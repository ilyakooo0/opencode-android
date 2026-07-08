package soy.iko.opencode.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import soy.iko.opencode.R
import soy.iko.opencode.core.MessageView
import soy.iko.opencode.ui.theme.AssistantBubbleShape
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme
import soy.iko.opencode.ui.theme.UserBubbleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single chat message rendered as a left/right-aligned bubble. Assistant
 * messages are rendered as markdown; user messages as plain text. The bubble
 * shape carries an iMessage-style "tail" on the speaker's side.
 *
 * Accessibility: the whole bubble is merged into one semantics node so
 * TalkBack reads the role label + content as a single utterance. Assistant
 * bubbles are marked as a [LiveRegionMode.Polite] live region so streaming
 * updates are announced without stealing focus.
 *
 * Long-press anywhere on the bubble copies the raw message text to the
 * clipboard and emits a long-press haptic, mirroring the standard chat UX.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageView,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val maxWidth = maxBubbleWidthDp()
    val roleLabel = if (isUser) {
        stringResource(R.string.chat_role_user)
    } else {
        stringResource(R.string.chat_role_assistant)
    }
    val timeLabel = remember(message.time) { formatTimestamp(message.time) }
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    // Build a single TalkBack utterance that includes the role and the
    // message body. Setting an explicit contentDescription on a merge node
    // replaces descendant text, so we must include the body ourselves rather
    // than letting it merge — otherwise TalkBack reads only "User".
    val announcement = buildString {
        append(roleLabel)
        if (message.time > 0uL) {
            append(", ")
            append(timeLabel)
        }
        append(". ")
        append(if (message.text.isBlank()) stringResource(R.string.chat_no_content) else message.text)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClick = {
                    if (message.text.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(message.text))
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onClick = {},
            )
            .semantics(mergeDescendants = true) {
                contentDescription = announcement
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = if (isUser) UserBubbleShape else AssistantBubbleShape,
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimens.bubbleHorizontalPadding,
                    vertical = Dimens.bubbleVerticalPadding,
                ),
            ) {
                if (!isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = roleLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (message.time > 0uL) {
                            Text(
                                text = timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.spaceTiny))
                } else {
                    // Show a timestamp on user bubbles too, aligned to the
                    // end so it reads as the message's send time.
                    if (message.time > 0uL) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(Modifier.height(Dimens.spaceTiny))
                    }
                }
                if (message.text.isBlank()) {
                    Text(
                        text = stringResource(R.string.chat_no_content),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                } else if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Assistant replies often contain markdown (code blocks,
                    // lists, etc.) — render them properly.
                    Markdown(
                        content = message.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                // Polite live region so streaming edits are
                                // announced without interrupting the user.
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
            }
        }
    }
}

@Composable
fun GeneratingIndicator(modifier: Modifier = Modifier) {
    val maxWidth = maxBubbleWidthDp()
    val generatingLabel = stringResource(R.string.chat_cd_generating)
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Three dots that pulse in sequence: each dot scales/fades with a
    // staggered delay so the indicator reads as "typing" rather than a
    // single frozen spinner. One shared infinite transition drives them.
    val transition = rememberInfiniteTransition(label = "generating-dots")
    val phase1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 0),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-1",
    )
    val phase2 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-2",
    )
    val phase3 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-3",
    )
    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceTiny),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = AssistantBubbleShape,
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceTiny),
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.bubbleHorizontalPadding,
                        vertical = Dimens.bubbleVerticalPadding,
                    )
                    .semantics(mergeDescendants = true) {
                        // Announced when the indicator appears/disappears.
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = generatingLabel
                    },
            ) {
                GeneratingDot(alpha = phase1, color = dotColor)
                GeneratingDot(alpha = phase2, color = dotColor)
                GeneratingDot(alpha = phase3, color = dotColor)
            }
        }
    }
}

@Composable
private fun GeneratingDot(alpha: Float, color: androidx.compose.ui.graphics.Color) {
    // Scale slightly with alpha so the pulse feels like motion, not just a
    // dimmer toggle. alpha drives both the dot's alpha and a small scale.
    val scale = 0.75f + alpha * 0.25f
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(Dimens.iconInlineSpinner),
    ) {
        val diameter = size.minDimension * scale
        drawCircle(
            color = color.copy(alpha = alpha.coerceIn(0.15f, 1f)),
            radius = diameter / 2f,
            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
        )
    }
}

private fun formatTimestamp(epochSeconds: ULong): String {
    val date = Date(epochSeconds.toLong() * 1000)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/**
 * Max chat-bubble width as a fraction of the window width, in dp. Uses
 * [LocalWindowInfo] (the recommended window-size source) rather than the
 * deprecated [androidx.compose.ui.platform.LocalConfiguration.screenWidthDp].
 */
@Composable
private fun maxBubbleWidthDp(): androidx.compose.ui.unit.Dp {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        containerSize.width.toDp() * Dimens.bubbleMaxWidthFraction
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessageBubbleUserPreview() {
    OpencodeTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageBubble(
                message = MessageView(
                    id = "u1",
                    role = "user",
                    text = "Hello! Can you summarize the build steps?",
                    time = 1_700_000_000uL,
                ),
            )
            MessageBubble(
                message = MessageView(
                    id = "a1",
                    role = "assistant",
                    text = "Sure — here's a **short** list:\n\n1. Generate types\n2. Build the native library\n3. Assemble the APK",
                    time = 1_700_000_010uL,
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneratingIndicatorPreview() {
    OpencodeTheme {
        GeneratingIndicator()
    }
}
