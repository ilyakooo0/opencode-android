package soy.iko.opencode.ui.file

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBannerFor
import soy.iko.opencode.ui.components.DiffView
import soy.iko.opencode.ui.components.LocalChatTextScale
import soy.iko.opencode.ui.components.LocalCodeWrap
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.highlightLine
import soy.iko.opencode.ui.components.syntaxFor
import soy.iko.opencode.ui.components.rememberHighlightPalette
import soy.iko.opencode.ui.components.scaledBy
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import soy.iko.opencode.data.network.NetworkConfig

/** Cap on how many lines the raw viewer renders and searches. See [NetworkConfig.maxRenderedFileLines]. */
private const val MAX_RENDERED_LINES = NetworkConfig.maxRenderedFileLines

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewScreen(
    container: AppContainer,
    path: String,
    onBack: () -> Unit,
    initialLine: Int? = null,
) {
    val vm: FileViewModel = viewModel(factory = vmFactory { FileViewModel(container, path) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val filename = remember(path) {
        val trimmed = path.trimEnd('/')
        trimmed.substringAfterLast('/').ifBlank { path.ifBlank { "/" } }
    }
    val shareLabel = stringResource(R.string.share)
    // Diff vs raw view toggle. Defaults to showing the diff when one exists; the user can
    // switch to the raw new content. Persisted via rememberSaveable across rotation. When the
    // viewer is opened at a specific line (from a search hit), start on the raw view so the
    // targeted line is actually rendered (the diff view has no line list to scroll).
    val hasDiff = state.content?.diff != null && state.content?.diff?.isNotBlank() == true
    var showDiff by rememberSaveable(path) { mutableStateOf(initialLine == null) }
    val showToggle = hasDiff && state.content?.content.orEmpty().isNotEmpty()
    // Find-in-file and line-wrapping state, persisted so a rotation or reload keeps the
    // user's query/mode.
    var findActive by rememberSaveable(path) { mutableStateOf(false) }
    var findQuery by rememberSaveable(path) { mutableStateOf("") }
    var matchPos by rememberSaveable(path) { mutableIntStateOf(0) }
    // Seed the per-file wrap toggle from the user's global code-wrap preference so the viewer
    // respects it by default; they can still flip it per-file (persisted across rotation).
    val codeWrap = LocalCodeWrap.current
    var wrap by rememberSaveable(path) { mutableStateOf(codeWrap) }
    val listState = rememberLazyListState()
    val rawText = state.content?.content.orEmpty()
    // Directory portion of the path, shown under the filename in the top bar so a user who
    // has drilled into a deep workspace keeps context of where this file lives.
    val pathSubtitle = remember(path) {
        val trimmed = path.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        parent.ifBlank { path }
    }
    // Split the raw text exactly once here (the line viewer and find-in-file both consume this
    // list) and cap it so a huge file doesn't get split twice or overwhelm the LazyColumn.
    val allLines = remember(rawText) { rawText.split("\n") }
    val truncated = allLines.size > MAX_RENDERED_LINES
    val lines = remember(allLines) {
        if (allLines.size > MAX_RENDERED_LINES) allLines.subList(0, MAX_RENDERED_LINES) else allLines
    }
    // The line the viewer was opened at (from a search hit), highlighted until the user scrolls
    // or starts an in-file find so the landed line stands out. Null once cleared.
    val highlightLine = rememberJumpToLineHighlight(
        path = path,
        initialLine = initialLine,
        content = state.content,
        isBinary = state.content?.isBinary == true,
        lineCount = lines.size,
        listState = listState,
    )
    // Find-in-file match-case toggle (default case-insensitive). Persisted so a rotation keeps
    // the choice, and applied to the off-thread match scan below.
    var caseSensitive by rememberSaveable(path) { mutableStateOf(false) }
    // Debounce the query so each keystroke doesn't trigger a full-file scan, and run the
    // scan on Dispatchers.Default so a large file doesn't jank the keyboard while typing.
    var debouncedFind by remember { mutableStateOf("") }
    LaunchedEffect(findQuery) {
        val q = findQuery.trim()
        if (q.isEmpty()) debouncedFind = "" else { delay(150); debouncedFind = q }
    }
    val matchIndices by produceState(emptyList<Int>(), lines, debouncedFind, caseSensitive) {
        val q = debouncedFind
        value = if (q.isEmpty()) emptyList()
        else withContext(Dispatchers.Default) {
            lines.mapIndexedNotNull { index, line ->
                index.takeIf { line.contains(q, ignoreCase = !caseSensitive) }
            }
        }
    }
    // Keep the current match index in range when the match set shrinks (e.g. the file
    // reloaded with fewer matches), so the counter never shows a stale "10 / 3".
    LaunchedEffect(matchIndices.size) {
        matchPos = matchPos.coerceIn(0, (matchIndices.size - 1).coerceAtLeast(0))
    }
    // Scroll to the current match whenever it (or the match set) changes.
    LaunchedEffect(matchPos, matchIndices) {
        matchIndices.getOrNull(matchPos)?.let { idx -> runCatchingCancellable { listState.animateScrollToItem(idx) } }
    }
    // "Go to line" dialog state. Clears the jump highlight and scrolls the LazyColumn to the
    // requested line (clamped to the rendered range) when the user confirms.
    var showGoToLine by rememberSaveable(path) { mutableStateOf(false) }
    fun goToLine(n: Int) {
        val target = (n - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        highlightLine.value = null
        scope.launch { runCatchingCancellable { listState.animateScrollToItem(target) } }
    }
    Scaffold(
        topBar = {
            FileViewTopBar(
                filename = filename,
                subtitle = pathSubtitle,
                loading = state.loading,
                content = rawText,
                wrap = wrap,
                diffShown = showToggle && showDiff,
                onBack = onBack,
                onReload = { vm.reload() },
                // Find searches the raw line list, which isn't on screen under a diff. Rather
                // than silently greying the icon out, keep it tappable and explain where find
                // lives when the user is in diff mode.
                onToggleFind = {
                    if (showToggle && showDiff) {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.switch_to_raw_to_search)) }
                    } else {
                        findActive = !findActive
                        if (!findActive) findQuery = ""
                    }
                },
                onToggleWrap = { wrap = !wrap },
                onGoToLine = { showGoToLine = true },
                onCopy = { copyToClipboard(context, filename, rawText) },
                onCopyPath = { copyToClipboard(context, context.getString(R.string.clip_label_path), path) },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, filename)
                        putExtra(Intent.EXTRA_TEXT, rawText)
                    }
                    runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                        .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.no_share_app)) } }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Pull-to-refresh wraps the content so a user who pull-to-refreshes out of habit
        // can reload the file, mirroring the file browser and session list gestures. The
        // top-bar reload icon remains for a targeted tap.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.loading && state.content != null,
            onRefresh = { vm.reload() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            // Surface SSE connection state so a dropped stream is visible while viewing a file.
            ConnectionBannerFor(container)
            // AnimatedContent cross-fades between loading / error / content states so the
            // viewer doesn't snap abruptly when a reload finishes or fails.
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "fileViewState",
            ) { s ->
                // Single source of truth for matches: the off-thread `matchIndices` produceState
                // (keyed on the file content + debounced query). Both navigation (onPrev/onNext +
                // scroll) and display (counter + highlight) read from it, so the counter can't show
                // a new query while next/prev act on a stale set. The earlier in-composition
                // per-layer scan re-ran the whole-file O(n) match on the UI thread each keystroke
                // (the exact work produceState was moved off-thread to avoid); it's acceptable for
                // the crossfade's exiting layer to briefly use this unified set until it settles.
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
                    onQueryChange = { findQuery = it; matchPos = 0; if (it.isNotEmpty()) highlightLine.value = null },
                    matchIndices = matchIndices,
                    matchPos = matchPos,
                    onPrev = { if (matchIndices.isNotEmpty()) matchPos = (matchPos - 1 + matchIndices.size) % matchIndices.size },
                    onNext = { if (matchIndices.isNotEmpty()) matchPos = (matchPos + 1) % matchIndices.size },
                    onCloseFind = { findActive = false; findQuery = "" },
                    wrap = wrap,
                    lines = lines,
                    truncated = truncated,
                    highlightLineIndex = highlightLine.value,
                    listState = listState,
                    onRetry = { vm.reload() },
                    caseSensitive = caseSensitive,
                    onToggleCaseSensitive = { caseSensitive = !caseSensitive },
                    onShareFull = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, filename)
                            putExtra(Intent.EXTRA_TEXT, rawText)
                        }
                        runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                            .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.no_share_app)) } }
                    },
                )
            }
            }
        }
    }

    GoToLineLauncher(
        visible = showGoToLine,
        maxLine = lines.size,
        onConfirm = { n -> showGoToLine = false; goToLine(n) },
        onDismiss = { showGoToLine = false },
    )
}

// Wraps the go-to-line dialog's visibility gate so the branch stays out of FileViewScreen
// (keeping that composable under the cyclomatic-complexity threshold).
@Composable
private fun GoToLineLauncher(
    visible: Boolean,
    maxLine: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        GoToLineDialog(maxLine = maxLine, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}

@Composable
private fun GoToLineDialog(maxLine: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed >= 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_line)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() } },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = {
                    Text(
                        stringResource(R.string.go_to_line_hint, maxLine),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { parsed?.let { onConfirm(it) } }),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConfirm(it) } }, enabled = valid) {
                Text(stringResource(R.string.go_to_line))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// Owns the "jump to a line from a search hit" behaviour: scroll to the target once the raw
// content loads (one-shot), and clear the highlight the moment the user drags the list by hand.
// Extracted so FileViewScreen stays under the cyclomatic-complexity threshold.
@Composable
private fun rememberJumpToLineHighlight(
    path: String,
    initialLine: Int?,
    content: Any?,
    isBinary: Boolean,
    lineCount: Int,
    listState: LazyListState,
): MutableState<Int?> {
    val highlight = rememberSaveable(path) { mutableStateOf<Int?>(null) }
    var didInitialScroll by rememberSaveable(path) { mutableStateOf(false) }
    LaunchedEffect(content, initialLine) {
        val line = initialLine ?: return@LaunchedEffect
        if (didInitialScroll || content == null || isBinary) return@LaunchedEffect
        val target = (line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        runCatchingCancellable { listState.scrollToItem(target) }
        highlight.value = target
        didInitialScroll = true
    }
    LaunchedEffect(listState) {
        if (initialLine == null) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) highlight.value = null
        }
    }
    return highlight
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewTopBar(
    filename: String,
    subtitle: String,
    loading: Boolean,
    content: String,
    wrap: Boolean,
    diffShown: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleFind: () -> Unit,
    onToggleWrap: () -> Unit,
    onGoToLine: () -> Unit,
    onCopy: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
) {
    TopAppBar(
        title = {
            // Two-line title: filename (primary) + the directory portion of the path
            // (secondary), so a file opened from deep in a workspace keeps its location
            // visible without an extra breadcrumb row.
            Column {
                Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            // In-place refresh: a file's content can change on the server (the agent may
            // be editing it), so reload without requiring a back-out. Shows a spinner while
            // loading since the content now stays on screen during a reload (the spinner is
            // the only progress signal).
            val loadingLabel = stringResource(R.string.loading)
            IconButton(onClick = onReload, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).semantics { contentDescription = loadingLabel },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
            if (content.isNotEmpty()) {
                // Find-in-file: toggles a search bar over the raw content. Stays enabled even in
                // diff mode — the find bar searches the raw text and scrolls the line LazyColumn,
                // neither of which is visible under a diff, so tapping it there surfaces a hint to
                // switch to Raw rather than silently no-opping behind a greyed-out icon.
                IconButton(onClick = onToggleFind) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.find_in_file))
                }
                // Overflow the less-frequent actions (wrap / copy / share) so a long filename
                // title isn't crowded off screen by five inline icons on a phone.
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // Wrap long lines instead of horizontal-scrolling; useful for prose. Disabled
                    // in diff mode because DiffView ignores wrap, so an active-looking toggle there
                    // would be a silent no-op.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.go_to_line)) },
                        onClick = { onGoToLine(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wrap_lines)) },
                        onClick = { onToggleWrap(); menuExpanded = false },
                        enabled = !diffShown,
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = null,
                                tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = { onCopy(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_path)) },
                        onClick = { onCopyPath(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = { onShare(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    )
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
    lines: List<String>,
    truncated: Boolean,
    highlightLineIndex: Int?,
    listState: LazyListState,
    onRetry: () -> Unit,
    onShareFull: () -> Unit,
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
) {
    when {
        // Full-screen spinner only on the initial load (no content yet). A reload keeps the
        // existing content on screen; the top-bar reload button shows the in-flight progress.
        state.loading && state.content == null -> {
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
            lines = lines,
            truncated = truncated,
            highlightLineIndex = highlightLineIndex,
            listState = listState,
            onShareFull = onShareFull,
            caseSensitive = caseSensitive,
            onToggleCaseSensitive = onToggleCaseSensitive,
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
    lines: List<String>,
    truncated: Boolean,
    highlightLineIndex: Int?,
    listState: LazyListState,
    onShareFull: () -> Unit,
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
) {
    // Overlay bar: stacks the diff/raw chips and the find bar at the top so both stay
    // reachable while scrolling. Each contributes a top inset so the content below
    // isn't hidden behind the overlay. The inset is measured from the actual overlay
    // height (not a hardcoded 52dp/row) so accessibility font scaling — which can
    // make the FilterChip labels and FindBar text field taller than 52dp — doesn't
    // cause the first lines of content to hide behind the overlay.
    // The raw text is on screen whenever we're NOT rendering the diff — i.e. either the
    // file has no diff toggle at all (plain file: showToggle == false) or the toggle is
    // set to Raw. Gate the find bar on this rather than on !showDiff: for a plain file
    // showDiff stays at its `true` default forever (nothing ever flips it), so !showDiff
    // would keep the find bar — and therefore find-in-file — permanently hidden.
    val rawOnScreen = !(showToggle && showDiff)
    val overlayRows = (if (showToggle) 1 else 0) + (if (findActive && rawOnScreen) 1 else 0)
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    // Only apply the measured inset while an overlay is actually composed. When overlayRows
    // drops to 0 the overlay Column is removed and onSizeChanged never fires to zero the
    // height, so keying the inset off overlayRows (rather than the stale measurement) avoids
    // a phantom top gap on the content below — e.g. after closing find on a plain file.
    val topInset = if (overlayRows > 0) {
        with(androidx.compose.ui.platform.LocalDensity.current) { overlayHeightPx.toDp() }
    } else 0.dp
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
            if (findActive && rawOnScreen) {
                FindBar(
                    query = findQuery,
                    onQueryChange = onQueryChange,
                    matchCount = matchIndices.size,
                    matchPos = matchPos,
                    onPrev = onPrev,
                    onNext = onNext,
                    onClose = onCloseFind,
                    caseSensitive = caseSensitive,
                    onToggleCaseSensitive = onToggleCaseSensitive,
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
                lines = lines,
                filename = filename,
                topInset = topInset,
                truncated = truncated,
                wrap = wrap,
                findQuery = findQuery,
                matchIndices = matchIndices,
                highlightLineIndex = highlightLineIndex,
                listState = listState,
                onShareFull = onShareFull,
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
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val matchCaseLabel = stringResource(R.string.match_case)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Case-sensitivity toggle. A small "Aa" chip keeps the bar compact while
                    // surfacing a previously substring-only, always-case-insensitive search.
                    FilterChip(
                        selected = caseSensitive,
                        onClick = onToggleCaseSensitive,
                        label = { Text("Aa", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.semantics { contentDescription = matchCaseLabel },
                    )
                    Spacer(Modifier.size(8.dp))
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
                            // labelMedium (not labelSmall) so the counter is legible for low-vision
                            // users at large font scales; the error color still distinguishes zero
                            // matches at a glance.
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                        )
                    }
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
    lines: List<String>,
    filename: String,
    topInset: androidx.compose.ui.unit.Dp,
    truncated: Boolean,
    wrap: Boolean,
    findQuery: String,
    matchIndices: List<Int>,
    highlightLineIndex: Int?,
    listState: LazyListState,
    onShareFull: () -> Unit,
) {
    // Honor the user's chat text-size preference (the same one the chat markdown honors) so
    // a user who bumped the scale for readability gets the same scale in the code viewer.
    // Applied by nesting a MaterialTheme with a scaled typography, exactly how MarkdownText
    // does it — the gutter/code Text below read MaterialTheme.typography.bodySmall, so this
    // reaches both without per-Text plumbing.
    val scale = LocalChatTextScale.current
    val base = MaterialTheme.typography
    val scaled = remember(scale, base) { base.scaledBy(scale) }
    // Gutter width scales with the digit count and the font scale so accessibility
    // text scaling (e.g. 1.3x) and the app's chat-text scale don't make line numbers overflow
    // the gutter and collide with the code text. 10dp/digit covers bodySmall at 1.0x; scale up
    // proportionally for larger font/app scales.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val gutterWidth = remember(lines.size, fontScale, scale) {
        ((lines.size.toString().length.coerceAtLeast(3) * 10) *
            fontScale.coerceAtLeast(1f) *
            scale.coerceAtLeast(1f)).dp
    }
    val hScrollState = rememberScrollState()
    val palette = rememberHighlightPalette()
    // Resolve the file's language once instead of re-parsing the extension for every line.
    val syntax = remember(filename) { syntaxFor(filename) }
    val q = findQuery.trim()
    val matchSet = remember(matchIndices) { matchIndices.toHashSet() }
    MaterialTheme(typography = scaled) {
        Column(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
            if (truncated) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.file_truncated, MAX_RENDERED_LINES),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Escape hatch: when only the first N lines render, offer the (already
                    // full-content) Share action inline so the user isn't stranded without the
                    // rest of the file. The overflow Share shares the same full content.
                    TextButton(onClick = onShareFull) {
                        Text(stringResource(R.string.share_full_file))
                    }
                }
            }
            // SelectionContainer lets the user select/copy a portion of the rendered code
            // (not just the whole-file Copy in the overflow). Selection across recycled
            // LazyColumn items can drop spans that have scrolled away, but partial selection
            // of the visible region — the common case — works.
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                        val isMatch = q.isNotEmpty() && index in matchSet
                        // The jump-to-line target keeps the same emphasis as a find match so the line
                        // the viewer landed on is obvious until the user scrolls or starts a find.
                        val isTarget = index == highlightLineIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScrollState))
                                .then(if (isMatch || isTarget) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
                        ) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.width(gutterWidth),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Heuristic syntax highlighting per line. Falls back to plain text for
                            // unknown extensions, so non-code files render unchanged. Memoized so the
                            // O(n) tokenizer + AnnotatedString allocation runs only when the line text,
                            // file, or palette actually changes — not on every recomposition (e.g. every
                            // keystroke into find-in-file, which would otherwise re-highlight all visible lines).
                            val highlighted = remember(line, syntax, palette) { highlightLine(line, syntax, palette) }
                            Text(
                                highlighted,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
