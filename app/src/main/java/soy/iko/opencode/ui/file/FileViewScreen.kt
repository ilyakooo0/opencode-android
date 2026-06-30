package soy.iko.opencode.ui.file

import android.content.Intent
import soy.iko.opencode.ui.components.showToast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.DiffView
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewScreen(
    container: AppContainer,
    path: String,
    onBack: () -> Unit,
) {
    val vm: FileViewModel = viewModel(factory = vmFactory { FileViewModel(container, path) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filename = remember(path) { path.substringAfterLast('/') }
    val shareLabel = stringResource(R.string.share)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val content = state.content?.content.orEmpty()
                    if (content.isNotEmpty()) {
                        IconButton(onClick = { copyToClipboard(context, filename, content) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy))
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, filename)
                                putExtra(Intent.EXTRA_TEXT, content)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, shareLabel)) }
                                .onFailure {
                                    showToast(context, context.getString(R.string.no_share_app))
                                }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    val loadingLabel = stringResource(R.string.loading)
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel },
                    )
                }
                state.error != null -> Text(
                    state.error ?: "",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                state.content?.isBinary == true -> Text(
                    stringResource(R.string.binary_file),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val content = state.content
                    if (content?.diff != null && content.diff.isNotBlank()) {
                        DiffView(
                            diff = content.diff,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    } else {
                        val text = content?.content.orEmpty()
                        if (text.isEmpty()) {
                            Text(
                                stringResource(R.string.empty_file),
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FileTextContent(text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTextContent(text: String) {
    val lines = remember(text) { text.split("\n") }
    val gutterWidth = remember(lines.size) {
        (lines.size.toString().length.coerceAtLeast(3) * 10).dp
    }
    val hScrollState = rememberScrollState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            Row(modifier = Modifier.horizontalScroll(hScrollState)) {
                Text(
                    "${index + 1}",
                    modifier = Modifier.width(gutterWidth),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    line,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
