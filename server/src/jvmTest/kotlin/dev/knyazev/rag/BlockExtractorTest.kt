package dev.knyazev.rag

import dev.knyazev.ui.ImageBlock
import dev.knyazev.ui.ImageGalleryBlock
import dev.knyazev.ui.LinkListBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull

class BlockExtractorTest {

    @Test
    fun `single image becomes ImageBlock with rewritten path`() {
        val md = """
            # Заголовок

            ![Портрет](profile.png)

            Текст после.
        """.trimIndent()

        val result = BlockExtractor.extract(md, "profile/index.md")

        assertEquals(1, result.blocks.size)
        val block = assertIs<ImageBlock>(result.blocks[0])
        assertEquals("/images/profile/profile.png", block.url)
        assertEquals("Портрет", block.caption)
        assertTrue("!(profile.png)" !in result.cleanedMarkdown)
        assertTrue("Текст после." in result.cleanedMarkdown)
    }

    @Test
    fun `two images in a row become ImageGalleryBlock`() {
        val md = """
            Галерея:

            ![A](a.png)
            ![B](b.png)

            Конец.
        """.trimIndent()

        val result = BlockExtractor.extract(md, "experience/001-project.md")

        assertEquals(1, result.blocks.size)
        val gallery = assertIs<ImageGalleryBlock>(result.blocks[0])
        assertEquals(2, gallery.images.size)
        assertEquals("/images/experience/a.png", gallery.images[0].url)
        assertEquals("/images/experience/b.png", gallery.images[1].url)
    }

    @Test
    fun `external http URL is not rewritten`() {
        val md = "![cdn](https://cdn.example.com/foo.png)"
        val result = BlockExtractor.extract(md, "profile/index.md")

        val block = assertIs<ImageBlock>(result.blocks.single())
        assertEquals("https://cdn.example.com/foo.png", block.url)
    }

    @Test
    fun `external link becomes LinkListBlock item and title remains inline`() {
        val md = "Мой код на [GitHub](https://github.com/example) — заходите."

        val result = BlockExtractor.extract(md, "profile/index.md")

        val linkList = assertIs<LinkListBlock>(result.blocks.single())
        assertEquals(1, linkList.items.size)
        assertEquals("https://github.com/example", linkList.items[0].url)
        assertEquals("GitHub", linkList.items[0].title)
        // В тексте остаётся plain-title, без markdown-ссылки.
        assertTrue("[GitHub]" !in result.cleanedMarkdown)
        assertTrue("Мой код на GitHub — заходите." in result.cleanedMarkdown)
    }

    @Test
    fun `anchor link is preserved and not extracted`() {
        val md = "См. [раздел](#section) ниже."

        val result = BlockExtractor.extract(md, "adr/024.md")

        assertTrue(result.blocks.isEmpty())
        assertTrue("[раздел](#section)" in result.cleanedMarkdown)
    }

    @Test
    fun `inline code link is ignored`() {
        val md = "Пример markdown: `[title](url)` — так пишут ссылки."

        val result = BlockExtractor.extract(md, "adr/024.md")

        assertTrue(result.blocks.isEmpty())
        assertTrue("`[title](url)`" in result.cleanedMarkdown)
    }

    @Test
    fun `fenced code block is not parsed`() {
        val md = """
            Вот пример:

            ```markdown
            ![demo](demo.png)
            [link](https://example.com)
            ```

            Больше ничего.
        """.trimIndent()

        val result = BlockExtractor.extract(md, "adr/024.md")

        assertTrue(result.blocks.isEmpty(), "fenced code must not produce blocks")
        assertTrue("![demo](demo.png)" in result.cleanedMarkdown)
        assertTrue("[link](https://example.com)" in result.cleanedMarkdown)
    }

    @Test
    fun `duplicate links are deduplicated in LinkListBlock`() {
        val md = """
            Ссылка [сайт](https://example.com) и ещё раз [тот же](https://example.com).
        """.trimIndent()

        val result = BlockExtractor.extract(md, "profile/index.md")

        val linkList = assertIs<LinkListBlock>(result.blocks.single())
        assertEquals(1, linkList.items.size)
    }

    @Test
    fun `document at root returns url under images root`() {
        val md = "![logo](logo.png)"

        val result = BlockExtractor.extract(md, "README.md")

        val block = assertIs<ImageBlock>(result.blocks.single())
        assertEquals("/images/logo.png", block.url)
    }

    @Test
    fun `empty markdown returns no blocks`() {
        val result = BlockExtractor.extract("", "profile/index.md")
        assertTrue(result.blocks.isEmpty())
        assertEquals("", result.cleanedMarkdown)
    }
}
