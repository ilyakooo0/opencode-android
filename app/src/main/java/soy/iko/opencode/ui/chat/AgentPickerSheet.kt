package soy.iko.opencode.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.R

/**
 * Flat LazyColumn index of the selected agent, offset by the leading "Default" row when it's
 * shown. A null selection targets that Default row (index 0). -1 when the selection isn't in
 * the current list.
 */
/** Whether the synthetic "Default" agent row matches the current search query. */
private fun defaultAgentMatches(query: String, label: String, desc: String): Boolean =
    query.isEmpty() ||
        label.contains(query, ignoreCase = true) ||
        desc.contains(query, ignoreCase = true)

private fun selectedAgentIndex(
    distinctAgents: List<Agent>,
    selected: String?,
    defaultMatches: Boolean,
): Int {
    if (selected == null) return if (defaultMatches) 0 else -1
    val base = if (defaultMatches) 1 else 0
    val pos = distinctAgents.indexOfFirst { it.name == selected }
    return if (pos >= 0) base + pos else -1
}

/** Bottom sheet that lists every agent and lets the user pick one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    agents: List<Agent>,
    selected: String?,
    loading: Boolean,
    error: Boolean,
    onSelect: (Agent?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Hoist the SheetState so selection can animate the sheet out (slide) instead of
    // snap-dismissing by tearing down the composition. skipPartiallyExpanded = true so the
    // sheet opens at full height and the list's heightIn cap resolves against a stable sheet,
    // not the half-height partial state (which previously left the list at ~25% of the screen
    // until the user dragged up).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.agent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (loading) {
            val loadingLabel = stringResource(R.string.loading_agents)
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                Text(
                    stringResource(R.string.loading_agents),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else if (error && agents.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        } else if (agents.isEmpty()) {
            Text(
                stringResource(R.string.no_agents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            var query by rememberSaveable { mutableStateOf("") }
            val keyboardController = LocalSoftwareKeyboardController.current
            val haptics = LocalHapticFeedback.current
            // Auto-focus the search field (and raise the keyboard) when the sheet opens, but skip
            // it for a short catalog that's faster to scan by eye than to filter.
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (agents.size > NetworkConfig.pickerSearchAutofocusThreshold) {
                    runCatching { searchFocus.requestFocus() }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(searchFocus),
                placeholder = { Text(stringResource(R.string.search_agents)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            )
            // Dedupe the source list once (keyed on `agents`, not the per-keystroke `filtered`),
            // so distinctBy doesn't re-walk the catalog on every character typed.
            val distinctAgents = remember(agents) { agents.distinctBy { it.name } }
            val filtered = remember(distinctAgents, query) {
                val q = query.trim()
                if (q.isEmpty()) distinctAgents
                else distinctAgents.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.displayDescription.contains(q, ignoreCase = true)
                }
            }
            // The "Default" option participates in the search too: show it when the query is
            // empty or matches its label/description. Without this it either always showed
            // (ignoring the filter) or, when a query matched no named agent, disappeared with
            // the whole list — leaving the default unselectable.
            val defaultLabel = stringResource(R.string.default_agent)
            val defaultDesc = stringResource(R.string.default_agent_desc)
            val q = query.trim()
            val defaultMatches = defaultAgentMatches(q, defaultLabel, defaultDesc)
            if (filtered.isEmpty() && !defaultMatches) {
                Text(
                    stringResource(R.string.no_agents_match, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                val listState = rememberLazyListState()
                // Flat index of the selected agent, offset by the leading "Default" row when
                // it's shown. selected == null targets that Default row (index 0). -1 when the
                // selection isn't in the current list.
                val selectedIndex = remember(distinctAgents, selected, defaultMatches) {
                    selectedAgentIndex(distinctAgents, selected, defaultMatches)
                }
                // Re-scroll to the selected item whenever the filter changes (not just once on
                // open), so clearing a filter that had hidden the selection brings it back into
                // view instead of leaving the list at the top.
                LaunchedEffect(filtered, defaultMatches) {
                    if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
                }
                // Cap the list height against the screen, not the (partial) sheet, so the list
                // is a predictable height regardless of drag position. weight(1f) would also
                // work but heightIn keeps the sheet compact for short catalogs.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = NetworkConfig.pickerSheetMaxHeightDp.dp)
                        .imePadding()
                        .navigationBarsPadding()
                        .semantics { selectableGroup() },
                ) {
                if (defaultMatches) {
                item(key = "__default") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                this.selected = (selected == null)
                            }
                            .clickable(role = Role.RadioButton) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                // Animate the sheet out before tearing down, so the selection
                                // reads as a smooth transition instead of a snap disappearance.
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                                onSelect(null)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.default_agent),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected == null) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.default_agent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
                // distinctBy the name so a server that returns two agents with the same name
                // (e.g. a built-in colliding with a project-level one) can't crash the list
                // with LazyColumn's "Key was already used".
                // Namespace the agent keys so an agent literally named "__default" can't
                // collide with the sentinel key of the default item above (a duplicate key
                // crashes LazyColumn).
                items(filtered, key = { "agent_" + it.name }) { agent ->
                    val isSelected = agent.name == selected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                this.selected = isSelected
                            }
                            .clickable(role = Role.RadioButton) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                                onSelect(agent)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                agent.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            agent.displayDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }
        }
    }
}
