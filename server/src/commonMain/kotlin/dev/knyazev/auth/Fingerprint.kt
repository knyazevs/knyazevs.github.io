package dev.knyazev.auth

import io.ktor.server.request.*
import okio.ByteString.Companion.encodeUtf8

/**
 * Fingerprint used to bind a session token to a browser.
 *
 * Derived from headers set by the trusted reverse proxy (Nginx) and the client:
 *   SHA-256( ja3 + ":" + clientCanvasFp )
 *
 * IP is deliberately omitted — mobile networks rotate IPs within minutes, and
 * a session tied to IP would trigger constant re-auth. Rate-limit keeps IP in
 * its own key where short-lived bucket churn is acceptable.
 */
fun sessionFingerprint(request: ApplicationRequest): String {
    val ja3 = request.headers["X-JA3-Fingerprint"] ?: "no-ja3"
    val clientFp = request.headers["X-Fingerprint"] ?: "no-fp"
    return "$ja3:$clientFp".encodeUtf8().sha256().hex()
}
