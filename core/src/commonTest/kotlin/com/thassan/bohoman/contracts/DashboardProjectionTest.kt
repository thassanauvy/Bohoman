package com.thassan.bohoman.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardProjectionTest {
    private val date = Deadline(date = "2030-11-04")

    @Test
    fun onlyOpenActiveTasksWithDeadlinesAppearOnDashboard() {
        val task = task(deadline = date)

        assertEquals(
            DashboardItem("task-1", DashboardSource.TASK, "Prepare", "Notes", date, 3),
            task.toDashboardItemOrNull(),
        )
        assertNull(task.copy(deadline = null).toDashboardItemOrNull())
        assertNull(task.copy(completed = true, completedAt = "2030-11-03T10:00:00Z").toDashboardItemOrNull())
        assertNull(task.copy(deletedAt = "2030-11-03T10:00:00Z").toDashboardItemOrNull())
    }

    @Test
    fun onlyActiveCountdownsAppearOnDashboard() {
        val countdown = CountdownDto(
            id = "countdown-1",
            title = "Trip",
            description = null,
            target = date,
            version = 2,
            createdAt = "2030-01-01T00:00:00Z",
            updatedAt = "2030-01-02T00:00:00Z",
        )

        assertEquals(
            DashboardItem("countdown-1", DashboardSource.COUNTDOWN, "Trip", null, date, 2),
            countdown.toDashboardItemOrNull(),
        )
        assertNull(countdown.copy(deletedAt = "2030-01-03T00:00:00Z").toDashboardItemOrNull())
    }

    private fun task(deadline: Deadline?) = TaskDto(
        id = "task-1",
        title = "Prepare",
        description = "Notes",
        priority = 2,
        deadline = deadline,
        version = 3,
        createdAt = "2030-01-01T00:00:00Z",
        updatedAt = "2030-01-02T00:00:00Z",
    )
}
