package dev.knyazev.guard

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.rag.Skill
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Результат классификации вопроса (ADR-026). Заменяет бинарный YES/NO из
 * старого QuestionGuard: роутер одновременно отсекает нерелевантные вопросы
 * и выбирает skill для обзорных — в одном LLM-вызове.
 */
sealed class RoutingDecision {
    /** Вопрос не о Сергее / не о проекте — отклонить. */
    object Irrelevant : RoutingDecision()
    /** Точечный вопрос — идти по обычному HyDE+BM25+RRF пути. */
    object GenericRag : RoutingDecision()
    /** Обзорный вопрос — исполнить соответствующий skill. */
    data class UseSkill(val name: String) : RoutingDecision()
}

/**
 * Классифицирует пользовательский вопрос в [RoutingDecision] одним LLM-вызовом.
 * Делает работу и guard'а (отсев не-по-теме), и роутера (выбор skill'а).
 *
 * Fail-open с circuit breaker: транзиентные ошибки классификатора не блокируют
 * пользователя (→ GenericRag). При [FAILURE_THRESHOLD] подряд упавших вызовах
 * breaker тормозит трафик (→ Irrelevant) на [COOLDOWN_MS] — защита от flood'а
 * оплачиваемых downstream'ов, когда наш собственный classifier сломан.
 */
class QuestionRouter(
    private val openRouterClient: OpenRouterClient,
    private val skills: List<Skill>,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val consecutiveFailures = atomic(0)
    private val openedAtMs = atomic(0L)

    suspend fun decide(question: String): RoutingDecision {
        if (isBreakerOpen()) {
            logger.warn { "QuestionRouter breaker OPEN — rejecting request (cooldown active)" }
            return RoutingDecision.Irrelevant
        }

        val messages = listOf(
            ChatMessage(role = "system", content = buildSystemPrompt()),
            ChatMessage(role = "user", content = question),
        )

        println("[ROUTER] decide(\"$question\") — known skills: ${skills.map { it.name }}")
        return runCatching {
            val raw = openRouterClient.complete(messages, maxTokens = 80, jsonMode = true).trim()
            consecutiveFailures.value = 0
            println("[ROUTER] raw json: '$raw'")
            parseJson(raw)
        }.getOrElse { e ->
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= FAILURE_THRESHOLD) {
                openedAtMs.value = clock()
                println("[ROUTER] ERROR: breaker TRIPPED after $failures failures — fail-closed")
                return RoutingDecision.Irrelevant
            }
            println("[ROUTER] WARN: classifier failed (fail-open → GenericRag, $failures/$FAILURE_THRESHOLD): ${e.message}")
            RoutingDecision.GenericRag
        }
    }

    private fun parseJson(raw: String): RoutingDecision {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            println("[ROUTER] WARN: not valid JSON '$raw' → GenericRag")
            return RoutingDecision.GenericRag
        }
        return when (val decision = obj["decision"]?.jsonPrimitive?.content?.uppercase()) {
            "IRRELEVANT" -> {
                println("[ROUTER] → IRRELEVANT")
                RoutingDecision.Irrelevant
            }
            "SKILL" -> {
                val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                val matched = skills.firstOrNull { it.name == name }
                if (matched == null) {
                    println("[ROUTER] WARN: unknown skill '$name' → GenericRag")
                    RoutingDecision.GenericRag
                } else {
                    println("[ROUTER] → SKILL:$name")
                    RoutingDecision.UseSkill(name)
                }
            }
            "GENERIC" -> {
                println("[ROUTER] → GENERIC")
                RoutingDecision.GenericRag
            }
            else -> {
                println("[ROUTER] WARN: unknown decision '$decision' → GenericRag")
                RoutingDecision.GenericRag
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val skillsBlock = if (skills.isEmpty()) {
            "(No skills configured.)"
        } else {
            skills.joinToString("\n") { s ->
                val triggers = if (s.triggers.isEmpty()) "" else " Triggers: ${s.triggers.joinToString("; ")}."
                "- ${s.name}: ${s.description}.$triggers"
            }
        }
        return """
            You are a routing classifier for a personal portfolio chatbot about Sergey Knyazev,
            a software engineer. Questions may be in any language (Russian, English, etc.).

            Respond with ONLY a JSON object, no other text.

            Classify the question into exactly ONE of three decisions:

            1. IRRELEVANT — the question is clearly unrelated to a software engineer's portfolio:
               weather, cooking, general math, creative writing, unrelated coding help for third-party
               projects, etc. High bar — use this only when there is no plausible connection to the
               portfolio. When in doubt, prefer GENERIC.
               JSON: {"decision":"IRRELEVANT"}

            2. SKILL — the question asks for a broad overview that maps to one of the available skills
               below. Key signals: "какие", "все", "список", "обзор", "расскажи о всех", "what are",
               "list", "tell me about your". A question must match BOTH the signal words AND a skill
               description to qualify — broad phrasing alone is not enough.
               JSON: {"decision":"SKILL","name":"<skill-name>"}

            3. GENERIC — everything else about the portfolio, the project, or Sergey personally:
               specific facts, how things work, technology choices, architecture details, experience,
               ADR content, etc. Also use for technical questions (RAG, HyDE, Kotlin, etc.) that
               likely refer to the portfolio even without explicitly naming Sergey.
               JSON: {"decision":"GENERIC"}

            Available skills:
            $skillsBlock

            Examples:
            Q: Какие проекты реализовал Сергей? → {"decision":"SKILL","name":"projects-overview"}
            Q: Расскажи о всех ADR → {"decision":"SKILL","name":"architecture-decisions"}
            Q: Расскажи о себе → {"decision":"SKILL","name":"biography"}
            Q: Какой стек у него в бэке? → {"decision":"GENERIC"}
            Q: Что в ADR-019? → {"decision":"GENERIC"}
            Q: Как работает RAG-пайплайн? → {"decision":"GENERIC"}
            Q: Почему Kotlin/Ktor? → {"decision":"GENERIC"}
            Q: Что такое HyDE в этом проекте? → {"decision":"GENERIC"}
            Q: Расскажи про RAG в проекте → {"decision":"GENERIC"}
            Q: Какой опыт в архитектуре? → {"decision":"GENERIC"}
            Q: Какая погода в Москве? → {"decision":"IRRELEVANT"}
            Q: Напиши мне код сортировки → {"decision":"IRRELEVANT"}
        """.trimIndent()
    }

    private fun isBreakerOpen(): Boolean {
        val openedAt = openedAtMs.value
        if (openedAt == 0L) return false
        if (clock() - openedAt >= COOLDOWN_MS) {
            openedAtMs.value = 0L
            consecutiveFailures.value = 0
            logger.info { "QuestionRouter breaker RESET after cooldown" }
            return false
        }
        return true
    }

    companion object {
        private const val FAILURE_THRESHOLD = 5
        private const val COOLDOWN_MS = 60_000L
    }
}
