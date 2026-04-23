package dev.knyazev.rag

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.ui.UiBlock
import dev.knyazev.ui.UiTools
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

private const val MAX_CONTEXT_CHARS = 400_000
private const val BLOCKS_HARD_CAP = 5

private val CITATION_RULES = """

Цитирование источников (обязательно):
Каждый файл контекста пронумерован: [1], [2], и т.д.
В тексте ответа ставь номера источников в квадратных скобках сразу после утверждения,
которое опирается на этот источник. Пример: «Проект МРС — low-code платформа [1].»
Можно ссылаться на несколько источников: [1][3]. Не группируй все ссылки в конце —
ставь по месту. НЕ используй markdown-ссылки вида [текст](путь) для источников —
только номера [1], [2].

Формат ответа (обязательно):
Сначала дай краткий ответ — 1-3 содержательных предложения с сутью и выводом.
Затем поставь маркер ---DETAIL--- на отдельной строке.
После маркера — развёрнутый ответ со списками, примерами и ссылками на источники.

Визуальные блоки (инструменты):
render_image / render_image_gallery / render_link_list / render_text_with_image —
используй, когда уместно показать картинку, список ссылок или визитку.
Если вызвал инструмент — НЕ дублируй ресурс в тексте ответа. Всего блоков
(включая авто-блоки из markdown) — не больше 5.
""".trimIndent()

class SkillExecutor(
    private val openRouterClient: OpenRouterClient,
    private val docsPath: String,
) {
    private val fs = FileSystem.SYSTEM

    suspend fun execute(skill: Skill, question: String): RagResult {
        println("[SKILL:${skill.name}] → execute(question=\"$question\")")
        println("[SKILL:${skill.name}] sources declared: ${skill.sources}")

        val files = resolveSources(skill.sources)
        println("[SKILL:${skill.name}] resolved ${files.size} files:")
        files.forEach { println("    - ${it.relPath} (${it.content.length} chars)") }
        require(files.isNotEmpty()) {
            "Skill '${skill.name}' resolved to zero files — check `sources` paths"
        }

        val totalChars = files.sumOf { it.content.length }
        println("[SKILL:${skill.name}] total context: $totalChars chars (~${totalChars / 4} tokens)")
        require(totalChars <= MAX_CONTEXT_CHARS) {
            "Skill '${skill.name}' context too large: ~$totalChars chars " +
                "(limit $MAX_CONTEXT_CHARS). Split sources or narrow the skill scope."
        }

        val sourcesList = files.map { it.relPath }

        val aggregatedBlocks = files
            .flatMap { BlockExtractor.extract(it.content, it.relPath).blocks }
            .distinct()
            .take(BLOCKS_HARD_CAP)
        println("[SKILL:${skill.name}] extracted ${aggregatedBlocks.size} UI blocks")

        val context = files.mapIndexed { idx, f ->
            val withAbsPaths = BlockExtractor.rewriteImagePaths(f.content, f.relPath)
            "[${idx + 1}] Источник: ${f.relPath}\n\n$withAbsPaths"
        }.joinToString("\n\n---\n\n")

        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val systemPrompt = "${skill.instruction}\n$CITATION_RULES\n\nТекущая дата: $today."
        val userMessage = "Контекст из документации:\n\n$context\n\n---\n\nВопрос: $question"

        println(
            "[SKILL:${skill.name}] messages: system=${systemPrompt.length} chars, " +
                "user=${userMessage.length} chars"
        )
        println("[SKILL:${skill.name}] → streamChat with ${UiTools.definitions.size} tools")

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessage),
        )

        return RagResult(
            sources = sourcesList,
            blocks = aggregatedBlocks,
            stream = openRouterClient.streamChat(messages, tools = UiTools.definitions),
        )
    }

    private data class LoadedFile(val relPath: String, val content: String)

    private fun resolveSources(sources: List<String>): List<LoadedFile> {
        val base = docsPath.toPath()
        println("[SKILL] resolveSources: base=$base (from docsPath='$docsPath')")
        val out = mutableListOf<LoadedFile>()
        val seen = mutableSetOf<String>()

        for (src in sources) {
            val normalized = src.trimEnd('/')
            val path = resolveRelative(base, normalized)
            val meta = fs.metadataOrNull(path)
            println(
                "[SKILL] resolveSources: src='$src' → path=$path " +
                    "exists=${meta != null} isDir=${meta?.isDirectory} isFile=${meta?.isRegularFile}"
            )
            if (meta == null) {
                println("[SKILL] WARN: source not found: $src (resolved to $path)")
                continue
            }
            if (meta.isDirectory) {
                fs.listRecursively(path)
                    .filter { p ->
                        val m = fs.metadataOrNull(p) ?: return@filter false
                        m.isRegularFile && p.name.endsWith(".md") && !p.name.startsWith(".")
                    }
                    .sortedBy { it.toString() }
                    .forEach { p ->
                        val rel = relativize(base, p)
                        if (seen.add(rel)) {
                            out.add(LoadedFile(rel, fs.read(p) { readUtf8() }))
                        }
                    }
            } else if (meta.isRegularFile) {
                val rel = relativize(base, path)
                if (seen.add(rel)) {
                    out.add(LoadedFile(rel, fs.read(path) { readUtf8() }))
                }
            }
        }
        return out
    }

    private fun resolveRelative(base: Path, relative: String): Path {
        if (relative.startsWith("/")) return relative.toPath()
        return (base.toString().trimEnd('/', '\\') + "/" + relative.trimStart('/')).toPath()
    }

    private fun relativize(base: Path, child: Path): String {
        val b = base.toString().trimEnd('/', '\\')
        val c = child.toString()
        return c.removePrefix(b).trimStart('/', '\\')
    }
}
