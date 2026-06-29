package soy.iko.opencode.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.R
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.ui.components.rememberRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logger = remember { CrashLogger.get(context) }
    val reports by logger.reports.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<String?>(null) }
    val shareLabel = stringResource(R.string.share)

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
                        IconButton(onClick = { logger.clearAll() }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
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
                Text(
                    stringResource(R.string.no_crash_reports),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(reports, key = { it.fileName }) { report ->
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
                            val shareScope = rememberCoroutineScope()
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
                                    context.startActivity(Intent.createChooser(send, shareLabel))
                                }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val reportName = viewing
    if (reportName != null) {
        var reportContent by remember(reportName) { mutableStateOf<String?>(null) }
        LaunchedEffect(reportName) {
            reportContent = withContext(Dispatchers.IO) { logger.readReport(reportName) }
        }
        val content = reportContent
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = {
                TextButton(onClick = { viewing = null }) { Text(stringResource(R.string.back)) }
            },
            dismissButton = {
                Row {
                    val shareLabel2 = stringResource(R.string.share)
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareLabel2)
                            putExtra(Intent.EXTRA_TEXT, content.orEmpty())
                        }
                        context.startActivity(Intent.createChooser(send, shareLabel2))
                    }) { Text(stringResource(R.string.share)) }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        logger.deleteReport(reportName)
                        viewing = null
                    }) { Text(stringResource(R.string.delete)) }
                }
            },
            title = { Text(stringResource(R.string.crash_report)) },
            text = {
                Text(
                    content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}
