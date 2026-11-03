package com.thassan.bohoman

import com.thassan.bohoman.auth.GoogleIdentityVerifier
import com.thassan.bohoman.auth.IdentityVerifier
import com.thassan.bohoman.auth.SessionService
import com.thassan.bohoman.contracts.*
import com.thassan.bohoman.data.ApiException
import com.thassan.bohoman.data.Database
import com.thassan.bohoman.data.JdbcStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.util.UUID

private val wireJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    isLenient = false
}

private const val MAX_JSON_BYTES = 65_536L

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

fun Application.module() = module(ServerConfig.fromEnvironment())

fun Application.module(config: ServerConfig) {
    val database = Database.open(config.databaseUrl, config.databaseUser, config.databasePassword)
    monitor.subscribe(ApplicationStopped) { database.close() }
    apiModule(config, JdbcStore(database), GoogleIdentityVerifier(config.googleClientIds))
}

/** Inject verified identity handling and a real database for integration tests; production has no bypass login. */
fun Application.apiModule(config: ServerConfig, store: JdbcStore, identityVerifier: IdentityVerifier) {
    val sessions = SessionService(config, store, identityVerifier)
    install(ContentNegotiation) { json(wireJson) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message ?: "Request failed"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "The request body is invalid"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "The request body is invalid"))
        }
        exception<Exception> { call, cause ->
            this@apiModule.log.error("Unexpected API failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("server_error", "An unexpected server error occurred"))
        }
    }
    install(CORS) {
        config.webOrigins.forEach { origin ->
            val uri = URI(origin)
            allowHost(uri.rawAuthority, schemes = listOf(uri.scheme))
        }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Bohoman-CSRF")
        allowCredentials = true
    }
    install(Authentication) {
        jwt("session") {
            realm = "Bohoman"
            verifier(sessions.verifier)
            validate { credential ->
                val userId = parseUuidOrNull(credential.payload.subject)
                val sessionId = parseUuidOrNull(credential.payload.getClaim("sid").asString())
                if (userId != null && sessionId != null && credential.payload.expiresAt != null &&
                    io { store.isSessionActive(sessionId, userId) }) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Please sign in to continue"))
            }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        route("/api/v1") {
            intercept(ApplicationCallPipeline.Call) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Content-Type-Options", "nosniff")
            }
            route("/auth") {
                post("/google") {
                    val request = call.jsonBody<GoogleAuthRequest>()
                    val browser = request.browser || call.request.headers[HttpHeaders.Origin] != null
                    if (browser) {
                        call.requireBrowserMutation(config)
                        if (!request.browser) throw ApiException(400, "browser_session_required", "Browser clients must use cookie sessions")
                    }
                    val session = io { sessions.signIn(request) }
                    call.respondSession(config, session, browser)
                }
                post("/refresh") {
                    val request = call.jsonBody<RefreshRequest>()
                    val token = call.refreshCredential(config, request.refreshToken)
                    val session = io { sessions.refresh(token.value) }
                    call.respondSession(config, session, token.browser)
                }
                post("/logout") {
                    val request = call.jsonBody<LogoutRequest>()
                    val token = call.refreshCredential(config, request.refreshToken)
                    io { sessions.logout(token.value) }
                    if (token.browser) call.setRefreshCookie(config, "", 0)
                    call.respond(mapOf("loggedOut" to true))
                }
            }
            authenticate("session") {
                get("/me") {
                    val user = io { store.findUser(call.userId()) }
                        ?: throw ApiException(401, "unauthorized", "Your account is no longer available")
                    call.respond(user)
                }
                route("/tasks") {
                    get {
                        val (limit, offset) = call.pagination()
                        call.respond(io { store.listTasks(call.userId(), limit, offset) })
                    }
                    post {
                        val request = call.jsonBody<CreateTaskRequest>()
                        call.respond(HttpStatusCode.Created, io { store.createTask(call.userId(), request) })
                    }
                    patch("/{id}") {
                        val request = call.jsonBody<UpdateTaskRequest>()
                        call.respond(io { store.updateTask(call.userId(), call.resourceId(), request) })
                    }
                    delete("/{id}") {
                        val version = call.expectedVersion()
                        call.respond(io { store.deleteTask(call.userId(), call.resourceId(), version) })
                    }
                }
                route("/countdowns") {
                    get {
                        val (limit, offset) = call.pagination()
                        call.respond(io { store.listCountdowns(call.userId(), limit, offset) })
                    }
                    post {
                        val request = call.jsonBody<CreateCountdownRequest>()
                        call.respond(HttpStatusCode.Created, io { store.createCountdown(call.userId(), request) })
                    }
                    patch("/{id}") {
                        val request = call.jsonBody<UpdateCountdownRequest>()
                        call.respond(io { store.updateCountdown(call.userId(), call.resourceId(), request) })
                    }
                    delete("/{id}") {
                        val version = call.expectedVersion()
                        call.respond(io { store.deleteCountdown(call.userId(), call.resourceId(), version) })
                    }
                }
                get("/dashboard") {
                    val (limit, offset) = call.pagination()
                    call.respond(io { store.dashboard(call.userId(), limit, offset) })
                }
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.jsonBody(): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(415, "json_required", "Use application/json for request bodies")
    }
    // Enforce the limit while reading, including chunked requests with no Content-Length.
    // Reading the whole body before checking its length permits unbounded allocation.
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_JSON_BYTES) {
        throw ApiException(413, "request_too_large", "Request body is too large")
    }
    val channel = receiveChannel()
    val bytes = channel.readBuffer(MAX_JSON_BYTES + 1).readByteArray()
    if (bytes.size > MAX_JSON_BYTES) {
        channel.cancel(null)
        throw ApiException(413, "request_too_large", "Request body is too large")
    }
    val content = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw ApiException(400, "invalid_request", "JSON must be encoded as UTF-8")
    }
    return wireJson.decodeFromString(content)
}

private fun ApplicationCall.requireBrowserMutation(config: ServerConfig) {
    val origin = request.headers[HttpHeaders.Origin]
    if (origin !in config.webOrigins || request.headers["X-Bohoman-CSRF"] != "1") {
        throw ApiException(403, "csrf_rejected", "This browser request is not allowed")
    }
}

private data class RefreshCredential(val value: String, val browser: Boolean)

private fun ApplicationCall.refreshCredential(config: ServerConfig, bodyToken: String?): RefreshCredential {
    val cookie = request.cookies[config.refreshCookieName]
    val browser = cookie != null || request.headers[HttpHeaders.Origin] != null
    if (browser) {
        requireBrowserMutation(config)
        if (bodyToken != null) throw ApiException(400, "browser_session_required", "Browser clients must use cookie sessions")
        return RefreshCredential(cookie ?: throw ApiException(401, "invalid_session", "Please sign in again"), true)
    }
    return RefreshCredential(bodyToken ?: throw ApiException(401, "invalid_session", "Please sign in again"), false)
}

private suspend fun ApplicationCall.respondSession(config: ServerConfig, session: SessionDto, browser: Boolean) {
    if (browser) {
        setRefreshCookie(config, requireNotNull(session.refreshToken), config.refreshTokenSeconds.toInt())
        respond(session.copy(refreshToken = null))
    } else respond(session)
}

private fun ApplicationCall.setRefreshCookie(config: ServerConfig, token: String, maxAgeSeconds: Int) {
    response.cookies.append(Cookie(
        name = config.refreshCookieName,
        value = token,
        encoding = CookieEncoding.RAW,
        maxAge = maxAgeSeconds,
        path = "/",
        secure = config.secureCookies,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    ))
}

private fun ApplicationCall.userId(): UUID = UUID.fromString(requireNotNull(principal<JWTPrincipal>()).subject)
private fun ApplicationCall.resourceId(): UUID = parseUuidOrNull(parameters["id"])
    ?: throw ApiException(400, "invalid_id", "Resource ID must be a UUID")
private fun parseUuidOrNull(value: String?): UUID? = if (value == null || value.length != 36) null else runCatching { UUID.fromString(value) }.getOrNull()

private fun ApplicationCall.expectedVersion(): Long = request.queryParameters["expectedVersion"]?.toLongOrNull()?.takeIf { it > 0 }
    ?: throw ApiException(400, "invalid_version", "Supply a positive expectedVersion query parameter")

private fun ApplicationCall.pagination(): Pair<Int, Int> {
    val limit = request.queryParameters["limit"]?.let { it.toIntOrNull() ?: 0 } ?: 100
    val offset = request.queryParameters["offset"]?.let { it.toIntOrNull() ?: -1 } ?: 0
    if (limit !in 1..100 || offset !in 0..1_000_000) throw ApiException(400, "invalid_pagination", "Limit must be 1–100 and offset must be 0–1000000")
    return limit to offset
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
