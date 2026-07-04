package soy.iko.opencode.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDurationFormatTest {

    @Test
    fun subSecondRendersInMilliseconds() {
        assertEquals("0ms", formatToolDuration(0))
        assertEquals("123ms", formatToolDuration(123))
        assertEquals("999ms", formatToolDuration(999))
    }

    @Test
    fun underTenSecondsRendersWithTenths() {
        assertEquals("1s", formatToolDuration(1_000))
        assertEquals("1.5s", formatToolDuration(1_500))
        assertEquals("2.3s", formatToolDuration(2_300))
        assertEquals("9.9s", formatToolDuration(9_900))
    }

    @Test
    fun wholeSecondsOmitTenths() {
        assertEquals("2s", formatToolDuration(2_000))
        assertEquals("10s", formatToolDuration(10_000))
    }

    @Test
    fun underAMinuteRendersSeconds() {
        assertEquals("59s", formatToolDuration(59_000))
    }

    @Test
    fun minutesPadSeconds() {
        assertEquals("1m 04s", formatToolDuration(64_000))
        assertEquals("2m 09s", formatToolDuration(129_000))
    }

    @Test
    fun hoursPadMinutes() {
        assertEquals("1h 05m", formatToolDuration(3_900_000))
        assertEquals("2h 00m", formatToolDuration(7_200_000))
    }
}
