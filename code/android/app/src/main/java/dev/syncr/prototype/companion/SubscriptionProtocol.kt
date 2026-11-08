package dev.syncr.prototype.companion

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

internal object SubscriptionProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val PERMISSION = "chatgpt.tokens.use.direct"
    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun identity(token: String, jwks: String, client: String, nonce: String?, subject: String? = null, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val jwt = SignedJWT.parse(token)
        require(jwt.header.algorithm == JWSAlgorithm.RS256) { "Unsupported sign-in signature." }
        val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: error("Sign-in key not found.")
        require(jwt.verify(RSASSAVerifier(key))) { "Invalid sign-in signature." }
        val c = jwt.jwtClaimsSet
        require(c.issuer == ISSUER && c.audience.contains(client) && c.expirationTime?.time?.let { it > now } == true) { "Invalid sign-in identity or expiry." }
        require(c.notBeforeTime == null || c.notBeforeTime.time <= now + 30_000)
        require(nonce == null || c.getStringClaim("nonce") == nonce) { "Sign-in nonce mismatch." }
        require(!c.subject.isNullOrBlank() && (subject == null || subject == c.subject)) { "The signed-in account changed." }
        return c.subject to (c.getStringClaim("email") ?: c.subject)
    }

    fun client(callback: Map<String, String>, state: String, issued: String?): String {
        require(callback["state"] == state) { "Sign-in state mismatch." }
        require(callback["error"] == null) { "Sign-in was declined. No account was changed." }
        val client = callback["client_id"] ?: issued
        require(!client.isNullOrBlank() && client != "dynamic_agent_client") { "Registration did not return a client ID." }
        require(issued == null || client == issued) { "Sign-in registration changed." }
        require(!callback["code"].isNullOrBlank()) { "Sign-in did not return a code." }
        return client
    }
}

/**
 * Only a terminal completed event makes output eligible for persistence. [onText] sees the words so far as they
 * arrive (at most every [everyMs]), for showing only: nothing is saved from it.
 */
internal class ResponseStream(private val onText: ((String) -> Unit)? = null, private val everyMs: Long = 60, private val now: () -> Long = System::currentTimeMillis) {
    private val text = StringBuilder()
    private var bytes = 0
    private var completed = false
    private var shownAt = 0L
    fun event(json: String) {
        val e = JSONObject(json)
        when (e.optString("type")) {
            "response.output_text.delta" -> {
                val delta = e.getString("delta")
                bytes += delta.toByteArray().size
                require(bytes <= 64_000) { "Response exceeded the size limit." }
                text.append(delta)
                val t = now()
                if (onText != null && t - shownAt >= everyMs) { shownAt = t; onText.invoke(text.toString()) }
            }
            "response.failed", "response.incomplete", "error" -> error(failure(e))
            "response.completed" -> {
                require(e.optJSONObject("response")?.optString("status") == "completed") { "Response was not completed." }
                completed = true
            }
        }
    }
    fun result(): String {
        check(completed && text.isNotBlank()) { "The response was interrupted or empty. Nothing was saved." }
        return text.toString()
    }

    /** What to tell the user: the plan's usage limits are named for what they are, anything else stays general. */
    private fun failure(e: JSONObject): String {
        val code = (e.optJSONObject("response")?.optJSONObject("error") ?: e.optJSONObject("error"))?.optString("code").orEmpty()
        return when (code) {
            "subscription_sharing_usage_limit_exceeded" -> "Your ChatGPT plan's usage limit is reached. Nothing was saved; try again when it resets."
            "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is not available for this account right now. Nothing was saved."
            else -> "The request did not complete. Check ChatGPT usage limits and try again."
        }
    }
}
