package dev.knyazev.rag

import dev.knyazev.ui.ImageBlock
import dev.knyazev.ui.ImageGalleryBlock
import dev.knyazev.ui.LinkListBlock
import dev.knyazev.ui.UiBlock

data class ExtractResult(
    val blocks: List<UiBlock>,
    /** Markdown без строк-картинок и с inline-ссылками, заменёнными на plain-текст. */
    val cleanedMarkdown: String,
)

/**
 * Чистая функция, извлекающая UI-блоки из тела markdown-чанка (ADR-024, механизм C).
 *
 * - `![alt](url)` на отдельной строке → ImageBlock (строка удаляется из cleanedMarkdown).
 * - 2+ таких строк подряд (через пустые строки) → один ImageGalleryBlock.
 * - Inline `[title](url)` с внешним или относительным URL → LinkItem в общем LinkListBlock;
 *   в тексте `[title](url)` заменяется на `title`, чтобы абзац остался связным.
 * - Якорные ссылки (`[...](#section)`) не трогаются.
 * - Относительные пути переписываются на абсолютные `/images/{docDir}/{path}`
 *   (см. build-static-data.ts — тот же алгоритм применяется к копиям файлов).
 */
object BlockExtractor {

    private val imageOnlyLineRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")
    private val linkRegex = Regex("""(?<!!)\[([^\]]+)\]\(([^)\s]+)\)""")
    private val fenceRegex = Regex("""^\s*```.*""")
    private val imageRegex = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")

    /**
     * Переписывает относительные пути в markdown-изображениях на абсолютные `/images/...`.
     * Ссылки, inline code и fenced blocks не трогаем. Используется для LLM-контекста —
     * модель видит реальные URL и может подставить их в render_* инструменты.
     */
    fun rewriteImagePaths(markdown: String, docFilePath: String): String {
        val docDir = docFilePath.substringBeforeLast('/', missingDelimiterValue = "")
        val lines = markdown.split('\n')
        var inFence = false
        return lines.joinToString("\n") { line ->
            if (fenceRegex.matches(line)) {
                inFence = !inFence
                return@joinToString line
            }
            if (inFence) return@joinToString line
            imageRegex.replace(line) { match ->
                val before = line.substring(0, match.range.first)
                val backticksBefore = before.count { it == '`' }
                if (backticksBefore % 2 == 1) return@replace match.value
                val alt = match.groupValues[1]
                val rawUrl = match.groupValues[2]
                if (rawUrl.startsWith("http://") ||
                    rawUrl.startsWith("https://") ||
                    rawUrl.startsWith("#") ||
                    rawUrl.startsWith("/")
                ) {
                    match.value
                } else {
                    "![$alt](${resolveUrl(rawUrl, docDir)})"
                }
            }
        }
    }

    fun extract(markdown: String, docFilePath: String): ExtractResult {
        val docDir = docFilePath.substringBeforeLast('/', missingDelimiterValue = "")
        val lines = markdown.split('\n')

        val resultLines = mutableListOf<String>()
        val imageGroups = mutableListOf<MutableList<ImageBlock>>()
        var currentGroup = mutableListOf<ImageBlock>()
        val linkItems = mutableListOf<LinkListBlock.LinkItem>()
        var inFence = false

        for (line in lines) {
            if (fenceRegex.matches(line)) {
                inFence = !inFence
                if (currentGroup.isNotEmpty()) {
                    imageGroups.add(currentGroup)
                    currentGroup = mutableListOf()
                }
                resultLines.add(line)
                continue
            }
            if (inFence) {
                // Внутри code-блока ничего не извлекаем — примеры остаются как есть.
                resultLines.add(line)
                continue
            }
            val imgMatch = imageOnlyLineRegex.matchEntire(line)
            when {
                imgMatch != null -> {
                    val alt = imgMatch.groupValues[1].takeIf { it.isNotBlank() }
                    val rawUrl = imgMatch.groupValues[2]
                    val url = resolveUrl(rawUrl, docDir)
                    currentGroup.add(ImageBlock(url = url, caption = alt, alt = alt))
                    // строка удалена из cleanedMarkdown
                }
                line.isBlank() -> {
                    // Пустая строка не закрывает группу изображений — позволяет GitHub-style вертикальную подряд-галерею.
                    resultLines.add(line)
                }
                else -> {
                    // Непустая не-image строка закрывает текущую группу изображений.
                    if (currentGroup.isNotEmpty()) {
                        imageGroups.add(currentGroup)
                        currentGroup = mutableListOf()
                    }
                    // Собираем внешние/относительные ссылки в linkItems и заменяем их на plain-текст.
                    // Inline code (внутри `...`) не трогаем — это markdown-примеры в документации.
                    val replaced = linkRegex.replace(line) { match ->
                        val before = line.substring(0, match.range.first)
                        val backticksBefore = before.count { it == '`' }
                        if (backticksBefore % 2 == 1) return@replace match.value
                        val title = match.groupValues[1]
                        val rawUrl = match.groupValues[2]
                        if (rawUrl.startsWith("#")) {
                            match.value
                        } else {
                            val url = resolveUrl(rawUrl, docDir)
                            linkItems.add(LinkListBlock.LinkItem(url = url, title = title))
                            title
                        }
                    }
                    resultLines.add(replaced)
                }
            }
        }
        if (currentGroup.isNotEmpty()) {
            imageGroups.add(currentGroup)
        }

        val blocks = mutableListOf<UiBlock>()
        for (group in imageGroups) {
            if (group.size == 1) {
                blocks.add(group[0])
            } else {
                blocks.add(
                    ImageGalleryBlock(
                        images = group.map { ImageGalleryBlock.GalleryImage(url = it.url, caption = it.caption) },
                    ),
                )
            }
        }
        if (linkItems.isNotEmpty()) {
            blocks.add(LinkListBlock(items = linkItems.distinctBy { it.url }))
        }

        return ExtractResult(
            blocks = blocks,
            cleanedMarkdown = resultLines.joinToString("\n").trim(),
        )
    }

    private fun resolveUrl(raw: String, docDir: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return raw
        return if (docDir.isEmpty()) "/images/$raw" else "/images/$docDir/$raw"
    }
}
