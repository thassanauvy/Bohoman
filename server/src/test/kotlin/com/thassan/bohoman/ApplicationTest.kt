package com.thassan.bohoman

import com.thassan.bohoman.auth.GoogleIdentity
import com.thassan.bohoman.auth.IdentityVerifier
import com.thassan.bohoman.contracts.*
import com.thassan.bohoman.data.Database
import com.thassan.bohoman.data.JdbcStore
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.*

/** Runs against PostgreSQL (including Flyway), never H2 or an in-memory replacement. */
class ApplicationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun independentDeviceSessionsSeeTheSameTasksAndCountdowns() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        val subject = "devices-${UUID.randomUUID()}"
        val android = login(client, subject)
        val web = client.post("/api/v1/auth/google") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header("X-Bohoman-CSRF", "1")
            jsonBody(GoogleAuthRequest(subject, "Web", browser = true))
        }.expect<SessionDto>()
        assertEquals(android.user.id, web.user.id)
        assertNotEquals(android.accessToken, web.accessToken)
        val task = client.post("/api/v1/tasks") {
            bearerAuth(android.accessToken)
            jsonBody(CreateTaskRequest("Created on Android", deadline = Deadline(date = "2030-11-03")))
        }.expect<TaskDto>(HttpStatusCode.Created)
        val countdown = client.post("/api/v1/countdowns") {
            bearerAuth(android.accessToken)
            jsonBody(CreateCountdownRequest("Trip", target = Deadline(date = "2030-12-03")))
        }.expect<CountdownDto>(HttpStatusCode.Created)
        assertEquals(listOf(task), client.get("/api/v1/tasks") { bearerAuth(web.accessToken) }.expect<List<TaskDto>>())
        assertEquals(listOf(countdown), client.get("/api/v1/countdowns") { bearerAuth(web.accessToken) }.expect<List<CountdownDto>>())
        val completed = client.patch("/api/v1/tasks/${task.id}") {
            bearerAuth(web.accessToken)
            jsonBody(UpdateTaskRequest(task.version, task.title, completed = true, deadline = task.deadline))
        }.expect<TaskDto>()
        assertEquals(listOf(completed), client.get("/api/v1/tasks") { bearerAuth(android.accessToken) }.expect<List<TaskDto>>())
        val rescheduled = client.patch("/api/v1/countdowns/${countdown.id}") {
            bearerAuth(web.accessToken)
            jsonBody(UpdateCountdownRequest(countdown.version, countdown.title, target = Deadline(date = "2030-12-04")))
        }.expect<CountdownDto>()
        assertEquals(countdown.version + 1, rescheduled.version)
        assertEquals(listOf(rescheduled), client.get("/api/v1/countdowns") { bearerAuth(android.accessToken) }.expect<List<CountdownDto>>())
        client.patch("/api/v1/countdowns/${countdown.id}") {
            bearerAuth(android.accessToken)
            jsonBody(UpdateCountdownRequest(countdown.version, "Stale edit", target = countdown.target))
        }.expectError(HttpStatusCode.Conflict)
        client.delete("/api/v1/countdowns/${countdown.id}?expectedVersion=${countdown.version}") {
            bearerAuth(android.accessToken)
        }.expectError(HttpStatusCode.Conflict)
        client.delete("/api/v1/countdowns/${countdown.id}?expectedVersion=${rescheduled.version}") {
            bearerAuth(web.accessToken)
        }.expect<CountdownDto>()
        assertTrue(client.get("/api/v1/countdowns") { bearerAuth(android.accessToken) }.expect<List<CountdownDto>>().isEmpty())
        assertTrue(client.get("/api/v1/dashboard") { bearerAuth(android.accessToken) }.expect<List<DashboardItem>>().isEmpty())
    }

    @Test
    fun rejectsOversizedAndInvalidUtf8BodiesBeforeAuthentication() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        client.post("/api/v1/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(" ".repeat(65_537))
        }.expectError(HttpStatusCode.PayloadTooLarge)
        // A streamed body has no Content-Length, and UTF-8 bytes can exceed its character count.
        val streamed = """{"idToken":"${"অ".repeat(22_000)}"}""".encodeToByteArray()
        client.post("/api/v1/auth/google") {
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.Application.Json
                override fun readFrom() = ByteReadChannel(streamed)
            })
        }.expectError(HttpStatusCode.PayloadTooLarge)
        client.post("/api/v1/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(byteArrayOf(0xff.toByte()))
        }.expectError(HttpStatusCode.BadRequest)
    }

    @Test
    fun ownershipVersioningDateSemanticsAndPersistence() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        val a = login(client, "a-${UUID.randomUUID()}")
        val b = login(client, "b-${UUID.randomUUID()}")
        val task = client.post("/api/v1/tasks") {
            bearerAuth(a.accessToken)
            jsonBody(CreateTaskRequest("Prepare for Eid", "A calendar date stays a calendar date", 2, Deadline(date = "2030-02-05")))
        }.expect<TaskDto>(HttpStatusCode.Created)
        val countdown = client.post("/api/v1/countdowns") {
            bearerAuth(a.accessToken)
            jsonBody(CreateCountdownRequest("Flight", target = Deadline(instant = "2030-05-10T04:00:00Z", timeZone = "Asia/Dhaka")))
        }.expect<CountdownDto>(HttpStatusCode.Created)
        assertEquals("2030-02-05", task.deadline?.date)
        assertNull(task.deadline?.instant)
        assertNull(task.deadline?.timeZone)
        assertEquals("2030-05-10T04:00:00Z", countdown.target.instant)
        assertEquals("Asia/Dhaka", countdown.target.timeZone)
        assertEquals(1L, task.version)
        assertEquals(1, client.get("/api/v1/countdowns") { bearerAuth(a.accessToken) }.expect<List<CountdownDto>>().size)
        val dashboard = client.get("/api/v1/dashboard") { bearerAuth(a.accessToken) }.expect<List<DashboardItem>>()
        assertEquals(setOf(task.id, countdown.id), dashboard.map { it.id }.toSet())
        assertEquals(setOf(DashboardSource.TASK, DashboardSource.COUNTDOWN), dashboard.map { it.source }.toSet())
        assertTrue(client.get("/api/v1/tasks") { bearerAuth(b.accessToken) }.expect<List<TaskDto>>().isEmpty())
        assertTrue(client.get("/api/v1/countdowns") { bearerAuth(b.accessToken) }.expect<List<CountdownDto>>().isEmpty())
        assertTrue(client.get("/api/v1/dashboard") { bearerAuth(b.accessToken) }.expect<List<DashboardItem>>().isEmpty())
        client.patch("/api/v1/tasks/${task.id}") {
            bearerAuth(b.accessToken)
            jsonBody(UpdateTaskRequest(task.version, "Stolen"))
        }.expectError(HttpStatusCode.NotFound)
        client.delete("/api/v1/tasks/${task.id}?expectedVersion=${task.version}") {
            bearerAuth(b.accessToken)
        }.expectError(HttpStatusCode.NotFound)
        client.patch("/api/v1/countdowns/${countdown.id}") {
            bearerAuth(b.accessToken)
            jsonBody(UpdateCountdownRequest(countdown.version, "Stolen", target = countdown.target))
        }.expectError(HttpStatusCode.NotFound)
        client.delete("/api/v1/countdowns/${countdown.id}?expectedVersion=${countdown.version}") {
            bearerAuth(b.accessToken)
        }.expectError(HttpStatusCode.NotFound)
        val completed = client.patch("/api/v1/tasks/${task.id}") {
            bearerAuth(a.accessToken)
            jsonBody(UpdateTaskRequest(task.version, task.title, task.description, true, task.priority, task.deadline))
        }.expect<TaskDto>()
        assertEquals(2L, completed.version)
        assertTrue(completed.completed)
        assertNotNull(completed.completedAt)
        client.patch("/api/v1/tasks/${task.id}") {
            bearerAuth(a.accessToken)
            jsonBody(UpdateTaskRequest(task.version, "Old edit"))
        }.expectError(HttpStatusCode.Conflict)
        client.delete("/api/v1/tasks/${task.id}?expectedVersion=${task.version}") {
            bearerAuth(a.accessToken)
        }.expectError(HttpStatusCode.Conflict)
        val currentDashboard = client.get("/api/v1/dashboard") { bearerAuth(a.accessToken) }.expect<List<DashboardItem>>()
        assertEquals(listOf(countdown.id), currentDashboard.map { it.id })
        // A second pool and repository use fresh connections, as a restarted server would.
        Database.open(config.databaseUrl, config.databaseUser, config.databasePassword).use { reopened ->
            val persisted = JdbcStore(reopened).listTasks(UUID.fromString(a.user.id)).single()
            assertTrue(persisted.completed)
            assertEquals(completed.version, persisted.version)
        }
        val deleted = client.delete("/api/v1/tasks/${task.id}?expectedVersion=${completed.version}") {
            bearerAuth(a.accessToken)
        }.expect<TaskDto>()
        assertNotNull(deleted.deletedAt)
        assertEquals(3L, deleted.version)
        assertTrue(client.get("/api/v1/tasks") { bearerAuth(a.accessToken) }.expect<List<TaskDto>>().isEmpty())
        val deletedCountdown = client.delete("/api/v1/countdowns/${countdown.id}?expectedVersion=${countdown.version}") {
            bearerAuth(a.accessToken)
        }.expect<CountdownDto>()
        assertNotNull(deletedCountdown.deletedAt)
        assertEquals(2L, deletedCountdown.version)
        assertTrue(client.get("/api/v1/dashboard") { bearerAuth(a.accessToken) }.expect<List<DashboardItem>>().isEmpty())
    }

    @Test
    fun authenticationRotationReplayAndLogout() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        client.get("/api/v1/tasks").expectError(HttpStatusCode.Unauthorized)
        client.get("/api/v1/tasks") { bearerAuth("made-up-token") }.expectError(HttpStatusCode.Unauthorized)
        val subject = "session-${UUID.randomUUID()}"
        val original = login(client, subject)
        val secondDevice = login(client, subject)
        assertEquals(original.user.id, secondDevice.user.id)
        val rotated = client.post("/api/v1/auth/refresh") { jsonBody(RefreshRequest(original.refreshToken)) }.expect<SessionDto>()
        assertNotEquals(original.refreshToken, rotated.refreshToken)
        client.get("/api/v1/me") { bearerAuth(rotated.accessToken) }.expect<UserDto>()
        // Replaying the old credential revokes its whole session, including the latest access token.
        client.post("/api/v1/auth/refresh") { jsonBody(RefreshRequest(original.refreshToken)) }.expectError(HttpStatusCode.Unauthorized)
        client.get("/api/v1/me") { bearerAuth(rotated.accessToken) }.expectError(HttpStatusCode.Unauthorized)
        client.post("/api/v1/auth/refresh") { jsonBody(RefreshRequest(rotated.refreshToken)) }.expectError(HttpStatusCode.Unauthorized)
        client.get("/api/v1/me") { bearerAuth(secondDevice.accessToken) }.expect<UserDto>()
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") { jsonBody(LogoutRequest(secondDevice.refreshToken)) }.status)
        client.get("/api/v1/me") { bearerAuth(secondDevice.accessToken) }.expectError(HttpStatusCode.Unauthorized)
        client.post("/api/v1/auth/refresh") { jsonBody(RefreshRequest(secondDevice.refreshToken)) }.expectError(HttpStatusCode.Unauthorized)
    }

    @Test
    fun browserCookiesHaveNoRefreshTokenInJsonAndRequireCsrfProtection() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        val request = GoogleAuthRequest("web-${UUID.randomUUID()}", browser = true)
        client.post("/api/v1/auth/google") { jsonBody(request) }.expectError(HttpStatusCode.Forbidden)
        client.post("/api/v1/auth/google") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            jsonBody(request)
        }.expectError(HttpStatusCode.Forbidden)
        val response = client.post("/api/v1/auth/google") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header("X-Bohoman-CSRF", "1")
            jsonBody(request)
        }
        val session = response.expect<SessionDto>()
        assertNull(session.refreshToken)
        val setCookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(setCookie.contains("SameSite=Strict", ignoreCase = true))
        assertTrue(setCookie.contains("Path=/", ignoreCase = true))
        val cookie = setCookie.substringBefore(';')
        client.post("/api/v1/auth/refresh") {
            header(HttpHeaders.Cookie, cookie)
            jsonBody(RefreshRequest())
        }.expectError(HttpStatusCode.Forbidden)
        client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, cookie)
            jsonBody(LogoutRequest())
        }.expectError(HttpStatusCode.Forbidden)
        val refreshed = client.post("/api/v1/auth/refresh") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "http://localhost:8081")
            header("X-Bohoman-CSRF", "1")
            jsonBody(RefreshRequest())
        }
        assertNull(refreshed.expect<SessionDto>().refreshToken)
        val rotatedCookie = assertNotNull(refreshed.headers[HttpHeaders.SetCookie]).substringBefore(';')
        assertNotEquals(cookie, rotatedCookie)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, rotatedCookie)
            header(HttpHeaders.Origin, "http://localhost:8081")
            header("X-Bohoman-CSRF", "1")
            jsonBody(LogoutRequest())
        }.status)
    }

    @Test
    fun validatesResourceFieldsAndPagination() = databaseTest { store, config ->
        application { apiModule(config, store, testIdentityVerifier()) }
        val a = login(client, "validation-${UUID.randomUUID()}")
        listOf(
            CreateTaskRequest(""),
            CreateTaskRequest("A", priority = 4),
            CreateTaskRequest("Invalid\u0000title"),
            CreateTaskRequest("A", description = "Invalid\u0000description"),
            CreateTaskRequest("A", deadline = Deadline(date = "2030-02-30")),
            CreateTaskRequest("A", deadline = Deadline(date = "2030-02-05", instant = "2030-02-05T00:00:00Z", timeZone = "Asia/Dhaka")),
            CreateTaskRequest("A", deadline = Deadline(instant = "2030-02-05T00:00:00Z", timeZone = "Invalid/Timezone")),
            CreateTaskRequest("A", deadline = Deadline(instant = "2030-02-05T00:00:00Z")),
        ).forEach { invalid ->
            client.post("/api/v1/tasks") { bearerAuth(a.accessToken); jsonBody(invalid) }.expectError(HttpStatusCode.BadRequest)
        }
        client.post("/api/v1/countdowns") {
            bearerAuth(a.accessToken)
            jsonBody(CreateCountdownRequest("Target required", target = Deadline()))
        }.expectError(HttpStatusCode.BadRequest)
        listOf("limit=0", "limit=101", "offset=-1", "offset=oops").forEach { query ->
            client.get("/api/v1/tasks?$query") { bearerAuth(a.accessToken) }.expectError(HttpStatusCode.BadRequest)
        }
        client.post("/api/v1/tasks") {
            bearerAuth(a.accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"No ownership injection","userId":"${UUID.randomUUID()}"}""")
        }.expectError(HttpStatusCode.BadRequest)
    }

    private fun databaseTest(block: suspend ApplicationTestBuilder.(JdbcStore, ServerConfig) -> Unit) {
        val url = System.getenv("BOHOMAN_TEST_DATABASE_URL")
        assumeTrue("Set BOHOMAN_TEST_DATABASE_URL to run PostgreSQL integration tests", !url.isNullOrBlank())
        val config = ServerConfig(
            databaseUrl = requireNotNull(url),
            databaseUser = System.getenv("BOHOMAN_TEST_DATABASE_USER") ?: "thassanauvy",
            databasePassword = System.getenv("BOHOMAN_TEST_DATABASE_PASSWORD") ?: "",
            jwtSecret = "integration-test-secret-with-at-least-32-bytes",
            googleClientIds = setOf("test-client"),
            webOrigins = setOf("http://localhost:8081"),
            secureCookies = false,
        )
        Database.open(config.databaseUrl, config.databaseUser, config.databasePassword).use { database ->
            testApplication { block(JdbcStore(database), config) }
        }
    }

    private fun testIdentityVerifier() = IdentityVerifier { token -> GoogleIdentity(token, "$token@example.test", "Test user", null) }
    private suspend fun login(client: HttpClient, subject: String): SessionDto = client.post("/api/v1/auth/google") {
        jsonBody(GoogleAuthRequest(subject, "Integration test"))
    }.expect()
    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(value))
    }
    private suspend inline fun <reified T> HttpResponse.expect(status: HttpStatusCode = HttpStatusCode.OK): T {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        return json.decodeFromString<T>(text)
    }
    private suspend fun HttpResponse.expectError(status: HttpStatusCode) {
        val error = expect<ApiError>(status)
        assertTrue(error.code.isNotBlank())
    }
}
