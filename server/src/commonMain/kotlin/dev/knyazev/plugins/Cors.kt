package dev.knyazev.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors(allowedOrigins: String) {
    install(CORS) {
        allowedOrigins.split(",").map(String::trim).filter(String::isNotEmpty).forEach { origin ->
            allowHost(
                host = origin.removePrefix("https://").removePrefix("http://"),
                schemes = listOf(if (origin.startsWith("https")) "https" else "http")
            )
        }
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Fingerprint")
        allowHeader("X-Api-Session")
    }
}
