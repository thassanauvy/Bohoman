package com.thassan.bohoman.contracts

import kotlinx.serialization.Serializable

/** Exactly one of date (ISO local date) or instant (ISO UTC instant) is set.
 * An instant also requires an IANA timeZone. A date never has a time zone. */
@Serializable
data class Deadline(val date: String? = null, val instant: String? = null, val timeZone: String? = null)

@Serializable
data class UserDto(val id: String, val email: String? = null, val displayName: String? = null, val pictureUrl: String? = null)

@Serializable
data class TaskDto(
    val id: String, val title: String, val description: String? = null,
    val completed: Boolean = false, val priority: Int = 0, val deadline: Deadline? = null,
    val completedAt: String? = null, val deletedAt: String? = null, val version: Long,
    val createdAt: String, val updatedAt: String,
)

@Serializable
data class CountdownDto(
    val id: String, val title: String, val description: String? = null, val target: Deadline,
    val deletedAt: String? = null, val version: Long, val createdAt: String, val updatedAt: String,
)

@Serializable
data class CreateTaskRequest(val title: String, val description: String? = null, val priority: Int = 0, val deadline: Deadline? = null)

/** PATCH sends the complete editable state; null explicitly clears optional fields. */
@Serializable
data class UpdateTaskRequest(
    val expectedVersion: Long, val title: String, val description: String? = null,
    val completed: Boolean = false, val priority: Int = 0, val deadline: Deadline? = null,
)

@Serializable
data class CreateCountdownRequest(val title: String, val description: String? = null, val target: Deadline)

@Serializable
data class UpdateCountdownRequest(val expectedVersion: Long, val title: String, val description: String? = null, val target: Deadline)

@Serializable
enum class DashboardSource { TASK, COUNTDOWN }

/** A projection of a task or countdown, never a separately persisted resource. */
@Serializable
data class DashboardItem(
    val id: String, val source: DashboardSource, val title: String, val description: String? = null,
    val target: Deadline, val version: Long,
)

@Serializable
data class GoogleAuthRequest(val idToken: String, val deviceLabel: String? = null, val browser: Boolean = false)

@Serializable
data class RefreshRequest(val refreshToken: String? = null)

@Serializable
data class LogoutRequest(val refreshToken: String? = null)

@Serializable
data class SessionDto(val accessToken: String, val refreshToken: String? = null, val expiresInSeconds: Long, val user: UserDto)

@Serializable
data class ApiError(val code: String, val message: String)
