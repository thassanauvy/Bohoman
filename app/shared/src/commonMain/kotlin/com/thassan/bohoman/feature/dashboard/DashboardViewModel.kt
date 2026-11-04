package com.thassan.bohoman.feature.dashboard

import com.thassan.bohoman.contracts.*
import com.thassan.bohoman.core.network.ApiException
import com.thassan.bohoman.core.network.BohomanApi
import com.thassan.bohoman.core.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Shared presentation state. The supplied scope follows the screen's lifecycle. */
class DashboardViewModel(private val api: BohomanApi, private val scope: CoroutineScope) {
    data class State(
        val initializing: Boolean = true,
        val busy: Boolean = false,
        val restoreFailed: Boolean = false,
        val user: UserDto? = null,
        val tasks: List<TaskDto> = emptyList(),
        val countdowns: List<CountdownDto> = emptyList(),
        val dashboard: List<DashboardItem> = emptyList(),
        val error: String? = null,
    )
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val tasks = TaskRepository(api)
    private val countdowns = CountdownRepository(api)
    private val dashboard = DashboardRepository(api)

    fun restore() = work {
        mutableState.update { it.copy(restoreFailed = false) }
        val user = try {
            api.restoreSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(restoreFailed = true) }
            throw failure
        }
        mutableState.update { it.copy(user = user, restoreFailed = false) }
        if (user != null) loadData()
    }

    fun signIn(provider: suspend () -> String) = work {
        val user = api.signIn(provider())
        mutableState.update { it.copy(user = user, restoreFailed = false) }
        loadData()
    }

    fun refresh() {
        if (mutableState.value.user != null) work { loadData() }
    }

    fun logout() = work {
        api.logout()
        mutableState.value = State(initializing = false, busy = true)
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    fun saveTask(original: TaskDto?, value: CreateTaskRequest, completed: Boolean, onSaved: () -> Unit) = work {
        val saved = if (original == null) tasks.create(value)
        else tasks.update(original.id, UpdateTaskRequest(original.version, value.title, value.description, completed, value.priority, value.deadline))
        applySavedTask(saved)
        onSaved()
        reloadAfterMutation()
    }

    fun toggleTask(task: TaskDto) = work {
        val saved = tasks.update(task.id, UpdateTaskRequest(task.version, task.title, task.description, !task.completed, task.priority, task.deadline))
        applySavedTask(saved)
        reloadAfterMutation()
    }

    fun deleteTask(task: TaskDto, onDeleted: () -> Unit) = work {
        applySavedTask(tasks.delete(task))
        onDeleted()
        reloadAfterMutation()
    }

    fun saveCountdown(original: CountdownDto?, value: CreateCountdownRequest, onSaved: () -> Unit) = work {
        val saved = if (original == null) countdowns.create(value)
        else countdowns.update(original.id, UpdateCountdownRequest(original.version, value.title, value.description, value.target))
        applySavedCountdown(saved)
        onSaved()
        reloadAfterMutation()
    }

    fun deleteCountdown(countdown: CountdownDto, onDeleted: () -> Unit) = work {
        applySavedCountdown(countdowns.delete(countdown))
        onDeleted()
        reloadAfterMutation()
    }

    // A successful write is authoritative, even if the subsequent full reload loses its connection.
    // Keep the returned version so the next edit never reuses the pre-save version.
    private fun applySavedTask(saved: TaskDto) = mutableState.update { current ->
        val taskList = current.tasks.filterNot { it.id == saved.id } + listOfNotNull(saved.takeIf { it.deletedAt == null })
        val item = saved.toDashboardItemOrNull()
        current.copy(tasks = taskList, dashboard = current.dashboard.filterNot { it.source == DashboardSource.TASK && it.id == saved.id } + listOfNotNull(item))
    }

    private fun applySavedCountdown(saved: CountdownDto) = mutableState.update { current ->
        val countdownList = current.countdowns.filterNot { it.id == saved.id } + listOfNotNull(saved.takeIf { it.deletedAt == null })
        val item = saved.toDashboardItemOrNull()
        current.copy(countdowns = countdownList, dashboard = current.dashboard.filterNot { it.source == DashboardSource.COUNTDOWN && it.id == saved.id } + listOfNotNull(item))
    }

    private suspend fun reloadAfterMutation() {
        try {
            loadData()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is ApiException && failure.status == 401) throw failure
            mutableState.update { it.copy(error = "Your change was saved. The other lists could not refresh; try Refresh when your connection is available.") }
        }
    }

    private suspend fun loadData() = coroutineScope {
        val taskList = async { tasks.list() }
        val countdownList = async { countdowns.list() }
        val dashboardList = async { dashboard.list() }
        val savedTasks = taskList.await()
        val savedCountdowns = countdownList.await()
        val savedDashboard = dashboardList.await()
        mutableState.update { it.copy(tasks = savedTasks, countdowns = savedCountdowns, dashboard = savedDashboard) }
    }

    private fun work(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiException) {
                when (failure.status) {
                    401 -> mutableState.value = State(initializing = false, busy = true, error = "Your session expired. Please sign in again.")
                    409 -> {
                        try { loadData() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep last visible data. */ }
                        mutableState.update { it.copy(error = "This item changed on another device. Close and reopen the editor to review the latest version.") }
                    }
                    else -> mutableState.update { it.copy(error = failure.message) }
                }
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message?.take(240) ?: "Unable to connect. Check your connection and try again.") }
            } finally {
                mutableState.update { it.copy(initializing = false, busy = false) }
            }
        }
    }
}
