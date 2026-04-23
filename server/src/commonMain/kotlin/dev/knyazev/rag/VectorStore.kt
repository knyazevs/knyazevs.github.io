package dev.knyazev.rag

import dev.knyazev.ui.UiBlock
import kotlin.math.sqrt

data class VectorEntry(
    val id: String,
    val content: String,
    val filePath: String,
    val sectionTitle: String,
    val breadcrumb: String,
    val vector: FloatArray,
    /** UI-блоки чанка (ADR-024) — эмитятся через SSE после ретривала. */
    val blocks: List<UiBlock> = emptyList(),
) {
    override fun equals(other: Any?) = other is VectorEntry && id == other.id
    override fun hashCode() = id.hashCode()
}

data class ScoredEntry(val entry: VectorEntry, val score: Float)

interface VectorStore {
    fun index(entry: VectorEntry)

    /** Simple top-K search without score — kept for backward compatibility. */
    fun search(queryVector: FloatArray, topK: Int): List<VectorEntry>

    /** Top-K search with scores, filtered by cosine similarity threshold. */
    fun searchWithScores(
        queryVector: FloatArray,
        topK: Int,
        threshold: Float = 0f,
    ): List<ScoredEntry>

    /** All indexed entries — used to initialise BM25Index after indexing. */
    fun allEntries(): List<VectorEntry>
}

/**
 * Writes happen exclusively at startup (single-threaded loader). After the server
 * starts accepting requests, the list is read-only — no concurrent mutation path.
 * A plain MutableList is therefore safe and avoids the JVM-only `CopyOnWriteArrayList`.
 */
class InMemoryVectorStore : VectorStore {

    private val entries = mutableListOf<VectorEntry>()

    override fun index(entry: VectorEntry) {
        entries.add(entry)
    }

    override fun search(queryVector: FloatArray, topK: Int): List<VectorEntry> =
        searchWithScores(queryVector, topK).map { it.entry }

    override fun searchWithScores(
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
    ): List<ScoredEntry> {
        val queryNorm = norm(queryVector)
        return entries
            .map { entry -> ScoredEntry(entry, cosineSimilarity(queryVector, queryNorm, entry.vector)) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(topK)
    }

    override fun allEntries(): List<VectorEntry> = entries.toList()

    private fun cosineSimilarity(a: FloatArray, aNorm: Float, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        val bNorm = norm(b)
        return if (aNorm == 0f || bNorm == 0f) 0f else dot / (aNorm * bNorm)
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }
}
