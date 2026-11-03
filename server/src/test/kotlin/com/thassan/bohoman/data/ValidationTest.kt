package com.thassan.bohoman.data

import com.thassan.bohoman.contracts.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationTest {
    @Test
    fun dateOnlyAndExactDeadlinesAreDistinct() {
        validateDeadline(null)
        validateDeadline(Deadline(date = "2028-02-29"))
        validateDeadline(Deadline(instant = "2026-11-03T18:00:00Z", timeZone = "Asia/Dhaka"))
        listOf(
            Deadline(),
            Deadline(date = "2027-02-29"),
            Deadline(date = "2026-11-03", timeZone = "Asia/Dhaka"),
            Deadline(date = "2026-11-03", instant = "2026-11-03T18:00:00Z"),
            Deadline(instant = "2026-11-03T18:00:00"),
            Deadline(instant = "2026-11-03T18:00:00Z", timeZone = "+06:00"),
            Deadline(instant = "2026-11-03T18:00:00Z", timeZone = "Not/AZone"),
            Deadline(date = "0000-01-01"),
        ).forEach { deadline ->
            val error = assertFailsWith<ApiException>("Should reject $deadline") { validateDeadline(deadline) }
            assertEquals(400, error.status)
        }
    }

    @Test
    fun boundsRejectInvalidInputBeforeSql() {
        assertEquals("Prepare release", validateTitle("  Prepare release  "))
        listOf("", " \n\t", "x".repeat(201), "Null\u0000title").forEach { assertFailsWith<ApiException> { validateTitle(it) } }
        assertFailsWith<ApiException> { validatePriority(-1) }
        assertFailsWith<ApiException> { validatePriority(4) }
        assertFailsWith<ApiException> { validateDescription("x".repeat(10_001)) }
        assertFailsWith<ApiException> { validateDescription("Null\u0000description") }
        assertFailsWith<ApiException> { validateVersion(0) }
        assertFailsWith<ApiException> { validatePage(0, 0) }
        assertFailsWith<ApiException> { validatePage(101, 0) }
        assertFailsWith<ApiException> { validatePage(100, -1) }
    }
}
