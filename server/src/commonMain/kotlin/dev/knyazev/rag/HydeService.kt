package dev.knyazev.rag

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * HyDE — Hypothetical Document Embeddings (ADR-19).
 *
 * Generates a short hypothetical answer to the user's question using a cheap LLM.
 * The hypothetical answer is semantically closer to relevant documentation chunks
 * than the raw question, closing the query-document semantic gap.
 *
 * Fail-open: on any error, the original question is returned unchanged.
 */
class HydeService(private val openRouterClient: OpenRouterClient) {

    suspend fun reformulate(question: String): String {
        return runCatching {
            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = """
                        Ты ассистент, генерирующий гипотетический фрагмент профессиональной документации
                        для поиска по эмбеддингам (HyDE).

                        Напиши 2–3 предложения на русском языке так, как если бы это был достоверный
                        фрагмент из CV, ADR или профиля инженера. Используй профессиональную лексику,
                        конкретные технологии, термины архитектуры. Не начинай с «Не знаю» — сформулируй
                        правдоподобный ответ по смыслу вопроса.

                        Отвечай только текстом фрагмента, без преамбул и пояснений.
                    """.trimIndent(),
                ),
                ChatMessage(
                    role = "user",
                    content = question,
                ),
            )
            val hypothetical = openRouterClient.complete(messages, maxTokens = 200)
            logger.debug { "HyDE reformulated: \"$question\" → \"$hypothetical\"" }
            hypothetical.ifBlank { question }
        }.getOrElse { e ->
            logger.warn { "HyDE failed (fail-open): ${e.message}" }
            question
        }
    }
}
