package com.thassan.bohoman.data

import com.thassan.bohoman.contracts.*
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Set BOHOMAN_TEST_DATABASE_URL to a disposable PostgreSQL database to run. */
class JdbcStoreIntegrationTest {
    private lateinit var source: HikariDataSource
    private lateinit var store: JdbcStore
    private val users = mutableSetOf<UUID>()
    private val url get() = System.getenv("BOHOMAN_TEST_DATABASE_URL")
    private val dbUser get() = System.getenv("BOHOMAN_TEST_DATABASE_USER") ?: "bohoman"
    private val dbPassword get() = System.getenv("BOHOMAN_TEST_DATABASE_PASSWORD") ?: ""

    @Before
    fun setUp() {
        assumeTrue("A disposable PostgreSQL database is required", !url.isNullOrBlank())
        source = Database.open(url!!, dbUser, dbPassword)
        store = JdbcStore(source)
    }

    @After
    fun tearDown() {
        if (::source.isInitialized) {
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM app_users WHERE id = ?").use { statement ->
                    users.forEach { statement.setObject(1, it); statement.executeUpdate() }
                }
            }
            source.close()
        }
    }

    private fun user(subject: String = UUID.randomUUID().toString(), email: String = "a@example.test"): UUID =
        UUID.fromString(store.findOrCreateGoogleUser(subject, email, "Test person", null).id).also(users::add)

    @Test
    fun identityUsesImmutableSubjectAndNeverEmail() {
        val subject = UUID.randomUUID().toString()
        val first = user(subject, "old@example.test")
        assertEquals(first, user(subject, "new@example.test"))
        assertEquals("new@example.test", store.findUser(first)?.email)
        assertNotEquals(first, user(email = "new@example.test"))
    }

    @Test
    fun ownershipCompletionVersionsAndSoftDeletionSurviveAReopenedPool() {
        val owner = user()
        val stranger = user()
        val task = store.createTask(owner, CreateTaskRequest("Ship", priority = 3, deadline = Deadline(date = "2027-03-10")))
        val countdown = store.createCountdown(owner, CreateCountdownRequest("Birthday", target = Deadline(date = "2027-04-12")))
        assertTrue(store.listTasks(stranger).isEmpty())
        assertTrue(store.listCountdowns(stranger).isEmpty())
        assertTrue(store.dashboard(stranger).isEmpty())
        assertEquals(404, assertFailsWith<ApiException> {
            store.updateTask(stranger, UUID.fromString(task.id), UpdateTaskRequest(999, "Guessed"))
        }.status)
        assertEquals(404, assertFailsWith<ApiException> { store.deleteTask(stranger, UUID.fromString(task.id)) }.status)
        assertEquals(404, assertFailsWith<ApiException> {
            store.updateCountdown(stranger, UUID.fromString(countdown.id), UpdateCountdownRequest(1, "Guessed", target = countdown.target))
        }.status)
        assertEquals(404, assertFailsWith<ApiException> { store.deleteCountdown(stranger, UUID.fromString(countdown.id)) }.status)
        assertEquals(2, store.dashboard(owner).size)

        val completed = store.updateTask(owner, UUID.fromString(task.id), UpdateTaskRequest(task.version, task.title, completed = true, deadline = task.deadline))
        assertEquals(task.version + 1, completed.version)
        assertNotNull(completed.completedAt)
        assertEquals(listOf(DashboardSource.COUNTDOWN), store.dashboard(owner).map { it.source })
        assertEquals(409, assertFailsWith<ApiException> {
            store.updateTask(owner, UUID.fromString(task.id), UpdateTaskRequest(task.version, "Stale edit"))
        }.status)
        Database.open(url!!, dbUser, dbPassword).use { reopened ->
            val saved = JdbcStore(reopened).listTasks(owner).single()
            assertTrue(saved.completed)
            assertEquals(completed.version, saved.version)
            assertEquals("2027-03-10", saved.deadline?.date)
        }
        val reopened = store.updateTask(owner, UUID.fromString(task.id), UpdateTaskRequest(completed.version, task.title, completed = false, deadline = task.deadline))
        assertNull(reopened.completedAt)
        assertEquals(2, store.dashboard(owner).size)
        assertEquals(409, assertFailsWith<ApiException> { store.deleteTask(owner, UUID.fromString(task.id), 1) }.status)
        val deleted = store.deleteTask(owner, UUID.fromString(task.id), reopened.version)
        assertNotNull(deleted.deletedAt)
        assertEquals(reopened.version + 1, deleted.version)
        assertTrue(store.listTasks(owner).isEmpty())
        val deletedCountdown = store.deleteCountdown(owner, UUID.fromString(countdown.id), countdown.version)
        assertNotNull(deletedCountdown.deletedAt)
        assertEquals(countdown.version + 1, deletedCountdown.version)
        assertTrue(store.dashboard(owner).isEmpty())
        source.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM tasks WHERE id = ? AND deleted_at IS NOT NULL").use {
                it.setObject(1, UUID.fromString(task.id))
                it.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun dateOnlyValuesAndInstantsRemainStableAcrossDatabaseTimezones() {
        val owner = user()
        val date = store.createTask(owner, CreateTaskRequest("All day", deadline = Deadline(date = "2027-01-01")))
        val exact = store.createCountdown(owner, CreateCountdownRequest("Midnight in Dhaka", target = Deadline(instant = "2026-12-31T18:00:00Z", timeZone = "Asia/Dhaka")))
        source.connection.use { connection ->
            listOf("Asia/Dhaka", "America/Los_Angeles").forEach { zone ->
                connection.prepareStatement("SELECT set_config('TimeZone', ?, false)").use { it.setString(1, zone); it.execute() }
                connection.prepareStatement("SELECT due_date::text FROM tasks WHERE id = ?").use {
                    it.setObject(1, UUID.fromString(date.id))
                    it.executeQuery().use { rows -> rows.next(); assertEquals("2027-01-01", rows.getString(1)) }
                }
                connection.prepareStatement("SELECT target_at FROM countdowns WHERE id = ?").use {
                    it.setObject(1, UUID.fromString(exact.id))
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(Instant.parse("2026-12-31T18:00:00Z"), rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant())
                    }
                }
            }
        }
        assertEquals("2027-01-01", store.listTasks(owner).single().deadline?.date)
        assertEquals("2026-12-31T18:00:00Z", store.listCountdowns(owner).single().target.instant)
    }

    @Test
    fun simultaneousRefreshCannotReuseTokenAndRevokesReplayedSession() {
        val owner = user()
        val oldHash = tokenHash()
        val session = store.createSession(owner, oldHash, "Test", Instant.now().plusSeconds(3600))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<SessionRecord?> {
                    start.await()
                    store.rotateSession(oldHash, tokenHash(), Instant.now().plusSeconds(7200))
                }
            }
            start.countDown()
            val rotated = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, rotated.count { it != null })
            assertFalse(store.isSessionActive(session, owner), "Replaying a retired token must revoke the session")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun expiredSessionsCannotRefreshAndLogoutAcceptsRetiredHash() {
        val owner = user()
        val expiredHash = tokenHash()
        val expired = store.createSession(owner, expiredHash, null, Instant.now().minusSeconds(1))
        assertNull(store.rotateSession(expiredHash, tokenHash(), Instant.now().plusSeconds(3600)))
        assertFalse(store.isSessionActive(expired, owner))

        val initial = tokenHash()
        val replacement = tokenHash()
        val active = store.createSession(owner, initial, null, Instant.now().plusSeconds(3600))
        assertNotNull(store.rotateSession(initial, replacement, Instant.now().plusSeconds(3600)))
        assertTrue(store.isSessionActive(active, owner))
        store.revokeSession(initial)
        assertFalse(store.isSessionActive(active, owner))
        assertNull(store.rotateSession(replacement, tokenHash(), Instant.now().plusSeconds(3600)))
    }

    @Test
    fun logoutAlwaysRevokesWhenRacingRefresh() {
        val owner = user()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(12) {
                val initial = tokenHash()
                val session = store.createSession(owner, initial, null, Instant.now().plusSeconds(3600))
                val start = CountDownLatch(1)
                val refresh = executor.submit<SessionRecord?> {
                    start.await()
                    store.rotateSession(initial, tokenHash(), Instant.now().plusSeconds(3600))
                }
                val logout = executor.submit {
                    start.await()
                    store.revokeSession(initial)
                }
                start.countDown()
                refresh.get(10, TimeUnit.SECONDS)
                logout.get(10, TimeUnit.SECONDS)
                assertFalse(store.isSessionActive(session, owner), "Logout must win over concurrent refresh")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun tokenHash(): String = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
}
