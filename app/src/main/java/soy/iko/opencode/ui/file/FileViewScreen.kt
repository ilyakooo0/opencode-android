package soy.iko.opencode.ui.file

import android.content.Intent
import soy.iko.opencode.ui.components.showToast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import soy.iko.opencode.ui.components.highlightLine
import soy.iko.opencode.ui.components.rememberHighlightPalette
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

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
    // Trim a trailing slash so "dir/" doesn't yield an empty filename; fall back to the
    // raw path if there's no basename at all (e.g. the root "/").
    val filename = remember(path) {
        val trimmed = path.trimEnd('/')
        trimmed.substringAfterLast('/').ifBlank { path.ifBlank { "/" } }
    }
    val shareLabel = stringResource(R.string.share)
    // Diff vs raw view toggle. Defaults to showing the diff when one exists (the common
    // case a developer opens a file for); the user can switch to the raw new content to
    // see the full file without +/- markers. Persisted across recomposition via
    // rememberSaveable so a rotation doesn't reset the user's choice.
    val hasDiff = state.content?.diff != null && state.content?.diff?.isNotBlank() == true
    var showDiff by rememberSaveable(path) { mutableStateOf(true) }
    val showToggle = hasDiff && state.content?.content.orEmpty().isNotEmpty()

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
                    // In-place refresh: a file's content can change on the server (the
                    // agent may be editing it), so reload without requiring a back-out.
                    // Disabled while loading to avoid stacking parallel fetches.
                    IconButton(onClick = { vm.reload() }, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
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
                            runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
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
            // AnimatedContent cross-fades between the loading / error / content states
            // so the viewer doesn't snap abruptly when a reload finishes or fails. The
            // transition is short (180ms) and fade-only to avoid a slide that would
            // disorient when the content is the same file just re-fetched.
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "fileViewState",
            ) { s ->
                when {
                    s.loading -> {
                        val loadingLabel = stringResource(R.string.loading)
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel },
                        )
                    }
                    s.error != null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            s.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(12.dp))
                        TextButton(onClick = { vm.reload() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    s.content?.isBinary == true -> Text(
                        stringResource(R.string.binary_file),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val content = s.content
                        // Diff/raw toggle: when both a diff and raw content are present,
                        // let the user switch. The chip row sits above the content so
                        // it's always reachable while scrolling.
                        if (showToggle) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = showDiff,
                                    onClick = { showDiff = true },
                                    label = { Text(stringResource(R.string.show_diff)) },
                                )
                                FilterChip(
                                    selected = !showDiff,
                                    onClick = { showDiff = false },
                                    label = { Text(stringResource(R.string.show_raw)) },
                                )
                            }
                        }
                        if (showDiff && content?.diff != null && content.diff.isNotBlank()) {
                            DiffView(
                                diff = content.diff,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = if (showToggle) 48.dp else 0.dp)
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
                                FileTextContent(
                                    text = text,
                                    filename = filename,
                                    topInset = if (showToggle) 48.dp else 0.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTextContent(text: String, filename: String, topInset: androidx.compose.ui.unit.Dp) {
    val lines = remember(text) { text.split("\n") }
    val gutterWidth = remember(lines.size) {
        (lines.size.toString().length.coerceAtLeast(3) * 10).dp
    }
    val hScrollState = rememberScrollState()
    val palette = rememberHighlightPalette()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset)
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
                // Heuristic syntax highlighting per line. Falls back to plain text for
                // unknown extensions, so non-code files render unchanged.
                Text(
                    highlightLine(line, filename, palette),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
