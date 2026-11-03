package com.thassan.bohoman

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.thassan.bohoman.auth.GoogleIdentityVerifier
import com.thassan.bohoman.data.ApiException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.*

class GoogleIdentityVerifierTest {
    private val verifier = GoogleIdentityVerifier(setOf(AUDIENCE), JwkProvider { jwk })

    @Test
    fun acceptsVerifiedGoogleSubjectAndOnlyVerifiedEmail() {
        val identity = verifier.verify(token {
            withClaim("email", "mutable@example.test")
            withClaim("email_verified", true)
            withClaim("name", "A user")
        })
        assertEquals("immutable-subject", identity.subject)
        assertEquals("mutable@example.test", identity.email)
        assertEquals("A user", identity.displayName)
        assertNull(verifier.verify(token { withClaim("email", "unverified@example.test") }).email)
    }

    @Test
    fun rejectsForgedSignatureAlgorithmIssuerAudienceExpiryAndMissingExpiry() {
        val badTokens = listOf(
            token { withIssuer("https://attacker.example") },
            token { withAudience("other-client") },
            token { withExpiresAt(Date.from(Instant.now().minusSeconds(600))) },
            token { withIssuedAt(Date.from(Instant.now().plusSeconds(600))) },
            token { withExpiresAt(null as Date?) },
            token { withSubject("") },
            token { withClaim("azp", "other-client") },
            token { withAudience(AUDIENCE, "other-client") },
            token(Algorithm.RSA256(otherPublic, otherPrivate)),
            token(Algorithm.HMAC256("attacker-secret")),
            "not-a-jwt",
        )
        badTokens.forEach { encoded ->
            assertEquals(401, assertFailsWith<ApiException> { verifier.verify(encoded) }.status)
        }
    }

    @Test
    fun acceptsBothGoogleIssuersAndRequiresAuthorizedPartyForMultipleAudiences() {
        verifier.verify(token { withIssuer("accounts.google.com") })
        verifier.verify(token { withAudience(AUDIENCE, "second-client"); withClaim("azp", AUDIENCE) })
    }

    private fun token(algorithm: Algorithm = Algorithm.RSA256(publicKey, privateKey), block: JWTCreator.Builder.() -> Unit = {}): String = JWT.create()
        .withKeyId("test-key")
        .withIssuer("https://accounts.google.com")
        .withAudience(AUDIENCE)
        .withSubject("immutable-subject")
        .withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .apply(block)
        .sign(algorithm)

    companion object {
        private const val AUDIENCE = "registered-client.apps.googleusercontent.com"
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = keys.public as RSAPublicKey
        private val privateKey = keys.private as RSAPrivateKey
        private val otherPublic = otherKeys.public as RSAPublicKey
        private val otherPrivate = otherKeys.private as RSAPrivateKey
        private val jwk = Jwk.fromValues(mapOf(
            "kid" to "test-key",
            "kty" to "RSA",
            "alg" to "RS256",
            "use" to "sig",
            "n" to unsignedBase64(publicKey.modulus),
            "e" to unsignedBase64(publicKey.publicExponent),
        ))
        private fun unsignedBase64(value: BigInteger): String {
            val bytes = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
