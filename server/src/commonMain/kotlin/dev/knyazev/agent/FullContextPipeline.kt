package dev.knyazev.agent

import dev.knyazev.llm.ChatEvent
import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.rag.RagResult
import dev.knyazev.ui.UiTools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val logger = KotlinLogging.logger("FullContextPipeline")

/**
 * Full-context режим (ADR-027). Загружает весь `docs/` целиком в system prompt
 * и делает один LLM-вызов. Prompt caching у Anthropic/OpenRouter делает warm-запросы
 * дешёвыми; cold — дорогой, осознанно.
 *
 * UI-тулы (render_image etc.) передаются — LLM может выбрать вставить картинку/карточку,
 * если вопрос того требует. Источники — полный список прочитанных файлов.
 */
class FullContextPipeline(
    private val openRouterClient: OpenRouterClient,
    private val projectRoot: String,
) {
    private val fs = FileSystem.SYSTEM

    /** Cached при первом вызове — контент docs/ меняется редко, а переинициализация на каждый запрос стоила бы заметной I/O. */
    private var cachedCorpus: LoadedCorpus? = null

    suspend fun ask(question: String): RagResult {
        val corpus = loadCorpus()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

        val systemPrompt = buildString {
            append(SYSTEM_PROMPT_BASE)
            append("\n\nТекущая дата: $today.\n\n")
            append("## Весь корпус документации\n\n")
            append(corpus.content)
        }

        println(
            "[FULL-CONTEXT] ask(\"$question\"): corpus=${corpus.files.size} files, " +
                "systemPrompt=${systemPrompt.length} chars (~${systemPrompt.length / 4} tokens)"
        )

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", question),
        )

        val stream: Flow<ChatEvent> = openRouterClient.streamChat(messages, tools = UiTools.definitions)

        return RagResult(
            sources = corpus.files,
            blocks = emptyList(),
            stream = stream,
        )
    }

    private fun loadCorpus(): LoadedCorpus {
        cachedCorpus?.let { return it }
        val docsRoot = "${projectRoot.trimEnd('/')}/docs".toPath()
        val files = collectMarkdown(docsRoot).sortedBy { it.toString() }
        val blocks = mutableListOf<String>()
        val paths = mutableListOf<String>()
        for ((idx, file) in files.withIndex()) {
            val rel = file.toString().removePrefix(projectRoot.trimEnd('/')).trimStart('/')
            // Skip .corpus-map.md — it's derivative, and its presence would double-count content
            if (rel.endsWith("/.corpus-map.md") || rel == "docs/.corpus-map.md") continue
            val content = runCatching { fs.read(file) { readUtf8() } }.getOrNull() ?: continue
            blocks += "[${idx + 1}] Источник: $rel\n\n$content"
            paths += rel
        }
        val full = blocks.joinToString("\n\n---\n\n")
        println("[FULL-CONTEXT] corpus loaded: ${paths.size} files, ${full.length} chars (~${full.length / 4} tokens)")
        val loaded = LoadedCorpus(files = paths, content = full)
        cachedCorpus = loaded
        return loaded
    }

    private fun collectMarkdown(root: Path): List<Path> = runCatching {
        fs.listRecursively(root)
            .filter { p ->
                val m = fs.metadataOrNull(p) ?: return@filter false
                m.isRegularFile && p.name.endsWith(".md") && !p.name.startsWith(".")
            }
            .toList()
    }.getOrDefault(emptyList())

    private data class LoadedCorpus(val files: List<String>, val content: String)

    companion object {
        private val SYSTEM_PROMPT_BASE = """
            Ты — интерпретационный слой персонального профиля Сергея Князева (Technical Lead / Architect).

            Правила:
            - Отвечай ТОЛЬКО на основе документов из корпуса ниже.
            - Не добавляй факты, которых нет в источнике. Не «продавай» автора.
            - Если информации в корпусе недостаточно — явно скажи об этом.
            - Отвечай на том языке, на котором задан вопрос.
            - Используй markdown: **жирный**, заголовки, списки.

            Цитирование:
            - Каждый файл контекста пронумерован: [1], [2], и т.д.
            - В тексте ответа ставь номер в квадратных скобках после утверждения, опирающегося на этот источник.
            - Пример: «Сергей использует Kotlin/Ktor [1] и Svelte 5 [3].» Можно комбинировать: [1][3].
            - Не группируй все ссылки в конце — ставь по месту.
            - НЕ используй markdown-ссылки [текст](путь) для источников — только номера.

            Формат ответа (обязательно):
            Краткий ответ — 1-3 содержательных предложения с сутью и выводом.
            Это самостоятельный абзац, а не подводка: не заканчивай двоеточием, не пиши вводные фразы
            («Ниже перечислены...»).
            Затем маркер ---DETAIL--- на отдельной строке.
            После маркера — развёрнутый ответ со списками и ссылками [1], [2].

            Визуальные блоки (инструменты):
            render_image / render_image_gallery / render_link_list / render_text_with_image — используй,
            когда картинка или список ссылок добавляет ценность ответу. Фото автора
            (/images/profile/profile.png в render_text_with_image) — ТОЛЬКО на вопросы «кто ты»/«расскажи о себе».
            Если вызвал инструмент — не дублируй ресурс в тексте. Всего блоков — не больше 3-4.
        """.trimIndent()
    }
}
