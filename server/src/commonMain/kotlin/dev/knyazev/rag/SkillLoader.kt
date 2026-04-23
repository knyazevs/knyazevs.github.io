package dev.knyazev.rag

import io.github.oshai.kotlinlogging.KotlinLogging
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

private val logger = KotlinLogging.logger {}

/**
 * Загружает skill-файлы из директории `skillsPath` (ADR-026). Каждый файл — это
 * markdown с YAML-frontmatter (минимальный парсер; окio читает с диска так же,
 * как DocumentLoader читает docs/).
 *
 * Формат:
 * ```
 * ---
 * name: projects-overview
 * description: Полный обзор всех проектов
 * triggers: [какие проекты, список проектов]
 * sources:
 *   - docs/experience/
 *   - docs/projects/
 * ---
 * # Инструкция агенту
 * ...
 * ```
 */
object SkillLoader {

    private val fs = FileSystem.SYSTEM

    fun load(skillsPath: String): List<Skill> {
        val dir = skillsPath.toPath()
        val meta = fs.metadataOrNull(dir)
        if (meta == null || !meta.isDirectory) {
            logger.warn { "Skills directory not found: $skillsPath — skills layer disabled" }
            return emptyList()
        }

        val files = fs.list(dir)
            .filter { path ->
                val m = fs.metadataOrNull(path) ?: return@filter false
                m.isRegularFile && path.name.endsWith(".md") && !path.name.startsWith(".")
            }
            .sortedBy { it.name }

        val skills = files.mapNotNull { path ->
            val raw = fs.read(path) { readUtf8() }
            runCatching { parse(raw) }
                .onFailure { e ->
                    logger.warn { "Skill ${path.name} skipped: ${e.message}" }
                }
                .getOrNull()
        }

        val duplicates = skills.groupingBy { it.name }.eachCount().filter { it.value > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate skill names: ${duplicates.keys.joinToString()}"
        }

        logger.info { "Loaded ${skills.size} skills: ${skills.joinToString { it.name }}" }
        return skills
    }

    /**
     * Минимальный парсер YAML-frontmatter: ожидаем `---\n<kv>\n---\n<body>`.
     * Полноценный YAML не тянем — формат фиксированный, поля плоские, списки
     * только inline `[a, b]` или block `  - item` с двумя пробелами.
     */
    internal fun parse(raw: String): Skill {
        val lines = raw.split('\n')
        require(lines.firstOrNull()?.trim() == "---") {
            "Skill file must start with '---' frontmatter marker"
        }
        val endIdx = (1..lines.lastIndex).firstOrNull { lines[it].trim() == "---" }
            ?: error("Skill file missing closing '---' frontmatter marker")

        val frontmatter = lines.subList(1, endIdx)
        val body = lines.subList(endIdx + 1, lines.size).joinToString("\n").trim()

        val fields = mutableMapOf<String, String>()
        val listFields = mutableMapOf<String, MutableList<String>>()
        var currentList: String? = null

        for (line in frontmatter) {
            if (line.isBlank()) {
                currentList = null
                continue
            }
            val listKey = currentList
            if (line.startsWith("  - ") && listKey != null) {
                listFields.getOrPut(listKey) { mutableListOf() }.add(line.removePrefix("  - ").trim())
                continue
            }
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim()
            currentList = null
            when {
                value.isEmpty() -> {
                    // Block-list: следующие строки "  - item"
                    currentList = key
                    listFields[key] = mutableListOf()
                }
                value.startsWith("[") && value.endsWith("]") -> {
                    // Inline-list: [a, b, c]
                    listFields[key] = value
                        .removeSurrounding("[", "]")
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableList()
                }
                else -> {
                    fields[key] = value.removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }

        val name = requireNotNull(fields["name"]) { "Skill missing required field: name" }
        val description = requireNotNull(fields["description"]) { "Skill '$name' missing description" }
        val triggers = listFields["triggers"] ?: emptyList()
        val sources = listFields["sources"] ?: emptyList()
        require(sources.isNotEmpty()) { "Skill '$name' must declare at least one source" }
        require(body.isNotBlank()) { "Skill '$name' has empty instruction body" }

        return Skill(
            name = name,
            description = description,
            triggers = triggers,
            sources = sources,
            instruction = body,
        )
    }
}
