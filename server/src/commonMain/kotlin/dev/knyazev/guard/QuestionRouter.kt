package dev.knyazev.guard

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.rag.Skill
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
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
            val answer = openRouterClient.complete(messages, maxTokens = 64).trim()
            consecutiveFailures.value = 0
            println("[ROUTER] raw answer: '$answer' (length=${answer.length})")
            parse(answer, question)
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

    private fun parse(answer: String, question: String): RoutingDecision {
        val upper = answer.uppercase()
        return when {
            upper.startsWith("IRRELEVANT") || upper.startsWith("NO") -> {
                println("[ROUTER] → IRRELEVANT")
                RoutingDecision.Irrelevant
            }
            upper.startsWith("SKILL:") -> {
                val name = answer.substringAfter(":").trim().substringBefore('\n').substringBefore(' ')
                val matched = skills.firstOrNull { it.name == name }
                if (matched == null) {
                    println("[ROUTER] WARN: returned unknown skill '$name' → GenericRag")
                    RoutingDecision.GenericRag
                } else {
                    println("[ROUTER] → SKILL:$name")
                    RoutingDecision.UseSkill(name)
                }
            }
            upper.startsWith("GENERIC") || upper.startsWith("YES") -> {
                println("[ROUTER] → GENERIC")
                RoutingDecision.GenericRag
            }
            else -> {
                println("[ROUTER] WARN: unparseable answer '$answer' → GenericRag")
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
            You are a routing classifier for a personal portfolio chatbot about Sergey Knyazev.
            Questions may be in any language (Russian, English, etc.).

            Classify the user question into exactly ONE of:
            - IRRELEVANT — question is clearly NOT about Sergey, his skills, his projects, or this portfolio
              (general knowledge, unrelated coding help, creative writing, math, etc.)
            - SKILL:<name> — question asks for a broad/overview answer that matches one of the listed skills
              (asks for "all" projects, "list" of something, "tell me about" a whole area).
              Key signals: "какие", "список", "все", "обзор", "расскажи о (всех|всё)", "what are all",
              "list of", "tell me about your".
            - GENERIC — question is about Sergey but asks a specific, pointed fact
              (e.g. "what stack at MRS", "when did he work at X", "which ADR about RAG").

            Available skills:
            $skillsBlock

            Examples:
            Q: Какие проекты реализовал Сергей? → SKILL:projects-overview
            Q: Расскажи о всех ADR → SKILL:architecture-decisions
            Q: Какой стек у него в бэке? → GENERIC
            Q: Какая погода в Москве? → IRRELEVANT
            Q: Расскажи о себе → SKILL:biography
            Q: Что в ADR-019? → GENERIC

            Output rules:
            - Return ONE line only.
            - Exactly: IRRELEVANT, GENERIC, or SKILL:<name> (case-sensitive name).
            - No explanation, no punctuation, no extra text.
            - When a question uses overview words ("какие", "все", "список") and matches
              a skill description — choose the skill, NOT GENERIC.
            - When unsure between IRRELEVANT and GENERIC — prefer GENERIC (fail-open).
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
