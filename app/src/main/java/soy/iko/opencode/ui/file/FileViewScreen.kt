package soy.iko.opencode.ui.file

import android.content.Intent
import soy.iko.opencode.ui.components.showToast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
    val filename = remember(path) {
        val trimmed = path.trimEnd('/')
        trimmed.substringAfterLast('/').ifBlank { path.ifBlank { "/" } }
    }
    val shareLabel = stringResource(R.string.share)
    // Diff vs raw view toggle. Defaults to showing the diff when one exists; the user can
    // switch to the raw new content. Persisted via rememberSaveable across rotation.
    val hasDiff = state.content?.diff != null && state.content?.diff?.isNotBlank() == true
    var showDiff by rememberSaveable(path) { mutableStateOf(true) }
    val showToggle = hasDiff && state.content?.content.orEmpty().isNotEmpty()
    // Find-in-file and line-wrapping state, persisted so a rotation or reload keeps the
    // user's query/mode.
    var findActive by rememberSaveable(path) { mutableStateOf(false) }
    var findQuery by rememberSaveable(path) { mutableStateOf("") }
    var matchPos by rememberSaveable(path) { mutableIntStateOf(0) }
    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rawText = state.content?.content.orEmpty()
    val lines = remember(rawText) { rawText.split("\n") }
    val matchIndices = remember(lines, findQuery) {
        val q = findQuery.trim()
        if (q.isEmpty()) emptyList()
        else lines.mapIndexed { index, line -> if (line.contains(q, ignoreCase = true)) index else -1 }
            .filter { it >= 0 }
    }
    // Scroll to the current match whenever it (or the match set) changes.
    LaunchedEffect(matchPos, matchIndices) {
        matchIndices.getOrNull(matchPos)?.let { idx -> runCatchingCancellable { listState.animateScrollToItem(idx) } }
    }

    Scaffold(
        topBar = {
            FileViewTopBar(
                filename = filename,
                loading = state.loading,
                content = rawText,
                wrap = wrap,
                showDiff = showDiff,
                onBack = onBack,
                onReload = { vm.reload() },
                onToggleFind = { findActive = !findActive; if (!findActive) findQuery = "" },
                onToggleWrap = { wrap = !wrap },
                onCopy = { copyToClipboard(context, filename, rawText) },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, filename)
                        putExtra(Intent.EXTRA_TEXT, rawText)
                    }
                    runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                        .onFailure { showToast(context, context.getString(R.string.no_share_app)) }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // AnimatedContent cross-fades between loading / error / content states so the
            // viewer doesn't snap abruptly when a reload finishes or fails.
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "fileViewState",
            ) { s ->
                FileViewStateContent(
                    state = s,
                    filename = filename,
                    showToggle = showToggle,
                    showDiff = showDiff,
                    onSetShowDiff = {
                        showDiff = it
                        // Close any open find bar when entering diff mode: find searches
                        // the raw content and scrolls the line LazyColumn, neither of
                        // which is visible in diff mode, so leaving it open would be a
                        // confusing no-op.
                        if (it) { findActive = false; findQuery = "" }
                    },
                    findActive = findActive,
                    findQuery = findQuery,
                    onQueryChange = { findQuery = it; matchPos = 0 },
                    matchIndices = matchIndices,
                    matchPos = matchPos,
                    onPrev = { if (matchIndices.isNotEmpty()) matchPos = (matchPos - 1 + matchIndices.size) % matchIndices.size },
                    onNext = { if (matchIndices.isNotEmpty()) matchPos = (matchPos + 1) % matchIndices.size },
                    onCloseFind = { findActive = false; findQuery = "" },
                    wrap = wrap,
                    listState = listState,
                    onRetry = { vm.reload() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewTopBar(
    filename: String,
    loading: Boolean,
    content: String,
    wrap: Boolean,
    showDiff: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleFind: () -> Unit,
    onToggleWrap: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    TopAppBar(
        title = { Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            // In-place refresh: a file's content can change on the server (the agent may
            // be editing it), so reload without requiring a back-out.
            IconButton(onClick = onReload, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            if (content.isNotEmpty()) {
                // Find-in-file: toggles a search bar over the raw content. Disabled in
                // diff mode — the find bar searches the raw text and scrolls the line
                // LazyColumn, neither of which is visible when the DiffView is shown, so
                // find would silently no-op. The user can switch to Raw to find.
                IconButton(onClick = onToggleFind, enabled = !showDiff) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.find_in_file))
                }
                // Wrap long lines instead of horizontal-scrolling, useful for prose.
                IconButton(onClick = onToggleWrap) {
                    Icon(
                        Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = stringResource(R.string.wrap_lines),
                        tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                }
            }
        },
    )
}

@Composable
private fun BoxScope.FileViewStateContent(
    state: FileViewState,
    filename: String,
    showToggle: Boolean,
    showDiff: Boolean,
    onSetShowDiff: (Boolean) -> Unit,
    findActive: Boolean,
    findQuery: String,
    onQueryChange: (String) -> Unit,
    matchIndices: List<Int>,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCloseFind: () -> Unit,
    wrap: Boolean,
    listState: LazyListState,
    onRetry: () -> Unit,
) {
    when {
        state.loading -> {
            val loadingLabel = stringResource(R.string.loading)
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel },
            )
        }
        state.error != null -> Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        state.content?.isBinary == true -> Text(
            stringResource(R.string.binary_file),
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> FileViewContentBody(
            content = state.content,
            filename = filename,
            showToggle = showToggle,
            showDiff = showDiff,
            onSetShowDiff = onSetShowDiff,
            findActive = findActive,
            findQuery = findQuery,
            onQueryChange = onQueryChange,
            matchIndices = matchIndices,
            matchPos = matchPos,
            onPrev = onPrev,
            onNext = onNext,
            onCloseFind = onCloseFind,
            wrap = wrap,
            listState = listState,
        )
    }
}

@Composable
private fun BoxScope.FileViewContentBody(
    content: soy.iko.opencode.data.model.FileContent?,
    filename: String,
    showToggle: Boolean,
    showDiff: Boolean,
    onSetShowDiff: (Boolean) -> Unit,
    findActive: Boolean,
    findQuery: String,
    onQueryChange: (String) -> Unit,
    matchIndices: List<Int>,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCloseFind: () -> Unit,
    wrap: Boolean,
    listState: LazyListState,
) {
    // Overlay bar: stacks the diff/raw chips and the find bar at the top so both stay
    // reachable while scrolling. Each contributes a top inset so the content below
    // isn't hidden behind the overlay. The inset is measured from the actual overlay
    // height (not a hardcoded 52dp/row) so accessibility font scaling — which can
    // make the FilterChip labels and FindBar text field taller than 52dp — doesn't
    // cause the first lines of content to hide behind the overlay.
    val overlayRows = (if (showToggle) 1 else 0) + (if (findActive && !showDiff) 1 else 0)
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val topInset = with(androidx.compose.ui.platform.LocalDensity.current) { overlayHeightPx.toDp() }
    if (overlayRows > 0) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { overlayHeightPx = it.height }
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showToggle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    FilterChip(
                        selected = showDiff,
                        onClick = { onSetShowDiff(true) },
                        label = { Text(stringResource(R.string.show_diff)) },
                    )
                    Spacer(Modifier.size(8.dp))
                    FilterChip(
                        selected = !showDiff,
                        onClick = { onSetShowDiff(false) },
                        label = { Text(stringResource(R.string.show_raw)) },
                    )
                }
            }
            if (findActive && !showDiff) {
                FindBar(
                    query = findQuery,
                    onQueryChange = onQueryChange,
                    matchCount = matchIndices.size,
                    matchPos = matchPos,
                    onPrev = onPrev,
                    onNext = onNext,
                    onClose = onCloseFind,
                )
            }
        }
    }
    if (showDiff && content?.diff != null && content.diff.isNotBlank()) {
        DiffView(
            diff = content.diff,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset)
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
                topInset = topInset,
                wrap = wrap,
                findQuery = findQuery,
                matchIndices = matchIndices,
                listState = listState,
            )
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.find_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            trailingIcon = {
                val countText = if (query.isBlank()) ""
                else if (matchCount == 0) stringResource(R.string.no_matches_in_file)
                else stringResource(R.string.match_count, matchPos + 1, matchCount)
                if (countText.isNotEmpty()) {
                    // Use error color for "no matches" so the zero-result state is
                    // distinguishable from a positive match count at a glance.
                    val color = if (query.isNotBlank() && matchCount == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        countText,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            },
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous))
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
private fun FileTextContent(
    text: String,
    filename: String,
    topInset: androidx.compose.ui.unit.Dp,
    wrap: Boolean,
    findQuery: String,
    matchIndices: List<Int>,
    listState: LazyListState,
) {
    val lines = remember(text) { text.split("\n") }
    // Gutter width scales with the digit count and the font scale so accessibility
    // text scaling (e.g. 1.3x) doesn't make line numbers overflow the gutter and
    // collide with the code text. 10dp/digit covers bodySmall at 1.0x; scale up
    // proportionally for larger font scales.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val gutterWidth = remember(lines.size, fontScale) {
        ((lines.size.toString().length.coerceAtLeast(3) * 10) * fontScale.coerceAtLeast(1f)).dp
    }
    val hScrollState = rememberScrollState()
    val palette = rememberHighlightPalette()
    val q = findQuery.trim()
    val matchSet = remember(matchIndices) { matchIndices.toHashSet() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset)
            .padding(8.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val isMatch = q.isNotEmpty() && index in matchSet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(hScrollState))
                    .then(if (isMatch) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
            ) {
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
