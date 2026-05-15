# Java & JVM

Глубокая подготовка к интервью на Tech Lead / Architect. Не поверхностный обзор — каждая
глава разбирает внутреннее устройство, типичные ловушки и нюансы, которые отличают
кандидата с опытом от того, кто прочитал книжку.

Темы независимы. Spring, JPA/Hibernate, Kotlin — отдельные темы в `docs/learn/`.

## Главы

| № | Глава | Статус |
|---|-------|--------|
| 01 | [Архитектура JVM: class loading, runtime data areas, execution engine](01-jvm-architecture.md) | готово |
| 02 | [Память: heap, stack, TLAB, escape analysis, off-heap](02-memory.md) | готово |
| 03 | [Garbage Collection: алгоритмы, GC-коллекторы, тюнинг](03-garbage-collection.md) | готово |
| 04 | [JIT: интерпретатор, C1, C2, Graal, deoptimization](04-jit.md) | готово |
| 05 | [Java Memory Model: happens-before, volatile, final, reordering](05-jmm.md) | готово |
| 06 | [java.util.concurrent: AQS, локи, пулы, CompletableFuture](06-concurrency.md) | готово |
| 07 | [Project Loom: virtual threads, pinning, structured concurrency](07-loom.md) | готово |
| 08 | [Class loading, JPMS, jlink](08-classloading-jpms.md) | готово |
| 09 | [Bytecode, invokedynamic, MethodHandle, VarHandle](09-bytecode-indy.md) | готово |
| 10 | [Performance & observability: JMH, JFR, async-profiler, safepoints](10-performance.md) | готово |

## Тесты

- [JVM internals (MCQ)](tests/01-jvm-mcq.md) — 10 вопросов: память, GC, JMM, Loom, bytecode
- [Диагностика и сценарии (freeform)](tests/02-jvm-freeform.md) — 3 сценария: heap dump, GC log, CPU profile

## Где история обучения

- `remediation/static/` — курированные пояснения к каверзным вопросам
- `remediation/generated/` — пояснения по провалам, написанные LLM во время сессии quiz
