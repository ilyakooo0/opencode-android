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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * - Exposes a keyboard "Send" action so users can fire-and-forget without
 *   tapping the icon.
 * - Send is disabled while [enabled] is false (e.g. during loading) or when
 *   the text is blank.
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Message…",
    sendContentDescription: String = "Send",
) {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(
                onClick = onSend,
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
