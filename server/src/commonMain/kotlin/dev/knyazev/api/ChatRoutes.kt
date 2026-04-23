package dev.knyazev.api

import dev.knyazev.agent.AgentMode
import dev.knyazev.agent.AgenticPipeline
import dev.knyazev.agent.FullContextPipeline
import dev.knyazev.guard.QuestionRouter
import dev.knyazev.guard.RoutingDecision
import dev.knyazev.llm.ChatEvent
import dev.knyazev.rag.RagPipeline
import dev.knyazev.rag.Skill
import dev.knyazev.rag.SkillExecutor
import dev.knyazev.rag.SuggestionsService
import io.github.oshai.kotlinlogging.KotlinLogging
import dev.knyazev.ui.UiBlock
import dev.knyazev.ui.UiTools
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger("ChatRoutes")
private val blockJson = Json { encodeDefaults = true }

@Serializable
data class ChatRequest(val question: String, val mode: String? = null)

/**
 * Сериализует UiBlock вместе с идентификатором для SSE-протокола ADR-025:
 * {"id":"B1","type":"image","url":"..."} — плоский JSON, type-дискриминатор из @SerialName.
 */
private fun encodeBlockWithId(id: String, block: UiBlock): String {
    val base = blockJson.encodeToJsonElement(UiBlock.serializer(), block) as JsonObject
    val wrapped = buildJsonObject {
        put("id", id)
        base.forEach { (k, v) -> put(k, v) }
    }
    return wrapped.toString()
}

fun Route.chatRoutes(
    ragPipeline: RagPipeline,
    agenticPipeline: AgenticPipeline,
    fullContextPipeline: FullContextPipeline,
    defaultMode: AgentMode,
    questionRouter: QuestionRouter,
    skillExecutor: SkillExecutor,
    skills: Map<String, Skill>,
    suggestionsService: SuggestionsService,
) {
    post("/api/chat") {
        val request = call.receive<ChatRequest>()
        val question = request.question.trim()

        if (question.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question must not be blank"))
            return@post
        }

        val mode = try {
            AgentMode.parse(request.mode, defaultMode)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid mode")))
            return@post
        }

        println("[CHAT] incoming question: \"$question\" mode=$mode")
        val decision = questionRouter.decide(question)
        println("[CHAT] router decision: $decision")
        if (decision is RoutingDecision.Irrelevant) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Этот чатбот отвечает только на вопросы о Сергее Князеве и его проекте.")
            )
            return@post
        }

        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")
        // Отключает буферизацию на nginx/cloudflare — важно для SSE, чтобы токены шли
        // по одному, а не пачкой при заполнении прокси-буфера.
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            // Первым сообщением — SSE-комментарий, чтобы пробить HTTP-буферы клиента и
            // дать браузеру понять что стрим открыт. ragPipeline.ask ниже может занять
            // 1-3 секунды (HyDE + embedding + search) — без пинга фронт получит status
            // и висит, а с пингом сразу знает что стрим жив.
            writeStringUtf8(": open\n\n")
            flush()

            val result = when (decision) {
                is RoutingDecision.UseSkill -> {
                    println("[CHAT] → SKILL path: '${decision.name}'")
                    val skill = skills[decision.name]
                        ?: error("Routing returned skill '${decision.name}' not found in registry")
                    skillExecutor.execute(skill, question)
                }
                RoutingDecision.GenericRag -> when (mode) {
                    AgentMode.AGENTIC -> {
                        println("[CHAT] → GENERIC/AGENTIC path")
                        agenticPipeline.ask(question)
                    }
                    AgentMode.FULL_CONTEXT -> {
                        println("[CHAT] → GENERIC/FULL-CONTEXT path")
                        fullContextPipeline.ask(question)
                    }
                    AgentMode.RAG -> {
                        println("[CHAT] → GENERIC/RAG path (legacy)")
                        ragPipeline.ask(question)
                    }
                }
                RoutingDecision.Irrelevant -> error("unreachable: Irrelevant handled above")
            }

            // ADR-025: блоки эмитятся ТОЛЬКО через tool-calls LLM (B-часть). Это гарантирует,
            // что каждый блок встаёт в правильном месте между текстом — модель сама решает,
            // какую картинку/ссылки показать и на какой позиции. Автоматическая C-эмиссия
            // (из markdown документа) отключена: она порождала блоки до текста («инородно»)
            // и фото профиля на каждом ответе, где профиль попадал в RAG-топ.
            var visualBlocksSent = 0
            var blockIdCounter = 0
            fun nextId(prefix: String) = "${prefix}${++blockIdCounter}"

            // State-машина ADR-025: токены оборачиваются в text-блоки, tool_call закрывает
            // текущий text-блок, после исполнения tool — открывается новый (при следующем токене).
            var currentTextId: String? = null
            var fullAnswer = ""

            suspend fun ByteWriteChannel.closeTextBlock() {
                val id = currentTextId ?: return
                writeStringUtf8("data: {\"block_end\":{\"id\":\"$id\"}}\n\n")
                flush()
                currentTextId = null
            }

            result.stream
                .catch { e ->
                    writeStringUtf8("data: {\"error\":\"${e.message?.replace("\"", "'")}\"}\n\n")
                }
                .collect { event ->
                    when (event) {
                        is ChatEvent.Token -> {
                            if (currentTextId == null) {
                                val id = nextId("T")
                                currentTextId = id
                                writeStringUtf8("data: {\"block_start\":{\"id\":\"$id\",\"type\":\"text\"}}\n\n")
                                flush()
                            }
                            fullAnswer += event.text
                            val escapedDelta = Json.encodeToString(String.serializer(), event.text)
                            writeStringUtf8(
                                "data: {\"token\":{\"id\":\"$currentTextId\",\"delta\":$escapedDelta}}\n\n",
                            )
                            flush()
                        }
                        is ChatEvent.ToolCall -> {
                            closeTextBlock()
                            val block = UiTools.execute(event.name, event.arguments)
                            if (block != null && visualBlocksSent < UiTools.BLOCKS_HARD_CAP) {
                                writeStringUtf8(
                                    "data: {\"block\":${encodeBlockWithId(nextId("B"), block)}}\n\n",
                                )
                                flush()
                                visualBlocksSent++
                            }
                        }
                    }
                }

            closeTextBlock()

            if (result.sources.isNotEmpty()) {
                val sourcesJson = result.sources.joinToString(",") { src ->
                    Json.encodeToString(String.serializer(), src)
                }
                writeStringUtf8("data: {\"sources\":[$sourcesJson]}\n\n")
                flush()
            }
            // Follow-up suggestions based on the summary part of the answer
            val summaryPart = fullAnswer.substringBefore("---DETAIL---").trim()
            val followUps = suggestionsService.generateFollowUps(question, summaryPart)
            if (followUps.isNotEmpty()) {
                val suggestionsJson = followUps.joinToString(",") { s ->
                    Json.encodeToString(String.serializer(), s)
                }
                writeStringUtf8("data: {\"suggestions\":[$suggestionsJson]}\n\n")
                flush()
            }
            writeStringUtf8("data: [DONE]\n\n")
            flush()
        }
    }
}
