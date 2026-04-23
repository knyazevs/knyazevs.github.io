package dev.knyazev.rag

import kotlin.math.ln

/**
 * Classic BM25 index for keyword-based retrieval (ADR-19).
 *
 * Complements dense vector search by excelling at exact term matches:
 * ADR numbers ("ADR-15"), technology names ("Kotlin", "Ktor"), proper nouns.
 *
 * Parameters follow the original paper (Robertson & Zaragoza, 2009):
 *   k1 = 1.5  — term frequency saturation
 *   b  = 0.75 — document length normalisation
 */
class BM25Index {

    companion object {
        private const val K1 = 1.5f
        private const val B = 0.75f
    }

    private data class IndexedEntry(
        val entry: VectorEntry,
        val tokens: List<String>,
    )

    private var indexed: List<IndexedEntry> = emptyList()
    private var avgDocLength: Float = 0f
    private var idf: Map<String, Float> = emptyMap()

    fun index(entries: List<VectorEntry>) {
        indexed = entries.map { entry ->
            IndexedEntry(entry, tokenize("${entry.sectionTitle} ${entry.content}"))
        }
        avgDocLength = if (indexed.isEmpty()) 0f
            else indexed.map { it.tokens.size }.average().toFloat()
        idf = computeIdf(indexed)
    }

    fun search(query: String, topK: Int): List<ScoredEntry> {
        if (indexed.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).toSet()

        val rawScores = indexed.map { doc ->
            val score = queryTokens.sumOf { token ->
                val idfScore = idf[token] ?: 0f
                val tf = doc.tokens.count { it == token }.toFloat()
                val dl = doc.tokens.size.toFloat()
                val tfNorm = tf * (K1 + 1f) / (tf + K1 * (1f - B + B * dl / avgDocLength))
                (idfScore * tfNorm).toDouble()
            }.toFloat()
            doc.entry to score
        }.filter { (_, score) -> score > 0f }

        if (rawScores.isEmpty()) return emptyList()

        // Normalise to [0..1] so scores are compatible with RRF
        val maxScore = rawScores.maxOf { (_, s) -> s }
        return rawScores
            .map { (entry, score) -> ScoredEntry(entry, if (maxScore > 0f) score / maxScore else 0f) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-zа-яё0-9-]+"))
            .filter { it.length > 1 }

    private fun computeIdf(docs: List<IndexedEntry>): Map<String, Float> {
        val n = docs.size.toFloat()
        val df = mutableMapOf<String, Int>()
        for (doc in docs) {
            doc.tokens.toSet().forEach { token -> df[token] = (df[token] ?: 0) + 1 }
        }
        return df.mapValues { (_, docFreq) ->
            ln((n - docFreq + 0.5f) / (docFreq + 0.5f) + 1f)
        }
    }
}
