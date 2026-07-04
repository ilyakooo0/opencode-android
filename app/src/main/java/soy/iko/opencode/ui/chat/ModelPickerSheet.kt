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
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.R

/**
 * Flat LazyColumn index of the selected model within the provider-grouped list (each group
 * preceded by a header row), so the sheet can scroll it into view on open. -1 when nothing
 * is selected or the selection isn't in the current list.
 */
private fun selectedModelIndex(
    grouped: Map<String, List<ModelOption>>,
    selected: ModelOption?,
): Int {
    if (selected == null) return -1
    var idx = 0
    for ((_, opts) in grouped) {
        idx++ // provider header
        val distinct = opts.distinctBy { it.providerID to it.modelID }
        val pos = distinct.indexOfFirst {
            it.providerID == selected.providerID && it.modelID == selected.modelID
        }
        if (pos >= 0) return idx + pos
        idx += distinct.size
    }
    return -1
}

/** Bottom sheet that lists every provider/model and lets the user pick one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    options: List<ModelOption>,
    selected: ModelOption?,
    loading: Boolean,
    error: Boolean,
    onSelect: (ModelOption) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Hoist SheetState + skipPartiallyExpanded so the sheet opens full-height and selection
    // animates out (see AgentPickerSheet for the full rationale).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.model),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (loading) {
            val loadingLabel = stringResource(R.string.loading_models)
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                Text(
                    stringResource(R.string.loading_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else if (error && options.isEmpty()) {
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
        } else if (options.isEmpty()) {
            Text(
                stringResource(R.string.no_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            var query by rememberSaveable { mutableStateOf("") }
            val keyboardController = LocalSoftwareKeyboardController.current
            val haptics = LocalHapticFeedback.current
            // Auto-focus the search field (and raise the keyboard) when the sheet opens so the
            // user can start typing to filter a long catalog without an extra tap. Skip it for a
            // short catalog that's faster to scan by eye than to filter.
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (options.size > NetworkConfig.pickerSearchAutofocusThreshold) {
                    runCatching { searchFocus.requestFocus() }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(searchFocus),
                placeholder = { Text(stringResource(R.string.search_models)) },
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
            // Dedupe the source list once (keyed on `options`), then filter the already-distinct
            // list, so distinctBy doesn't re-walk the catalog on every keystroke.
            val distinct = remember(options) { options.distinctBy { it.providerID to it.modelID } }
            val filtered = remember(distinct, query) {
                val q = query.trim()
                if (q.isEmpty()) distinct
                else distinct.filter {
                    it.modelLabel.contains(q, ignoreCase = true) ||
                        it.providerLabel.contains(q, ignoreCase = true)
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.no_models_match, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                // Group by provider so a long catalog is scannable: a provider header
                // followed by its models, instead of a flat list mixing providers.
                val grouped = remember(filtered) { filtered.groupBy { it.providerID } }
                val listState = rememberLazyListState()
                // Flat LazyColumn index of the selected model (accounting for the provider
                // header rows), so we can scroll it into view when the sheet opens. -1 when
                // nothing is selected or the selection isn't in the current list.
                val selectedIndex = remember(grouped, selected) { selectedModelIndex(grouped, selected) }
                // Re-scroll on filter change so the selection comes back into view after the
                // filter is cleared (mirrors AgentPickerSheet).
                LaunchedEffect(grouped) {
                    if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = NetworkConfig.pickerSheetMaxHeightDp.dp)
                        .imePadding()
                        .navigationBarsPadding()
                        .semantics { selectableGroup() },
                ) {
                    grouped.forEach { (providerID, opts) ->
                        item(key = "header_$providerID") {
                            Text(
                                opts.firstOrNull()?.providerLabel ?: providerID,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        // opts is already distinct (filtered derives from the distinct list),
                        // so no per-row distinctBy is needed here.
                        items(opts, key = { it.providerID to it.modelID }) { option ->
                    val isSelected = option.providerID == selected?.providerID &&
                        option.modelID == selected?.modelID
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                this.selected = isSelected
                            }
                            .clickable(role = Role.RadioButton) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                                onSelect(option)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                option.modelLabel,
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
                    }
                }
                    }
            }
        }
        }
    }
}
