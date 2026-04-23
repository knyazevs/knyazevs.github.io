package dev.knyazev.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

private val logger = KotlinLogging.logger("CorpusMap")

/**
 * Карта корпуса (ADR-027). Читается с диска на старте из `docs/.corpus-map.md`,
 * генерируемого скриптом `scripts/generate-corpus-map.ts`.
 *
 * Карта — не выжимка, а индекс: для каждого файла путь, заголовок, метаданные и
 * одно предложение. Агент использует её, чтобы знать что читать через `read_file`.
 *
 * Если файл не найден — стартуем с пустой картой и warn'ом: agentic-режим всё ещё
 * работает, просто первая итерация будет `list_files` вместо целенаправленного `read_file`.
 */
class CorpusMap(val content: String) {
    val isEmpty: Boolean get() = content.isBlank()
    val chars: Int get() = content.length

    companion object {
        fun load(projectRoot: String): CorpusMap {
            val fs = FileSystem.SYSTEM
            val path = "${projectRoot.trimEnd('/')}/docs/.corpus-map.md".toPath()
            val meta = fs.metadataOrNull(path)
            if (meta == null || !meta.isRegularFile) {
                logger.warn {
                    "Corpus map not found at $path. Run `bun run build:corpus-map`. " +
                        "Agentic mode will work but first tool call will be list_files."
                }
                return CorpusMap("")
            }
            val text = fs.read(path) { readUtf8() }
            println("[CORPUS-MAP] loaded from $path: ${text.length} chars (~${text.length / 4} tokens)")
            return CorpusMap(text)
        }
    }
}
