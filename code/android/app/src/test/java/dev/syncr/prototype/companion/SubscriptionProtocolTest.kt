package dev.syncr.prototype.companion

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.*
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class SubscriptionProtocolTest {
    @Test fun pkceAndCallbackBinding() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SubscriptionProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val callback = mapOf("state" to "s", "code" to "c", "client_id" to "issued")
        assertEquals("issued", SubscriptionProtocol.client(callback, "s", null))
        for (bad in listOf(callback + ("state" to "wrong"), callback + ("error" to "access_denied"), callback + ("client_id" to "dynamic_agent_client"), callback - "code")) {
            assertThrows(IllegalArgumentException::class.java) { SubscriptionProtocol.client(bad, "s", null) }
        }
        assertThrows(IllegalArgumentException::class.java) { SubscriptionProtocol.client(callback, "s", "other") }
    }
    @Test fun validatesSignatureNonceIssuerAudienceIdentityAndExpiry() {
        val key = RSAKeyGenerator(2048).keyID("key").generate()
        val jwks = JWKSet(key.toPublicJWK()).toString()
        val claims = JWTClaimsSet.Builder().issuer(SubscriptionProtocol.ISSUER).audience("client").subject("subject")
            .expirationTime(Date(System.currentTimeMillis() + 60000)).claim("nonce", "nonce").claim("email", "user@example.test").build()
        fun token(c: JWTClaimsSet): String = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), c)
            .apply { sign(RSASSASigner(key)) }.serialize()
        assertEquals("subject", SubscriptionProtocol.identity(token(claims), jwks, "client", "nonce").first)
        for (c in listOf(JWTClaimsSet.Builder(claims).issuer("https://wrong.test").build(),
            JWTClaimsSet.Builder(claims).audience("other").build(), JWTClaimsSet.Builder(claims).claim("nonce", "other").build(),
            JWTClaimsSet.Builder(claims).expirationTime(Date(0)).build())) {
            assertThrows(IllegalArgumentException::class.java) { SubscriptionProtocol.identity(token(c), jwks, "client", "nonce") }
        }
        assertThrows(IllegalArgumentException::class.java) { SubscriptionProtocol.identity(token(claims), jwks, "client", "nonce", "another") }
        val wrong = RSAKeyGenerator(2048).keyID("key").generate()
        assertThrows(IllegalArgumentException::class.java) { SubscriptionProtocol.identity(token(claims), JWKSet(wrong.toPublicJWK()).toString(), "client", "nonce") }
    }
    @Test fun onlyCompletedStreamsMayBeSaved() {
        val stream = ResponseStream()
        stream.event("""{"type":"response.output_text.delta","delta":"Hello"}""")
        assertThrows(IllegalStateException::class.java) { stream.result() }
        stream.event("""{"type":"response.completed","response":{"status":"completed"}}""")
        assertEquals("Hello", stream.result())
        for (type in listOf("response.failed", "response.incomplete", "error")) {
            assertThrows(IllegalStateException::class.java) { ResponseStream().event("""{"type":"$type"}""") }
        }
    }
}
