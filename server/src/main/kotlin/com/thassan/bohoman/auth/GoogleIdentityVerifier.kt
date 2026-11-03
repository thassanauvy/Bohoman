package com.thassan.bohoman.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.thassan.bohoman.data.ApiException
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Only verified subject is used as identity; email is display information. */
data class GoogleIdentity(val subject: String, val email: String?, val displayName: String?, val pictureUrl: String?)

fun interface IdentityVerifier {
    fun verify(idToken: String): GoogleIdentity
}

class GoogleIdentityVerifier(
    private val audiences: Set<String>,
    private val keys: JwkProvider = JwkProviderBuilder(URI("https://www.googleapis.com/oauth2/v3/certs").toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build(),
) : IdentityVerifier {
    override fun verify(idToken: String): GoogleIdentity {
        if (idToken.length !in 1..16384) unauthorized()
        try {
            val unverified = JWT.decode(idToken)
            if (unverified.algorithm != "RS256" || unverified.keyId.isNullOrBlank()) unauthorized()
            val publicKey = keys.get(unverified.keyId).publicKey as? RSAPublicKey ?: unauthorized()
            val token = JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer("https://accounts.google.com", "accounts.google.com")
                .withAnyOfAudience(*audiences.toTypedArray())
                .acceptLeeway(30)
                .build()
                .verify(idToken)
            if (token.expiresAt == null || token.issuedAt == null || token.subject.isNullOrBlank() || token.subject.length > 255) unauthorized()
            val authorizedParty = token.getClaim("azp").asString()
            if ((token.audience.size > 1 && authorizedParty == null) || (authorizedParty != null && authorizedParty !in audiences)) unauthorized()
            val email = token.getClaim("email").asString()?.takeIf {
                token.getClaim("email_verified").asBoolean() == true && it.length <= 320
            }
            return GoogleIdentity(
                subject = token.subject,
                email = email,
                displayName = token.getClaim("name").asString()?.take(200),
                pictureUrl = token.getClaim("picture").asString()?.takeIf { it.startsWith("https://") && it.length <= 2048 },
            )
        } catch (e: ApiException) {
            throw e
        } catch (_: Exception) {
            unauthorized()
        }
    }

    private fun unauthorized(): Nothing = throw ApiException(401, "invalid_google_token", "Google sign-in could not be verified")
}
