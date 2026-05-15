# Performance & Observability: JMH, JFR, async-profiler, safepoints

Производительность JVM-приложений не меряется «на глаз». Каждый инструмент в этой главе
решает конкретный класс вопросов: JMH — микробенчмарки без обмана компилятором, JFR —
low-overhead observability в production, async-profiler — где реально тратится CPU
без safepoint-bias, async-profiler + heap — allocation profiling без overhead allocation
API. Знание инструментов — не роскошь, а prerequisite для любого серьёзного разговора о
производительности.

---

## 1. JMH: правильные микробенчмарки

### Теория

Наивный микробенчмарк на Java не измеряет то, что думает автор. Три главных враги:

**JIT warm-up**: первые N тысяч итераций исполняются интерпретатором или C1. Измерение
без прогрева — измерение интерпретатора, не оптимизированного кода.

**Dead code elimination**: JIT видит что результат вычисления нигде не используется →
удаляет вычисление. Benchmark измеряет нулевую работу и показывает нереалистично
высокий throughput.

**Constant folding**: если вход бенчмарка — константа, JIT вычислит результат один раз
и закеширует. Бенчмарк тестирует возврат константы.

JMH (Java Microbenchmark Harness, OpenJDK) решает все три проблемы:
- Автоматический warmup (настраиваемые итерации)
- `Blackhole` — специальный объект, «потребляющий» результаты. JIT не может исключить
  вычисление если его результат передаётся в Blackhole
- `@State` и `@Param` — входные данные через поля, не константы. JIT не знает их
  значений на этапе компиляции

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class StringBenchmark {

    @Param({"10", "100", "1000"})
    private int size;

    private String input;

    @Setup
    public void setup() {
        input = "x".repeat(size);
    }

    @Benchmark
    public int baseline(Blackhole bh) {
        bh.consume(input.length()); // измеряем cost вызова length()
        return input.length();
    }

    @Benchmark
    public String concat() {
        return input + "_suffix"; // аллокация — видно через gc.alloc.rate.norm
    }
}
```

**Режимы измерения:**
- `Mode.AverageTime`: среднее время на операцию (мкс, нс)
- `Mode.Throughput`: операций в секунду
- `Mode.SampleTime`: распределение latency (показывает p99, p99.9)
- `Mode.SingleShotTime`: один вызов — измерение cold performance без warmup

### На практике

`gc.alloc.rate.norm` — количество байт, аллоцированных на операцию. Запустить через
добавление `-prof gc` к JMH: `java -jar benchmarks.jar -prof gc`. Это способ измерить
аллокации без изменения кода: если concat() аллоцирует 100 байт, а baseline — 0, разница
видна в отчёте.

Ловушки JMH:
- **`@Fork(0)`** (нет fork, test в том же JVM) — JIT-профиль от других тестов влияет
  на результат. Всегда форкать минимум 2 раза.
- **Слишком короткие итерации**: если метод работает 1 нс, а измерение длится 100 мс —
  JMH делает 10^8 итераций и усредняет. Это нормально. Но если измерение 1 мс — шум
  превысит сигнал.
- **`@TearDown` и `@Setup`** с `Level.Invocation` — overhead самих setup/teardown
  попадает в измерение для очень быстрых методов. Использовать `Level.Trial` или
  `Level.Iteration` там где возможно.

---

## 2. JFR: production observability без overhead

### Теория

JFR (Java Flight Recorder) — встроенный в JVM механизм сбора событий. Overhead < 1%
CPU при стандартных настройках. Доступен без агентов и без рестарта в Java 11+.

Архитектура: JVM записывает события (GC cycles, JIT compilations, thread sleeps,
IO waits, exceptions) в кольцевой буфер в памяти. Буфер дампится на диск при срабатывании
триггера или по команде. Формат бинарный, читается JDK Mission Control (JMC) или
командной строкой через `jfr print`.

**Запуск:**

```bash
# Постоянная запись с дампом по команде:
java -XX:StartFlightRecording=filename=app.jfr,settings=profile -jar app.jar

# Или через jcmd к живому процессу:
jcmd <pid> JFR.start name=recording settings=profile
jcmd <pid> JFR.dump name=recording filename=app.jfr
jcmd <pid> JFR.stop name=recording
```

**Профили:**
- `default`: минимальный overhead, production-safe
- `profile`: больше событий, ~2% CPU overhead, для диагностики

**Ключевые события JFR:**
- `jdk.GarbageCollection`: GC паузы с причиной и длительностью
- `jdk.ThreadSleep` / `jdk.MonitorWait`: блокировки потоков
- `jdk.SocketRead` / `jdk.SocketWrite`: IO latency
- `jdk.ClassLoad`: загрузка классов
- `jdk.Compilation`: JIT компиляции
- `jdk.ExecutionSample`: CPU sampling (каждые 10ms по умолчанию)
- `jdk.ObjectAllocationInNewTLAB`: аллокации (дорогое событие, только в profile)
- `jdk.VirtualThreadPinned`: pinning Loom virtual threads

### На практике

Диагностический workflow:
1. Запустить JFR в production при подозрительном поведении
2. Скачать `.jfr` файл (`jcmd <pid> JFR.dump`)
3. Открыть в JMC или: `jfr print --categories GC app.jfr`

Поиск проблемы с latency:
- Долгие `jdk.MonitorWait` с конкретным классом монитора → contended lock
- Долгие `jdk.SocketRead` → timeout в downstream сервисе
- Частые `jdk.GarbageCollection` с типом `G1 Young Generation` длиннее 100ms → GC tuning

**Пользовательские JFR события:**

```java
@Name("com.example.RequestProcessed")
@Label("Request Processed")
@Category("Application")
@StackTrace(false)
public class RequestEvent extends Event {
    @Label("HTTP Method") public String method;
    @Label("Path") public String path;
    @Label("Status") public int status;
    @Label("Duration ms") public long durationMs;
}

// Использование:
var event = new RequestEvent();
event.begin();
// ... обработка запроса ...
event.method = "GET";
event.path = "/api/users";
event.status = 200;
event.durationMs = elapsed;
event.commit();
```

Пользовательские события доступны в JMC как дополнительные треки — видно рядом с GC
и JIT на одной временной шкале.

---

## 3. async-profiler: CPU и allocation без safepoint-bias

### Теория

**Safepoint** — точка в программе, где JVM гарантирует что все потоки находятся в
безопасном состоянии (не в середине инструкции, heap consistent). JVM использует
safepoints для GC, JIT-компиляции, heap dump. Все потоки должны дойти до safepoint
перед STW-операцией.

**Safepoint-bias** — фундаментальная проблема встроенного Java-профилера (`-Xss`, JVMTI
sampling). Он сэмплирует стеки только в safepoint'ах. Safepoints расставлены компилятором
не равномерно: они есть в конце методов, в branching, но не внутри длинных циклов без
backward jumps. Горячий код в tight loop — не виден. Профилировщик видит «подожди
safepoint» а не реальную работу.

**async-profiler** использует `AsyncGetCallTrace` (JVMTI extension) и perf_events (Linux)
или `SIGPROF` (macOS). Это interrupt-based sampling: сигнал прерывает поток в произвольный
момент, не ожидая safepoint. Горячие tight loops — видны корректно.

```bash
# Скачать и запустить:
./asprof -d 30 -f profile.html <pid>      # flamegraph за 30 секунд
./asprof -e alloc -d 30 -f alloc.html <pid>  # allocation profiling
./asprof -e lock -d 30 -f lock.html <pid>    # contended locks
```

**Flame graph**: вертикальная ось — глубина стека, горизонтальная — время (ширина
блока = % CPU). Широкие плато наверху = горячий код. Читать снизу вверх: `main` →
`handleRequest` → `processData` — это call stack. Чем шире блок, тем больше CPU.

### На практике

Алгоритм диагностики CPU-проблемы:

1. Запустить async-profiler под нагрузкой: `./asprof -d 60 -f cpu.html <pid>`
2. Открыть flamegraph в браузере (это HTML с интерактивностью)
3. Найти широкие плато — горячие методы
4. Кликнуть на плато — zoom in, видно что внутри
5. Обратить внимание на неожиданных соседей в стеке

Частые находки:
- `java/util/regex/Pattern.match` широкое → regex в горячем пути без компиляции Pattern
- `sun/misc/Unsafe.park` широкое → потоки спят на lock'е, нужен lock contention анализ
- `GC worker` широкое → высокое GC давление, нужен allocation profiling

**Allocation profiling** (`-e alloc`): каждый N-й аллоцированный байт в TLAB → запись
стека. Показывает top аллокаторов без overhead JVMTI AllocationSampler.

**Wall-clock профилирование** (`-e wall`): сэмплирует все потоки включая спящие. Полезно
для поиска threading-проблем: если все потоки спят в одном месте → contention.

---

## 4. Safepoints: где JVM «замирает»

### Теория

**Time-to-safepoint (TTSP)** — промежуток между «JVM запросила safepoint» и «все потоки
добрались до ближайшей safepoint». Это невидимая задержка сверх GC-паузы.

Почему TTSP бывает долгим: если поток крутится в tight loop (JIT-оптимизированный цикл
без safepoint poll), JVM ждёт пока цикл не выйдет в точку с safepoint poll. JIT вставляет
safepoint poll в backward jumps (конец итерации цикла), но при некоторых оптимизациях
(loop unrolling, intrinsics) это может быть редко.

В итоге: GC запросила safepoint, 7 из 8 потоков дошли мгновенно, 8-й крутит tight loop
200 мс без safepoint poll. Вся пауза для приложения — 200 мс, хотя GC-работа заняла 5 мс.

В GC логах это видно как разница между `Safepoint: xxx ms` и `GC pause: yyy ms`:
```
[GC pause (G1 Evacuation Pause) (young) 5.123ms]
[Total time for which application threads were stopped: 205.456 ms,
 Stopping threads took: 200.123 ms]   ← вот TTSP
```

`-Xlog:safepoint` включает детальное логирование safepoint событий.

**Elimination of safepoint polls** через JVM флаг
`-XX:+UseCountedLoopSafepoints` (Java 16+): JIT вставляет safepoint poll в counted loops
(циклы с известным счётчиком). Включено по умолчанию с Java 17+. До этого — источник
долгих TTSP.

### На практике

Симптом долгого TTSP: GC pause в логах 5ms, но приложение видит задержку 200ms. JFR
событие `jdk.SafepointStateSynchronization` покажет TTSP напрямую.

Mitigation: `-XX:+UseCountedLoopSafepoints`, убрать tight loops без IO на горячем пути,
или явно вставить yield point (`Thread.yield()`, `LockSupport.parkNanos(1)`) — не нужно
в 99% случаев, но бывает нужно в специализированных обработчиках.

---

## 5. Объединённый workflow диагностики

### Теория

Реальная проблема редко локализуется одним инструментом. Workflow:

```
Симптом: деградация latency (p99 растёт)
         ↓
JFR summary: GC? MonitorWait? SocketRead?
         ↓
GC: смотреть GC логи, GCViewer → тюнинг коллектора
MonitorWait: lock contention → async-profiler -e lock
SocketRead: downstream latency → distributed tracing (не JVM)
CPU: async-profiler -e cpu → flamegraph
Alloc: async-profiler -e alloc → источники мусора
```

**Метрики для мониторинга в production:**
- GC pause time (p99, max) — из JFR или micrometer JVM metrics
- JVM threads: platform threads, virtual threads
- Heap usage after GC (not before — это invariant для поколений)
- Metaspace usage (утечка classloaders)
- Compilation rate (too high = JIT под давлением)
- TLAB allocation rate

### На практике

Spring Boot Actuator + Micrometer + Prometheus — стандартный стек для JVM метрик.
`/actuator/metrics/jvm.gc.pause` — GC паузы. `jvm.memory.used` — heap по pool'ам.

JFR + async-profiler — для диагностики (не постоянный мониторинг). JFR включается
постоянно в production (overhead < 1%). async-profiler — по требованию при проблеме.

Типичный тред-офф: JFR sampling каждые 10ms = пропускает события короче 10ms.
async-profiler sampling каждые 10ms = нет safepoint-bias, но тоже 10ms resolution.
Для p99 latency < 1ms нужен ручной instrumentation (Custom JFR events) или HdrHistogram
с explicit timestamps.

---

## Каверзные вопросы к интервью

**Почему JMH требует @Fork?**  
Без fork benchmark запускается в том же JVM что и сам JMH. JIT-профиль JMH-кода
(init, измерение, отчёт) влияет на компиляцию тестируемого кода. Fork создаёт чистый
процесс с пустым JIT-профилем. Без fork — результаты нестабильны и нерепрезентативны.

**Что такое safepoint-bias и почему async-profiler его не имеет?**  
JVMTI-профилировщики сэмплируют стеки только в safepoints — специальных точках в
байткоде. Горячие tight loops без safepoints невидимы. async-profiler использует
сигнал (SIGPROF/perf_events) для прерывания потока в произвольный момент и вызывает
AsyncGetCallTrace вне safepoint. Видит реальный горячий код без bias.

**Почему overhead JFR < 1% а JVMTI allocation profiler может быть > 20%?**  
JFR использует binary ring buffer в JVM native memory: запись события — несколько
записей в буфер без аллокации. JVMTI AllocationListener вызывается на каждой аллокации
через JVM callback — это overhead per object. При 2 Гб/с allocation rate и объектах
по 100 байт = 20 млн callbacks/сек. async-profiler alloc mode сэмплирует 1 из N TLAB
событий — существенно реже.

**Как диагностировать медленный TTSP?**  
Включить `-Xlog:safepoint` или смотреть JFR `jdk.SafepointStateSynchronization`.
Большой TTSP + нет GC = tight loop удерживает поток. Включить `-XX:+UseCountedLoopSafepoints`
(уже включено в Java 17+). В крайнем случае — найти горячий цикл через flamegraph и
убедиться что JIT не устранил safepoint polls.

**Что показывает flamegraph и как его читать?**  
Каждый блок = один метод в стеке. Ширина = % времени этот метод был в стеке (CPU или
wall). Вертикаль = глубина. Читать снизу: точки входа → точки горячей работы. Широкое
плато наверху = горячий метод, много CPU. Flamegraph не показывает времени вызовов,
только доли. Цвет — произвольный (green = Java, yellow = C++, red = kernel обычно в
async-profiler).
