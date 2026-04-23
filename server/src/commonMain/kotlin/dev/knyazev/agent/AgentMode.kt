package dev.knyazev.agent

/**
 * Режим работы агента на Generic-пути (ADR-027). Выбирается клиентом на каждый
 * запрос через поле `mode` в `ChatRequest`. Skill-путь и Irrelevant не зависят от режима.
 */
enum class AgentMode {
    /** Дефолт: карта корпуса + инструменты навигации. Дёшево, прозрачно. */
    AGENTIC,

    /** Премиум: весь корпус в system prompt с prompt caching. Дорогой cold start, дешёвый warm. */
    FULL_CONTEXT,

    /** Legacy: HyDE + BM25 + RRF retrieval (ADR-019). Оставлен как baseline и для сравнения. */
    RAG;

    companion object {
        fun parse(raw: String?, default: AgentMode): AgentMode = when (raw?.trim()?.lowercase()) {
            null, "" -> default
            "agentic" -> AGENTIC
            "full_context", "full-context", "fullcontext" -> FULL_CONTEXT
            "rag" -> RAG
            else -> throw IllegalArgumentException("Unknown agent mode: '$raw'. Expected: agentic, full_context, rag")
        }
    }
}
