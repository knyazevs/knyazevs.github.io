package dev.knyazev.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RrfFusionTest {

    private fun entry(id: String) = VectorEntry(
        id = id,
        content = "",
        filePath = "docs/$id.md",
        sectionTitle = "",
        breadcrumb = "",
        vector = FloatArray(0),
    )

    @Test
    fun `doc appearing in both lists ranks highest`() {
        val a = entry("a"); val b = entry("b"); val c = entry("c")

        val dense = listOf(ScoredEntry(a, 0.9f), ScoredEntry(b, 0.8f))
        val bm25 = listOf(ScoredEntry(c, 0.9f), ScoredEntry(a, 0.7f))

        val fused = RrfFusion.fuse(dense, bm25, topK = 3)
        assertEquals("a", fused.first().entry.id, "a is in both lists — must win fusion")
    }

    @Test
    fun `rrf score uses standard k=60 reciprocal rank sum`() {
        val a = entry("a")
        val dense = listOf(ScoredEntry(a, 1f))  // rank 1 → 1/(60+1)
        val bm25 = listOf(ScoredEntry(a, 1f))   // rank 1 → 1/(60+1)

        val fused = RrfFusion.fuse(dense, bm25, topK = 1)
        val expected = 2f / 61f
        assertTrue(
            kotlin.math.abs(fused[0].score - expected) < 1e-6,
            "expected $expected but got ${fused[0].score}",
        )
    }

    @Test
    fun `deduplicates entries by id across lists`() {
        val a = entry("a"); val b = entry("b")
        val dense = listOf(ScoredEntry(a, 0.9f), ScoredEntry(b, 0.8f))
        val bm25 = listOf(ScoredEntry(a, 0.9f), ScoredEntry(b, 0.8f))

        val fused = RrfFusion.fuse(dense, bm25, topK = 10)
        assertEquals(2, fused.size, "a and b must each appear once")
    }

    @Test
    fun `threshold filters low-scoring docs`() {
        val a = entry("a"); val b = entry("b")
        val dense = listOf(ScoredEntry(a, 1f), ScoredEntry(b, 1f))
        val bm25 = emptyList<ScoredEntry>()

        // a at rank 0: 1/61 ≈ 0.0164
        // b at rank 1: 1/62 ≈ 0.0161
        val filtered = RrfFusion.fuse(dense, bm25, topK = 10, threshold = 0.0163f)
        assertEquals(listOf("a"), filtered.map { it.entry.id })
    }

    @Test
    fun `empty inputs yield empty output`() {
        val fused = RrfFusion.fuse(emptyList(), emptyList(), topK = 5)
        assertTrue(fused.isEmpty())
    }
}
