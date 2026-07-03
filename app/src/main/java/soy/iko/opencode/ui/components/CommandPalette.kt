package soy.iko.opencode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable

/**
 * A keyboard-first command palette: a search field over a list of [actions], filtered as the
 * user types. Enter runs the first match, tap runs any entry; the field auto-focuses so a
 * hardware-keyboard user can type immediately after opening it. Dismissed via back/scrim, or
 * Escape (handled by the opener). Selecting an action dismisses first, then runs it, so a
 * follow-on dialog isn't immediately covered by the palette.
 */
@Composable
fun CommandPalette(
    actions: List<PaletteAction>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, actions) {
        if (query.isBlank()) actions else actions.filter { it.label.contains(query, ignoreCase = true) }
    }
    // Keyboard selection index over `filtered`. Clamped when the list shrinks; Enter/Go run the
    // selected row (or the first match when nothing is highlighted) — the keyboard-first behavior
    // a command palette is expected to have.
    var selectedIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, query) {
        // Reset selection whenever the result set changes so the highlight never points past the end.
        if (selectedIndex > filtered.lastIndex) selectedIndex = filtered.lastIndex.coerceAtLeast(0)
    }
    LaunchedEffect(selectedIndex) {
        // Keep the highlighted row visible as the user arrows through the list.
        if (filtered.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun run(action: PaletteAction) {
        onDismiss()
        action.onSelect()
    }
    fun moveSelection(delta: Int) {
        if (filtered.isNotEmpty()) {
            selectedIndex = (selectedIndex + delta).coerceIn(0, filtered.lastIndex)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.palette_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val target = filtered.getOrNull(selectedIndex) ?: filtered.firstOrNull()
                        target?.let { run(it) }
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { ev -> handlePaletteKey(ev, filtered, selectedIndex, ::moveSelection, ::run) },
                )
                LaunchedEffect(Unit) { runCatchingCancellable { focus.requestFocus() } }
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.palette_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.id }) { action ->
                            val index = filtered.indexOf(action)
                            val isSelected = index == selectedIndex
                            Text(
                                action.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                    )
                                    .clickable(role = Role.Button) { run(action) }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Keyboard navigation for the palette: Enter runs the highlighted (or first) action; arrows/Page
 *  keys move the highlight; Home/End jump to the ends. Returns true when the event is consumed.
 *  Extracted from the composable so the key-handling branches don't inflate its complexity. */
private fun handlePaletteKey(
    ev: KeyEvent,
    filtered: List<PaletteAction>,
    selectedIndex: Int,
    moveSelection: (Int) -> Unit,
    run: (PaletteAction) -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    return when (ev.key) {
        Key.Enter -> {
            val target = filtered.getOrNull(selectedIndex) ?: filtered.firstOrNull()
            target?.let(run)
            true
        }
        Key.DirectionDown, Key.PageDown -> { moveSelection(1); true }
        Key.DirectionUp, Key.PageUp -> { moveSelection(-1); true }
        Key.MoveHome -> { moveSelection(Int.MIN_VALUE / 2); true }
        Key.MoveEnd -> { moveSelection(Int.MAX_VALUE / 2); true }
        else -> false
    }
}
