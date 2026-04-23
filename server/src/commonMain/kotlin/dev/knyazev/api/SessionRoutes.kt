package dev.knyazev.api

import dev.knyazev.auth.SessionTokenService
import dev.knyazev.auth.sessionFingerprint
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Clock
import kotlinx.serialization.Serializable

@Serializable
data class SessionResponse(val token: String, val expiresAtMs: Long)

/**
 * Issues a short-lived HMAC session token bound to the caller's browser fingerprint.
 *
 * Clients call this once on startup and re-fetch near expiry. Rate-limited by the
 * SESSION_RATE_LIMIT scope so an attacker can't grind tokens trying to forge one
 * (note: brute-forcing HMAC-SHA256 is infeasible anyway — the rate limit mostly
 * protects the issue path from being used as a keepalive / pingback vector).
 */
fun Route.sessionRoutes(sessionSecret: String) {
    get("/api/session") {
        val fp = sessionFingerprint(call.request)
        val now = Clock.System.now().toEpochMilliseconds()
        val issued = SessionTokenService.issue(fp, sessionSecret, now)
        call.respond(SessionResponse(token = issued.token, expiresAtMs = issued.expiresAtMs))
    }
}
