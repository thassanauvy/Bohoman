package com.thassan.bohoman.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.thassan.bohoman.ServerConfig
import com.thassan.bohoman.contracts.GoogleAuthRequest
import com.thassan.bohoman.contracts.SessionDto
import com.thassan.bohoman.contracts.UserDto
import com.thassan.bohoman.data.ApiException
import com.thassan.bohoman.data.JdbcStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Date
import java.util.UUID

class SessionService(
    private val config: ServerConfig,
    private val store: JdbcStore,
    private val identities: IdentityVerifier,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("token_use", "access")
        .build()

    fun signIn(request: GoogleAuthRequest): SessionDto {
        val deviceLabel = request.deviceLabel
        if (deviceLabel != null && deviceLabel.length !in 1..100) {
            throw ApiException(400, "invalid_device_label", "Device label must contain 1–100 characters")
        }
        val identity = identities.verify(request.idToken)
        val user = store.findOrCreateGoogleUser(identity.subject, identity.email, identity.displayName, identity.pictureUrl)
        val refresh = newRefreshToken()
        val sessionId = store.createSession(UUID.fromString(user.id), hash(refresh), request.deviceLabel, clock.instant().plusSeconds(config.refreshTokenSeconds))
        return issue(user, sessionId, refresh)
    }

    fun refresh(token: String): SessionDto {
        validateRefreshToken(token)
        val next = newRefreshToken()
        val session = store.rotateSession(hash(token), hash(next), clock.instant().plusSeconds(config.refreshTokenSeconds))
            ?: throw ApiException(401, "invalid_session", "Your session has expired. Please sign in again.")
        val user = store.findUser(session.userId)
            ?: throw ApiException(401, "invalid_session", "Your session is no longer available")
        return issue(user, session.id, next)
    }

    fun logout(token: String) {
        validateRefreshToken(token)
        store.revokeSession(hash(token))
    }

    private fun issue(user: UserDto, sessionId: UUID, refresh: String): SessionDto {
        val now = clock.instant()
        val access = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(user.id)
            .withClaim("sid", sessionId.toString())
            .withClaim("token_use", "access")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(config.accessTokenSeconds)))
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)
        return SessionDto(accessToken = access, refreshToken = refresh, expiresInSeconds = config.accessTokenSeconds, user = user)
    }

    private fun newRefreshToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun validateRefreshToken(token: String) {
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ApiException(401, "invalid_session", "Your session has expired. Please sign in again.")
    }

    companion object {
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
