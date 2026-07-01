package soy.iko.opencode.ui.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    container: AppContainer,
    onOpenFile: (String) -> Unit,
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
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("file_search"),
                placeholder = { Text(stringResource(R.string.search_files)) },
                label = { Text(stringResource(R.string.search_files)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

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
                            androidx.compose.material3.TextButton(onClick = { vm.open(state.path) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        state.isSearching -> SearchResults(state.results, onOpenFile)
                        else -> DirectoryListing(
                            state = state,
                            onOpenDir = vm::open,
                            onUp = vm::up,
                            onOpenFile = onOpenFile,
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
                    .padding(horizontal = 6.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun SearchResults(results: List<String>, onOpenFile: (String) -> Unit) {
    if (results.isEmpty()) {
        Text(stringResource(R.string.no_matches), modifier = Modifier.padding(24.dp))
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
            )
            HorizontalDivider()
        }
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
        Text(
            stringResource(R.string.empty_folder),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileRow(
    icon: Boolean,
    label: String,
    onClick: () -> Unit,
    status: FileStatusEntry? = null,
    sublabel: String? = null,
) {
    val desc = if (label == "..") stringResource(R.string.parent_dir)
        else if (icon) stringResource(R.string.folder, label)
        else stringResource(R.string.file_label, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc }
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
    val (letter, color, desc) = when (status.status) {
        "added" -> Triple("A", MaterialTheme.colorScheme.primary, stringResource(R.string.git_added))
        "modified" -> Triple("M", MaterialTheme.colorScheme.tertiary, stringResource(R.string.git_modified))
        "deleted" -> Triple("D", MaterialTheme.colorScheme.error, stringResource(R.string.git_deleted))
        else -> Triple("·", MaterialTheme.colorScheme.onSurfaceVariant, "")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            if (desc.isNotEmpty()) contentDescription = desc
        },
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
