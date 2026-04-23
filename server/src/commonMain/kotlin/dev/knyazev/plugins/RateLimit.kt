package dev.knyazev.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import okio.ByteString.Companion.encodeUtf8

/**
 * Custom rate-limit plugin (multiplatform).
 *
 * Replaces `io.ktor:ktor-server-rate-limit` (JVM-only) with a pure-common implementation
 * based on a token-bucket algorithm. Keys are derived from a composite fingerprint
 * (JA3 + canvas-fp + IP, SHA-256 hashed) — see ADR-18.
 *
 * Buckets are kept in-memory (no persistence — single instance deployment, scale-to-zero
 * resets buckets anyway). Cleanup runs opportunistically on access: buckets untouched
 * for > 5 × refillPeriod are dropped during the next `acquire` call from any key in the
 * same scope. Cheaper than a separate sweeper coroutine for the ≤ few thousand unique
 * keys/hour this service sees.
 */

data class RateLimitScope(
    val name: String,
    val limit: Int,
    val refillPeriodMs: Long,
) {
    private val buckets = mutableMapOf<String, Bucket>()
    private val lock = SynchronizedObject()

    private class Bucket(var tokens: Double, var lastRefillMs: Long, var lastAccessMs: Long)

    /** @return `true` if request allowed, `false` if quota exhausted. */
    fun acquire(key: String, nowMs: Long): Boolean = synchronized(lock) {
        maybeCleanup(nowMs)
        val bucket = buckets.getOrPut(key) {
            Bucket(tokens = limit.toDouble(), lastRefillMs = nowMs, lastAccessMs = nowMs)
        }
        val elapsed = nowMs - bucket.lastRefillMs
        if (elapsed > 0) {
            val refill = (elapsed.toDouble() / refillPeriodMs) * limit
            bucket.tokens = (bucket.tokens + refill).coerceAtMost(limit.toDouble())
            bucket.lastRefillMs = nowMs
        }
        bucket.lastAccessMs = nowMs
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun maybeCleanup(nowMs: Long) {
        if (buckets.size < CLEANUP_THRESHOLD) return
        val cutoff = nowMs - refillPeriodMs * STALE_MULTIPLIER
        buckets.entries.removeAll { it.value.lastAccessMs < cutoff }
    }

    private companion object {
        const val CLEANUP_THRESHOLD = 256
        const val STALE_MULTIPLIER = 5
    }
}

data class RateLimitConfig(val scopes: MutableMap<String, RateLimitScope> = mutableMapOf()) {
    fun register(name: String, limit: Int, refillPeriodMs: Long) {
        scopes[name] = RateLimitScope(name, limit, refillPeriodMs)
    }
}

private val RateLimitKey = AttributeKey<RateLimitConfig>("dev.knyazev.RateLimit")

val RateLimit = createApplicationPlugin("RateLimit", ::RateLimitConfig) {
    application.attributes.put(RateLimitKey, pluginConfig)
}

private val rateLimitLogger = KotlinLogging.logger {}

/**
 * Wrap a subtree of routes so every request is rate-limited under the named scope.
 * Mirrors the `rateLimit(ChatRateLimit) { ... }` DSL from the JVM plugin.
 */
fun Route.rateLimit(scopeName: String, build: Route.() -> Unit): Route {
    val config = application.attributes.getOrNull(RateLimitKey)
        ?: error("RateLimit plugin not installed — call install(RateLimit) { ... } in Application")
    val scope = config.scopes[scopeName]
        ?: error("Rate-limit scope '$scopeName' is not registered")

    val child = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            RouteSelectorEvaluation.Transparent
        override fun toString(): String = "(rateLimit:$scopeName)"
    })
    child.install(RateLimitInterceptor) { this.scope = scope }
    child.build()
    return child
}

private class RateLimitInterceptorConfig {
    lateinit var scope: RateLimitScope
}

private val RateLimitInterceptor = createRouteScopedPlugin(
    name = "RateLimitInterceptor",
    createConfiguration = ::RateLimitInterceptorConfig,
) {
    val scope = pluginConfig.scope
    onCall { call ->
        val key = compositeKey(call.request)
        val now = Clock.System.now().toEpochMilliseconds()
        if (!scope.acquire(key, now)) {
            val retryAfter = scope.refillPeriodMs / 1000
            call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate limit exceeded"))
        }
    }
}

const val CHAT_RATE_LIMIT = "chat"
const val MEDIA_RATE_LIMIT = "media"
const val SESSION_RATE_LIMIT = "session"

fun Application.configureRateLimit() {
    install(RateLimit) {
        register(CHAT_RATE_LIMIT, limit = 10, refillPeriodMs = 60_000L)
        register(MEDIA_RATE_LIMIT, limit = 20, refillPeriodMs = 60_000L)
        // Tokens expire after 20 min, so even a legit reconnect-heavy client
        // needs only a handful per hour. Attackers get no value from grinding.
        register(SESSION_RATE_LIMIT, limit = 6, refillPeriodMs = 60_000L)
    }
}

/**
 * Composite rate limit key (ADR-18):
 *   SHA-256( ja3 + ":" + clientFingerprint + ":" + ip )
 *
 * Degrades gracefully — missing signals fall back to sentinel values so rate limiting
 * still functions even without the full fingerprint chain. Trust model: JA3 and
 * X-Real-IP must be set by the trusted reverse proxy (Nginx). When they arrive missing
 * in production, the service is either running bare or being hit directly — log a
 * warning so misconfiguration is visible.
 */
private fun compositeKey(request: ApplicationRequest): String {
    val ja3Header = request.headers["X-JA3-Fingerprint"]
    val clientFpHeader = request.headers["X-Fingerprint"]
    val realIp = request.headers["X-Real-IP"]
    val forwardedFor = request.headers["X-Forwarded-For"]?.substringBefore(",")

    val ja3 = ja3Header ?: "no-ja3"
    val clientFp = clientFpHeader ?: "no-fp"
    val ip = realIp ?: forwardedFor ?: "unknown"

    if (ja3Header == null || realIp == null) {
        rateLimitLogger.warn {
            "Rate-limit fallback: ja3=${ja3Header ?: "missing"} realIp=${realIp ?: "missing"} " +
                "xff=${forwardedFor ?: "missing"} (reverse proxy headers missing)"
        }
    }

    return "$ja3:$clientFp:$ip".encodeUtf8().sha256().hex()
}
