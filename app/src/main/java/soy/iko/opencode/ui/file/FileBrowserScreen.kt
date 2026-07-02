package soy.iko.opencode.ui.file

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.data.model.FindMatch
import soy.iko.opencode.data.model.SymbolResult
import soy.iko.opencode.data.model.symbolKindLabel
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.vmFactory

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
            TopAppBar(
                title = { Text(stringResource(R.string.files), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tappable breadcrumb trail so deep paths are navigable.
            Breadcrumbs(
                path = state.path,
                onNavigate = vm::open,
                modifier = Modifier.fillMaxWidth(),
            )
            val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).testTag("file_search"),
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
                    when {
                        state.loading || state.searching -> {
                            val loadingLabel = stringResource(R.string.loading)
                            CircularProgressIndicator(
                                Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel },
                            )
                        }
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
                            TextResults(state.textResults) { path, line -> onOpenFile(path, line) }
                        state.mode == SearchMode.SYMBOL ->
                            SymbolResults(state.symbolResults) { path, line -> onOpenFile(path, line) }
                        state.isSearching -> SearchResults(state.results) { onOpenFile(it, null) }
                        else -> DirectoryListing(
                            state = state,
                            onOpenDir = vm::open,
                            onUp = vm::up,
                            onOpenFile = { onOpenFile(it, null) },
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
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
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
            Text(
                segment,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button) { onNavigate(target) }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun SearchResults(results: List<String>, onOpenFile: (String) -> Unit) {
    if (results.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Search,
            message = stringResource(R.string.no_matches),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it }) { path ->
            // Split the path into directory (muted) + filename (emphasized) so results
            // are scannable instead of a wall of identical-looking full paths.
            val dir = path.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
            val name = path.substringAfterLast('/')
            FileRow(
                icon = false,
                label = name,
                sublabel = dir.takeIf { it.isNotEmpty() },
                onClick = { onOpenFile(path) },
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

/** Content (ripgrep) search results: file + line number, with the matched line highlighted. */
@Composable
private fun TextResults(results: List<FindMatch>, onOpen: (String, Int?) -> Unit) {
    if (results.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Search,
            message = stringResource(R.string.no_matches),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
private fun SymbolResults(results: List<SymbolResult>, onOpen: (String, Int?) -> Unit) {
    if (results.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Search,
            message = stringResource(R.string.no_matches),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    if (state.entries.isEmpty()) {
        EmptyFileState(
            icon = Icons.Filled.Folder,
            message = stringResource(R.string.empty_folder),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.path.isNotBlank()) {
            item(key = "__up") {
                FileRow(icon = true, label = "..", onClick = onUp)
                HorizontalDivider()
            }
        }
        items(state.entries, key = { it.path + "_" + it.name }) { node ->
            FileRow(
                icon = node.isDirectory,
                label = node.name,
                onClick = { if (node.isDirectory) onOpenDir(node.path) else onOpenFile(node.path) },
                status = state.statusMap[node.path],
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider()
        }
    }
}

/** Shared empty-state for the file browser: icon + message, matching the icon+text
 *  pattern used by the session and server lists (the prior versions were bare Text
 *  lines that read as status messages rather than intentional empty states). */
@Composable
private fun EmptyFileState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    icon: Boolean,
    label: String,
    onClick: () -> Unit,
    status: FileStatusEntry? = null,
    sublabel: String? = null,
    modifier: Modifier = Modifier,
) {
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
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = fullDesc }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (icon) Icons.Filled.Folder else Icons.Filled.Description,
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
