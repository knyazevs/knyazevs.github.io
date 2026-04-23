package dev.knyazev.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BM25IndexTest {

    private fun entry(id: String, title: String, content: String) = VectorEntry(
        id = id,
        content = content,
        filePath = "docs/$id.md",
        sectionTitle = title,
        breadcrumb = "",
        vector = FloatArray(0),
    )

    @Test
    fun `exact term match ranks highest`() {
        val index = BM25Index()
        index.index(listOf(
            entry("1", "Kotlin", "Сервер написан на Kotlin и Ktor"),
            entry("2", "Other",  "Документ без ключевых слов"),
            entry("3", "TypeScript", "Фронтенд использует Svelte и TypeScript"),
        ))

        val results = index.search("Kotlin Ktor", topK = 3)
        assertTrue(results.isNotEmpty(), "should find Kotlin doc")
        assertEquals("1", results.first().entry.id)
    }

    @Test
    fun `scores are normalised to 0_1 range`() {
        val index = BM25Index()
        index.index(listOf(
            entry("1", "alpha", "alpha alpha alpha"),
            entry("2", "beta",  "beta"),
        ))

        val results = index.search("alpha", topK = 5)
        assertTrue(results.all { it.score in 0f..1f }, "scores must be in [0,1]")
        assertEquals(1f, results.first().score, "top hit must normalise to 1.0")
    }

    @Test
    fun `cyrillic and ascii tokens both match`() {
        val index = BM25Index()
        index.index(listOf(
            entry("1", "Опыт", "Десять лет Kotlin разработки"),
            entry("2", "Other", "общая информация"),
        ))

        val ruResults = index.search("Kotlin", topK = 3)
        val enResults = index.search("разработки", topK = 3)
        assertTrue(ruResults.isNotEmpty())
        assertTrue(enResults.isNotEmpty())
    }

    @Test
    fun `no match returns empty list`() {
        val index = BM25Index()
        index.index(listOf(entry("1", "A", "foo bar")))

        val results = index.search("completely unrelated query", topK = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `empty index returns empty results`() {
        val index = BM25Index()
        index.index(emptyList())
        assertTrue(index.search("anything", topK = 5).isEmpty())
    }
}
