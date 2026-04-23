package dev.knyazev.rag

import dev.knyazev.ui.UiBlock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private val fs = FileSystem.SYSTEM

@Serializable
private data class CachedEntry(
    val id: String,
    val content: String,
    val filePath: String,
    val sectionTitle: String,
    val breadcrumb: String,
    val vector: List<Float>,
    /**
     * UI-блоки чанка (ADR-024). Default = пустой список для backward-compat:
     * кэши, записанные до внедрения блоков, читаются без ошибки;
     * при следующем изменении исходного файла пересчитаются с блоками.
     */
    val blocks: List<UiBlock> = emptyList(),
)

@Serializable
private data class CachedFile(
    val hash: String,
    val entries: List<CachedEntry>,
)

@Serializable
private data class CacheRoot(
    val embeddingModel: String,
    val files: Map<String, CachedFile>,
)

/**
 * Per-file embedding cache.
 *
 * - Groups chunks by their `sourceFile` (absolute path to the original file on disk).
 * - Stores SHA-256 of the file content alongside the embeddings.
 * - On load: unchanged files → cached vectors; changed/new files → need re-embedding;
 *   deleted files → removed from cache.
 * - Switching `embeddingModel` invalidates the entire cache.
 */
object EmbeddingCache {

    data class LoadResult(
        /** Entries loaded from cache — ready to insert into VectorStore. */
        val cached: List<VectorEntry>,
        /** Chunks from new or modified files — need embedding via API. */
        val toEmbed: List<DocumentChunk>,
    )

    /**
     * Loads cached entries where file content hasn't changed.
     * Returns [LoadResult] — cached entries + chunks that need fresh embedding.
     */
    fun load(
        allChunks: List<DocumentChunk>,
        embeddingModel: String,
        cacheFilePath: String,
    ): LoadResult {
        val chunksBySource = allChunks.groupBy { it.sourceFile }

        val cacheFile = cacheFilePath.toPath()
        if (fs.metadataOrNull(cacheFile) == null) {
            logger.info { "Cache file not found — full indexing required (${allChunks.size} chunks)" }
            return LoadResult(cached = emptyList(), toEmbed = allChunks)
        }

        val root = try {
            json.decodeFromString<CacheRoot>(fs.read(cacheFile) { readUtf8() })
        } catch (e: Exception) {
            logger.warn { "Cache file unreadable (${e.message}) — full indexing required" }
            return LoadResult(cached = emptyList(), toEmbed = allChunks)
        }

        if (root.embeddingModel != embeddingModel) {
            logger.info { "Embedding model changed (${root.embeddingModel} → $embeddingModel) — full re-indexing" }
            return LoadResult(cached = emptyList(), toEmbed = allChunks)
        }

        val cached = mutableListOf<VectorEntry>()
        val toEmbed = mutableListOf<DocumentChunk>()

        for ((sourceFile, chunks) in chunksBySource) {
            val currentHash = hashFile(sourceFile)
            val cachedFile = root.files[sourceFile]

            if (cachedFile != null && cachedFile.hash == currentHash) {
                cached.addAll(cachedFile.entries.map { it.toVectorEntry() })
            } else {
                if (cachedFile != null) {
                    logger.info { "File changed → re-embed: $sourceFile" }
                } else {
                    logger.info { "New file → embed: $sourceFile" }
                }
                toEmbed.addAll(chunks)
            }
        }

        logger.info { "Cache: ${cached.size} vectors from cache, ${toEmbed.size} chunks to embed" }
        return LoadResult(cached = cached, toEmbed = toEmbed)
    }

    /**
     * Saves all current entries grouped by their source file.
     * [allChunks] is needed to know which sourceFile each entry came from
     * (the VectorEntry doesn't store sourceFile — only the chunk does).
     */
    fun save(
        entries: List<VectorEntry>,
        allChunks: List<DocumentChunk>,
        embeddingModel: String,
        cacheFilePath: String,
    ) {
        runCatching {
            // Build a lookup: filePath#index → sourceFile
            val chunkSourceMap = allChunks.associate { chunk ->
                chunk.filePath to chunk.sourceFile
            }

            // Group entries by sourceFile
            val grouped = mutableMapOf<String, MutableList<VectorEntry>>()
            for (entry in entries) {
                val sourceFile = chunkSourceMap[entry.filePath] ?: continue
                grouped.getOrPut(sourceFile) { mutableListOf() }.add(entry)
            }

            val files = grouped.map { (sourceFile, fileEntries) ->
                sourceFile to CachedFile(
                    hash = hashFile(sourceFile),
                    entries = fileEntries.map { it.toCachedEntry() },
                )
            }.toMap()

            val root = CacheRoot(embeddingModel = embeddingModel, files = files)
            fs.write(cacheFilePath.toPath()) { writeUtf8(json.encodeToString(CacheRoot.serializer(), root)) }
            logger.info { "Cache saved: ${entries.size} vectors across ${files.size} files → $cacheFilePath" }
        }.onFailure { e ->
            logger.warn { "Failed to save embedding cache: ${e.message}" }
        }
    }

    private fun hashFile(absolutePath: String): String {
        return try {
            val bytes = fs.read(absolutePath.toPath()) { readByteArray() }
            bytes.toByteString().sha256().hex()
        } catch (_: Exception) {
            "missing"
        }
    }

    private fun CachedEntry.toVectorEntry() = VectorEntry(
        id = id,
        content = content,
        filePath = filePath,
        sectionTitle = sectionTitle,
        breadcrumb = breadcrumb,
        vector = FloatArray(vector.size) { i -> vector[i] },
        blocks = blocks,
    )

    private fun VectorEntry.toCachedEntry() = CachedEntry(
        id = id,
        content = content,
        filePath = filePath,
        sectionTitle = sectionTitle,
        breadcrumb = breadcrumb,
        vector = vector.toList(),
        blocks = blocks,
    )
}
