package com.thassan.bohoman

import com.thassan.bohoman.contracts.Deadline
import com.thassan.bohoman.core.time.*
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class CountdownTimeTest {
    @Test fun dateOnlyRemainsTheSameCalendarDateAcrossZones() {
        val target = Deadline(date = "2026-11-04")
        val now = Instant.parse("2026-11-03T20:00:00Z")
        assertEquals("2026-11-04", deadlineLabel(target))
        assertEquals("Today", countdownLabel(target, now, TimeZone.of("Asia/Dhaka")))
        assertEquals("Tomorrow", countdownLabel(target, now, TimeZone.of("America/New_York")))
        assertNull(parseDeadline("2026-11-04", "", "", true)?.instant)
    }

    @Test fun exactDeadlinesMeasureTheSameInstantAcrossZones() {
        val target = Deadline(instant = "2026-11-04T02:00:00Z", timeZone = "Asia/Dhaka")
        val now = Instant.parse("2026-11-03T20:00:00Z")
        assertEquals("In 6h 0m", countdownLabel(target, now, TimeZone.of("Asia/Dhaka")))
        assertEquals("In 6h 0m", countdownLabel(target, now, TimeZone.of("America/New_York")))
    }

    @Test fun invalidOrAmbiguousDeadlineInputIsRejected() {
        assertFails { parseDeadline("2026-11-04", "2026-11-04T00:00:00Z", "Asia/Dhaka", true) }
        assertFails { parseDeadline("2026-02-30", "", "", true) }
        assertFails { parseDeadline("", "2026-11-04T00:00:00Z", "not/a/zone", true) }
        assertFails { parseDeadline("", "", "", true) }
        assertFails { parseDeadline("2026-11-04", "", "Asia/Dhaka", true) }
        assertNull(parseDeadline("", "", "", false))
        assertFails { parseDeadline("0000-11-04", "", "", true) }
        assertFails { parseDeadline("+10000-11-04", "", "", true) }
        assertFails { parseDeadline("", "0000-11-04T00:00:00Z", "Asia/Dhaka", true) }
    }

    @Test fun nonexistentDaylightSavingTimeIsRejected() {
        assertFails { parseLocalDeadline("2026-03-08T02:30", "America/New_York") }
        assertEquals("2026-03-08T07:30:00Z", parseLocalDeadline("2026-03-08T03:30", "America/New_York").instant)
    }

    @Test fun editingAnOverlappingTimePreservesTheSelectedInstant() {
        val laterOccurrence = Deadline(instant = "2026-11-01T06:30:00Z", timeZone = "America/New_York")
        assertEquals(laterOccurrence, parseLocalDeadline("2026-11-01T01:30", "America/New_York", laterOccurrence))
        val earlierOccurrence = laterOccurrence.copy(instant = "2026-11-01T05:30:00Z")
        assertEquals(earlierOccurrence, parseLocalDeadline("2026-11-01T01:30", "America/New_York", earlierOccurrence))
    }

    @Test fun exactCountdownHandlesSubMinuteAndPastBoundaries() {
        val target = Deadline(instant = "2026-11-04T02:00:00Z", timeZone = "Asia/Dhaka")
        assertEquals("Less than a minute", countdownLabel(target, Instant.parse("2026-11-04T01:59:59Z"), TimeZone.UTC))
        assertEquals("Now", countdownLabel(target, Instant.parse("2026-11-04T02:00:00Z"), TimeZone.UTC))
        assertEquals("Just passed", countdownLabel(target, Instant.parse("2026-11-04T02:00:01Z"), TimeZone.UTC))
        assertEquals("1h 1m ago", countdownLabel(target, Instant.parse("2026-11-04T03:01:00Z"), TimeZone.UTC))
    }
}
