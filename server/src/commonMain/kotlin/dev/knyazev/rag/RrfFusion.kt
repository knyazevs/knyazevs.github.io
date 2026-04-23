package dev.knyazev.rag

/**
 * Reciprocal Rank Fusion (ADR-19).
 *
 * Merges ranked lists from dense (vector) and sparse (BM25) retrieval
 * without requiring score normalisation across heterogeneous systems.
 *
 * Formula (Cormack et al., 2009):
 *   RRF_score(d) = Σ_i  1 / (k + rank_i(d))
 *
 * k=60 is the standard constant from the original paper — it dampens the
 * impact of very high-ranked documents and gives more weight to documents
 * that appear consistently across multiple lists.
 */
object RrfFusion {

    private const val DEFAULT_K = 60

    /**
     * Fuse dense and BM25 results into a single ranked list.
     *
     * @param denseResults  scored results from vector search (ordered by score desc)
     * @param bm25Results   scored results from BM25 search (ordered by score desc)
     * @param topK          number of results to return
     * @param threshold     minimum RRF score to include (filters low-quality candidates)
     * @param k             RRF constant (default 60)
     */
    fun fuse(
        denseResults: List<ScoredEntry>,
        bm25Results: List<ScoredEntry>,
        topK: Int,
        threshold: Float = 0f,
        k: Int = DEFAULT_K,
    ): List<ScoredEntry> {
        val scores = mutableMapOf<String, Float>()
        val entryById = mutableMapOf<String, VectorEntry>()

        fun accumulateRrf(results: List<ScoredEntry>) {
            results.forEachIndexed { rank, scored ->
                val id = scored.entry.id
                scores[id] = (scores[id] ?: 0f) + 1f / (k + rank + 1)
                entryById[id] = scored.entry
            }
        }

        accumulateRrf(denseResults)
        accumulateRrf(bm25Results)

        return scores.entries
            .filter { (_, score) -> score >= threshold }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (id, score) ->
                val entry = entryById[id]
                    ?: error("RRF invariant violated: scored id=$id not found in entry index")
                ScoredEntry(entry, score)
            }
    }
}
