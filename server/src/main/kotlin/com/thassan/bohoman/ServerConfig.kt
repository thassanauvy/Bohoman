package com.thassan.bohoman

import java.net.URI

/** Secrets are read only by the server and are never included in a generated toString(). */
class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val googleClientIds: Set<String>,
    val webOrigins: Set<String>,
    val secureCookies: Boolean = true,
    val issuer: String = "bohoman",
    val audience: String = "bohoman-api",
    val accessTokenSeconds: Long = 900,
    val refreshTokenSeconds: Long = 30L * 24 * 60 * 60,
    val port: Int = 8080,
) {
    init {
        require(databaseUrl.startsWith("jdbc:postgresql:")) { "BOHOMAN_DATABASE_URL must be a PostgreSQL JDBC URL" }
        require(jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) { "BOHOMAN_JWT_SECRET must have at least 32 bytes" }
        require(googleClientIds.isNotEmpty()) { "BOHOMAN_GOOGLE_CLIENT_IDS must contain at least one registered client ID" }
        require(accessTokenSeconds in 60..3600 && refreshTokenSeconds in 3600..90L * 24 * 3600)
        require(port in 1..65535)
        webOrigins.forEach { origin ->
            val uri = URI(origin)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
                uri.rawQuery == null && uri.fragment == null && uri.rawPath.isNullOrEmpty()) {
                "BOHOMAN_WEB_ORIGINS must contain exact origins without paths or trailing slashes"
            }
            require(uri.scheme == "https" || (!secureCookies && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
                "HTTP origins are supported only for local development with BOHOMAN_SECURE_COOKIES=false"
            }
        }
    }

    val refreshCookieName: String get() = if (secureCookies) "__Host-bohoman_refresh" else "bohoman_refresh"

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Missing required server setting: $name")
            fun csv(name: String) = env[name].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            return ServerConfig(
                databaseUrl = required("BOHOMAN_DATABASE_URL"),
                databaseUser = required("BOHOMAN_DATABASE_USER"),
                databasePassword = required("BOHOMAN_DATABASE_PASSWORD"),
                jwtSecret = required("BOHOMAN_JWT_SECRET"),
                googleClientIds = csv("BOHOMAN_GOOGLE_CLIENT_IDS"),
                webOrigins = csv("BOHOMAN_WEB_ORIGINS"),
                secureCookies = env["BOHOMAN_SECURE_COOKIES"]?.toBooleanStrict() ?: true,
                port = env["PORT"]?.toInt() ?: 8080,
            )
        }
    }
}
