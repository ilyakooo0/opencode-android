package soy.iko.opencode.ui.components

import androidx.compose.runtime.Immutable

/** One entry in the [CommandPalette]: a label the user can search for and an action to run. */
@Immutable
data class PaletteAction(
    val id: String,
    val label: String,
    val onSelect: () -> Unit,
)
