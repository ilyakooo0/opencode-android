package soy.iko.opencode.ui.file

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.data.model.FindMatch
import soy.iko.opencode.data.model.SymbolResult
import soy.iko.opencode.data.model.symbolKindLabel
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.AppTopBar
import soy.iko.opencode.ui.components.ConnectionBannerFor
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    container: AppContainer,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FileBrowserViewModel = viewModel(factory = vmFactory { FileBrowserViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.transientErrors.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = stringResource(R.string.files), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Surface SSE connection state so a dropped stream is visible while browsing
            // files, not just on the chat/session screens.
            Box(modifier = Modifier.fillMaxWidth()) {
                ConnectionBannerFor(container)
            }
            // Tappable breadcrumb trail so deep paths are navigable.
            Breadcrumbs(
                path = state.path,
                onNavigate = vm::open,
                modifier = Modifier.fillMaxWidth(),
            )
            val keyboardController = LocalSoftwareKeyboardController.current
            // Search is the primary reason to open this screen, so focus the field on first
            // composition. Focus only (no forced keyboard) so it doesn't fight the breadcrumb/listing.
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatchingCancellable { searchFocus.requestFocus() } }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .focusRequester(searchFocus)
                    .testTag("file_search"),
                label = {
                    Text(
                        stringResource(
                            when (state.mode) {
                                SearchMode.FILES -> R.string.search_files
                                SearchMode.TEXT -> R.string.search_in_files
                                SearchMode.SYMBOL -> R.string.search_symbols_label
                            },
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            )
            // Mode selector: file names / contents / symbols.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchModeChip(stringResource(R.string.search_mode_files), state.mode == SearchMode.FILES) { vm.setMode(SearchMode.FILES) }
                SearchModeChip(stringResource(R.string.search_mode_text), state.mode == SearchMode.TEXT) { vm.setMode(SearchMode.TEXT) }
                SearchModeChip(stringResource(R.string.search_mode_symbols), state.mode == SearchMode.SYMBOL) { vm.setMode(SearchMode.SYMBOL) }
            }
            Spacer(Modifier.size(8.dp))

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val loadingLabel = stringResource(R.string.loading)
                    when {
                        // Full-screen spinner only for the initial directory load. An in-flight
                        // search keeps the previous results visible (a slim top bar below shows the
                        // progress) instead of blanking the list behind a spinner on every keystroke.
                        state.loading -> CircularProgressIndicator(
                            Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel },
                        )
                        state.error != null -> Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                state.error ?: "",
                                color = MaterialTheme.colorScheme.error,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                            // Retry the operation that actually failed: re-run the search
                            // when one was active (open() would reset the query and drop the
                            // user back into the directory listing), otherwise reload the dir.
                            androidx.compose.material3.TextButton(
                                onClick = { if (state.isSearching) vm.setQuery(state.query) else vm.open(state.path) },
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        state.mode == SearchMode.TEXT ->
                            TextResults(
                                state.textResults,
                                searchEmptyMessage(state.searching, state.query.isBlank(), R.string.search_contents_hint),
                            ) { path, line -> onOpenFile(path, line) }
                        state.mode == SearchMode.SYMBOL ->
                            SymbolResults(
                                state.symbolResults,
                                searchEmptyMessage(state.searching, state.query.isBlank(), R.string.search_symbols_hint),
                            ) { path, line -> onOpenFile(path, line) }
                        state.isSearching -> SearchResults(state.results) { onOpenFile(it, null) }
                        else -> DirectoryListing(
                            state = state,
                            onOpenDir = vm::open,
                            onUp = vm::up,
                            onOpenFile = { onOpenFile(it, null) },
                        )
                    }
                    // Slim progress bar for an in-flight search; the results list stays visible
                    // underneath so incremental typing doesn't flash an empty screen each keystroke.
                    if (state.searching) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .semantics { contentDescription = loadingLabel },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val segments = if (path.isBlank()) emptyList() else path.trim('/').split('/').filter { it.isNotEmpty() }
    val scrollState = rememberScrollState()
    // Scroll to the deepest segment when the path changes so the current directory stays in
    // view instead of hiding off the right edge behind the earlier segments on a deep path.
    LaunchedEffect(path) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onNavigate("") }) {
            Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.root), modifier = Modifier.size(24.dp))
        }
        var acc = ""
        segments.forEachIndexed { index, segment ->
            acc = if (acc.isEmpty()) segment else "$acc/$segment"
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val target = acc
            // Cap each segment's width so a single long folder name (e.g. a lengthy monorepo
            // directory) doesn't push its siblings and the current folder off the screeen.
            // The deepest segment gets a larger cap since it's the one the user navigated to
            // and the auto-scroll keeps it in view.
            val segMaxWidth = if (index == segments.lastIndex) 220.dp else 120.dp
            Text(
                segment,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = segMaxWidth)
                    .clickable(role = Role.Button) { onNavigate(target) }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun SearchResults(results: List<String>, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    if (results.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Search,
            message = stringResource(R.string.no_matches),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Key on path + index (not the bare path): a duplicate path from findFiles would
        // otherwise crash LazyColumn with "Key already used". Mirrors TextResults/SymbolResults.
        itemsIndexed(results, key = { i, p -> "$p:$i" }) { _, path ->
            // Split the path into directory (muted) + filename (emphasized) so results
            // are scannable instead of a wall of identical-looking full paths.
            val dir = path.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
            val name = path.substringAfterLast('/')
            FileRow(
                icon = false,
                label = name,
                sublabel = dir.takeIf { it.isNotEmpty() },
                onClick = { onOpenFile(path) },
                onCopyPath = {
                    copyToClipboard(context, context.getString(R.string.clip_label_path), path)
                },
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** Empty-state message for a content/symbol search: a "type to search" hint before anything
 *  is typed, nothing while a search is in flight (the top progress bar covers that, so the
 *  first keystroke doesn't flash a false "No matches"), and "No matches" only once a real
 *  query genuinely returned nothing. */
@Composable
private fun searchEmptyMessage(searching: Boolean, queryBlank: Boolean, hint: Int): String? = when {
    searching -> null
    queryBlank -> stringResource(hint)
    else -> stringResource(R.string.no_matches)
}

/** Content (ripgrep) search results: file + line number, with the matched line highlighted. */
@Composable
private fun TextResults(results: List<FindMatch>, emptyMessage: String?, onOpen: (String, Int?) -> Unit) {
    if (results.isEmpty()) {
        if (emptyMessage != null) {
            EmptyFileState(
                icon = Icons.Filled.Search,
                message = emptyMessage,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "__count") {
            Text(
                pluralStringResource(R.plurals.file_search_results, results.size, results.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { heading() },
            )
        }
        itemsIndexed(results, key = { i, m -> "${m.filePath}:${m.lineNumber}:$i" }) { _, match ->
            val name = match.filePath.substringAfterLast('/')
            val dir = match.filePath.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
            val highlighted = remember(match) { highlightMatchLine(match) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(match.filePath, match.lineNumber) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        ":${match.lineNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (dir.isNotEmpty()) {
                    Text(
                        dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    highlighted,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            HorizontalDivider()
        }
    }
}

/** Workspace symbol results: symbol name + kind, and the file:line it's defined at. */
@Composable
private fun SymbolResults(results: List<SymbolResult>, emptyMessage: String?, onOpen: (String, Int?) -> Unit) {
    if (results.isEmpty()) {
        if (emptyMessage != null) {
            EmptyFileState(
                icon = Icons.Filled.Search,
                message = emptyMessage,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "__count") {
            Text(
                pluralStringResource(R.plurals.file_search_results, results.size, results.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { heading() },
            )
        }
        itemsIndexed(results, key = { i, s -> "${s.filePath}:${s.name}:$i" }) { _, symbol ->
            val fileName = symbol.filePath.substringAfterLast('/')
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(symbol.filePath, symbol.displayLine) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        symbol.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        symbolKindLabel(symbol.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    "$fileName:${symbol.displayLine}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
        }
    }
}

/** Build the matched line with each ripgrep submatch bolded. Offsets are byte columns
 *  within the line; clamped to the (trimmed) line length so a multibyte line can't crash. */
private fun highlightMatchLine(match: FindMatch): androidx.compose.ui.text.AnnotatedString {
    val line = match.lineText
    return buildAnnotatedString {
        var cursor = 0
        for (sub in match.submatches.sortedBy { it.start }) {
            val start = sub.start.coerceIn(0, line.length)
            val end = sub.end.coerceIn(start, line.length)
            if (start > cursor) append(line.substring(cursor, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(start, end)) }
            cursor = end
        }
        if (cursor < line.length) append(line.substring(cursor))
    }
}

@Composable
private fun DirectoryListing(
    state: FileBrowserState,
    onOpenDir: (String) -> Unit,
    onUp: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    if (state.entries.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Folder,
            message = stringResource(R.string.empty_folder),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    // Sort preference for the directory listing. Folders-first + name sort is the most useful
    // default; the toggle flips between folders-first name sort and the server's raw order.
    var foldersFirst by rememberSaveable { mutableStateOf(true) }
    // Hidden-files preference. The server may already include dotfiles in its listing; this
    // hides them by default (the common case for browsing a repo) and lets the user reveal
    // .env / .git / .opencode on demand. Default off so the listing isn't cluttered.
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val visible = remember(state.entries, showHidden) {
        if (showHidden) state.entries else state.entries.filterNot { it.name.startsWith(".") }
    }
    val sorted = remember(visible, foldersFirst) {
        if (!foldersFirst) visible
        else {
            val (dirs, files) = visible.partition { it.isDirectory }
            dirs.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.path.isNotBlank()) {
            item(key = "__up") {
                FileRow(icon = true, label = "..", onClick = onUp)
                HorizontalDivider()
            }
        }
        item(key = "__sort") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.FilterChip(
                    selected = showHidden,
                    onClick = { showHidden = !showHidden },
                    label = { Text(stringResource(R.string.show_hidden), style = MaterialTheme.typography.labelSmall) },
                )
                androidx.compose.material3.FilterChip(
                    selected = foldersFirst,
                    onClick = { foldersFirst = !foldersFirst },
                    leadingIcon = {
                        if (foldersFirst) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    label = { Text(stringResource(R.string.sort_name), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        items(sorted, key = { it.path + "_" + it.name }) { node ->
            FileRow(
                icon = node.isDirectory,
                label = node.name,
                onClick = { if (node.isDirectory) onOpenDir(node.path) else onOpenFile(node.path) },
                onCopyPath = {
                    copyToClipboard(context, context.getString(R.string.clip_label_path), node.path)
                },
                status = state.statusMap[node.path],
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider()
        }
    }
}

/** Shared empty-state for the file browser. Delegates to the canonical [EmptyState] so every
 *  screen's "nothing here" reads and behaves identically (icon size, spacing, styling). */
@Composable
private fun EmptyFileState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String, modifier: Modifier = Modifier) {
    EmptyState(icon = icon, title = message, modifier = modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    icon: Boolean,
    label: String,
    onClick: () -> Unit,
    status: FileStatusEntry? = null,
    sublabel: String? = null,
    onCopyPath: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val copyPathLabel = stringResource(R.string.copy_path)
    // Long-press → "Copy path" dropdown. Rendered only when [onCopyPath] is supplied, so the
    // parent ".." row (which passes none) stays a plain tap target. Anchored within the row.
    var menu by remember { mutableStateOf(false) }
    val fileDesc = if (label == "..") stringResource(R.string.parent_dir)
        else if (icon) stringResource(R.string.folder, label)
        else stringResource(R.string.file_label, label)
    // Build a combined description so TalkBack announces both the file name and its git
    // status. The parent sets an explicit contentDescription (mergeDescendants), which
    // would otherwise suppress the StatusBadge's own description.
    val statusDesc = status?.let {
        when (it.status) {
            "added" -> stringResource(R.string.git_added)
            "modified" -> stringResource(R.string.git_modified)
            "deleted" -> stringResource(R.string.git_deleted)
            else -> ""
        }
    }.orEmpty()
    val fullDesc = if (statusDesc.isNotEmpty()) "$fileDesc, $statusDesc" else fileDesc
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onCopyPath?.let { { menu = true } },
                onLongClickLabel = onCopyPath?.let { copyPathLabel },
            )
            .semantics(mergeDescendants = true) { contentDescription = fullDesc }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (icon) Icons.Filled.Folder else iconForFile(label),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sublabel != null) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (status != null) StatusBadge(status)
        // Explicit overflow button so "Copy path" is reachable without a long-press (which
        // isn't available to TalkBack/keyboard users or anyone who can't hold the gesture).
        // Rendered only when the row supplies an onCopyPath (the parent ".." row does not).
        if (onCopyPath != null) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                text = { Text(copyPathLabel) },
                onClick = { menu = false; onCopyPath?.invoke() },
            )
        }
    }
}

@Composable
private fun StatusBadge(status: FileStatusEntry) {
    val (letter, color) = when (status.status) {
        "added" -> "A" to MaterialTheme.colorScheme.primary
        "modified" -> "M" to MaterialTheme.colorScheme.tertiary
        "deleted" -> "D" to MaterialTheme.colorScheme.error
        else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        if (status.added > 0 || status.removed > 0) {
            Text(
                "+${status.added} −${status.removed}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Pick an icon for a file by its extension so the directory listing is scannable instead of a
 *  wall of identical `Description` glyphs: images, source code, and Markdown/article files each
 *  get a distinct glyph; unknown extensions fall back to the generic document icon. */
private fun iconForFile(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic" -> Icons.Filled.Image
        "kt", "kts", "java", "js", "ts", "jsx", "tsx", "py", "rb", "go", "rs", "c", "cpp", "h",
        "hpp", "cs", "swift", "scala", "gradle", "groovy", "sh", "bash", "zsh", "fish", "ps1",
        "lua", "r", "dart", "php", "pl", "sql", "vue", "svelte" -> Icons.Filled.Code
        "md", "markdown", "txt", "rst", "adoc", "rtf", "doc", "docx", "pdf", "epub" -> Icons.Filled.Article
        else -> Icons.Filled.Description
    }
}
