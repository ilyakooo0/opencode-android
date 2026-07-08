package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

/**
 * The chat message composer. A multi-line text field plus a send icon button.
 *
 * - Honors IME insets via [Modifier.imePadding] so the keyboard never covers
 *   the input bar (the chat Scaffold relies on this).
 * - Uses [ImeAction.Default] so the keyboard's action key inserts a newline,
 *   allowing multi-line messages. The send button on the right fires the
 *   message.
 * - Send is disabled while [enabled] is false (e.g. during loading) or when
 *   the text is blank.
 * - When [generating] is true, a Stop button replaces the Send button so the
 *   user can cancel the wait; tapping it calls [onStop].
 * - A long-press haptic fires on send for tactile confirmation.
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    generating: Boolean = false,
    onStop: () -> Unit = {},
    stopContentDescription: String = "Stop generating",
    placeholder: String = "Message…",
    sendContentDescription: String = "Send",
) {
    val hapticFeedback = LocalHapticFeedback.current
    Surface(
        tonalElevation = Dimens.inputBarTonalElevation,
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.inputBarHorizontalPadding,
                    vertical = Dimens.inputBarVerticalPadding,
                ),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            if (generating) {
                IconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStop()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = stopContentDescription
                    },
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                }
            } else {
                IconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.semantics {
                        contentDescription = sendContentDescription
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatInputBarPreview() {
    OpencodeTheme {
        ChatInputBar(
            text = "Hello world",
            onTextChange = {},
            onSend = {},
            enabled = true,
        )
    }
}
