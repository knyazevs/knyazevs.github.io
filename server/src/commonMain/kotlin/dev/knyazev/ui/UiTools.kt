package dev.knyazev.ui

import dev.knyazev.llm.ToolDefinition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger("UiTools")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Каталог UI-инструментов (ADR-024, механизм B). LLM через OpenRouter tool-calling
 * вызывает `render_image` / `render_image_gallery` / `render_link_list` с аргументами
 * — бэкенд валидирует JSON и превращает в UiBlock, который эмитится в SSE.
 *
 * Исполнение локальное: никаких сайд-эффектов, только построение структуры ответа.
 */
object UiTools {

    const val BLOCKS_HARD_CAP: Int = 5

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "render_image",
            description = "Показать одно изображение с опциональной подписью и ссылкой.",
            parametersSchema = schemaImage(),
        ),
        ToolDefinition(
            name = "render_image_gallery",
            description = "Показать сетку из 2+ изображений. Используй когда есть несколько релевантных картинок.",
            parametersSchema = schemaImageGallery(),
        ),
        ToolDefinition(
            name = "render_link_list",
            description = "Показать список внешних ссылок карточками (репозитории, демо, статьи).",
            parametersSchema = schemaLinkList(),
        ),
        ToolDefinition(
            name = "render_text_with_image",
            description = "Блок «фото + текст рядом». Используй для самопрезентации/визитки (вопрос «кто ты?»), " +
                "краткой карточки проекта или любого случая, когда изображение и поясняющий текст должны стоять рядом. " +
                "Текст поддерживает markdown (жирный, списки). imagePosition: \"left\" — фото слева, \"right\" — справа.",
            parametersSchema = schemaTextWithImage(),
        ),
    )

    /**
     * Диспатчер tool-calls: по имени валидирует аргументы и строит соответствующий UiBlock.
     * Невалидный JSON или неизвестное имя — null + WARN-лог, SSE-событие не эмитится.
     */
    fun execute(name: String, argumentsJson: String): UiBlock? {
        val args = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull()
        if (args == null) {
            logger.warn { "UiTools '$name' invalid JSON args: ${argumentsJson.take(200)}" }
            return null
        }
        return runCatching<UiBlock?> {
            when (name) {
                "render_image" -> json.decodeFromJsonElement(ImageBlock.serializer(), args).normalizeUrls()
                "render_image_gallery" -> json.decodeFromJsonElement(ImageGalleryBlock.serializer(), args).normalizeUrls()
                "render_link_list" -> json.decodeFromJsonElement(LinkListBlock.serializer(), args)
                "render_text_with_image" -> json.decodeFromJsonElement(TextWithImageBlock.serializer(), args).normalizeUrls()
                else -> {
                    logger.warn { "UiTools: неизвестный инструмент '$name'" }
                    null
                }
            }
        }.onFailure {
            logger.warn { "UiTools '$name' decode error: ${it.message}" }
        }.getOrNull()
    }

    /**
     * Нормализует URL картинки в веб-доступный `/images/...`.
     * LLM может подтянуть из контекста путь `docs/profile/profile.png` (из frontmatter
     * или тела старых документов) — бэкенд-страховка переписывает его.
     * Http(s) и уже абсолютные `/...` оставляем как есть.
     */
    private fun normalizeImageUrl(url: String): String {
        val trimmed = url.trim()
        val normalized = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> trimmed
            trimmed.startsWith("docs/") -> "/images/" + trimmed.removePrefix("docs/")
            else -> "/images/$trimmed"
        }
        if (normalized != trimmed) {
            println("[UiTools] normalized image URL '$trimmed' → '$normalized'")
        }
        return normalized
    }

    private fun ImageBlock.normalizeUrls(): ImageBlock = copy(url = normalizeImageUrl(url))

    private fun ImageGalleryBlock.normalizeUrls(): ImageGalleryBlock =
        copy(images = images.map { it.copy(url = normalizeImageUrl(it.url)) })

    private fun TextWithImageBlock.normalizeUrls(): TextWithImageBlock =
        copy(image = image.copy(url = normalizeImageUrl(image.url)))

    private fun schemaImage(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "Абсолютный URL изображения.") }
            putJsonObject("caption") { put("type", "string"); put("description", "Короткая подпись под картинкой.") }
            putJsonObject("alt") { put("type", "string"); put("description", "Alt-текст для доступности.") }
            putJsonObject("link") { put("type", "string"); put("description", "URL, на который ведёт клик по картинке.") }
        }
        putJsonArray("required") { add("url") }
    }

    private fun schemaImageGallery(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("images") {
                put("type", "array")
                put("description", "Список картинок (минимум 2).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("url") { put("type", "string") }
                        putJsonObject("caption") { put("type", "string") }
                    }
                    putJsonArray("required") { add("url") }
                }
            }
        }
        putJsonArray("required") { add("images") }
    }

    private fun schemaTextWithImage(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст в markdown (жирный, списки, акценты). Должен быть коротким — 2-4 предложения.")
            }
            putJsonObject("image") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") { put("type", "string") }
                    putJsonObject("alt") { put("type", "string") }
                    putJsonObject("caption") { put("type", "string") }
                }
                putJsonArray("required") { add("url") }
            }
            putJsonObject("imagePosition") {
                put("type", "string")
                putJsonArray("enum") { add("left"); add("right") }
                put("description", "Сторона изображения. По умолчанию \"left\".")
            }
        }
        putJsonArray("required") { add("text"); add("image") }
    }

    private fun schemaLinkList(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                put("description", "Список ссылок-карточек.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("url") { put("type", "string") }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("description") { put("type", "string") }
                    }
                    putJsonArray("required") { add("url"); add("title") }
                }
            }
        }
        putJsonArray("required") { add("items") }
    }
}

