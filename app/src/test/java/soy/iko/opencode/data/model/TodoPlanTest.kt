package soy.iko.opencode.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPlanTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun element(s: String) = json.parseToJsonElement(s)

    @Test
    fun parsesTodosFromObject() {
        val todos = parseTodos(
            element("""{"todos":[{"id":"1","content":"A","status":"completed"},{"id":"2","content":"B","status":"in_progress"}]}"""),
        )
        assertEquals(2, todos.size)
        assertEquals("A", todos[0].content)
        assertEquals(TodoStatus.COMPLETED, todos[0].statusEnum())
        assertEquals(TodoStatus.IN_PROGRESS, todos[1].statusEnum())
    }

    @Test
    fun parsesTodosFromBareArray() {
        val todos = parseTodos(element("""[{"content":"only","status":"pending"}]"""))
        assertEquals(1, todos.size)
        assertEquals(TodoStatus.PENDING, todos[0].statusEnum())
    }

    @Test
    fun ignoresUnknownKeysAndUnknownStatus() {
        val todos = parseTodos(element("""{"todos":[{"content":"x","status":"weird","extra":123}]}"""))
        assertEquals(1, todos.size)
        assertEquals("x", todos[0].content)
        assertEquals(TodoStatus.UNKNOWN, todos[0].statusEnum())
    }

    @Test
    fun returnsEmptyForNullAndGarbage() {
        assertTrue(parseTodos(null).isEmpty())
        assertTrue(parseTodos(element(""""just a string"""")).isEmpty())
        assertTrue(parseTodos(element("""{"nope":true}""")).isEmpty())
    }

    @Test
    fun statusEnumNormalizesAliases() {
        assertEquals(TodoStatus.IN_PROGRESS, TodoItem(status = "in-progress").statusEnum())
        assertEquals(TodoStatus.COMPLETED, TodoItem(status = "DONE").statusEnum())
        assertEquals(TodoStatus.CANCELLED, TodoItem(status = "canceled").statusEnum())
        assertEquals(TodoStatus.PENDING, TodoItem(status = "").statusEnum())
    }

    @Test
    fun currentPlanPicksLatestNonEmptyTodowrite() {
        val messages = listOf(
            message("m1", todoPart("p1", """{"todos":[{"content":"old","status":"pending"}]}""")),
            message("m2", todoPart("p2", """{"todos":[{"content":"new1","status":"completed"},{"content":"new2","status":"pending"}]}""")),
            message("m3", TextPart(id = "t", text = "just talking")),
        )
        val plan = currentTodoPlan(messages)
        assertEquals(2, plan.size)
        assertEquals("new1", plan[0].content)
    }

    @Test
    fun currentPlanSkipsEmptyLatestForPriorNonEmpty() {
        val messages = listOf(
            message("m1", todoPart("p1", """{"todos":[{"content":"keep","status":"pending"}]}""")),
            message("m2", todoPart("p2", """{"todos":[]}""")),
        )
        val plan = currentTodoPlan(messages)
        assertEquals(1, plan.size)
        assertEquals("keep", plan[0].content)
    }

    @Test
    fun currentPlanEmptyWhenNoTodowrite() {
        val messages = listOf(
            message("m1", TextPart(id = "t", text = "hi")),
            message("m2", ToolPart(id = "b", tool = "bash", state = ToolCompleted(output = "done"))),
        )
        assertTrue(currentTodoPlan(messages).isEmpty())
    }

    private fun message(id: String, vararg parts: Part) =
        MessageWithParts(AssistantMessage(id = id, sessionID = "s"), parts.toList())

    private fun todoPart(id: String, inputJson: String): ToolPart =
        ToolPart(id = id, tool = "todowrite", state = ToolCompleted(input = element(inputJson)))
}
