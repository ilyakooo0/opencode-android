package soy.iko.opencode.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.vmFactory
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: UsageViewModel = viewModel(factory = vmFactory { UsageViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UsageViewModel.State.Loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.usage_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is UsageViewModel.State.Disconnected -> Centered {
                    Text(stringResource(R.string.not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is UsageViewModel.State.Error -> Centered {
                    Text(stringResource(R.string.usage_failed), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                }
                is UsageViewModel.State.Ready ->
                    if (s.report.isEmpty) {
                        Centered {
                            Text(stringResource(R.string.usage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        UsageContent(s.report)
                    }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun UsageContent(report: UsageReport) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TotalsCard(report) }
        item { TokenBreakdownCard(report.totalTokens) }
        if (report.byModel.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.usage_by_model)) }
            // Key on provider+model: the same model id served by two providers yields two
            // rows, so keying on the bare model would duplicate keys and crash the list.
            items(report.byModel, key = { it.provider + "/" + it.model }) { m ->
                UsageRow(title = m.provider + " / " + m.model, cost = m.cost, tokens = m.tokens, messages = m.messages)
            }
        }
        if (report.bySession.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.usage_all_sessions)) }
            items(report.bySession, key = { it.sessionId }) { s ->
                UsageRow(title = s.title, cost = s.cost, tokens = s.tokens, messages = s.messages)
            }
        }
    }
}

@Composable
private fun TotalsCard(report: UsageReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.usage_total_cost),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCost(report.totalCost),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.usage_messages_count, report.messageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.usage_total_tokens) + ": " + formatInt(report.totalTokens.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenBreakdownCard(tokens: Tokens) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            StatRow(stringResource(R.string.usage_input), formatInt(tokens.input))
            StatRow(stringResource(R.string.usage_output), formatInt(tokens.output))
            if (tokens.reasoning > 0) StatRow(stringResource(R.string.usage_reasoning), formatInt(tokens.reasoning))
            if (tokens.cache.read > 0 || tokens.cache.write > 0) {
                StatRow(stringResource(R.string.usage_cache), formatInt(tokens.cache.read + tokens.cache.write))
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun UsageRow(title: String, cost: Double, tokens: Tokens, messages: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.usage_messages_count, messages) + " · " + formatInt(tokens.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatCost(cost),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// Locale.US so amounts/counts format identically regardless of device locale (stable digits
// and a dot decimal for a dollar figure). Sub-cent totals get more precision so a tiny run
// doesn't collapse to "$0.00".
private fun formatCost(cost: Double): String =
    if (cost in 0.0..0.01) String.format(Locale.US, "$%.4f", cost)
    else String.format(Locale.US, "$%.2f", cost)

private val integerFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

private fun formatInt(n: Long): String = integerFormat.format(n)
