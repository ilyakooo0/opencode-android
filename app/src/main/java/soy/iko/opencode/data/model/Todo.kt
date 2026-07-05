package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The tool name opencode uses to write the agent's task plan. */
const val TODO_WRITE_TOOL = "todowrite"

/**
 * A single entry in an agent's task plan, as written by opencode's `todowrite` tool.
 * Every field is defaulted and decoding ignores unknown keys (see [parseTodos]) so a
 * change to the tool's payload shape degrades to a best-effort render instead of
 * dropping the whole plan.
 */
@Immutable
@Serializable
data class TodoItem(
    val id: String = "",
    val content: String = "",
    val status: String = "pending",
    val priority: String? = null,
)

/** Normalized lifecycle of a [TodoItem]; [UNKNOWN] covers any status the client doesn't model. */
enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED, UNKNOWN }

/** Normalized priority of a [TodoItem]; [NONE] covers null/unmodelled priorities. The order
 *  matters: HIGH > MEDIUM > LOW, used by [TodoPlan] tinting and a11y labels. */
enum class TodoPriority { HIGH, MEDIUM, LOW, NONE }

fun TodoItem.priorityEnum(): TodoPriority = when ((priority ?: "").trim().lowercase()) {
    "high", "h", "1", "urgent", "critical" -> TodoPriority.HIGH
    "medium", "med", "m", "normal", "2" -> TodoPriority.MEDIUM
    "low", "l", "3", "minor", "backlog" -> TodoPriority.LOW
    else -> TodoPriority.NONE
}

fun TodoItem.statusEnum(): TodoStatus = when (status.trim().lowercase()) {
    "pending", "todo", "" -> TodoStatus.PENDING
    "in_progress", "in-progress", "inprogress", "running", "active" -> TodoStatus.IN_PROGRESS
    "completed", "complete", "done" -> TodoStatus.COMPLETED
    "cancelled", "canceled", "skipped" -> TodoStatus.CANCELLED
    else -> TodoStatus.UNKNOWN
}

@Serializable
private data class TodoWriteInput(val todos: List<TodoItem> = emptyList())

// A lenient decoder mirroring OpencodeJson's resilience (unknown keys ignored, nulls
// coerced to defaults) without pulling the network layer into data/model.
private val todoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Extract the todo list from a `todowrite` tool call's input JSON. The tool sends the
 * full list on every write, so a single call's input is a complete plan snapshot.
 * Accepts either the documented `{ "todos": [...] }` object or a bare array, and returns
 * an empty list on anything unparseable.
 */
fun parseTodos(input: JsonElement?): List<TodoItem> {
    if (input == null) return emptyList()
    return runCatching {
        todoJson.decodeFromJsonElement(TodoWriteInput.serializer(), input).todos
    }.recoverCatching {
        todoJson.decodeFromJsonElement(ListSerializer(TodoItem.serializer()), input)
    }.getOrDefault(emptyList())
}

/**
 * The current task plan for a conversation: the todos of the most recent `todowrite`
 * tool call that carries a non-empty, parseable list. opencode rewrites the whole list
 * on every write, so a single call is a complete snapshot; scanning newest-first and
 * skipping empties avoids a mid-stream write (input not yet arrived) briefly blanking a
 * plan that's still valid.
 */
fun currentTodoPlan(messages: List<MessageWithParts>): List<TodoItem> {
    for (mi in messages.indices.reversed()) {
        val parts = messages[mi].parts
        for (pi in parts.indices.reversed()) {
            val part = parts[pi]
            if (part is ToolPart && part.tool.equals(TODO_WRITE_TOOL, ignoreCase = true)) {
                val todos = parseTodos(part.state.inputElement())
                if (todos.isNotEmpty()) return todos
            }
        }
    }
    return emptyList()
}
