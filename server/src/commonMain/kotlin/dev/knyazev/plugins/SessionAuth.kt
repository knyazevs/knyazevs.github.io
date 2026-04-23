package dev.knyazev.plugins

import dev.knyazev.auth.SessionTokenService
import dev.knyazev.auth.sessionFingerprint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.time.Clock

/**
 * Route-scoped session auth.
 *
 * Protected routes require an `X-Api-Session` header carrying a token issued by
 * `GET /api/session`. The token is HMAC-bound to the caller's browser fingerprint,
 * so a leaked token can't be replayed from a different browser.
 *
 * 401 on missing/invalid/expired token — client is expected to re-fetch `/api/session`
 * and retry. The client library does this transparently.
 */

private val sessionAuthLogger = KotlinLogging.logger {}

class SessionAuthConfig(var secret: String = "")

private val SessionAuthSecretKey = AttributeKey<String>("dev.knyazev.SessionAuth.secret")

val SessionAuth = createApplicationPlugin("SessionAuth", ::SessionAuthConfig) {
    require(pluginConfig.secret.isNotBlank()) { "SessionAuth: secret must be configured" }
    application.attributes.put(SessionAuthSecretKey, pluginConfig.secret)
}

fun Application.configureSessionAuth(secret: String) {
    install(SessionAuth) { this.secret = secret }
}

/**
 * Wrap routes so every request must carry a valid `X-Api-Session` token bound to
 * the current browser fingerprint.
 */
fun Route.sessionAuth(build: Route.() -> Unit): Route {
    val secret = application.attributes.getOrNull(SessionAuthSecretKey)
        ?: error("SessionAuth plugin not installed — call configureSessionAuth(secret) in Application")

    val child = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            RouteSelectorEvaluation.Transparent
        override fun toString(): String = "(sessionAuth)"
    })
    child.install(SessionAuthInterceptor) { this.secret = secret }
    child.build()
    return child
}

private class SessionAuthInterceptorConfig {
    var secret: String = ""
}

private val SessionAuthInterceptor = createRouteScopedPlugin(
    name = "SessionAuthInterceptor",
    createConfiguration = ::SessionAuthInterceptorConfig,
) {
    val secret = pluginConfig.secret
    onCall { call ->
        val token = call.request.headers["X-Api-Session"]
        if (token.isNullOrBlank()) {
            sessionAuthLogger.info { "SessionAuth: missing X-Api-Session on ${call.request.local.uri}" }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "session token required"))
            return@onCall
        }
        val fp = sessionFingerprint(call.request)
        val now = Clock.System.now().toEpochMilliseconds()
        if (!SessionTokenService.verify(token, fp, secret, now)) {
            sessionAuthLogger.info { "SessionAuth: invalid/expired token on ${call.request.local.uri}" }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or expired session token"))
            return@onCall
        }
    }
}
