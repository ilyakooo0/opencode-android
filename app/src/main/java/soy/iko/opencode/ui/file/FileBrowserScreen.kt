package soy.iko.opencode.ui.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.di.AppContainer
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.path.isBlank()) "Files" else state.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Search files…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading || state.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null && !state.isSearching -> Text(
                        state.error!!,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
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

@Composable
private fun SearchResults(results: List<String>, onOpenFile: (String) -> Unit) {
    if (results.isEmpty()) {
        Text("No matches.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it }) { path ->
            FileRow(icon = false, label = path, onClick = { onOpenFile(path) })
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.path.isNotBlank()) {
            item(key = "__up") {
                FileRow(icon = true, label = "..", onClick = onUp)
                HorizontalDivider()
            }
        }
        items(state.entries, key = { it.path }) { node ->
            FileRow(
                icon = node.isDirectory,
                label = node.name,
                onClick = { if (node.isDirectory) onOpenDir(node.path) else onOpenFile(node.path) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileRow(icon: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (icon) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "  $label",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
