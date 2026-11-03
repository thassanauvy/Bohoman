package com.thassan.bohoman.data

import com.thassan.bohoman.contracts.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

data class SessionRecord(val id: UUID, val userId: UUID)

/** All item access requires the authenticated user ID and includes it in SQL. */
class JdbcStore(private val dataSource: DataSource) {
    fun findOrCreateGoogleUser(subject: String, email: String?, displayName: String?, pictureUrl: String?): UserDto {
        if (subject.isBlank() || subject.length > 255) invalid("Invalid Google subject")
        if ((email?.length ?: 0) > 320 || (displayName?.length ?: 0) > 200 || (pictureUrl?.length ?: 0) > 2048) {
            invalid("Google profile exceeds supported lengths")
        }
        return transaction { connection ->
            // Serialize first sign-in for the same immutable identity; no orphan users on a race.
            connection.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "google:$subject") { Unit }
            val existingId = connection.query(
                "SELECT user_id FROM auth_identities WHERE provider = 'google' AND provider_subject = ?", subject
            ) { it.getObject("user_id", UUID::class.java) }.singleOrNull()
            if (existingId != null) {
                connection.query(
                    "UPDATE app_users SET email = ?, display_name = ?, picture_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING *",
                    email, displayName, pictureUrl, existingId, mapper = ::readUser
                ).single()
            } else {
                val created = connection.query(
                    "INSERT INTO app_users(email, display_name, picture_url) VALUES (?, ?, ?) RETURNING *",
                    email, displayName, pictureUrl, mapper = ::readUser
                ).single()
                connection.execute(
                    "INSERT INTO auth_identities(provider, provider_subject, user_id) VALUES ('google', ?, ?)",
                    subject, UUID.fromString(created.id)
                )
                created
            }
        }
    }

    fun findUser(userId: UUID): UserDto? = read { connection ->
        connection.query("SELECT * FROM app_users WHERE id = ?", userId, mapper = ::readUser).singleOrNull()
    }

    fun createSession(userId: UUID, tokenHash: String, deviceLabel: String?, expiresAt: Instant): UUID {
        validateHash(tokenHash)
        if ((deviceLabel?.length ?: 0) > 100) invalid("Device label is limited to 100 characters")
        return read { connection ->
            connection.query(
                "INSERT INTO user_sessions(user_id, refresh_token_hash, device_label, expires_at) VALUES (?, ?, ?, ?) RETURNING id",
                userId, tokenHash, deviceLabel, expiresAt
            ) { it.getObject("id", UUID::class.java) }.single()
        }
    }

    /** Rotation and retirement commit together. Reuse revokes the whole session. */
    fun rotateSession(tokenHash: String, newTokenHash: String, newExpiresAt: Instant): SessionRecord? {
        validateHash(tokenHash)
        validateHash(newTokenHash)
        require(tokenHash != newTokenHash) { "Refresh tokens must rotate" }
        return transaction { connection ->
            connection.lockToken(tokenHash)
            val found = connection.query(
                "SELECT id, user_id, (expires_at > clock_timestamp() AND revoked_at IS NULL) AS active FROM user_sessions WHERE refresh_token_hash = ? FOR UPDATE",
                tokenHash
            ) { Triple(it.getObject("id", UUID::class.java), it.getObject("user_id", UUID::class.java), it.getBoolean("active")) }.singleOrNull()
            if (found == null) {
                connection.execute(
                    "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE id IN (SELECT session_id FROM retired_session_tokens WHERE token_hash = ?)",
                    tokenHash
                )
                null
            } else if (!found.third) {
                null
            } else {
                connection.execute("INSERT INTO retired_session_tokens(token_hash, session_id) VALUES (?, ?)", tokenHash, found.first)
                connection.execute(
                    "UPDATE user_sessions SET refresh_token_hash = ?, expires_at = ?, rotated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    newTokenHash, newExpiresAt, found.first
                )
                SessionRecord(found.first, found.second)
            }
        }
    }

    fun revokeSession(tokenHash: String) {
        validateHash(tokenHash)
        transaction { connection ->
            connection.lockToken(tokenHash)
            connection.execute(
                "UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE refresh_token_hash = ? OR id IN (SELECT session_id FROM retired_session_tokens WHERE token_hash = ?)",
                tokenHash, tokenHash
            )
        }
    }

    fun isSessionActive(sessionId: UUID, userId: UUID): Boolean = read { connection ->
        connection.query(
            "SELECT 1 FROM user_sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()",
            sessionId, userId
        ) { true }.singleOrNull() ?: false
    }

    fun listTasks(userId: UUID, limit: Int = 100, offset: Int = 0): List<TaskDto> {
        validatePage(limit, offset)
        return read { it.query(
            "SELECT * FROM tasks WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
            userId, limit, offset, mapper = ::readTask
        ) }
    }

    fun createTask(userId: UUID, request: CreateTaskRequest): TaskDto {
        val title = validateTitle(request.title)
        validateDescription(request.description)
        validatePriority(request.priority)
        validateDeadline(request.deadline)
        return read { it.query(
            "INSERT INTO tasks(user_id, title, description, priority, due_date, due_at, due_timezone) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *",
            userId, title, request.description, request.priority, request.deadline.dateValue(), request.deadline.instantValue(), request.deadline?.timeZone,
            mapper = ::readTask
        ).single() }
    }

    fun updateTask(userId: UUID, id: UUID, request: UpdateTaskRequest): TaskDto {
        val title = validateTitle(request.title)
        validateDescription(request.description)
        validatePriority(request.priority)
        validateDeadline(request.deadline)
        validateVersion(request.expectedVersion)
        return read { connection ->
            connection.query(
                """UPDATE tasks SET title = ?, description = ?, completed = ?, priority = ?, due_date = ?, due_at = ?, due_timezone = ?,
                    completed_at = CASE WHEN ? THEN COALESCE(completed_at, CURRENT_TIMESTAMP) ELSE NULL END
                    WHERE id = ? AND user_id = ? AND version = ? AND deleted_at IS NULL RETURNING *""",
                title, request.description, request.completed, request.priority, request.deadline.dateValue(), request.deadline.instantValue(), request.deadline?.timeZone,
                request.completed, id, userId, request.expectedVersion, mapper = ::readTask
            ).singleOrNull() ?: missingOrConflict(connection, "tasks", userId, id)
        }
    }

    fun deleteTask(userId: UUID, id: UUID, expectedVersion: Long? = null): TaskDto = deleteItem("tasks", userId, id, expectedVersion, ::readTask)

    fun listCountdowns(userId: UUID, limit: Int = 100, offset: Int = 0): List<CountdownDto> {
        validatePage(limit, offset)
        return read { it.query(
            "SELECT * FROM countdowns WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
            userId, limit, offset, mapper = ::readCountdown
        ) }
    }

    fun createCountdown(userId: UUID, request: CreateCountdownRequest): CountdownDto {
        val title = validateTitle(request.title)
        validateDescription(request.description)
        validateDeadline(request.target)
        return read { it.query(
            "INSERT INTO countdowns(user_id, title, description, target_date, target_at, target_timezone) VALUES (?, ?, ?, ?, ?, ?) RETURNING *",
            userId, title, request.description, request.target.dateValue(), request.target.instantValue(), request.target.timeZone,
            mapper = ::readCountdown
        ).single() }
    }

    fun updateCountdown(userId: UUID, id: UUID, request: UpdateCountdownRequest): CountdownDto {
        val title = validateTitle(request.title)
        validateDescription(request.description)
        validateDeadline(request.target)
        validateVersion(request.expectedVersion)
        return read { connection ->
            connection.query(
                """UPDATE countdowns SET title = ?, description = ?, target_date = ?, target_at = ?, target_timezone = ?
                    WHERE id = ? AND user_id = ? AND version = ? AND deleted_at IS NULL RETURNING *""",
                title, request.description, request.target.dateValue(), request.target.instantValue(), request.target.timeZone,
                id, userId, request.expectedVersion, mapper = ::readCountdown
            ).singleOrNull() ?: missingOrConflict(connection, "countdowns", userId, id)
        }
    }

    fun deleteCountdown(userId: UUID, id: UUID, expectedVersion: Long? = null): CountdownDto = deleteItem("countdowns", userId, id, expectedVersion, ::readCountdown)

    fun dashboard(userId: UUID, limit: Int = 100, offset: Int = 0): List<DashboardItem> {
        validatePage(limit, offset)
        return read { connection ->
            connection.query(
                """SELECT * FROM (
                    SELECT id, 'TASK' AS source, title, description, due_date AS target_date, due_at AS target_at, due_timezone AS target_timezone, version
                    FROM tasks WHERE user_id = ? AND deleted_at IS NULL AND NOT completed AND (due_date IS NOT NULL OR due_at IS NOT NULL)
                    UNION ALL
                    SELECT id, 'COUNTDOWN' AS source, title, description, target_date, target_at, target_timezone, version
                    FROM countdowns WHERE user_id = ? AND deleted_at IS NULL
                ) items
                ORDER BY COALESCE(target_date, (target_at AT TIME ZONE target_timezone)::date), target_at NULLS FIRST, id, source
                LIMIT ? OFFSET ?""", userId, userId, limit, offset
            ) { row ->
                DashboardItem(
                    id = row.getString("id"), source = DashboardSource.valueOf(row.getString("source")),
                    title = row.getString("title"), description = row.getString("description"),
                    target = row.deadline("target")!!, version = row.getLong("version")
                )
            }
        }
    }

    private fun <T> deleteItem(table: String, userId: UUID, id: UUID, expectedVersion: Long?, mapper: (ResultSet) -> T): T {
        expectedVersion?.let(::validateVersion)
        return read { connection ->
            val versionClause = if (expectedVersion != null) " AND version = ?" else ""
            val values = if (expectedVersion != null) arrayOf<Any?>(id, userId, expectedVersion) else arrayOf<Any?>(id, userId)
            connection.query(
                "UPDATE $table SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND deleted_at IS NULL$versionClause RETURNING *",
                *values, mapper = mapper
            ).singleOrNull() ?: missingOrConflict(connection, table, userId, id)
        }
    }

    private fun missingOrConflict(connection: Connection, table: String, userId: UUID, id: UUID): Nothing {
        val owned = connection.query("SELECT 1 FROM $table WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, userId) { true }.isNotEmpty()
        if (owned) throw ApiException(409, "version_conflict", "This item changed on another device. Refresh before editing again.")
        throw ApiException(404, "not_found", "Item not found")
    }

    private fun <T> read(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }
}

private fun validateHash(hash: String) {
    require(Regex("[0-9a-f]{64}").matches(hash)) { "Expected a SHA-256 refresh token hash" }
}

private fun Deadline?.dateValue(): LocalDate? = this?.date?.let(LocalDate::parse)
private fun Deadline?.instantValue(): Instant? = this?.instant?.let(Instant::parse)

// Logout and rotation of the same token must see each other's committed state.
// Row locks alone are insufficient when rotation changes the lookup key.
private fun Connection.lockToken(hash: String) {
    query("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", hash) { Unit }
}

private fun PreparedStatement.bind(values: Array<out Any?>) {
    values.forEachIndexed { index, value ->
        setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
    }
}

private fun Connection.execute(sql: String, vararg values: Any?): Int = prepareStatement(sql).use {
    it.bind(values)
    it.executeUpdate()
}

private fun <T> Connection.query(sql: String, vararg values: Any?, mapper: (ResultSet) -> T): List<T> = prepareStatement(sql).use {
    it.bind(values)
    it.executeQuery().use { rows -> buildList { while (rows.next()) add(mapper(rows)) } }
}

private fun ResultSet.instant(column: String): String? = getObject(column, OffsetDateTime::class.java)?.toInstant()?.toString()

private fun ResultSet.deadline(prefix: String): Deadline? {
    val date = getObject("${prefix}_date", LocalDate::class.java)?.toString()
    val instant = instant("${prefix}_at")
    return if (date == null && instant == null) null else Deadline(date = date, instant = instant, timeZone = getString("${prefix}_timezone"))
}

private fun readUser(row: ResultSet): UserDto = UserDto(
    id = row.getString("id"), email = row.getString("email"), displayName = row.getString("display_name"), pictureUrl = row.getString("picture_url")
)

private fun readTask(row: ResultSet): TaskDto = TaskDto(
    id = row.getString("id"), title = row.getString("title"), description = row.getString("description"),
    completed = row.getBoolean("completed"), priority = row.getInt("priority"), deadline = row.deadline("due"),
    completedAt = row.instant("completed_at"), deletedAt = row.instant("deleted_at"), version = row.getLong("version"),
    createdAt = row.instant("created_at")!!, updatedAt = row.instant("updated_at")!!
)

private fun readCountdown(row: ResultSet): CountdownDto = CountdownDto(
    id = row.getString("id"), title = row.getString("title"), description = row.getString("description"), target = row.deadline("target")!!,
    deletedAt = row.instant("deleted_at"), version = row.getLong("version"), createdAt = row.instant("created_at")!!, updatedAt = row.instant("updated_at")!!
)
