package com.thassan.bohoman.core.network

/** Public application settings only. Never put OAuth secrets or database credentials here. */
data class AppConfig(
    val apiBaseUrl: String = "http://localhost:8080",
    val deviceLabel: String = "Bohoman",
    val browser: Boolean = false,
    val signInSetupMessage: String = "Google sign-in is not configured for this platform.",
)

/** Platform adapters persist refresh tokens in the OS credential store. */
interface RefreshTokenStore {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

/** Ephemeral fallback; browsers use an HttpOnly server cookie instead. */
class MemoryRefreshTokenStore : RefreshTokenStore {
    private var value: String? = null
    override suspend fun read(): String? = value
    override suspend fun write(value: String?) { this.value = value }
}
