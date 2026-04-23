package dev.knyazev.agent

import dev.knyazev.agent.tools.NavigationTools
import dev.knyazev.llm.ChatEvent
import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.rag.RagResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Agentic-режим (ADR-027). Карта корпуса в system prompt + инструменты файловой навигации.
 * LLM сама решает что читать; тексты и tool-вызовы крутятся внутри до финального ответа.
 *
 * Источники (`RagResult.sources`) собираются из путей, которые агент прочитал через
 * `read_file` — это и даёт UI-ленту источников под ответом, совпадающую с реальными
 * файлами, на которых построен ответ.
 */
class AgenticPipeline(
    private val openRouterClient: OpenRouterClient,
    private val navigator: NavigationTools.Executor,
    private val corpusMap: CorpusMap,
) {
    suspend fun ask(question: String): RagResult {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

        val systemPrompt = buildString {
            append(SYSTEM_PROMPT_BASE)
            append("\n\nТекущая дата: $today.\n\n")
            if (corpusMap.isEmpty) {
                append(
                    "Карта корпуса сейчас недоступна. Начни с `list_files` для нужной директории " +
                        "(docs/, server/src/, app/src/, scripts/, server/skills/).\n"
                )
            } else {
                append("## Карта корпуса\n\n")
                append(corpusMap.content)
            }
        }

        val initialMessages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", question),
        )

        println("[AGENTIC] ask(\"$question\"): systemPrompt=${systemPrompt.length} chars (~${systemPrompt.length / 4} tokens)")

        // Пути, которые агент прочитал через read_file — становятся источниками для UI.
        val visitedPaths = mutableListOf<String>()

        val stream: Flow<ChatEvent> = openRouterClient.streamAgentic(
            initialMessages = initialMessages,
            navigationTools = NavigationTools.definitions,
            maxIterations = 8,
            navigator = { name, args ->
                if (name == NavigationTools.READ_FILE.name) {
                    // Выдёргиваем path из аргументов для списка источников. Повторные чтения
                    // того же файла дедуплицируются — показать один и тот же источник дважды бессмысленно.
                    val path = extractPathArg(args)
                    if (path != null && path !in visitedPaths) visitedPaths.add(path)
                }
                navigator.execute(name, args)
            },
        )

        return RagResult(
            sources = visitedPaths, // mutable list — будет финализирован к моменту, когда ChatRoutes прочитает после стрима
            blocks = emptyList(),
            stream = stream,
        )
    }

    /** Достаёт path из сырого JSON аргументов `read_file`, не парся всю структуру — дёшево. */
    private fun extractPathArg(argsJson: String): String? {
        val regex = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(argsJson)?.groupValues?.getOrNull(1)
    }

    companion object {
        private val SYSTEM_PROMPT_BASE = """
            Ты — интерпретационный слой персонального профиля Сергея Князева (Technical Lead / Architect).
            Отвечаешь на вопросы о нём: опыт, проекты, навыки, архитектурные решения, блог.

            Правила:
            - Отвечай ТОЛЬКО на основе файлов, которые ты прочитал через инструмент read_file.
              Если читал через list_files — это не контент, это названия.
            - Не выдумывай факты, цифры, названия компаний и технологий, которых нет в файлах.
            - Если информации недостаточно — явно скажи об этом и предложи задать вопрос конкретнее.
            - Отвечай на том же языке, на котором задан вопрос.
            - Используй markdown: **жирный** для акцентов, списки, заголовки по необходимости.

            Навигация:
            - Карта корпуса ниже — индекс. Используй её, чтобы понять ГДЕ искать, но не отвечай по ней одной.
            - Читай конкретные файлы через `read_file(path)` — только после чтения у тебя есть факты.
            - Для поиска упоминаний по всему корпусу — `grep(pattern, path?)`.
            - Для обзора директории — `list_files(path)`.
            - Типично: 1-3 read_file + финальный ответ. Не зачитывай 10 файлов подряд.

            Цитирование:
            - После каждого факта ставь номер источника [1], [2] — это индекс файла в порядке, в котором
              ты их читал через read_file. Первый прочитанный = [1], второй = [2], и т.д.
            - Пример: «Сергей использует Kotlin/Ktor [1] и Svelte 5 [2].»
            - Не группируй ссылки в конце — ставь по месту факта.

            Формат ответа (обязательно):
            Краткий ответ — 1-3 содержательных предложения с сутью и выводом.
            Это самостоятельный абзац, а не подводка: не заканчивай двоеточием, не пиши «Ниже перечислены...».
            Затем маркер ---DETAIL--- на отдельной строке.
            После маркера — развёрнутый ответ со списками и ссылками [1], [2] на источники.
        """.trimIndent()
    }
}
