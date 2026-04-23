# ADR-21: Перевод сервера на Kotlin/Native + Ktor

- Статус: Принято
- Дата: 2026-04-20
- Авторы: Sergey Knyazev, Claude Opus 4.7
- Теги: server, kotlin-native, ktor, cold-start, serverless, cost

## Контекст

В ADR-15 выбран стек Kotlin/Ktor на JVM. За полгода эксплуатации и на фоне
обсуждения переезда в Yandex Cloud всплыли три болевые точки:

1. **Cold start 2-5 секунд.** JVM init + загрузка классов + старт Ktor + загрузка
   RAG-индекса. Для Serverless Containers со `scale-to-zero` это означает
   многосекундную задержку первого запроса после простоя.

2. **Overhead по памяти.** Baseline JVM-процесса — 200-400 MB RSS даже при
   минимальной нагрузке. На Serverless-тарифах биллинг идёт за GB·секунды,
   и половина счёта уходит на «пустую» JVM.

3. **Размер образа.** `eclipse-temurin:21-jre-jammy` + shadowJar даёт ~280 MB.
   При редких деплоях не критично, но медленный pull при холодном поднятии
   инстанса добавляется к cold start.

Параллельно проект преследует цель демонстрации технической глубины.
Server-side Kotlin/Native — нетривиальный путь, ценный как артефакт.

---

## Решение: мигрировать сервер на Kotlin/Native target

### Целевая конфигурация

- `kotlin("multiplatform")` вместо `kotlin("jvm")` — source sets: `commonMain`, `nativeMain`
- Ktor 3.x `ktor-server-cio` на native target (официально поддерживается)
- `ktor-client-cio` на native — клиент к OpenRouter/OpenAI работает без изменений
- `kotlinx-datetime` вместо `java.time` (уже сделано в предыдущей итерации)
- `kotlinx-serialization` — мультиплатформенный, работает без изменений
- Бинарник линкуется статически, упаковывается в `scratch`-образ

### Замены JVM-зависимостей

| Было (JVM) | Станет (native) | Стоимость миграции |
|---|---|---|
| Logback + SLF4J | kermit (`co.touchlab:kermit`) | ~30 строк: обёртка над kermit под сигнатуру текущих логгеров |
| `ktor-server-rate-limit` | Самописный лимитер | ~50-80 строк: per-IP token bucket в concurrent map |
| `ktor-server-call-logging` | Custom interceptor через kermit | ~20 строк: Ktor `intercept(Plugins)` |
| `dotenv-kotlin` | В проде не нужен — читаем `getenv`. В dev — отдельный jvmMain с dotenv-kotlin | 0 строк в nativeMain |

### Что НЕ меняется

- Архитектура RAG (ADR-16, ADR-19) — чистый Kotlin, уже multiplatform-готова
- Контракты API (ADR-17, ADR-18)
- `EmbeddingCache`, `InMemoryVectorStore`, `BM25Index`, `HydeService` — ни одной JVM-зависимости
- Фронтенд (Astro + Svelte) — не затрагивается

### План миграции

1. Перевести Gradle-проект на `kotlin("multiplatform")` с двумя таргетами: `jvm` (оставляем для локального dev) и `native` (linuxX64 для прода).
2. Выделить JVM-специфичный код (Logback wiring, dotenv) в `jvmMain`.
3. Написать native-реализации логгера, rate-limit, call-logging в `nativeMain`.
4. Собрать native-бинарник, проверить SSE-стриминг end-to-end.
5. Переписать Dockerfile: multi-stage build → distroless base → ~40 MB image.
6. Миграция по флагу: оба образа (JVM и native) собираются параллельно до стабилизации.

---

## Альтернативы и причины отказа

### Остаться на JVM
**Отклонено**: не решает cold start и memory overhead. Для scale-to-zero нужен AOT-бинарник в любом виде.

### GraalVM native-image
**Отклонено**: сопоставимый профиль по cold start / image size с K/N, но:
- требует поддержки reflect-config.json для всех reflective путей (Jackson, Logback, Ktor features)
- при обновлении зависимостей нужно перегенерировать reflect-config и тестировать
- меньше интересна как артефакт: «JVM, но скомпилированная»
- экосистемный путь K/N стратегически важнее для роли Technical Lead с Kotlin-стеком

### AppCDS / CRaC на JVM
**Отклонено**: снижает cold start до ~1 сек, но не решает memory overhead и размер образа. Compromise, но не радикальное улучшение.

### Переписать на Go / Rust
**Отклонено**: потеря инвестиции в существующую Kotlin-кодовую базу. Миграция «всего сервера на другой язык» — месяцы работы, не дни. Не соответствует принципу инкрементальности.

### Ktor 3 на Node.js / Wasm
**Отклонено**: Kotlin/JS для Ktor-server существует, но менее зрелый чем native. Wasm для сервера пока экспериментален в Ktor.

---

## Последствия

### Положительные
- Cold start 100-300 мс → Serverless Containers scale-to-zero становится реальным деплойным профилем
- RAM 50-80 MB → биллинг в Serverless падает в 3-4 раза при тех же запросах
- Docker image ~40 MB → быстрый pull, дешевле регистри
- Symmetrical Kotlin stack: K/N + Svelte + Astro — унифицированное повествование для CV
- Артефакт уровня Staff+: server-side K/N — редкий скилл

### Отрицательные / Стоимость
- Compile time растёт: полная native-сборка ~2-4 минуты vs ~20 секунд на JVM. Смягчается incremental-компиляцией
- Отладка хуже: нет JFR, async-profiler, heap dumps. LLDB и kermit-логи вместо jstack
- Пиковая throughput ниже JIT — но для CV-трафика (десятки req/день) неактуально
- ~150 строк самописного кода взамен JVM-плагинов Ktor (rate-limit, call-logging, logger)
- Риск edge-cases в K/N memory model при concurrent access к `InMemoryVectorStore` — требует аудита
- Меньше community-ресурсов по server-side K/N: некоторые проблемы придётся решать самостоятельно

### Нейтральные
- Зависимости, которые уже multiplatform: `ktor-client`, `ktor-server-core`, `ktor-server-cio`, `kotlinx-serialization`, `kotlinx-coroutines`, `kotlinx-datetime` — переход не требует изменений кода
- Архитектура RAG остаётся нетронутой: вся доменная логика в чистом Kotlin

---

## Связь с другими ADR

- ADR-15 — выбор backend-стека: данный ADR уточняет таргет (JVM → native), не меняя выбор языка и фреймворка
- ADR-16, ADR-19 — RAG: не затрагиваются, доменная логика multiplatform-совместима
- ADR-17, ADR-18 — API protection: rate-limit и fingerprint переписываются на native, контракт сохраняется
