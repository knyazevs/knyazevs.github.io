package dev.knyazev.api

import dev.knyazev.rag.SuggestionsService
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class SuggestionsResponse(val suggestions: List<String>)

fun Route.suggestionsRoutes(suggestionsService: SuggestionsService) {
    get("/api/suggestions") {
        call.respond(SuggestionsResponse(suggestionsService.getInitial()))
    }
}
