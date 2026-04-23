package dev.knyazev.agent.tools

import dev.knyazev.llm.ToolDefinition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

private val logger = KotlinLogging.logger("NavigationTools")

/**
 * Файловые инструменты для agentic-режима (ADR-027): `list_files`, `read_file`, `grep`.
 *
 * Sandbox — whitelist корневых директорий относительно `projectRoot`. Любой путь
 * вне whitelist'а или содержащий `..` отклоняется с текстовой ошибкой, которая
 * возвращается LLM как tool result (не исключение — иначе модель не поймёт, что
 * делать дальше).
 */
object NavigationTools {

    private val json = Json { ignoreUnknownKeys = true }

    private val ALLOWED_PREFIXES = listOf(
        "docs/",
        "server/src/",
        "server/skills/",
        "app/src/",
        "scripts/",
    )

    /** Верхняя граница на объём одного tool result, чтобы LLM не захлебнулась на случайном большом файле. */
    private const val MAX_FILE_CHARS = 50_000
    private const val MAX_LIST_ENTRIES = 200
    private const val MAX_GREP_HITS = 60
    private const val GREP_CONTEXT_LINES = 1

    val LIST_FILES = ToolDefinition(
        name = "list_files",
        description = "List files and subdirectories under a path relative to the project root. " +
            "Use to discover what's in docs/, server/src/, app/src/, scripts/, server/skills/. " +
            "Example: path='docs/experience' lists all experience files. Empty string '' lists project root.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Path relative to project root. Must be inside docs/, server/src/, server/skills/, app/src/, or scripts/. " +
                            "Examples: 'docs/adr', 'server/src/commonMain/kotlin/dev/knyazev/rag'.",
                    )
                }
            }
            putJsonArray("required") { add("path") }
        },
    )

    val READ_FILE = ToolDefinition(
        name = "read_file",
        description = "Read the full contents of a file at a path relative to the project root. " +
            "Use after list_files to fetch the detail of a specific markdown document or source file. " +
            "Large files are truncated at ${MAX_FILE_CHARS} chars.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "File path relative to project root, e.g. 'docs/experience/001-low-code-platform.md'.",
                    )
                }
            }
            putJsonArray("required") { add("path") }
        },
    )

    val GREP = ToolDefinition(
        name = "grep",
        description = "Search for a literal (non-regex) substring across markdown and source files in the sandbox. " +
            "Returns matching lines with file paths and line numbers. Use to find mentions of a specific " +
            "technology, person, or concept across the corpus without reading every file.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Literal substring to search for (case-insensitive). Not a regex.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional path prefix to restrict search. If omitted, searches all sandbox directories.",
                    )
                }
            }
            putJsonArray("required") { add("pattern") }
        },
    )

    val definitions: List<ToolDefinition> = listOf(LIST_FILES, READ_FILE, GREP)
    val names: Set<String> = definitions.map { it.name }.toSet()

    /** Исполнитель навигационных тулов; инстанцируется в Bootstrap с абсолютным `projectRoot`. */
    class Executor(private val projectRoot: String) {
        private val fs = FileSystem.SYSTEM
        private val rootPath: Path = projectRoot.toPath()

        suspend fun execute(name: String, argumentsJson: String): String {
            val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
                ?: return "ERROR: invalid JSON arguments: ${argumentsJson.take(200)}"
            return when (name) {
                LIST_FILES.name -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull
                        ?: return "ERROR: 'path' is required"
                    listFiles(path)
                }
                READ_FILE.name -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull
                        ?: return "ERROR: 'path' is required"
                    readFile(path)
                }
                GREP.name -> {
                    val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
                        ?: return "ERROR: 'pattern' is required"
                    val path = args["path"]?.jsonPrimitive?.contentOrNull
                    grep(pattern, path)
                }
                else -> "ERROR: unknown tool '$name'"
            }
        }

        /**
         * Нормализует путь и проверяет sandbox. Возвращает null, если путь за пределами whitelist'а
         * или пытается выйти через `..`. Пустая строка допустима — это корень (только для list_files).
         */
        private fun resolveSafe(rawPath: String, allowRoot: Boolean): Path? {
            val normalized = rawPath.trim().trimStart('/').trimEnd('/')
            if (normalized.isEmpty()) {
                return if (allowRoot) rootPath else null
            }
            if (normalized.contains("..")) return null
            val inWhitelist = ALLOWED_PREFIXES.any { prefix ->
                normalized == prefix.trimEnd('/') || normalized.startsWith(prefix)
            }
            if (!inWhitelist) return null
            return "${rootPath}/$normalized".toPath()
        }

        private fun listFiles(rawPath: String): String {
            val path = resolveSafe(rawPath, allowRoot = true)
                ?: return deniedError(rawPath)
            val meta = fs.metadataOrNull(path)
                ?: return "ERROR: path not found: $rawPath"
            if (!meta.isDirectory) return "ERROR: not a directory: $rawPath"

            val entries = runCatching {
                fs.list(path)
                    .filter { !it.name.startsWith(".") }
                    .sortedBy { it.name }
            }.getOrElse { return "ERROR: cannot list: ${it.message}" }

            if (entries.isEmpty()) return "(empty directory)"

            val lines = mutableListOf<String>()
            for (entry in entries.take(MAX_LIST_ENTRIES)) {
                val m = fs.metadataOrNull(entry) ?: continue
                val suffix = if (m.isDirectory) "/" else ""
                lines += "${entry.name}$suffix"
            }
            if (entries.size > MAX_LIST_ENTRIES) {
                lines += "... (${entries.size - MAX_LIST_ENTRIES} more, narrow the path)"
            }
            return lines.joinToString("\n")
        }

        private fun readFile(rawPath: String): String {
            val path = resolveSafe(rawPath, allowRoot = false)
                ?: return deniedError(rawPath)
            val meta = fs.metadataOrNull(path)
                ?: return "ERROR: file not found: $rawPath"
            if (!meta.isRegularFile) return "ERROR: not a regular file: $rawPath"

            val content = runCatching { fs.read(path) { readUtf8() } }
                .getOrElse { return "ERROR: cannot read: ${it.message}" }
            return if (content.length > MAX_FILE_CHARS) {
                content.take(MAX_FILE_CHARS) +
                    "\n\n[truncated — file is ${content.length} chars, shown first $MAX_FILE_CHARS]"
            } else {
                content
            }
        }

        private fun grep(pattern: String, rawPath: String?): String {
            val searchRoots: List<Path> = if (rawPath.isNullOrBlank()) {
                ALLOWED_PREFIXES.mapNotNull { prefix ->
                    val p = "${rootPath}/${prefix.trimEnd('/')}".toPath()
                    if (fs.metadataOrNull(p)?.isDirectory == true) p else null
                }
            } else {
                val resolved = resolveSafe(rawPath, allowRoot = false)
                    ?: return deniedError(rawPath)
                listOf(resolved)
            }

            val needle = pattern.lowercase()
            val hits = mutableListOf<String>()

            for (root in searchRoots) {
                val meta = fs.metadataOrNull(root) ?: continue
                val files = if (meta.isRegularFile) listOf(root)
                else collectFiles(root)

                for (file in files) {
                    if (hits.size >= MAX_GREP_HITS) break
                    val content = runCatching { fs.read(file) { readUtf8() } }.getOrNull() ?: continue
                    val relPath = file.toString().removePrefix(rootPath.toString()).trimStart('/')
                    val lines = content.split('\n')
                    for ((idx, line) in lines.withIndex()) {
                        if (!line.lowercase().contains(needle)) continue
                        val before = if (idx > 0 && GREP_CONTEXT_LINES > 0) lines[idx - 1] else null
                        val after = if (idx < lines.size - 1 && GREP_CONTEXT_LINES > 0) lines[idx + 1] else null
                        hits += buildString {
                            append("$relPath:${idx + 1}: ${line.trim()}")
                            if (before != null && before.isNotBlank()) append("\n  ↑ ${before.trim().take(120)}")
                            if (after != null && after.isNotBlank()) append("\n  ↓ ${after.trim().take(120)}")
                        }
                        if (hits.size >= MAX_GREP_HITS) break
                    }
                }
                if (hits.size >= MAX_GREP_HITS) break
            }

            if (hits.isEmpty()) return "(no matches for '$pattern')"
            val header = "Matches for '$pattern' (${hits.size}${if (hits.size >= MAX_GREP_HITS) "+, truncated" else ""}):\n"
            return header + hits.joinToString("\n\n")
        }

        /** Рекурсивно собирает только .md/.kt/.svelte/.ts/.astro файлы — не хочется grep'ать по .png. */
        private fun collectFiles(root: Path): List<Path> {
            val allowed = listOf(".md", ".kt", ".svelte", ".ts", ".astro", ".yaml", ".yml", ".json")
            return runCatching {
                fs.listRecursively(root)
                    .filter { p ->
                        val m = fs.metadataOrNull(p) ?: return@filter false
                        m.isRegularFile && allowed.any { ext -> p.name.endsWith(ext) } && !p.name.startsWith(".")
                    }
                    .sortedBy { it.toString() }
                    .toList()
            }.getOrDefault(emptyList())
        }

        private fun deniedError(rawPath: String): String =
            "ERROR: path '$rawPath' is outside the sandbox. Allowed roots: ${ALLOWED_PREFIXES.joinToString(", ")}"
    }
}
