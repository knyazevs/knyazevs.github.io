package dev.knyazev.llm

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

class OpenAiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val embeddingModel: String = "text-embedding-3-small",
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient() {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun embedText(text: String): FloatArray {
        val requestBody = buildJsonObject {
            put("model", embeddingModel)
            put("input", text)
        }

        val response = client.post("$baseUrl/embeddings") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "Embedding API error ${response.status}: $rawBody" }
            error("Embedding API error ${response.status}: $rawBody")
        }
        val body = json.parseToJsonElement(rawBody).jsonObject
        val embeddingArray = body["data"]
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject?.get("embedding")
            ?.jsonArray
            ?: error("Unexpected embedding response: $rawBody")

        return FloatArray(embeddingArray.size) { i ->
            embeddingArray[i].jsonPrimitive.float
        }
    }

    suspend fun textToSpeech(text: String, voice: String = "alloy"): ByteArray {
        val requestBody = buildJsonObject {
            put("model", "tts-1")
            put("input", text)
            put("voice", voice)
        }

        val response = client.post("$baseUrl/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        return response.readRawBytes()
    }

    suspend fun speechToText(audioBytes: ByteArray, filename: String = "audio.webm"): String {
        val response = client.post("$baseUrl/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", "whisper-1")
                        append("language", "ru")
                        append(
                            key = "file",
                            value = audioBytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "audio/webm")
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            }
                        )
                    }
                )
            )
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["text"]!!.jsonPrimitive.content
    }
}
