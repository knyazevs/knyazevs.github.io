package dev.knyazev.api

import dev.knyazev.llm.OpenAiClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class TtsRequest(val text: String, val voice: String = "alloy")

fun Route.ttsRoutes(openAiClient: OpenAiClient) {
    post("/api/tts") {
        val request = call.receive<TtsRequest>()
        val text = request.text.trim()
        if (text.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text must not be blank"))
            return@post
        }

        val audioBytes = openAiClient.textToSpeech(text, request.voice)
        call.respondBytes(audioBytes, ContentType("audio", "mpeg"))
    }
}
