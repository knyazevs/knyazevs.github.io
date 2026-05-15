---
test_id: jvm-02-freeform
topic: java-jvm
type: freeform
questions:
  - id: jvm-q11
    text: |
      Сервис на Java 21 (Spring Boot, G1 GC) начал показывать скачки p99 latency
      до 500–800ms каждые 3–5 минут при нормальной нагрузке. Все прочие метрики
      (CPU, memory heap usage) выглядят нормально. Как ты будешь диагностировать
      эту проблему? Опиши конкретный план с инструментами и что будешь искать
      в каждом шаге.
    rubric: |
      Сильный ответ упоминает (минимум 4 из):
      - Первый шаг — включить/проверить GC логи: скачки каждые 3–5 минут с
        длительностью 500ms — типичная картина Full GC или долгой G1 mixed collection.
        `-Xlog:gc*:file=gc.log:time,uptime,level,tags`
      - Смотреть тип паузы: Evacuation Pause (normal) vs Full GC (аварийный).
        Full GC в G1 — почти всегда симптом проблемы.
      - JFR: `jcmd <pid> JFR.start settings=profile` — смотреть GC events,
        Safepoint events, монитор waits. JFR покажет точное время и type событий.
      - Safepoint: проверить TTSP (Time-to-safepoint). Если пауза GC = 5ms,
        но приложение stopped = 500ms — причина в долгом TTSP, а не в самом GC.
        `-Xlog:safepoint` или JFR `jdk.SafepointStateSynchronization`.
      - async-profiler в период деградации: `./asprof -e wall -d 10 -f wall.html <pid>`.
        Wall-clock профиль покажет где потоки висят.
      - Humongous allocation в G1: если GC паузы нормальные, но часто — смотреть
        на allocation rate. `jcmd <pid> VM.native_memory` или async-profiler alloc.
      - Downstream latency: если GC не виноват — проверить есть ли корреляция
        с latency downstream-сервисов (DB, кэш). Скачки могут быть не GC, а IO.
      - Для GC-причины: проверить `InitiatingHeapOccupancyPercent`, размер heap,
        promotion failure в GC логах.

      Слабый ответ: «увеличим Xmx» без анализа, или «перейдём на ZGC» без понимания
      причины, или общие слова без конкретных инструментов.

      Минимум для прохождения: упомянуть GC логи + хотя бы один способ различить
      GC pause vs TTSP, с конкретным инструментом (JFR или -Xlog:safepoint).
    remediation: jvm-q11

  - id: jvm-q12
    text: |
      Ты переводишь legacy Spring Boot 2 сервис на Java 21 с виртуальными потоками
      (spring.threads.virtual.enabled=true). После включения throughput при
      IO-bound нагрузке не вырос, а latency даже немного ухудшилась.
      Объясни возможные причины и как диагностировать каждую из них.
    rubric: |
      Сильный ответ упоминает (минимум 3 из):
      - Pinning: виртуальные потоки не могут отмонтироваться внутри synchronized
        блоков. Если JDBC-драйвер, HTTP-клиент или другая библиотека использует
        synchronized — каждый IO вызов pinning carrier thread. Нужно проверить через
        `-Djdk.tracePinnedThreads=full` или JFR событие `jdk.VirtualThreadPinned`.
      - JDBC driver: большинство JDBC-драйверов до недавнего времени использовали
        synchronized. PostgreSQL JDBC 42.7+ переписан на ReentrantLock. Нужно проверить
        версию драйвера и есть ли pinning на DB-запросах.
      - Connection pool: Hikari CP сам по себе OK с virtual threads, но если он
        использует synchronized внутри — проблема. Hikari 5.1+ поддерживает vt.
      - CPU-bound: если нагрузка не IO-bound (много CPU работы между IO) — виртуальные
        потоки не помогут. Carrier threads ограничены числом CPU ядер.
      - ThreadLocal и состояние: некоторые библиотеки используют ThreadLocal для
        per-thread state. С virtual threads такой state работает, но может быть
        больше уникальных Thread объектов — memory overhead.
      - GC pressure: больше виртуальных потоков = больше объектов на heap. Если heap
        не настроен — больше GC.

      Слабый ответ: «virtual threads не работают» без понимания почему, или «нужно
      больше RAM» без анализа.

      Минимум для прохождения: упомянуть pinning как главный suspect с конкретным
      способом диагностики (tracePinnedThreads или JFR), плюс хотя бы один другой
      фактор.
    remediation: jvm-q12

  - id: jvm-q13
    text: |
      На сервере в production наблюдается постепенный рост Metaspace до OOM
      в течение нескольких часов. При рестарте — всё нормально, через несколько
      часов снова OOM. Как ты исследуешь и устранишь проблему?
    rubric: |
      Сильный ответ упоминает (минимум 4 из):
      - Metaspace OOM = утечка ClassLoader'ов. Metaspace освобождается только когда
        ClassLoader собирается GC. Значит ClassLoader'ы не собираются — утечка.
      - Диагноз: включить `-XX:+TraceClassLoading -XX:+TraceClassUnloading` —
        видно какие классы загружаются и выгружаются. Если загружаются тысячи классов
        одного имени без выгрузки — ClassLoader не освобождается.
      - Heap dump: `jcmd <pid> GC.heap_dump` или JFR + анализ в JMC.
        Искать: много `java.lang.ClassLoader` объектов, много `java.lang.Class` объектов.
      - Анализ в JMC/Eclipse Memory Analyzer: retention path от ClassLoader'а —
        что держит его от GC. Типичные причины: ThreadLocal не очищен, статическое
        поле ссылается на объект загруженный тем CL, JNI GlobalReference.
      - Hot reload: если сервис умеет hot-reload плагинов/скриптов — каждый reload
        создаёт новый ClassLoader. Старый не освобождается если есть утечка.
      - Groovy/scripting engine: Groovy-скрипты компилируются в классы, каждый
        скрипт = новый CL. При частом выполнении скриптов без кэширования → утечка.
        Решение: кэшировать скомпилированные ScriptClass по тексту скрипта.
      - Cglib/ByteBuddy proxies: некоторые фреймворки генерируют классы в runtime.
        При неправильном кэшировании — каждый вызов новый Class. Проверить через
        TraceClassLoading.
      - Практическое решение: найти место создания ClassLoader → исправить утечку
        (очистить ThreadLocal, убрать статическую ссылку, кэшировать скрипты).

      Слабый ответ: «увеличить -XX:MaxMetaspaceSize» — это отложит OOM, не решит.
      «Перезагружать сервис раз в час» — тоже не решение.

      Минимум для прохождения: понять что Metaspace OOM = ClassLoader утечка (не
      «классы занимают много памяти»), предложить конкретный способ диагностики
      (TraceClassLoading или heap dump анализ), назвать хотя бы одну типичную причину.
    remediation: jvm-q13
---

# Тест: Диагностика JVM (freeform)

3 сценария с открытым ответом. Каждый — реальная production-ситуация.

Отвечать развёрнуто: инструменты, конкретные шаги, что ищем в каждом шаге.

Валидация по rubric — LLM проверяет наличие ключевых пунктов.

Покрытие по главам:
- Сценарий 1 (latency скачки) → GC: [03-garbage-collection.md](../03-garbage-collection.md),
  safepoints: [10-performance.md](../10-performance.md)
- Сценарий 2 (virtual threads) → Loom: [07-loom.md](../07-loom.md)
- Сценарий 3 (Metaspace OOM) → ClassLoading: [08-classloading-jpms.md](../08-classloading-jpms.md)
