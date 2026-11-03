package com.thassan.bohoman.core.repository

import com.thassan.bohoman.contracts.*
import com.thassan.bohoman.core.network.BohomanApi

/** Foreground refresh deliberately fetches complete lists; no timestamp-based sync cursor. */
internal suspend fun <T> loadAll(page: suspend (limit: Int, offset: Int) -> List<T>): List<T> {
    val result = mutableListOf<T>()
    while (true) {
        val next = page(100, result.size)
        result.addAll(next)
        if (next.size < 100) return result
    }
}

class TaskRepository(private val api: BohomanApi) {
    suspend fun list(): List<TaskDto> = loadAll(api::tasks)
    suspend fun create(value: CreateTaskRequest): TaskDto = api.createTask(value)
    suspend fun update(id: String, value: UpdateTaskRequest): TaskDto = api.updateTask(id, value)
    suspend fun delete(value: TaskDto): TaskDto = api.deleteTask(value.id, value.version)
}

class CountdownRepository(private val api: BohomanApi) {
    suspend fun list(): List<CountdownDto> = loadAll(api::countdowns)
    suspend fun create(value: CreateCountdownRequest): CountdownDto = api.createCountdown(value)
    suspend fun update(id: String, value: UpdateCountdownRequest): CountdownDto = api.updateCountdown(id, value)
    suspend fun delete(value: CountdownDto): CountdownDto = api.deleteCountdown(value.id, value.version)
}

class DashboardRepository(private val api: BohomanApi) {
    suspend fun list(): List<DashboardItem> = loadAll(api::dashboard)
}
