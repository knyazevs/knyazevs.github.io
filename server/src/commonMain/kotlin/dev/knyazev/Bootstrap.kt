package dev.knyazev

import dev.knyazev.agent.AgentMode
import dev.knyazev.agent.AgenticPipeline
import dev.knyazev.agent.CorpusMap
import dev.knyazev.agent.FullContextPipeline
import dev.knyazev.agent.tools.NavigationTools
import dev.knyazev.config.AppConfig
import dev.knyazev.guard.QuestionRouter
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.plugins.configureCors
import dev.knyazev.plugins.configureRateLimit
import dev.knyazev.plugins.configureRouting
import dev.knyazev.plugins.configureSerialization
import dev.knyazev.plugins.configureSessionAuth
import dev.knyazev.rag.BM25Index
import dev.knyazev.rag.CodeLoader
import dev.knyazev.rag.DocumentLoader
import dev.knyazev.rag.EmbeddingCache
import dev.knyazev.rag.EmbeddingService
import dev.knyazev.rag.HydeService
import dev.knyazev.rag.InMemoryVectorStore
import dev.knyazev.rag.RagPipeline
import dev.knyazev.rag.SkillExecutor
import dev.knyazev.rag.SkillLoader
import dev.knyazev.rag.SuggestionsService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

fun main() {
    runServer()
}

/**
 * Common server bootstrap — single entry point for JVM and Kotlin/Native.
 * Loads documents + code, builds embedding + BM25 indexes, wires Ktor engine
 * and blocks in `start(wait = true)`.
 */
fun runServer() {
    val config = AppConfig.load()

    val openRouterClient = OpenRouterClient(
        apiKey = config.openRouterApiKey,
        model = config.llmModel,
        fallbackModels = config.llmFallbackModels,
        classifierModel = config.classifierModel,
        embeddingModel = config.embeddingModel,
        embeddingBaseUrl = config.embeddingBaseUrl,
        baseUrl = config.llmBaseUrl,
    )
    if (config.llmFallbackModels.isNotEmpty()) {
        logger.info { "LLM fallback chain: ${config.llmModel} → ${config.llmFallbackModels.joinToString(" → ")}" }
    }

    val embeddingService = EmbeddingService(openRouterClient)
    val vectorStore = InMemoryVectorStore()
    val bm25Index = BM25Index()
    val hydeService = HydeService(openRouterClient)
    val ragPipeline = RagPipeline(embeddingService, vectorStore, bm25Index, hydeService, openRouterClient)

    logger.info { "Loading documents from ${config.docsPath}..." }
    val documents = DocumentLoader.load(config.docsPath)
    logger.info { "Loaded ${documents.size} document chunks" }

    logger.info { "Loading code from ${config.codePath}..." }
    val codeChunks = CodeLoader.load(config.codePath)
    logger.info { "Loaded ${codeChunks.size} code chunks" }

    val allChunks = documents + codeChunks
    val cacheResult = EmbeddingCache.load(allChunks, config.embeddingModel, config.cachePath)

    cacheResult.cached.forEach { vectorStore.index(it) }

    if (cacheResult.toEmbed.isNotEmpty()) {
        logger.info { "Embedding ${cacheResult.toEmbed.size} chunks via API (${cacheResult.cached.size} from cache)..." }
        val newEntries = embeddingService.embedChunks(cacheResult.toEmbed, idOffset = cacheResult.cached.size)
        newEntries.forEach { vectorStore.index(it) }
        EmbeddingCache.save(vectorStore.allEntries(), allChunks, config.embeddingModel, config.cachePath)
    } else {
        logger.info { "All ${cacheResult.cached.size} vectors loaded from cache — no API calls needed" }
    }

    bm25Index.index(vectorStore.allEntries())
    logger.info { "Indexing complete: ${vectorStore.allEntries().size} chunks in vector store + BM25" }

    println("[SKILL-BOOT] Loading skills from ${config.skillsPath}...")
    val skills = SkillLoader.load(config.skillsPath)
    skills.forEach { s ->
        println(
            "[SKILL-BOOT] '${s.name}': sources=${s.sources} triggers=${s.triggers.size} " +
                "instruction=${s.instruction.length} chars"
        )
    }
    val skillsByName = skills.associateBy { it.name }

    val questionRouter = QuestionRouter(openRouterClient, skills)
    val skillExecutor = SkillExecutor(openRouterClient, config.docsPath)

    // ADR-027: агентные режимы. codePath — корень проекта (относительно рабочей дирeктории сервера).
    val navigator = NavigationTools.Executor(projectRoot = config.codePath)
    val corpusMap = CorpusMap.load(projectRoot = config.codePath)
    val agenticPipeline = AgenticPipeline(openRouterClient, navigator, corpusMap)
    val fullContextPipeline = FullContextPipeline(openRouterClient, projectRoot = config.codePath)
    val defaultMode = runCatching { AgentMode.parse(config.agentDefaultMode, AgentMode.AGENTIC) }
        .getOrElse {
            logger.warn { "Invalid agent.defaultMode '${config.agentDefaultMode}', falling back to AGENTIC" }
            AgentMode.AGENTIC
        }
    logger.info { "Agent default mode: $defaultMode (corpus map: ${corpusMap.chars} chars)" }

    val suggestionsService = SuggestionsService(openRouterClient, skills)
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    startupScope.launch {
        runCatching {
            withTimeout(10_000) { suggestionsService.generateInitial(vectorStore) }
        }.onFailure { logger.warn { "Initial suggestions generation skipped: ${it.message}" } }
    }

    embeddedServer(CIO, port = config.port) {
        configureSerialization()
        configureCors(config.corsAllowedOrigin)
        configureRateLimit()
        configureSessionAuth(config.sessionSecret)
        configureRouting(
            ragPipeline,
            agenticPipeline,
            fullContextPipeline,
            defaultMode,
            openRouterClient,
            questionRouter,
            skillExecutor,
            skillsByName,
            suggestionsService,
            config.sessionSecret,
        )
    }.start(wait = true)
}
