package soy.iko.opencode.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.R

/** Bottom sheet that lists every agent and lets the user pick one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    agents: List<Agent>,
    selected: String?,
    loading: Boolean,
    error: Boolean,
    onSelect: (Agent?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.agent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (error && agents.isEmpty()) {
            Text(
                stringResource(R.string.load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRetry() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else if (agents.isEmpty()) {
            Text(
                stringResource(R.string.no_agents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item(key = "__default") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                role = Role.RadioButton
                                if (selected == null) this.selected = true
                            }
                            .clickable { onSelect(null); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.default_agent),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected == null) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.default_agent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(agents, key = { it.name }) { agent ->
                    val isSelected = agent.name == selected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                role = Role.RadioButton
                                if (isSelected) this.selected = true
                            }
                            .clickable { onSelect(agent); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                agent.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Text(
                            agent.displayDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
