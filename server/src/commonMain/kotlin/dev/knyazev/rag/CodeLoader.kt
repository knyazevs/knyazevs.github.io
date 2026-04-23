package dev.knyazev.rag

import io.github.oshai.kotlinlogging.KotlinLogging
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

private val logger = KotlinLogging.logger {}

/**
 * Loads source code files from the repository into DocumentChunks for RAG indexing.
 *
 * Strategy:
 * - Walks server/, app/src/, scripts/ and config files at repo root
 * - Small files (≤ 80 lines): one chunk = whole file
 * - Larger files: split at top-level Kotlin/TS declaration boundaries
 * - Very large files (> 500 lines): skipped — unlikely to be useful in RAG context
 *
 * Breadcrumb format: "code/path/to/File.kt › fun myFunction(...)"
 */
object CodeLoader {

    private val fs = FileSystem.SYSTEM

    private val INCLUDED_EXTENSIONS = setOf(
        "kt", "kts",
        "ts", "mjs", "js",
        "svelte",
        "toml", "yml", "yaml",
        "json", "md",
        "conf",
    )

    private val EXCLUDED_DIRS = setOf(
        "node_modules", ".git", "build", "dist", ".gradle",
        "out", "generated", ".idea", "cache", ".kotlin",
    )

    private val EXCLUDED_FILES = setOf(
        "bun.lock", "package-lock.json", "yarn.lock",
        "gradlew", "gradlew.bat",
    )

    // Directories relative to repo root to index
    private val SOURCE_DIRS = listOf("server/src", "app/src", "scripts")

    // Config files at repo root
    private val ROOT_FILES = listOf(
        "server/build.gradle.kts",
        "server/.env.example",
        "app/astro.config.mjs",
        "app/package.json",
        "CLAUDE.md",
    )

    private const val SKIP_THRESHOLD = 500 // lines
    private const val SPLIT_THRESHOLD = 80 // lines — whole file below this

    fun load(repoRoot: String): List<DocumentChunk> {
        val root = repoRoot.toPath()
        val chunks = mutableListOf<DocumentChunk>()

        // Source directories — use pruning walk so we never descend into excluded dirs
        for (relDir in SOURCE_DIRS) {
            val dir = root / relDir
            val meta = fs.metadataOrNull(dir) ?: continue
            if (!meta.isDirectory) continue
            walkPruned(dir)
                .filter { extensionOf(it) in INCLUDED_EXTENSIONS }
                .filter { it.name !in EXCLUDED_FILES }
                .sortedBy { it.toString() }
                .flatMapTo(chunks) { file -> chunkFile(file, root) }
        }

        // Selected root-level config files
        for (relPath in ROOT_FILES) {
            val file = root / relPath
            val meta = fs.metadataOrNull(file) ?: continue
            if (meta.isRegularFile) chunks.addAll(chunkFile(file, root))
        }

        return chunks
    }

    /**
     * Directory walk that prunes excluded subtrees (vs okio's listRecursively
     * which always descends). Matters for huge dirs like node_modules.
     */
    private fun walkPruned(dir: Path): Sequence<Path> = sequence {
        val children = try {
            fs.list(dir)
        } catch (_: Exception) {
            return@sequence
        }
        for (child in children) {
            val name = child.name
            if (name in EXCLUDED_DIRS || name.startsWith(".")) continue
            val meta = fs.metadataOrNull(child) ?: continue
            when {
                meta.isDirectory -> yieldAll(walkPruned(child))
                meta.isRegularFile -> yield(child)
            }
        }
    }

    private fun chunkFile(file: Path, basePath: Path): List<DocumentChunk> {
        val absolutePath = fs.canonicalize(file).toString()
        val relativePath = "code/" + relativize(basePath, file)
        val lines = try {
            fs.read(file) { readUtf8() }.split('\n')
        } catch (_: Exception) {
            return emptyList()
        }

        if (lines.size > SKIP_THRESHOLD) {
            logger.info { "Skipping $relativePath — ${lines.size} lines > SKIP_THRESHOLD ($SKIP_THRESHOLD)" }
            return emptyList()
        }
        if (lines.size <= SPLIT_THRESHOLD) return listOf(wholeFileChunk(relativePath, absolutePath, lines))

        return when (extensionOf(file)) {
            "kt", "kts" -> splitByDeclarations(relativePath, absolutePath, lines, KOTLIN_DECL)
            "ts", "mjs", "js" -> splitByDeclarations(relativePath, absolutePath, lines, TYPESCRIPT_DECL)
            else -> listOf(wholeFileChunk(relativePath, absolutePath, lines))
        }.ifEmpty { listOf(wholeFileChunk(relativePath, absolutePath, lines)) }
    }

    private val KOTLIN_DECL = Regex(
        """^(fun |suspend fun |private fun |internal fun |protected fun |override fun |""" +
            """class |data class |sealed class |abstract class |object |companion object |interface )"""
    )

    private val TYPESCRIPT_DECL = Regex(
        """^(export (async )?function |export const |export class |export interface |""" +
            """export type |export default |async function |function )"""
    )

    private fun splitByDeclarations(path: String, sourceFile: String, lines: List<String>, pattern: Regex): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        var chunkStart = 0
        var chunkLabel = path

        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            if (i > 0 && pattern.containsMatchIn(trimmed)) {
                val content = lines.subList(chunkStart, i).joinToString("\n").trim()
                if (content.isNotBlank()) {
                    chunks.add(DocumentChunk(path, chunkLabel, content, breadcrumb(path, chunkLabel), sourceFile))
                }
                chunkLabel = trimmed.take(80).trimEnd()
                chunkStart = i
            }
        }

        val tail = lines.subList(chunkStart, lines.size).joinToString("\n").trim()
        if (tail.isNotBlank()) {
            chunks.add(DocumentChunk(path, chunkLabel, tail, breadcrumb(path, chunkLabel), sourceFile))
        }

        return chunks
    }

    private fun wholeFileChunk(path: String, sourceFile: String, lines: List<String>): DocumentChunk {
        val content = lines.joinToString("\n").trim()
        return DocumentChunk(path, path, content, path, sourceFile)
    }

    private fun breadcrumb(path: String, label: String): String =
        if (label == path) path else "$path › $label"

    private fun extensionOf(path: Path): String = path.name.substringAfterLast('.', "")

    private fun relativize(base: Path, child: Path): String {
        val b = base.toString().trimEnd('/', '\\')
        val c = child.toString()
        return c.removePrefix(b).trimStart('/', '\\')
    }
}
