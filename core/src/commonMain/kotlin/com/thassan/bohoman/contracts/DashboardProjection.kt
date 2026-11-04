package com.thassan.bohoman.contracts

/**
 * Derives the dashboard representation of an active task.
 *
 * Dashboard items are projections, not separately persisted entities. Keeping
 * this rule shared prevents an optimistic client update from disagreeing with
 * the server's dashboard query.
 */
fun TaskDto.toDashboardItemOrNull(): DashboardItem? = deadline
    ?.takeIf { !completed && deletedAt == null }
    ?.let { target ->
        DashboardItem(
            id = id,
            source = DashboardSource.TASK,
            title = title,
            description = description,
            target = target,
            version = version,
        )
    }

/** Derives the dashboard representation of an active personal countdown. */
fun CountdownDto.toDashboardItemOrNull(): DashboardItem? = takeIf { deletedAt == null }
    ?.let { countdown ->
        DashboardItem(
            id = countdown.id,
            source = DashboardSource.COUNTDOWN,
            title = countdown.title,
            description = countdown.description,
            target = countdown.target,
            version = countdown.version,
        )
    }
