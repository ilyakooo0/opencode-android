package soy.iko.opencode.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.data.model.TodoItem
import soy.iko.opencode.data.model.TodoPriority
import soy.iko.opencode.data.model.TodoStatus
import soy.iko.opencode.data.model.priorityEnum
import soy.iko.opencode.data.model.statusEnum
import soy.iko.opencode.ui.components.rememberVisibilityTransitions

/** Renders a task plan as a checklist: a status icon per row plus its description, with
 *  completed/cancelled items struck through and the in-progress item emphasized. */
@Composable
fun TodoPlanChecklist(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (todo in todos) TodoRow(todo)
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val status = todo.statusEnum()
    val priority = todo.priorityEnum()
    val struck = status == TodoStatus.COMPLETED || status == TodoStatus.CANCELLED
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TodoStatusIcon(status)
        Text(
            todo.content,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                status == TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                struck -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (status == TodoStatus.IN_PROGRESS) FontWeight.Medium else null,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        // Priority badge: a small uppercase label to the right of the content. Tinted to
        // distinguish high (error) from medium (tertiary) from low (muted); skipped entirely
        // when the server didn't report a priority (NONE) so a plain todo doesn't get a
        // meaningless "LOW" chip. Struck-through items keep the badge but muted, so a
        // completed/cancelled high-priority task still records its original urgency.
        if (priority != TodoPriority.NONE) {
            val label = when (priority) {
                TodoPriority.HIGH -> stringResource(R.string.todo_priority_high)
                TodoPriority.MEDIUM -> stringResource(R.string.todo_priority_medium)
                TodoPriority.LOW -> stringResource(R.string.todo_priority_low)
                TodoPriority.NONE -> ""
            }
            val tint = when (priority) {
                TodoPriority.HIGH -> if (struck) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                TodoPriority.MEDIUM -> if (struck) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary
                TodoPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                TodoPriority.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val a11yLabel = stringResource(R.string.todo_priority_label, label)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier
                    .padding(start = 8.dp, top = 2.dp)
                    .semantics { contentDescription = a11yLabel },
            )
        }
    }
}

@Composable
private fun TodoStatusIcon(status: TodoStatus) {
    val icon = when (status) {
        TodoStatus.COMPLETED -> Icons.Filled.CheckCircle
        TodoStatus.IN_PROGRESS -> Icons.Filled.Autorenew
        TodoStatus.CANCELLED -> Icons.Filled.Cancel
        else -> Icons.Filled.RadioButtonUnchecked
    }
    val tint = when (status) {
        TodoStatus.COMPLETED, TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val desc = when (status) {
        TodoStatus.COMPLETED -> R.string.todo_completed
        TodoStatus.IN_PROGRESS -> R.string.todo_in_progress
        TodoStatus.CANCELLED -> R.string.todo_cancelled
        else -> R.string.todo_pending
    }
    Icon(
        icon,
        contentDescription = stringResource(desc),
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

/** A collapsible plan bar pinned above the composer: the header shows completed/total and
 *  a chevron; expanding reveals the full (scrollable, height-capped) checklist. Renders
 *  nothing when there's no plan, so the caller can place it unconditionally. */
@Composable
fun TodoPlanBar(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completed = remember(todos) { todos.count { it.statusEnum() == TodoStatus.COMPLETED } }
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) expandedState else collapsedState }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.plan),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    stringResource(R.string.plan_progress, completed, todos.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            val expandMotion = rememberVisibilityTransitions()
            AnimatedVisibility(
                visible = expanded,
                enter = expandMotion.enter,
                exit = expandMotion.exit,
            ) {
                TodoPlanChecklist(
                    todos,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}
