package soy.iko.opencode.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.RelativeTimeText
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

/** Cross-session message search: type a query, tap a result to open that conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: GlobalSearchViewModel = viewModel(factory = vmFactory { GlobalSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatchingCancellable { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = stringResource(R.string.search_all_title), onBack = onBack)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_all_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val searchingLabel = stringResource(R.string.searching)
                when {
                    state.searching -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center).semantics { contentDescription = searchingLabel },
                    )
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
                                stringResource(R.string.search_results_count, state.results.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (state.truncated) {
                            item(key = "__truncated") {
                                Text(
                                    stringResource(R.string.search_truncated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                        items(state.results, key = { it.session.id }) { hit ->
                            SearchResultCard(
                                hit = hit,
                                query = state.query.trim(),
                                onClick = { onOpenSession(hit.session.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                hit.session.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RelativeTimeText(
                hit.session.time?.updated ?: hit.session.time?.created,
                modifier = Modifier.padding(top = 2.dp),
            )
            val highlightColor = MaterialTheme.colorScheme.primary
            val snippet = remember(hit.snippet, query, highlightColor) {
                highlightMatches(hit.snippet, query, highlightColor)
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

/** Bold + tint every case-insensitive occurrence of [query] within [text] so the user can
 *  see where the hit is in the surrounding snippet. */
private fun highlightMatches(
    text: String,
    query: String,
    color: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val idx = text.indexOf(query, start, ignoreCase = true)
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
