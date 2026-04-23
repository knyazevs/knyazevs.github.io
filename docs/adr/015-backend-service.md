# ADR-15: Backend-сервис — Kotlin/Ktor

- Статус: Принято
- Дата: 2026-04-14
- Авторы: Sergey Knyazev, Claude Sonnet 4.6
- Теги: backend, kotlin, ktor, архитектура, vps

## Контекст

ADR-8 зафиксировал необходимость собственного VPS-бэкенда:
- защита API-ключей (OpenRouter, OpenAI) от экспозиции в клиентском коде
- единое место для смены AI-провайдеров
- возможность rate limiting и логирования

Фронтенд (Astro + Svelte) деплоится на GitHub Pages как статический сайт.
`RightPanel.svelte` содержит заглушку `// TODO: вызов backend API (ADR-8)`.

Нужен HTTP-сервис, который:
- принимает запросы от фронтенда
- реализует RAG-пайплайн поверх документации (ADR-16)
- проксирует TTS и ASR к OpenAI
- стримит ответы LLM через SSE

---

## Решение

Реализовать бэкенд на **Kotlin + Ktor (CIO engine)**.

### Почему Kotlin/Ktor

Kotlin — основной рабочий стек автора. Выбор этого стека для CV-проекта:
- демонстрирует реальную компетенцию, а не синтетический пример
- сам сервис является артефактом компетентности (ADR-0)
- Ktor построен на Kotlin Coroutines — нативная поддержка SSE-стриминга
  без колбэков и thread-per-request блокировок
- минималистичный фреймворк: явная конфигурация, нет магии, легко читать

### Архитектура сервиса

```
server/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
└── src/main/kotlin/dev/knyazev/
    ├── Application.kt           # точка входа, DI-wiring
    ├── config/AppConfig.kt      # конфигурация из env
    ├── plugins/
    │   ├── Routing.kt           # регистрация маршрутов
    │   ├── Cors.kt              # CORS для GitHub Pages
    │   └── Serialization.kt     # kotlinx.serialization JSON
    ├── api/
    │   ├── ChatRoutes.kt        # POST /api/chat → SSE
    │   ├── TtsRoutes.kt         # POST /api/tts → audio stream
    │   └── AsrRoutes.kt         # POST /api/asr → {text}
    ├── rag/                     # RAG-пайплайн (см. ADR-16)
    └── llm/
        ├── OpenRouterClient.kt  # стриминговый LLM
        └── OpenAiClient.kt      # TTS, ASR, Embeddings
```

### API контракт

| Метод | Путь | Запрос | Ответ |
|-------|------|--------|-------|
| POST | `/api/chat` | `{"question": "string"}` | `text/event-stream`: `data: {"token":"..."}` / `data: [DONE]` |
| POST | `/api/tts` | `{"text": "string", "voice": "alloy"}` | `audio/mpeg` stream |
| POST | `/api/asr` | multipart `audio` | `{"text": "string"}` |
| GET | `/api/health` | — | `{"status":"ok"}` |

### Ключевые зависимости

```kotlin
ktor-server-core-jvm
ktor-server-cio-jvm             // CIO engine — coroutines-native
ktor-server-cors-jvm
ktor-server-content-negotiation-jvm
ktor-serialization-kotlinx-json-jvm
ktor-client-core-jvm
ktor-client-cio-jvm
kotlinx-serialization-json
logback-classic
```

### Конфигурация (env)

```
PORT=8080
OPENROUTER_API_KEY=...
OPENAI_API_KEY=...
LLM_MODEL=anthropic/claude-3-haiku
DOCS_PATH=/app/docs
CORS_ALLOWED_ORIGIN=https://knyazevs.github.io
```

### Деплой

Multi-stage Dockerfile:
1. `gradle:8-jdk21` → `gradle shadowJar` → fat JAR
2. `eclipse-temurin:21-jre` → копирует JAR + `docs/` для RAG

---

## Альтернативы и причины отказа

### Node.js + Express / Fastify
**Отказано**: не является рабочим стеком автора. Использование Node.js
в CV-проекте ослабило бы демонстрацию профессиональных компетенций.

### Go + Fiber/Chi
**Отказано**: не является рабочим стеком автора. Аналогичное обоснование.

### Spring Boot (Kotlin)
**Отказано**: избыточно для сервиса с 4 эндпоинтами. Ktor — более легковесный
и явный выбор, лучше демонстрирует понимание инструмента.

### Serverless (Lambda, Cloud Functions)
**Отказано**: SSE-стриминг и in-memory RAG-индекс при старте плохо совместимы
с cold start и ограничениями времени выполнения serverless-платформ.

---

## Последствия

### Положительные
- Бэкенд является дополнительным публичным артефактом компетентности
- Kotlin Coroutines обеспечивают чистый streaming без сложности
- Единый стек: знания переносятся с основной работы напрямую
- Ktor легко читается и модифицируется (сигнал для ревьювера)

### Отрицательные / Стоимость
- Требует JVM на VPS (чуть больше RAM, чем Go/Node)
- Gradle-сборка медленнее в Docker без кэширования
- Необходим поддерживаемый VPS (uptime, обновления)

---

## Связь с другими ADR

- ADR-0 — мотивация проекта: сервер как артефакт компетентности
- ADR-3 — ограничения LLM: имплементируются в system prompt
- ADR-8 — инфраструктура AI: конкретизация решения о VPS-бэкенде
- ADR-16 — архитектура RAG-пайплайна
