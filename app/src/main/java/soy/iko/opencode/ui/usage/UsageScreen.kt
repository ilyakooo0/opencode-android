package soy.iko.opencode.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.AppTopBar
import soy.iko.opencode.ui.components.ConnectionBannerFor
import soy.iko.opencode.ui.components.EmptyState
import soy.iko.opencode.ui.components.SectionHeader
import soy.iko.opencode.ui.vmFactory
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(container: AppContainer, onBack: () -> Unit, onOpenSession: (String) -> Unit = {}) {
    val vm: UsageViewModel = viewModel(factory = vmFactory { UsageViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.usage_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ConnectionBannerFor(container)
                val loadingLabel = stringResource(R.string.usage_loading)
                when (val s = state) {
                    is UsageViewModel.State.Loading -> Centered {
                        CircularProgressIndicator(
                            Modifier.semantics { contentDescription = loadingLabel },
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(loadingLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UsageViewModel.State.Disconnected -> EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.not_connected),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    is UsageViewModel.State.Error -> EmptyState(
                        icon = Icons.Filled.ErrorOutline,
                        title = stringResource(R.string.usage_failed),
                        description = s.message,
                        modifier = Modifier.align(Alignment.Center),
                        actionLabel = stringResource(R.string.retry),
                        onAction = { vm.load() },
                    )
                    is UsageViewModel.State.Ready ->
                        if (s.report.isEmpty) {
                            EmptyState(
                                icon = Icons.Filled.QueryStats,
                                title = stringResource(R.string.usage_empty),
                                description = stringResource(R.string.usage_empty_desc),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            UsageContent(s.report, onOpenSession)
                        }
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
private fun UsageContent(report: UsageReport, onOpenSession: (String) -> Unit) {
    // Optional session-title filter. The global totals (cost/tokens/by-model) always reflect
    // the whole history, but a user with many sessions can narrow the per-session list to find
    // a specific conversation's spend without scrolling through all of it.
    var sessionQuery by rememberSaveable { mutableStateOf("") }
    val filteredSessions = remember(report.bySession, sessionQuery) {
        val q = sessionQuery.trim()
        if (q.isEmpty()) report.bySession
        else report.bySession.filter { it.title.contains(q, ignoreCase = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TotalsCard(report) }
        item { TokenBreakdownCard(report.totalTokens) }
        if (report.byModel.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.usage_by_model), Modifier.semantics { heading() }) }
            // Key on provider+model: the same model id served by two providers yields two
            // rows, so keying on the bare model would duplicate keys and crash the list.
            items(report.byModel, key = { it.provider + "/" + it.model }) { m ->
                UsageRow(
                    title = m.provider + " / " + m.model,
                    cost = m.cost,
                    tokens = m.tokens,
                    messages = m.messages,
                    costFraction = fractionOf(m.cost, report.totalCost),
                )
            }
        }
        if (report.bySession.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.usage_all_sessions), Modifier.semantics { heading() }) }
            // Only show the filter field once there are enough sessions to justify it, so a
            // short history isn't cluttered with a search bar.
            if (report.bySession.size >= NetworkConfig.usageSessionSearchThreshold) {
                item(key = "__session_search") {
                    OutlinedTextField(
                        value = sessionQuery,
                        onValueChange = { sessionQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.usage_filter_sessions)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (sessionQuery.isNotEmpty()) {
                                IconButton(onClick = { sessionQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        },
                    )
                }
            }
            if (sessionQuery.isNotBlank() && filteredSessions.isEmpty()) {
                item(key = "__no_match") {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = stringResource(R.string.usage_no_sessions),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(filteredSessions, key = { it.sessionId }) { s ->
                UsageRow(
                    title = s.title,
                    cost = s.cost,
                    tokens = s.tokens,
                    messages = s.messages,
                    costFraction = fractionOf(s.cost, report.totalCost),
                    onClick = { onOpenSession(s.sessionId) },
                )
            }
        }
    }
}

private fun fractionOf(part: Double, total: Double): Float =
    if (total > 0.0) (part / total).toFloat().coerceIn(0f, 1f) else 0f

/** A slim horizontal bar showing this row's share of the total cost. */
@Composable
private fun CostBar(fraction: Float) {
    val pct = (fraction * 100).toInt()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "${pct}% of total"
                invisibleToUser()
            },
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary),
            )
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
                pluralStringResource(R.plurals.usage_messages_count, report.messageCount, report.messageCount),
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
private fun UsageRow(
    title: String,
    cost: Double,
    tokens: Tokens,
    messages: Int,
    costFraction: Float,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(vertical = 6.dp)
    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    pluralStringResource(R.plurals.usage_messages_count, messages, messages) + " · " + formatInt(tokens.total),
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
            // Chevron signals that tapping a session row navigates to it (mirrors the M3
            // list-item pattern for navigable rows), so the affordance isn't hidden behind
            // just the click ripple.
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        CostBar(costFraction)
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
