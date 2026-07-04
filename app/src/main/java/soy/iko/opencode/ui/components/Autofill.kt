@file:Suppress("MatchingDeclarationName")

package soy.iko.opencode.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * A stable, non-experimental hint for which autofill category a field represents, so callers
 * don't have to opt into the experimental [AutofillType] API (or reference `androidx.compose.ui
 * .autofill` types) at every text-field call site.
 */
enum class AutofillHint { Username, Password, Email }

/**
 * Registers an autofill node for [hint] with the Compose autofill tree so password managers and
 * the system credential service can populate the field. Returns a [Modifier] that reports the
 * field's window bounds so the platform's autofill UI anchors to it.
 *
 * Compose's autofill API is still experimental (`androidx.compose.ui.autofill`); encapsulating
 * it here keeps the opt-in local and gives every auth field the same hint wiring. Without it,
 * Compose text fields carry no autofill hints and password managers can't fill them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun autofillModifier(hint: AutofillHint, onFill: (String) -> Unit): Modifier {
    val currentOnFill by rememberUpdatedState(onFill)
    val types = remember(hint) {
        listOf(
            when (hint) {
                AutofillHint.Username -> AutofillType.Username
                AutofillHint.Password -> AutofillType.Password
                AutofillHint.Email -> AutofillType.EmailAddress
            },
        )
    }
    val node = remember(types) { AutofillNode(autofillTypes = types, onFill = { currentOnFill(it) }) }
    LocalAutofillTree.current += node
    return Modifier.onGloballyPositioned { coords -> node.boundingBox = coords.boundsInWindow() }
}
