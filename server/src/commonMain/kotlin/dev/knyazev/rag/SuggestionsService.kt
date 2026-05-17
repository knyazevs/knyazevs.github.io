package dev.knyazev.rag

import dev.knyazev.guard.QuestionRouter
import dev.knyazev.guard.RoutingDecision
import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.Volatile

private val logger = KotlinLogging.logger {}

class SuggestionsService(
    private val llmClient: OpenRouterClient,
    private val skills: List<Skill> = emptyList(),
    private val questionRouter: QuestionRouter? = null,
) {

    @Volatile
    private var initialSuggestions: List<String> = DEFAULT_SUGGESTIONS

    private val skillGuidance: String by lazy { buildSkillGuidance() }

    private fun buildSkillGuidance(): String {
        if (skills.isEmpty()) return ""
        val lines = skills.flatMap { s ->
            s.triggers.map { t -> "  - $t  (→ ${s.name})" }
        }
        if (lines.isEmpty()) return ""
        return """

            Priority — favor questions phrased like these trigger examples (they unlock full
            structured answers via skill-routing, not narrow top-5 retrieval). Mix at least
            one such question into the result:
            ${lines.joinToString("\n")}
        """.trimIndent()
    }

    /**
     * Generates initial suggestions from indexed content summary.
     * Called once at startup after indexing. Fail-safe: falls back to defaults.
     */
    suspend fun generateInitial(vectorStore: VectorStore) {
        runCatching {
            val sample = vectorStore.allEntries()
                .shuffled()
                .take(15)
                .joinToString("\n") { "- [${it.breadcrumb}] ${it.content.take(120)}" }

            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = """
                        You generate suggested questions for a portfolio chatbot about Sergey Knyazev.
                        Based on the content summary below, create exactly 4 short questions (under 40 chars each)
                        that a recruiter or hiring manager would ask. Mix technical and personal questions.
                        Write questions in Russian. Return ONLY the questions, one per line, no numbering.
                        $skillGuidance
                    """.trimIndent(),
                ),
                ChatMessage(role = "user", content = sample),
            )

            val response = llmClient.complete(messages, model = llmClient.classifierModelName, maxTokens = 200)
            val questions = response.lines()
                .map { it.trim().trimStart('-', '•', '*', ' ') }
                .filter { it.length in 10..60 && it.endsWith("?") }
                .take(4)
                .let { filterRelevant(it) }

            if (questions.size >= 2) {
                initialSuggestions = questions
                logger.info { "Generated ${questions.size} initial suggestions" }
            } else {
                logger.info { "LLM returned too few suggestions, using defaults" }
            }
        }.onFailure { e ->
            logger.warn { "Failed to generate suggestions (using defaults): ${e.message}" }
        }
    }

    fun getInitial(): List<String> = initialSuggestions

    /**
     * Generates follow-up questions based on the answer and question context.
     * Fail-safe: returns empty list on error.
     */
    suspend fun generateFollowUps(question: String, answer: String): List<String> {
        return runCatching {
            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = """
                        Based on the Q&A below about Sergey Knyazev, suggest exactly 3 natural follow-up questions.
                        Write in Russian. Short (under 40 chars). One per line, no numbering, no dashes.
                        $skillGuidance
                    """.trimIndent(),
                ),
                ChatMessage(
                    role = "user",
                    content = "Вопрос: $question\n\nОтвет: ${answer.take(500)}",
                ),
            )
            val response = llmClient.complete(messages, model = llmClient.classifierModelName, maxTokens = 150)
            val candidates = response.lines()
                .map { it.trim().trimStart('-', '•', '*', ' ') }
                .filter { it.length in 10..60 && it.endsWith("?") }
                .take(3)
            filterRelevant(candidates)
        }.getOrElse { e ->
            logger.warn { "Failed to generate follow-ups: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun filterRelevant(candidates: List<String>): List<String> {
        val router = questionRouter ?: return candidates
        return coroutineScope {
            candidates
                .map { q -> async { q to router.decide(q) } }
                .awaitAll()
                .filter { (_, decision) -> decision !is RoutingDecision.Irrelevant }
                .map { (q, _) -> q }
        }
    }

    companion object {
        private val DEFAULT_SUGGESTIONS = listOf(
            "Какой стек использует Сергей?",
            "Расскажи про RAG в проекте",
            "Какой опыт в архитектуре?",
            "Почему Kotlin/Ktor?",
        )
    }
}
