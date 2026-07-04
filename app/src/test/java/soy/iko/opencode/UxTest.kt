package soy.iko.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TimeInfo
import soy.iko.opencode.ui.components.RelativeTimeUnit
import soy.iko.opencode.ui.components.relativeTimeUnit
import soy.iko.opencode.ui.session.SessionListState
import java.util.concurrent.TimeUnit

class UxTest {

    // --- relativeTimeUnit (pure logic, locale-independent) ---

    @Test
    fun relativeTimeHandlesNullAndZero() {
        assertEquals(null, relativeTimeUnit(null))
        assertEquals(null, relativeTimeUnit(0))
        assertEquals(null, relativeTimeUnit(-1))
    }

    @Test
    fun relativeTimeFormatsMinutes() {
        val now = System.currentTimeMillis()
        assertEquals(RelativeTimeUnit.Now(), relativeTimeUnit(now))
        assertEquals(
            RelativeTimeUnit.Minutes(5),
            relativeTimeUnit(now - TimeUnit.MINUTES.toMillis(5)),
        )
    }

    @Test
    fun relativeTimeFormatsHoursAndDays() {
        val now = System.currentTimeMillis()
        assertEquals(
            RelativeTimeUnit.Hours(3),
            relativeTimeUnit(now - TimeUnit.HOURS.toMillis(3)),
        )
        assertEquals(
            RelativeTimeUnit.Days(2),
            relativeTimeUnit(now - TimeUnit.DAYS.toMillis(2)),
        )
        assertEquals(
            RelativeTimeUnit.Weeks(1),
            relativeTimeUnit(now - TimeUnit.DAYS.toMillis(7)),
        )
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
