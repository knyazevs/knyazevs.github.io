package dev.knyazev.rag

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingCacheTest {

    private lateinit var workDir: File
    private lateinit var sourceFile: File
    private lateinit var cachePath: String

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("embedding-cache-test").toFile()
        sourceFile = File(workDir, "doc.md").apply { writeText("# Title\n\nContent v1") }
        cachePath = File(workDir, "cache.json").absolutePath
    }

    @AfterTest
    fun teardown() {
        workDir.deleteRecursively()
    }

    private fun chunk(filePath: String, content: String, sourceFile: String) = DocumentChunk(
        filePath = filePath,
        sectionTitle = "Title",
        content = content,
        breadcrumb = "doc.md › Title",
        sourceFile = sourceFile,
    )

    private fun entry(id: String, filePath: String) = VectorEntry(
        id = id,
        content = "content",
        filePath = filePath,
        sectionTitle = "Title",
        breadcrumb = "doc.md › Title",
        vector = floatArrayOf(0.1f, 0.2f, 0.3f),
    )

    @Test
    fun `missing cache file reports all chunks as needing embedding`() {
        val chunks = listOf(chunk("doc.md", "Content v1", sourceFile.absolutePath))

        val result = EmbeddingCache.load(chunks, "text-embedding-3-small", cachePath)

        assertTrue(result.cached.isEmpty())
        assertEquals(chunks, result.toEmbed)
    }

    @Test
    fun `unchanged file is served from cache`() {
        val chunks = listOf(chunk("doc.md", "Content v1", sourceFile.absolutePath))
        val entries = listOf(entry("0", "doc.md"))

        EmbeddingCache.save(entries, chunks, "text-embedding-3-small", cachePath)

        val reloaded = EmbeddingCache.load(chunks, "text-embedding-3-small", cachePath)
        assertEquals(1, reloaded.cached.size)
        assertEquals("0", reloaded.cached[0].id)
        assertTrue(reloaded.toEmbed.isEmpty())
    }

    @Test
    fun `modified file triggers re-embedding`() {
        val chunks = listOf(chunk("doc.md", "Content v1", sourceFile.absolutePath))
        val entries = listOf(entry("0", "doc.md"))

        EmbeddingCache.save(entries, chunks, "text-embedding-3-small", cachePath)

        // Modify source
        sourceFile.writeText("# Title\n\nContent v2 — CHANGED")

        val reloaded = EmbeddingCache.load(chunks, "text-embedding-3-small", cachePath)
        assertTrue(reloaded.cached.isEmpty(), "hash mismatch must invalidate cached entries")
        assertEquals(chunks, reloaded.toEmbed)
    }

    @Test
    fun `changing embedding model invalidates entire cache`() {
        val chunks = listOf(chunk("doc.md", "Content v1", sourceFile.absolutePath))
        val entries = listOf(entry("0", "doc.md"))

        EmbeddingCache.save(entries, chunks, "text-embedding-3-small", cachePath)

        val reloaded = EmbeddingCache.load(chunks, "text-embedding-3-large", cachePath)
        assertTrue(reloaded.cached.isEmpty())
        assertEquals(chunks, reloaded.toEmbed)
    }
}
