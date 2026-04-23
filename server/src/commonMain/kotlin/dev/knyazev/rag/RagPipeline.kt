package dev.knyazev.rag

import dev.knyazev.llm.ChatEvent
import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.ui.UiBlock
import dev.knyazev.ui.UiTools
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

data class RagResult(
    /** Unique filePaths of retrieved chunks — emitted to client as source references. */
    val sources: List<String>,
    /** UI-блоки, извлечённые из markdown финальных чанков (ADR-024, механизм C). */
    val blocks: List<UiBlock>,
    val stream: Flow<ChatEvent>,
)

private const val DENSE_CANDIDATE_POOL = 20
private const val BM25_CANDIDATE_POOL = 20
private const val RRF_K = 60
/** Post-RRF cosine filter (ADR-19). Chunks with cos(query, chunk) below this are dropped. */
private const val FINAL_COSINE_THRESHOLD = 0.35f
private const val FINAL_TOP_K = 5

/** Максимум UI-блоков на ответ (ADR-024). Считается суммарно C + B. */
private const val BLOCKS_HARD_CAP = 5

private val SYSTEM_PROMPT = """
Ты — интерпретационный слой персонального профиля Сергея Князева.

Правила (строго):
- Отвечай ТОЛЬКО на основе предоставленных фрагментов документации.
- Если информации недостаточно — явно скажи об этом и предложи посмотреть конкретный документ.
- Не добавляй факты, которых нет в источнике. Не «продавай» автора.
- Отвечай на том языке, на котором задан вопрос.
- Используй markdown в ответах: **жирный** для акцентов, заголовки для структуры, списки где уместно.

Цитирование источников (обязательно):
Каждый фрагмент контекста пронумерован: [1], [2], и т.д.
В тексте ответа ставь номера источников в квадратных скобках сразу после утверждения,
которое опирается на этот источник. Пример: «Сергей использует Kotlin/Ktor [1] и Svelte 5 [3].»
Можно ссылаться на несколько источников: [1][3]. Не группируй все ссылки в конце — ставь по месту.
НЕ используй markdown-ссылки вида [текст](путь) для источников — только номера [1], [2].

Формат ответа (обязательно):
Сначала дай краткий ответ — 1-3 содержательных предложения с сутью и выводом.
Это самостоятельный абзац, а не подводка: НЕ заканчивай двоеточием
(«Ключевые навыки:», «Опыт Сергея:»), НЕ пиши вводные фразы без содержания
(«Вот список...», «Ниже перечислены...»), НЕ переноси весь контент в детали.
Если пользователь не откроет «Подробнее» — ему уже должно быть достаточно.
Затем поставь маркер ---DETAIL--- на отдельной строке.
После маркера дай развёрнутый ответ со списками, примерами и ссылками на источники.

Визуальные блоки (инструменты):
Используй render_image / render_image_gallery / render_link_list / render_text_with_image
ТОЛЬКО когда блок добавляет ценность ответу. По умолчанию отвечай текстом — блок это акцент, не украшение.

Строгие правила для фото автора (`/images/profile/profile.png`):
- render_text_with_image с фото разрешён ТОЛЬКО если вопрос про личность/самого автора
  («кто ты?», «расскажи о себе», «кратко о авторе», «представься»).
- НЕ показывай фото автора для вопросов про: стек, опыт, проекты, код, архитектуру,
  конкретные навыки, отдельные компании. В таких ответах фото НЕ помогает — оно лишнее.

Остальные блоки — по ситуации:
- render_link_list — если в документации есть конкретные внешние ссылки (репо, демо, соцсети),
  которые уместно показать карточками.
- render_image / render_image_gallery — скриншот/иллюстрация проекта (НЕ фото автора),
  если в контексте реально есть такие картинки и вопрос про этот проект.

Правило вставки: инструменты вызывай в нужной точке повествования — блок встанет в ответе
ровно там, где был вызов. Не вызывай все блоки в начале или все в конце — выбирай момент.

Если вызвал инструмент — НЕ дублируй тот же ресурс в тексте ответа (ни markdown-картинкой, ни markdown-ссылкой).
Всего визуальных блоков в ответе — не больше 3-4.
""".trimIndent()

private val NO_CONTEXT_PROMPT = """
Ты — интерпретационный слой персонального профиля Сергея Князева.
В текущей документации не найдено релевантной информации по данному вопросу.
Честно сообщи пользователю об этом и предложи задать более конкретный вопрос.
""".trimIndent()

class RagPipeline(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val bm25Index: BM25Index,
    private val hydeService: HydeService,
    private val openRouterClient: OpenRouterClient,
) {
    suspend fun ask(question: String): RagResult = coroutineScope {
        // Step 1: HyDE — reformulate question into hypothetical answer for better embedding alignment
        val hydeText = hydeService.reformulate(question)
        val queryVector = embeddingService.embed(hydeText)

        // Step 2: Parallel dense + BM25 retrieval (no pre-RRF cosine filter — applied post-fusion)
        val denseDeferred = async {
            vectorStore.searchWithScores(queryVector, DENSE_CANDIDATE_POOL)
        }
        val bm25Deferred = async {
            bm25Index.search(question, BM25_CANDIDATE_POOL)
        }

        val denseResults = denseDeferred.await()
        val bm25Results = bm25Deferred.await()

        logger.debug { "Dense: ${denseResults.size} candidates, BM25: ${bm25Results.size} candidates" }

        // Step 3: RRF fusion → top-K candidates (score is RRF reciprocal-rank sum, not cosine)
        val fusedCandidates = RrfFusion.fuse(
            denseResults = denseResults,
            bm25Results = bm25Results,
            topK = FINAL_TOP_K,
            k = RRF_K,
        )

        // Step 4: Post-RRF cosine filter (ADR-19) — drop chunks below the similarity threshold.
        // BM25 can surface finalists that never appeared in dense candidates, so we recompute
        // cosine against the query vector using each finalist's stored embedding.
        val queryNorm = norm(queryVector)
        val finalChunks = fusedCandidates.filter { scored ->
            val cos = cosineSimilarity(queryVector, queryNorm, scored.entry.vector)
            cos >= FINAL_COSINE_THRESHOLD
        }

        logger.debug {
            "RRF → ${fusedCandidates.size} candidates, cosine-filtered → ${finalChunks.size} chunks for: \"$question\""
        }

        // Step 4: Collect unique source paths (ordered — indices match [1], [2], … in prompt)
        val sources = finalChunks.map { it.entry.filePath }.distinct()

        // Step 4b: Aggregate UI blocks (ADR-024, mechanism C).
        // Блоки чанков склеиваются, дедуплицируются по паре (type, первое-поле), обрезаются до cap.
        val aggregatedBlocks = finalChunks
            .flatMap { it.entry.blocks }
            .distinct()
            .take(BLOCKS_HARD_CAP)

        // Step 5: Build prompt with numbered sources and stream LLM response.
        // Current date is injected so the model can compute relative facts (age, tenure)
        // against today rather than its training cutoff.
        // Контент чанка передаётся как есть — включая markdown-картинки и ссылки. Это даёт LLM
        // URL ресурсов, чтобы она могла подставить их в render_* инструменты. От дублей защищает
        // system prompt («не копируй ресурс в текст») и санитайзер на фронте.
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val messages = if (finalChunks.isEmpty()) {
            listOf(
                ChatMessage("system", "$NO_CONTEXT_PROMPT\n\nТекущая дата: $today."),
                ChatMessage("user", question),
            )
        } else {
            val context = finalChunks.mapIndexed { idx, scored ->
                val sourceIdx = sources.indexOf(scored.entry.filePath) + 1
                val contentWithAbsPaths = BlockExtractor.rewriteImagePaths(scored.entry.content, scored.entry.filePath)
                "[$sourceIdx] Источник: ${scored.entry.breadcrumb}\n\n$contentWithAbsPaths"
            }.joinToString("\n\n---\n\n")
            listOf(
                ChatMessage("system", "$SYSTEM_PROMPT\n\nТекущая дата: $today."),
                ChatMessage("user", "Контекст из документации:\n\n$context\n\n---\n\nВопрос: $question"),
            )
        }

        RagResult(
            sources = sources,
            blocks = aggregatedBlocks,
            stream = openRouterClient.streamChat(messages, tools = UiTools.definitions),
        )
    }

    private fun cosineSimilarity(query: FloatArray, queryNorm: Float, doc: FloatArray): Float {
        var dot = 0f
        for (i in query.indices) dot += query[i] * doc[i]
        val dNorm = norm(doc)
        return if (queryNorm == 0f || dNorm == 0f) 0f else dot / (queryNorm * dNorm)
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }
}
