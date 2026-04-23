package dev.knyazev.api

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class AutocompleteRequest(val input: String)

@Serializable
data class AutocompleteResponse(val completion: String)

private val AUTOCOMPLETE_PROMPT = """
You complete partially typed questions about Sergey Knyazev's portfolio.
The user is typing a question. Return ONLY the continuation (the missing part), not the full question.
If the input is too short or unclear, return an empty string.
Keep it short — one natural question ending. Write in the same language as the input.
Do NOT repeat the input. Return ONLY the missing tail.
""".trimIndent()

fun Route.autocompleteRoutes(openRouterClient: OpenRouterClient) {
    post("/api/autocomplete") {
        val request = call.receive<AutocompleteRequest>()
        val input = request.input.trim()

        if (input.length < 3) {
            call.respond(AutocompleteResponse(""))
            return@post
        }

        val messages = listOf(
            ChatMessage("system", AUTOCOMPLETE_PROMPT),
            ChatMessage("user", input),
        )

        val completion = openRouterClient.complete(
            messages = messages,
            model = openRouterClient.classifierModelName,
            maxTokens = 40,
        )

        call.respond(AutocompleteResponse(completion))
    }
}
