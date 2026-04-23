package dev.knyazev.rag

import dev.knyazev.llm.OpenRouterClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

class EmbeddingService(private val openRouterClient: OpenRouterClient) {

    suspend fun embed(text: String): FloatArray = openRouterClient.embedText(text)

    /** Embeds a list of chunks and returns VectorEntries (does NOT insert into a store). */
    fun embedChunks(chunks: List<DocumentChunk>, idOffset: Int = 0): List<VectorEntry> = runBlocking {
        chunks.mapIndexed { index, chunk ->
            val embeddingText = buildContextualEmbeddingText(chunk)
            val vector = openRouterClient.embedText(embeddingText)
            if ((index + 1) % 10 == 0) {
                logger.info { "Embedded ${index + 1}/${chunks.size} chunks" }
            }
            VectorEntry(
                id = "${chunk.filePath}#${idOffset + index}",
                content = chunk.content,
                filePath = chunk.filePath,
                sectionTitle = chunk.sectionTitle,
                breadcrumb = chunk.breadcrumb,
                vector = vector,
                blocks = chunk.blocks,
            )
        }
    }

    fun indexAll(chunks: List<DocumentChunk>, store: VectorStore) {
        val entries = embedChunks(chunks)
        entries.forEach { store.index(it) }
    }

    /**
     * Prepend breadcrumb to chunk content before embedding.
     * This anchors the chunk in its document context so the embedding model
     * captures not just the text but also where it lives in the knowledge base.
     */
    private fun buildContextualEmbeddingText(chunk: DocumentChunk): String =
        "[${chunk.breadcrumb}]\n\n${chunk.content}".take(8000)
}
