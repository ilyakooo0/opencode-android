package soy.iko.opencode.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.ui.components.AppTopBar
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.ui.components.LocalRelativeTimeTick
import soy.iko.opencode.ui.components.rememberRelativeTime
import soy.iko.opencode.ui.components.rememberRelativeTimeTick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logger = remember { CrashLogger.get(context) }
    val reports by logger.reports.collectAsStateWithLifecycle()
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearAll by rememberSaveable { mutableStateOf(false) }
    var pendingReportDelete by rememberSaveable { mutableStateOf<String?>(null) }
    // Optional text filter over the crash report previews/file names. Only surfaced once there
    // are enough reports that scrolling is slower than typing.
    var crashQuery by rememberSaveable { mutableStateOf("") }
    val shareScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    // Separate host for transient error messages (share failures, etc.) so a transient error
    // can't preempt an undo snackbar's full window — the undo and error channels don't share
    // a queue, so a share error can't cancel an in-flight undo and strand a pending delete.
    val errorSnackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val reportDeletedLabel = stringResource(R.string.report_deleted)
    val reportsClearedLabel = stringResource(R.string.reports_cleared)
    // Deferred report deletions are owned by the CrashLogger (its process-lived scope),
    // so they survive both rotation and navigating away from this screen. The screen only
    // shows the Undo snackbar and asks the logger to cancel a pending delete on undo.
    val shareLabel = stringResource(R.string.share)
    val shareSubject = stringResource(R.string.crash_report_share_subject)
    val timeTick = rememberRelativeTimeTick()

    // "Export all": let the user save every crash report into a single text file they pick via
    // SAF (CreateDocument). Concatenating avoids needing a zip dependency; each report is
    // delimited so a single file stays readable and shareable for support/debugging.
    val exportAllLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        shareScope.launch {
            val ok = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val all = reports.joinToString("\n\n${"=".repeat(60)}\n\n") { report ->
                        "### ${report.fileName}\n\n" + logger.readReport(report.fileName).orEmpty()
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(all.toByteArray()) }
                        ?: error("no output stream")
                }
            }.isSuccess
            if (ok) snackbar.showSnackbar(context.getString(R.string.export_all))
        }
    }

    // Report-delete undo events, shown via a single collectLatest collector rather than a
    // per-confirm launch: a serialized showSnackbar would queue each report's undo behind the
    // previous one's full window. collectLatest instead cancels the current snackbar and shows
    // the newest immediately. Because the actual delete is scheduled at confirm time (on the
    // CrashLogger's own scope, so it still commits if the user navigates away during the undo
    // window), a superseded report would otherwise be deleted with no reachable Undo; the
    // collector withdraws the previously-pending report's scheduled delete when a newer one
    // supersedes its snackbar, keeping every delete undoable for its whole window.
    val reportDeleteEvents = remember {
        // Buffer capacity matches the snackbar event buffer (NetworkConfig.snackbarEventBufferCapacity).
        // With extraBufferCapacity = 1 + DROP_OLDEST, a rapid 3x delete can drop the MIDDLE
        // event's snackbar while its scheduleDelete has already fired — silently deleting a
        // report with no Undo. A larger buffer lets all pending deletes queue behind the
        // collectLatest collector (which cancels the prior snackbar and withdraws its pending
        // delete), so every delete stays undoable for its whole window.
        MutableSharedFlow<String>(
            extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
    LaunchedEffect(Unit) {
        var pending: String? = null
        reportDeleteEvents.collectLatest { name ->
            // Only reached when a newer delete arrives (collectLatest supersedes the prior
            // snackbar) — navigating away tears down this collector without a new emission, so
            // that report's delete still commits. cancelScheduledDelete no-ops if it already fired.
            pending?.let { logger.cancelScheduledDelete(it) }
            pending = name
            coroutineScope {
                val dismisser = launch {
                    delay(NetworkConfig.undoReportDeleteDelayMs)
                    snackbar.currentSnackbarData?.dismiss()
                }
                val result = snackbar.showSnackbar(
                    message = reportDeletedLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Indefinite,
                )
                dismisser.cancel()
                if (result == SnackbarResult.ActionPerformed) {
                    logger.cancelScheduledDelete(name)
                    pending = null
                }
            }
        }
    }

    // Clear-all undo: defers the bulk delete for the same window as a single-report delete,
    // so the previously-irreversible "Clear all" is now recoverable.
    val clearAllEvents = remember {
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    LaunchedEffect(Unit) {
        clearAllEvents.collectLatest {
            logger.scheduleClearAll(NetworkConfig.undoReportDeleteDelayMs)
            coroutineScope {
                val dismisser = launch {
                    delay(NetworkConfig.undoReportDeleteDelayMs)
                    snackbar.currentSnackbarData?.dismiss()
                }
                val result = snackbar.showSnackbar(
                    message = reportsClearedLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Indefinite,
                )
                dismisser.cancel()
                if (result == SnackbarResult.ActionPerformed) logger.cancelScheduledClearAll()
            }
        }
    }

    // TopAppBar scroll behavior: collapse/raise the app bar as the report list scrolls.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.diagnostics),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (reports.isNotEmpty()) {
                        IconButton(onClick = {
                            runCatching {
                                // Timestamped export name so successive exports don't overwrite
                                // one another when the user exports more than once (e.g. after new
                                // crashes accumulate). Mirrors SettingsScreen.backupFilename().
                                val now = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                    .format(java.util.Date())
                                exportAllLauncher.launch("opencode-crash-reports-$now.txt")
                            }
                        }) {
                            Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.export_all))
                        }
                        IconButton(onClick = {
                            showClearAll = true
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_all))
                        }
                    }
                },
            )
        },
        snackbarHost = {
            // Stack both hosts; the undo snackbar takes priority (rendered first), the error
            // host renders below it so both can be visible simultaneously without preempting.
            Column {
                SnackbarHost(snackbar)
                SnackbarHost(errorSnackbar)
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        Column(modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = soy.iko.opencode.data.network.NetworkConfig.listContentMaxWidthDp.dp).imePadding().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            Text(
                stringResource(R.string.diagnostics_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                stringResource(R.string.crash_reports),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
            // Crossfade between empty and list states so the transition reads as a smooth fade
            // instead of an instant snap. Matches the session list's Crossfade pattern; reduced
            // motion is honored by Crossfade's default spec. The content lambda branches on its
            // target-state parameter (not the captured `reports`) so the outgoing layer keeps
            // rendering the OLD state type while it fades out — reading `reports` directly would
            // recompose both layers to the latest content and defeat the crossfade into an
            // instant snap.
            val stateKey = if (reports.isEmpty()) "empty" else "list"
            Crossfade(
                targetState = stateKey,
                animationSpec = tween(NetworkConfig.motionFadeDurationMs.toInt()),
                label = "diagnostics_state",
            ) { key ->
                if (key == "empty") {
                EmptyState(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.no_crash_reports),
                    description = stringResource(R.string.no_crash_reports_desc),
                    actionLabel = stringResource(R.string.report_issue),
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(context.getString(R.string.url_report_issue))),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            } else {
                val filtered = remember(reports, crashQuery) { filterCrashReports(reports, crashQuery) }
                // Windowed render: compose only the first `renderCap` rows, growing as the
                // user scrolls near the bottom. Bounds composition work on a device that has
                // accumulated many crash reports (e.g. a flaky build used for weeks), matching
                // the session/file lists' pattern. rememberSaveable (not plain remember) so the
                // window survives a rotation alongside the LazyListState — see SessionListScreen.
                var renderCap by rememberSaveable {
                    mutableIntStateOf(NetworkConfig.diagnosticsListInitialPage)
                }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(listState, filtered.size) {
                    androidx.compose.runtime.snapshotFlow {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= renderCap - NetworkConfig.diagnosticsListPageStep / 2
                    }.collect { nearEnd ->
                        if (nearEnd && renderCap < filtered.size) {
                            renderCap = (renderCap + NetworkConfig.diagnosticsListPageStep).coerceAtMost(filtered.size)
                        }
                    }
                }
                val capped = remember(filtered, renderCap) {
                    if (filtered.size <= renderCap) filtered else filtered.take(renderCap)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    crashSearchHeader(reports = reports, filtered = filtered, crashQuery = crashQuery) {
                        crashQuery = it
                    }
                    items(capped, key = { it.fileName }) { report ->
                        // rememberSaveable so an open row menu survives a rotation; BackHandler
                        // so system back closes it instead of navigating away, matching the
                        // session/server/file list dropdown back-handling.
                        var menuExpanded by rememberSaveable(report.fileName) { mutableStateOf(false) }
                        BackHandler(enabled = menuExpanded) { menuExpanded = false }
                        // Swipe end-to-start reveals a delete affordance. The delete is
                        // deferred for an undo window (matching the session/server lists);
                        // confirmValueChange snaps back so the row remains while the undo
                        // snackbar is shown, and is removed only when the window expires.
                        val swipeState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                pendingReportDelete = report.fileName
                                false
                            },
                        )
                        SwipeToDismissBox(
                            state = swipeState,
                            enableDismissFromStartToEnd = false,
                            modifier = reducedMotionAnimateItem(),
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteSweep,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { viewing = report.fileName }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Filled.BugReport,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(report.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(
                                        rememberRelativeTime(report.timestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // The filename often encodes the exception class (the
                                    // CrashLogger names reports "<ExceptionClass>_<ts>.txt"),
                                    // so showing it muted under the relative time lets a user
                                    // scan for a specific exception type without opening each.
                                    Text(
                                        report.fileName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    shareScope.launch {
                                        val content = withContext(Dispatchers.IO) {
                                            logger.readReport(report.fileName).orEmpty()
                                        }
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                                            putExtra(Intent.EXTRA_TEXT, content)
                                        }
                                        runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                                            .onFailure {
                                                Log.w("Diagnostics", "Failed to share crash report", it)
                                                // FIX 20: "no app to share" only fits ActivityNotFoundException; other
                                                // failures (e.g. an oversized report exceeding the Binder limit →
                                                // TransactionTooLargeException) get a generic toast, not that misleading one.
                                                val msg = if (it is ActivityNotFoundException) R.string.no_share_app else R.string.error_generic
                                                shareScope.launch { errorSnackbar.showSnackbar(context.getString(msg)) }
                                            }
                                    }
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(R.string.more_options_for, report.fileName),
                                        )
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                            text = {
                                                Text(
                                                    stringResource(R.string.delete),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                pendingReportDelete = report.fileName
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            }
        }
        }
    }

    val reportName = viewing
    if (reportName != null) {
        var reportContent by remember(reportName) { mutableStateOf<String?>(null) }
        var loadFailed by remember(reportName) { mutableStateOf(false) }
        // A retry trigger: bumping this re-runs the load LaunchedEffect (keyed on it) and
        // clears the failure state, so a transient read error is recoverable without closing
        // and reopening the report — matching the file viewer's error-retry pattern.
        var retryKey by remember(reportName) { mutableIntStateOf(0) }
        LaunchedEffect(reportName, retryKey) {
            if (retryKey > 0) { reportContent = null; loadFailed = false }
            val loaded = withContext(Dispatchers.IO) {
                // runCatchingCancellable (not runCatching) so dismissing the dialog or
                // switching reports mid-read lets the CancellationException propagate
                // instead of being swallowed and setting state on a torn-down effect.
                runCatchingCancellable { logger.readReport(reportName) }.getOrNull()
            }
            if (loaded != null) reportContent = loaded else loadFailed = true
        }
        val content = reportContent
        Dialog(
            onDismissRequest = { viewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.crash_report)) },
                            navigationIcon = {
                                IconButton(onClick = { viewing = null }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                                }
                            },
                            actions = {
                                IconButton(
                                    // Disable Copy until the report has loaded so it can't copy an empty body.
                                    enabled = content != null,
                                    onClick = {
                                        copyToClipboard(context, context.getString(R.string.crash_report), content.orEmpty())
                                    },
                                ) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy)) }
                                IconButton(
                                    // Disable Share until the report has loaded — otherwise tapping it
                                    // while the async read is still in flight (content == null) shares an
                                    // empty body instead of the report.
                                    enabled = content != null,
                                    onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                                            putExtra(Intent.EXTRA_TEXT, content.orEmpty())
                                        }
                                        runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                                            .onFailure {
                                                Log.w("Diagnostics", "Failed to share crash report", it)
                                                // FIX 20: "no app to share" only fits ActivityNotFoundException; other failures
                                                // (e.g. an oversized report exceeding the Binder limit → TransactionTooLargeException)
                                                // get a generic toast, not that misleading one.
                                                val msg = if (it is ActivityNotFoundException) R.string.no_share_app else R.string.error_generic
                                                shareScope.launch { errorSnackbar.showSnackbar(context.getString(msg)) }
                                            }
                                    },
                                ) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share)) }
                                IconButton(onClick = {
                                    // Confirm before deleting a single report, matching clear-all.
                                    pendingReportDelete = reportName
                                }, enabled = content != null) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            },
                        )
                    },
                ) { padding ->
                    when {
                        content != null -> SelectionContainer {
                            // Wrap in SelectionContainer so a user can select/copy just the
                            // relevant stack snippet instead of the whole-report Copy button.
                            Text(
                                content,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(padding)
                                    .padding(16.dp),
                            )
                        }
                        loadFailed -> Box(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.report_load_failed),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = { retryKey++ }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                        else -> {
                            val loadingLabel = stringResource(R.string.loading)
                            Box(
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                            }
                        }
                    }
                }
            }
        }
    }

    pendingReportDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingReportDelete = null },
            title = { Text(stringResource(R.string.delete_report_title)) },
            text = { Text(stringResource(R.string.delete_report_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingReportDelete = null
                    viewing = null
                    // Defer the actual delete so the Undo snackbar can cancel it. The
                    // deletion is owned by the CrashLogger's own scope, so it commits even
                    // if the user navigates away during the undo window. The Undo snackbar
                    // is shown by the single collectLatest collector above (not a per-confirm
                    // launch) so rapid consecutive deletes don't produce a dead Undo button.
                    logger.scheduleDelete(name, NetworkConfig.undoReportDeleteDelayMs)
                    reportDeleteEvents.tryEmit(name)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReportDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text(stringResource(R.string.clear_all)) },
            text = { Text(stringResource(R.string.clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    shareScope.launch { clearAllEvents.emit(Unit) }
                }) { Text(stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Filter crash reports by preview/file name. Extracted from [DiagnosticsScreen] to keep its
 *  cyclomatic complexity under the detekt threshold. Empty query returns all reports. */
private fun filterCrashReports(
    reports: List<CrashLogger.CrashReport>,
    query: String,
): List<CrashLogger.CrashReport> {
    val q = query.trim()
    if (q.isEmpty()) return reports
    return reports.filter {
        it.preview.contains(q, ignoreCase = true) || it.fileName.contains(q, ignoreCase = true)
    }
}

/** The diagnostics filter field with a clear button. Extracted from [DiagnosticsScreen] to keep
 *  its cyclomatic complexity under the detekt threshold. */
@Composable
private fun CrashQueryField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.search_crash_reports)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        // Match the session/file/server search fields: ImeAction.Search dismisses the keyboard
        // on submit so the IME doesn't keep covering the crash list after filtering.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

/** Conditionally emits the search field and the no-match placeholder into the crash list.
 *  Extracted from [DiagnosticsScreen] to keep its cyclomatic complexity under the threshold. */
private fun androidx.compose.foundation.lazy.LazyListScope.crashSearchHeader(
    reports: List<CrashLogger.CrashReport>,
    filtered: List<CrashLogger.CrashReport>,
    crashQuery: String,
    onQueryChange: (String) -> Unit,
) {
    if (reports.size >= NetworkConfig.diagnosticsSearchThreshold) {
        item(key = "__search") {
            CrashQueryField(query = crashQuery, onQueryChange = onQueryChange)
        }
    }
    if (crashQuery.isNotBlank() && filtered.isEmpty()) {
        item(key = "__no_match") {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.no_crash_matches),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}
