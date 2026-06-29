package soy.iko.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TimeInfo
import soy.iko.opencode.ui.components.relativeTime
import soy.iko.opencode.ui.session.SessionListState
import java.util.concurrent.TimeUnit

class UxTest {

    // --- relativeTime ---

    @Test
    fun relativeTimeHandlesNullAndZero() {
        assertEquals("", relativeTime(null))
        assertEquals("", relativeTime(0))
        assertEquals("", relativeTime(-1))
    }

    @Test
    fun relativeTimeFormatsMinutes() {
        val now = System.currentTimeMillis()
        assertEquals("now", relativeTime(now))
        assertEquals("5m", relativeTime(now - TimeUnit.MINUTES.toMillis(5)))
    }

    @Test
    fun relativeTimeFormatsHoursAndDays() {
        val now = System.currentTimeMillis()
        assertEquals("3h", relativeTime(now - TimeUnit.HOURS.toMillis(3)))
        assertEquals("2d", relativeTime(now - TimeUnit.DAYS.toMillis(2)))
        assertEquals("1w", relativeTime(now - TimeUnit.DAYS.toMillis(7)))
    }

    // --- Session search/filter ---

    private fun session(id: String, title: String) =
        Session(id = id, title = title)

    @Test
    fun emptyQueryReturnsAllSessions() {
        val state = SessionListState(
            sessions = listOf(session("1", "Alpha"), session("2", "Beta")),
        )
        assertEquals(2, state.filtered.size)
    }

    @Test
    fun queryFiltersByTitle() {
        val state = SessionListState(
            sessions = listOf(session("1", "Refactor tests"), session("2", "Add docs")),
            query = "refactor",
        )
        val result = state.filtered
        assertEquals(1, result.size)
        assertEquals("1", result.first().id)
    }

    @Test
    fun queryFiltersByPreviewText() {
        val state = SessionListState(
            sessions = listOf(session("1", "Alpha"), session("2", "Beta")),
            previews = mapOf("2" to "discuss the database migration"),
            query = "database",
        )
        val result = state.filtered
        assertEquals(1, result.size)
        assertEquals("2", result.first().id)
    }

    @Test
    fun queryIsCaseInsensitive() {
        val state = SessionListState(
            sessions = listOf(session("1", "Build Pipeline")),
            query = "build",
        )
        assertEquals(1, state.filtered.size)
    }

    @Test
    fun noMatchReturnsEmpty() {
        val state = SessionListState(
            sessions = listOf(session("1", "Alpha")),
            query = "zzz",
        )
        assertTrue(state.filtered.isEmpty())
    }
}
