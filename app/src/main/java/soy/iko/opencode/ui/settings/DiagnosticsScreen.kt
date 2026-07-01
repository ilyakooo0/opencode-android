package soy.iko.opencode.ui.settings

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.ui.components.showToast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    val shareScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val reportDeletedLabel = stringResource(R.string.report_deleted)
    // Track report names whose deletion has been deferred for an undo window, so a
    // swipe-triggered delete can be cancelled before the file is actually removed.
    val deferredDeletes = remember { mutableStateOf<Set<String>>(emptySet()) }
    val shareLabel = stringResource(R.string.share)
    val timeTick = rememberRelativeTimeTick()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (reports.isNotEmpty()) {
                        IconButton(onClick = {
                            showClearAll = true
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_all))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
            if (reports.isEmpty()) {
                EmptyCrashReports(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(reports, key = { it.fileName }) { report ->
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
                                    Text(report.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        rememberRelativeTime(report.timestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    shareScope.launch {
                                        val content = withContext(Dispatchers.IO) {
                                            logger.readReport(report.fileName).orEmpty()
                                        }
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, shareLabel)
                                            putExtra(Intent.EXTRA_TEXT, content)
                                        }
                                        runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                                            .onFailure {
                                                Log.w("Diagnostics", "Failed to share crash report", it)
                                                showToast(context, context.getString(R.string.no_share_app))
                                            }
                                    }
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
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

    val reportName = viewing
    if (reportName != null) {
        var reportContent by remember(reportName) { mutableStateOf<String?>(null) }
        var loadFailed by remember(reportName) { mutableStateOf(false) }
        LaunchedEffect(reportName) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { logger.readReport(reportName) }.getOrNull()
            }
            if (loaded != null) reportContent = loaded else loadFailed = true
        }
        val content = reportContent
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = {
                TextButton(onClick = { viewing = null }) { Text(stringResource(R.string.close)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareLabel)
                            putExtra(Intent.EXTRA_TEXT, content.orEmpty())
                        }
                        runCatchingCancellable { context.startActivity(Intent.createChooser(send, shareLabel)) }
                            .onFailure {
                                Log.w("Diagnostics", "Failed to share crash report", it)
                                showToast(context, context.getString(R.string.no_share_app))
                            }
                    }) { Text(stringResource(R.string.share)) }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        // Confirm before deleting a single report, matching clear-all.
                        pendingReportDelete = reportName
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            title = { Text(stringResource(R.string.crash_report)) },
            text = {
                when {
                    content != null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            content,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    loadFailed -> Text(
                        stringResource(R.string.report_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    else -> {
                        val loadingLabel = stringResource(R.string.loading)
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                        }
                    }
                }
            },
        )
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
                    // report stays in the list until the window expires; the undo
                    // action removes the name from the deferred set.
                    deferredDeletes.value = deferredDeletes.value + name
                    shareScope.launch {
                        delay(NetworkConfig.undoReportDeleteDelayMs)
                        if (name in deferredDeletes.value) {
                            deferredDeletes.value = deferredDeletes.value - name
                            logger.deleteReport(name)
                        }
                    }
                    // showSnackbar is suspend; run it on the screen's scope so the
                    // undo action can race the deferred delete window.
                    shareScope.launch {
                        val result = snackbar.showSnackbar(
                            message = reportDeletedLabel,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            deferredDeletes.value = deferredDeletes.value - name
                        }
                    }
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
                    logger.clearAll()
                }) { Text(stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Empty state for the crash reports list. Rendered with an icon + text to match the
 *  empty-state pattern used by the session and server lists (the prior version was a
 *  bare `Text` line that read as a status message rather than an intentional state). */
@Composable
private fun EmptyCrashReports(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.no_crash_reports),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
