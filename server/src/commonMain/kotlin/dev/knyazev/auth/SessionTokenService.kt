package dev.knyazev.auth

import okio.ByteString.Companion.encodeUtf8

/**
 * HMAC-signed session tokens bound to a browser fingerprint.
 *
 * Token format: `{expiresAtMs}.{hmacHex}`
 *   hmac = HMAC-SHA256( "{fingerprint}.{expiresAtMs}", SESSION_SECRET )
 *
 * The fingerprint itself is not embedded — it arrives in headers on every request,
 * so the server re-derives it and recomputes the HMAC. That way the token is useless
 * without matching browser signals even if stolen by a server-side actor who doesn't
 * know the JA3 + canvas-fp of the original client.
 *
 * IP is intentionally NOT part of the session binding — mobile networks rotate IPs
 * within minutes. Rate-limit keeps IP in its key (where short-lived blocks are fine).
 */
object SessionTokenService {
    const val DEFAULT_TTL_MS: Long = 20 * 60 * 1000L   // 20 min

    data class IssuedToken(val token: String, val expiresAtMs: Long)

    fun issue(fingerprint: String, secret: String, nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): IssuedToken {
        val expiresAtMs = nowMs + ttlMs
        val sig = hmacHex("$fingerprint.$expiresAtMs", secret)
        return IssuedToken(token = "$expiresAtMs.$sig", expiresAtMs = expiresAtMs)
    }

    fun verify(token: String, fingerprint: String, secret: String, nowMs: Long): Boolean {
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return false
        val expiresAtMs = token.substring(0, dot).toLongOrNull() ?: return false
        if (expiresAtMs < nowMs) return false
        val providedSig = token.substring(dot + 1)
        val expectedSig = hmacHex("$fingerprint.$expiresAtMs", secret)
        return constantTimeEquals(expectedSig, providedSig)
    }

    private fun hmacHex(payload: String, secret: String): String =
        payload.encodeUtf8().hmacSha256(secret.encodeUtf8()).hex()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
