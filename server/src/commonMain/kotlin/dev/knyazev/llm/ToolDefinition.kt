package dev.knyazev.llm

import kotlinx.serialization.json.JsonObject

/**
 * Описание одного OpenRouter-инструмента (function tool) для передачи в `tools`
 * поле chat-completions-запроса. Схема параметров — JSON Schema (draft-07),
 * совпадающая по форме с аргументами, которые модель вернёт в `tool_calls`.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
)

/**
 * Событие из streaming-чата OpenRouter — либо очередной токен текста,
 * либо завершённый вызов инструмента, собранный из дельт `tool_calls`.
 */
sealed class ChatEvent {
    data class Token(val text: String) : ChatEvent()
    data class ToolCall(val id: String, val name: String, val arguments: String) : ChatEvent()
}
