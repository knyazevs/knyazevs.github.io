package dev.knyazev.rag

import dev.knyazev.ui.ImageBlock
import dev.knyazev.ui.ImageGalleryBlock
import dev.knyazev.ui.UiBlock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

data class DocumentChunk(
    val filePath: String,
    val sectionTitle: String,
    val content: String,
    /** Full breadcrumb path used in contextual embedding: "path/file.md › H1 title › Section title" */
    val breadcrumb: String,
    /** Absolute path to the source file this chunk was derived from — used for per-file cache invalidation. */
    val sourceFile: String,
    /**
     * UI-блоки, извлечённые из markdown-тела (ADR-024, механизм C).
     * Эмитируются фронту через SSE при ретривале чанка. Content остаётся оригиналом —
     * очистка под LLM-контекст происходит на лету в RagPipeline.
     */
    val blocks: List<UiBlock> = emptyList(),
)

object DocumentLoader {

    private val fs = FileSystem.SYSTEM

    fun load(docsPath: String): List<DocumentChunk> {
        val docsDir = docsPath.toPath()
        val metadata = fs.metadataOrNull(docsDir)
        require(metadata != null && metadata.isDirectory) {
            "Docs directory not found: $docsPath"
        }

        return fs.listRecursively(docsDir)
            .filter { path ->
                val meta = fs.metadataOrNull(path) ?: return@filter false
                meta.isRegularFile &&
                    path.name.endsWith(".md") &&
                    !path.name.startsWith(".")
            }
            .sortedBy { it.toString() }
            .flatMap { file -> chunkFile(file, docsDir) }
            .toList()
    }

    private fun chunkFile(file: Path, basePath: Path): List<DocumentChunk> {
        val absolutePath = fs.canonicalize(file).toString()
        val relativePath = relativize(basePath, file)
        val lines = fs.read(file) { readUtf8() }.split('\n')
        val chunks = mutableListOf<DocumentChunk>()

        // Track document-level title (first H1) for breadcrumb context
        var documentTitle: String? = null
        var currentSection = relativePath
        val currentLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("# ") && documentTitle == null -> {
                    // First H1 = document title, not a chunk boundary
                    documentTitle = line.removePrefix("# ").trim()
                }
                line.startsWith("## ") || line.startsWith("### ") -> {
                    flushChunk(relativePath, absolutePath, currentSection, currentLines, chunks, documentTitle)
                    currentSection = line.trimStart('#').trim()
                }
                else -> currentLines.add(line)
            }
        }
        flushChunk(relativePath, absolutePath, currentSection, currentLines, chunks, documentTitle)

        return chunks
    }

    private fun flushChunk(
        filePath: String,
        sourceFile: String,
        sectionTitle: String,
        lines: MutableList<String>,
        chunks: MutableList<DocumentChunk>,
        documentTitle: String?,
    ) {
        val content = lines.joinToString("\n").trim()
        if (content.isNotBlank()) {
            val breadcrumb = buildBreadcrumb(filePath, documentTitle, sectionTitle)
            val blocks = filterDecorativeImages(filePath, BlockExtractor.extract(content, filePath).blocks)
            chunks.add(DocumentChunk(filePath, sectionTitle, content, breadcrumb, sourceFile, blocks))
        }
        lines.clear()
    }

    // Фото в docs/profile/ — оформление карточки, а не иллюстрация контента. Чанк профиля
    // уходит в топ RRF почти на любой запрос о Сергее, и без этой фильтрации его фото
    // автоматом улетало C-блоком к каждому ответу. URL остаётся в content для LLM —
    // она сама решит показать фото через render_text_with_image, когда это уместно.
    private fun filterDecorativeImages(filePath: String, blocks: List<UiBlock>): List<UiBlock> {
        if (!filePath.startsWith("profile/")) return blocks
        return blocks.filterNot { it is ImageBlock || it is ImageGalleryBlock }
    }

    private fun buildBreadcrumb(filePath: String, documentTitle: String?, sectionTitle: String): String {
        val parts = mutableListOf(filePath)
        if (documentTitle != null) parts.add(documentTitle)
        if (sectionTitle != filePath) parts.add(sectionTitle)
        return parts.joinToString(" › ")
    }

    private fun relativize(base: Path, child: Path): String {
        val b = base.toString().trimEnd('/', '\\')
        val c = child.toString()
        return c.removePrefix(b).trimStart('/', '\\')
    }
}
