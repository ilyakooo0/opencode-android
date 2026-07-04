package soy.iko.opencode.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.AppTopBar
import soy.iko.opencode.ui.components.ConnectionBannerFor
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.RelativeTimeText
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

/** Cross-session message search: type a query, tap a result to open that conversation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    container: AppContainer,
    onOpenSession: (String, String?) -> Unit,
    onBack: () -> Unit,
) {
    val vm: GlobalSearchViewModel = viewModel(factory = vmFactory { GlobalSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val matchCaseLabel = stringResource(R.string.match_case)

    LaunchedEffect(Unit) {
        runCatchingCancellable { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = stringResource(R.string.search_all_title), onBack = onBack)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = soy.iko.opencode.data.network.NetworkConfig.listContentMaxWidthDp.dp).imePadding().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
                    .testTag("global_search"),
                label = { Text(stringResource(R.string.search_all_hint)) },
                placeholder = { Text(stringResource(R.string.search_all_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // "Aa" toggle: a FilterChip beside the clear (x) so the case-sensitivity
                        // control sits with the query it modifies, not in the type-filter row.
                        FilterChip(
                            selected = state.matchCase,
                            onClick = { vm.setMatchCase(!state.matchCase) },
                            label = { Text("Aa") },
                            modifier = Modifier.semantics { contentDescription = matchCaseLabel },
                        )
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            // Type-filter row: All / Messages / Tool calls / Reasoning. Wraps via FlowRow so a
            // long localized label doesn't overflow on narrow screens.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val filters = listOf(
                    SearchTypeFilter.ALL to R.string.search_filter_all,
                    SearchTypeFilter.MESSAGES to R.string.search_filter_messages,
                    SearchTypeFilter.TOOLS to R.string.search_filter_tools,
                    SearchTypeFilter.REASONING to R.string.search_filter_reasoning,
                )
                filters.forEach { (filter, labelRes) ->
                    FilterChip(
                        selected = state.typeFilter == filter,
                        onClick = { vm.setTypeFilter(filter) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                ConnectionBannerFor(container)
                val searchingLabel = stringResource(R.string.searching)
                // Crossfade between content states so transitions read as a smooth fade instead
                // of an instant snap. Matches the session list's Crossfade pattern; reduced
                // motion is honored by Crossfade's default spec.
                val stateKey = globalSearchStateKey(state)
                    @Suppress("UnusedCrossfadeTargetStateParameter")
                    Crossfade(
                        targetState = stateKey,
                        animationSpec = tween(soy.iko.opencode.data.network.NetworkConfig.motionFadeDurationMs.toInt()),
                        label = "global_search_state",
                    ) {
                    when {
                    state.searching -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            Modifier.semantics { contentDescription = searchingLabel },
                        )
                        // Surface determinate progress so a 50-session pass doesn't look stuck.
                        if (state.totalCount > 1) {
                            Spacer(Modifier.size(12.dp))
                            Text(
                                stringResource(R.string.search_progress, state.searchedCount, state.totalCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.error != null -> EmptyState(
                        icon = Icons.Filled.ErrorOutline,
                        title = state.error ?: "",
                        modifier = Modifier.align(Alignment.Center),
                        actionLabel = stringResource(R.string.retry),
                        onAction = vm::retry,
                    )
                    state.hasSearched && state.results.isEmpty() -> EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = stringResource(R.string.search_no_matches),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // Typed something but below the min length (no search run yet): nudge to keep
                    // typing rather than reverting to the untouched start state, which reads as if
                    // the field were empty.
                    state.query.isNotBlank() && !state.hasSearched -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.search_keep_typing),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // Empty query + persisted history: surface suggestions so a returning user can
                    // re-run a prior search in one tap instead of retyping it.
                    state.query.isBlank() && state.history.isNotEmpty() -> HistorySuggestions(
                        history = state.history,
                        onPick = { query ->
                            vm.setQuery(query)
                            keyboard?.hide()
                        },
                        onClear = vm::clearHistory,
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.results.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.search_all_start),
                        description = stringResource(R.string.search_all_start_hint),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "__count") {
                            Text(
                                pluralStringResource(R.plurals.search_results_count, state.results.size, state.results.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        if (state.truncated) {
                            item(key = "__truncated") {
                                // Surface that the search was capped AND offer a one-tap "search
                                // more" so users with >50 sessions can page through older matches
                                // instead of being stuck at the cap.
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.search_truncated),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = { vm.searchMore() },
                                        enabled = !state.searching,
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                    ) {
                                        Text(stringResource(R.string.search_more))
                                    }
                                }
                            }
                        }
                        items(state.results, key = { it.session.id }) { hit ->
                            SearchResultCard(
                                hit = hit,
                                query = state.query.trim(),
                                ignoreCase = !state.matchCase,
                                onClick = { onOpenSession(hit.session.id, hit.firstMatchMessageId) },
                                modifier = Modifier.then(reducedMotionAnimateItem()),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Maps the global-search view state to a stable string key for the Crossfade, so the screen's
 *  content-state transitions fade smoothly. Extracted from [GlobalSearchScreen] to keep its
 *  cyclomatic complexity under the detekt threshold. */
private fun globalSearchStateKey(state: GlobalSearchState): String = when {
    state.searching -> "searching"
    state.error != null -> "error"
    state.hasSearched && state.results.isEmpty() -> "no_matches"
    state.query.isNotBlank() && !state.hasSearched -> "keep_typing"
    state.query.isBlank() && state.history.isNotEmpty() -> "history"
    state.results.isEmpty() -> "start"
    else -> "results"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySuggestions(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.recent_searches),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.clear_history))
            }
        }
        Spacer(Modifier.size(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { query ->
                AssistChip(
                    onClick = { onPick(query) },
                    leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, query: String, ignoreCase: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val highlightColor = MaterialTheme.colorScheme.primary
    // Highlight the title too when the match was in the title (otherwise the body snippet below
    // is highlighted but the title above stays plain — an inconsistency).
    val title = remember(hit.session.displayTitle, query, hit.matchedTitle, highlightColor, ignoreCase) {
        if (hit.matchedTitle) highlightMatches(hit.session.displayTitle, query, highlightColor, ignoreCase)
        else AnnotatedString(hit.session.displayTitle)
    }
    Card(modifier = modifier.fillMaxWidth().clickable(role = Role.Button) { onClick() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Show how many times the query occurs so the user knows whether the session is
                // worth opening (multiple hits) instead of seeing only the single returned snippet.
                if (hit.matchCount > 1) {
                    val countLabel = pluralStringResource(R.plurals.search_results_count, hit.matchCount, hit.matchCount)
                    Text(
                        countLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            RelativeTimeText(
                hit.session.time?.updated ?: hit.session.time?.created,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (hit.snippet.isNotEmpty()) {
                val snippet = remember(hit.snippet, query, highlightColor, ignoreCase) {
                    highlightMatches(hit.snippet, query, highlightColor, ignoreCase)
                }
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Bold + tint every occurrence of [query] within [text] so the user can see where the hit is
 *  in the surrounding snippet. [ignoreCase] matches the search's case mode so highlights and
 *  matches never diverge. */
private fun highlightMatches(
    text: String,
    query: String,
    color: androidx.compose.ui.graphics.Color,
    ignoreCase: Boolean,
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val idx = text.indexOf(query, start, ignoreCase = ignoreCase)
            if (idx < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, idx))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(text.substring(idx, idx + query.length))
            }
            start = idx + query.length
        }
    }
}
