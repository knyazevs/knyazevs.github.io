# Перевёл сервер с JVM на Kotlin/Native

- Дата: 2026-04-20
- Теги: kotlin-native, ktor, rag, cost, cold-start

## Контекст

Сервер крутится на Yandex Serverless Containers. Когда никто не заходит — контейнер должен останавливаться, это вся суть pay-per-request. Но на JVM «никто не заходит» — это единицы запросов в сутки, а каждый холодный старт — это подтянуть JRE, поднять ktor, прогреть JIT, проиндексировать документы. Секунды — это ладно, но ещё и память: JVM даже в простое держит heap, и минимальная выделенная память в контейнере упирается в это.

Начал я вообще с другого вопроса — «можно ли перевести сервер на Yandex Cloud Functions». Ответ: нет, слишком долгая инициализация (RAG-индекс строится на старте), функции не подходят. Дальше пошло «как оптимизировать траты на Serverless Containers», и быстро стало понятно, что главный рычаг — это размер образа + скорость старта. GraalVM native-image я отмёл почти сразу из-за мороки с reflection-конфигами в ktor. И тут мне вспомнилось, что ktor официально поддерживает Kotlin/Native target.

## Что думал изначально

Если честно, я был уверен, что это не production-ready путь в 2026 году. Классические возражения: SLF4J/logback — JVM-only, `java.time` — JVM, `dotenv-kotlin` — JVM, конкурентные структуры из `java.util.concurrent` — JVM. И ktor server под K/N мне виделся «экспериментальным» — какой-то CIO-engine, но я ожидал, что в реальности там полно дыр.

Мой план на старте был: оформить ADR с выбором GraalVM, потому что это «безопасный путь», и не трогать K/N.

## Что пошло не так / что изменилось

Я начал писать ADR — и стал честно расписывать альтернативы. Дошёл до K/N, начал перечислять «блокеры», и каждый из них при ближайшем рассмотрении оказался **не блокером**:

- `java.time` → `kotlinx-datetime` — давно работает, API проще.
- SLF4J → тонкий интерфейс `Logger` + `expect/actual`. В jvmMain — делегирует SLF4J, в nativeMain — тупо `println` с поддержкой LOG_LEVEL через `getenv`. Двадцать строк кода.
- `dotenv-kotlin` → аналогично, `expect object EnvReader { fun get(key: String): String? }`. В nativeMain читает через `platform.posix.getenv`.
- `CopyOnWriteArrayList` в vector store — я когда-то поставил её «на всякий случай», но реально запись в этот список происходит только на старте, из одного потока лоадера. После старта — чтение. Никакой CoW-гарантии не нужно, плоский `mutableListOf` работает.
- `java.security.MessageDigest` → `okio.ByteString.sha256()`. Один импорт.
- `AtomicInteger` → `kotlinx.atomicfu.atomic`. Через `.value` вместо `.get()`.
- `ktor-server-rate-limit` — вот это единственный JVM-only плагин, который пришлось переписать с нуля. Получилось ~100 строк: токен-бакет в `mutableMapOf` с `kotlinx.atomicfu.locks.synchronized`, кастомный `RateLimit` плагин через `createApplicationPlugin`, DSL `rateLimit(scope) { ... }` сохранил ту же сигнатуру.

Главное — ktor **реально** работает под K/N. `ktor-server-cio` есть в мультиплатформенных артефактах, `io.ktor:ktor-server-core:3.1.2` без `-jvm` суффикса подтягивает всё нужное. `embeddedServer(CIO, port = ...) { ... }.start(wait = true)` запустилось с первой попытки.

Что удивило по пути — это пара мелочей, которые стоили мне по полчаса:

1. **`io.ktor.plugin`** (Ktor Gradle plugin) — JVM-only. Пришлось переключиться на голый `kotlin("multiplatform")`, shadow plugin тоже несовместим. Написал свой `jvmFatJar` через `tasks.register<Jar>("jvmFatJar")` с `zipTree(jvmJar.archiveFile)` и `configurations.named("jvmRuntimeClasspath")`. Commands в Dockerfile оставил как были — добавил таск-алиас `shadowJar` который просто `dependsOn("jvmFatJar")`.

2. **Heap space в linkReleaseExecutableLinuxX64** — конановский линкер съел всё, что выделил Gradle по умолчанию. Помог `org.gradle.jvmargs=-Xmx6g`.

3. **Multipart API** — `part.streamProvider()` JVM-only. В commonMain — `part.provider()` возвращающий `ByteReadChannel`, а дальше `.readRemaining().readByteArray()` из `kotlinx.io`.

4. **Дубликат `ApplicationKt`** — и в commonMain, и в jvmMain у меня лежали файлы с `fun main()` в одном пакете. Компилятор JVM их сливал в одну сущность. Переименовал commonMain-файл в `Bootstrap.kt`, функцию назвал `runServer()`, а JVM-обёртка теперь однострочная: `fun main() { runServer() }`. Нативная — симметрично.

## Решение и вывод

Поэтапно, с чекпоинтами между этапами:

- **Этап 1**: перевёл `build.gradle.kts` на `kotlin("multiplatform")`, добавил `linuxX64` target, заскафолдил nativeMain с заглушкой. Важно — JVM-сборка продолжала работать, все существующие файлы были в jvmMain. Чекпоинт: `./gradlew shadowJar` прошёл → можно продолжать.

- **Этап 2**: переписал «платформенные» утилиты на мультиплатформенные эквиваленты (логгер, env, атомики, файлы, хеш), перенёс 22 файла из jvmMain в commonMain. В jvmMain остались только `Application.kt`, `RateLimit.kt`, `AsrRoutes.kt`, `TtsRoutes.kt`, `Routing.kt` + actual-реализации. Чекпоинт: `./gradlew shadowJar` снова зелёный.

- **Этап 3**: написал свой rate-limit плагин, обновил multipart, вынес общий бутстрап в `Bootstrap.kt`, свёл Application.kt к двум строкам. Теперь и `./gradlew shadowJar`, и `./gradlew linkReleaseExecutableLinuxX64` работают. Получил 6.9 MB исполняемый файл — против ~70 MB fat JAR + JRE.

Результат:

```
build/bin/linuxX64/releaseExecutable/knyazevs-server.kexe — 6.9 MB
```

Distroless base-debian12 как runtime → итоговый образ ~30 MB против ~300 MB с eclipse-temurin.

Что получил:
- Холодный старт — доли секунды вместо 4-6 секунд JVM warmup.
- Контейнер теперь реально можно спокойно скейлить в 0 без ощутимой деградации первого запроса.
- Образ втрое-десятеро меньше → деплой и registry storage дешевле.

Что потерял:
- SLF4J-уровень удобства логов (MDC, паттерны, ротация) — на native тупой println. Достаточно для моего масштаба, но для нагруженного сервиса нужен был бы полноценный мультиплатформенный логгер типа kermit.
- Hot-reload/debugger experience слабее, чем в JVM. Для разработки всё ещё собираю и гоняю JVM-версию, а нативный бинарь — только под деплой.

## Что бы сделал иначе

Начал бы с KMP-структуры сразу — было бы много меньше мороки. Я поставил себя в ситуацию «переписать всё за один коммит», когда можно было растянуть на три маленьких этапа, каждый из которых самодостаточен. В этот раз так и получилось, но только потому что я остановился, подышал, и разбил работу на phase 1/2/3 с чекпоинтами после каждого — изначально хотел ломить всё в одну итерацию.

И раньше бы снял своё предубеждение «K/N — экспериментально». Оно было основано на опыте 2021-22 годов, а сейчас — абсолютно рабочий инструмент для backend при одном условии: не лепить JVM-specific библиотеки. Тонкая абстракция + `expect/actual` решает 95% реальных зависимостей.
