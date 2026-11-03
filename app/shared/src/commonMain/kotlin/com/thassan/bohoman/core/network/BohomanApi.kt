package com.thassan.bohoman.core.network

import com.thassan.bohoman.contracts.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

/** The only route from clients to application data. Access tokens never leave memory. */
class BohomanApi(
    private val config: AppConfig,
    private val tokenStore: RefreshTokenStore,
    private val client: HttpClient = platformHttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    },
) {
    private val baseUrl = config.apiBaseUrl.trimEnd('/') + "/api/v1"
    private val refreshMutex = Mutex()
    private var accessToken: String? = null

    suspend fun signIn(idToken: String): UserDto = refreshMutex.withLock {
        val session: SessionDto = checked(client.post("$baseUrl/auth/google") {
            contentType(ContentType.Application.Json)
            header("X-Bohoman-CSRF", "1")
            setBody(GoogleAuthRequest(idToken, config.deviceLabel, config.browser))
        }).body()
        saveSession(session)
        session.user
    }

    suspend fun restoreSession(): UserDto? = refreshMutex.withLock {
        if (!config.browser && tokenStore.read() == null) return@withLock null
        try {
            refreshSession().user
        } catch (failure: ApiException) {
            if (failure.status != 401) throw failure
            clearSession()
            null
        }
    }

    private suspend fun saveSession(session: SessionDto) {
        // Persist the rotating token before publishing access so later requests cannot race the write.
        if (!config.browser) tokenStore.write(session.refreshToken)
        accessToken = session.accessToken
    }

    private suspend fun refreshSession(): SessionDto {
        val session: SessionDto = checked(client.post("$baseUrl/auth/refresh") {
            contentType(ContentType.Application.Json)
            header("X-Bohoman-CSRF", "1")
            setBody(RefreshRequest(if (config.browser) null else tokenStore.read()))
        }).body()
        saveSession(session)
        return session
    }

    suspend fun logout() = refreshMutex.withLock {
        checked(client.post("$baseUrl/auth/logout") {
            contentType(ContentType.Application.Json)
            header("X-Bohoman-CSRF", "1")
            accessToken?.let { bearerAuth(it) }
            setBody(LogoutRequest(if (config.browser) null else tokenStore.read()))
        })
        clearSession()
    }

    private suspend fun clearSession() {
        accessToken = null
        tokenStore.write(null)
    }

    private suspend fun request(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val usedToken = accessToken
        suspend fun execute(token: String?) = client.request("$baseUrl$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            header("X-Bohoman-CSRF", "1")
            token?.let { bearerAuth(it) }
            configure()
        }
        val first = execute(usedToken)
        if (first.status != HttpStatusCode.Unauthorized) return checked(first)
        refreshMutex.withLock {
            if (accessToken == usedToken) {
                try {
                    refreshSession()
                } catch (failure: ApiException) {
                    if (failure.status == 401) clearSession()
                    throw failure
                }
            }
        }
        val retryToken = accessToken
        val retry = execute(retryToken)
        if (retry.status == HttpStatusCode.Unauthorized) {
            refreshMutex.withLock {
                if (accessToken == retryToken) clearSession()
            }
        }
        return checked(retry)
    }

    suspend fun profile(): UserDto = request("/me").body()
    suspend fun tasks(limit: Int, offset: Int): List<TaskDto> = request("/tasks") {
        parameter("limit", limit); parameter("offset", offset)
    }.body()
    suspend fun countdowns(limit: Int, offset: Int): List<CountdownDto> = request("/countdowns") {
        parameter("limit", limit); parameter("offset", offset)
    }.body()
    suspend fun dashboard(limit: Int, offset: Int): List<DashboardItem> = request("/dashboard") {
        parameter("limit", limit); parameter("offset", offset)
    }.body()
    suspend fun createTask(value: CreateTaskRequest): TaskDto = request("/tasks", HttpMethod.Post) { setBody(value) }.body()
    suspend fun updateTask(id: String, value: UpdateTaskRequest): TaskDto = request("/tasks/$id", HttpMethod.Patch) { setBody(value) }.body()
    suspend fun deleteTask(id: String, version: Long): TaskDto = request("/tasks/$id", HttpMethod.Delete) {
        parameter("expectedVersion", version)
    }.body()
    suspend fun createCountdown(value: CreateCountdownRequest): CountdownDto = request("/countdowns", HttpMethod.Post) { setBody(value) }.body()
    suspend fun updateCountdown(id: String, value: UpdateCountdownRequest): CountdownDto = request("/countdowns/$id", HttpMethod.Patch) { setBody(value) }.body()
    suspend fun deleteCountdown(id: String, version: Long): CountdownDto = request("/countdowns/$id", HttpMethod.Delete) {
        parameter("expectedVersion", version)
    }.body()

    private suspend fun checked(response: HttpResponse): HttpResponse {
        if (response.status.isSuccess()) return response
        val error = try {
            response.body<ApiError>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        throw ApiException(response.status.value, error?.code ?: "request_failed", error?.message ?: "The request could not be completed (${response.status.value}).")
    }

    fun close() = client.close()
}
