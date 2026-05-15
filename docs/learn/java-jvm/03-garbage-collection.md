# Garbage Collection: алгоритмы, коллекторы, тюнинг

GC — это компромисс, а не серебряная пуля. Задача: убирать мусор быстро и незаметно.
Проблема: «незаметно» для latency-чувствительного сервиса и «незаметно» для
throughput-ориентированного батча — разные цели. Выбор коллектора и его тюнинг
всегда начинаются с ответа: что важнее — паузы или пропускная способность.

---

## 1. Алгоритмы: откуда растут ноги

### Теория

Все GC-алгоритмы комбинируют три базовых подхода.

**Mark-Sweep**: обходи граф живых объектов от GC roots (локальные переменные, static поля,
JNI-ссылки), помечай живых, подметай кучу — освобождай память мёртвых. Просто, но
оставляет фрагментацию: дыры разного размера разбросаны по heap, большой объект может
не влезть даже если суммарно памяти хватает.

**Mark-Sweep-Compact**: после sweep компактирует heap — двигает живые объекты вплотную
друг к другу, сбрасывает указатель аллокации (bump pointer) в начало свободного
пространства. Нет фрагментации, аллокация сводится к одному atomic increment. Цена:
нужно обновить все ссылки на перемещённые объекты — дополнительный проход.

**Copying (Semi-Space)**: heap делится на два полупространства. Аллокация только в «from».
GC копирует живые объекты в «to», меняет местами «from» и «to». Автоматически компактно,
нет фрагментации, аллокация — bump pointer. Цена: в любой момент половина heap недоступна.
Это именно то, что происходит в Young Generation при Minor GC — Eden и Survivor используют
copying.

**Generational Hypothesis** превращает copying в практичный алгоритм: поскольку большинство
объектов умирают молодыми, Semi-Space работает только для молодого поколения. Там
много мусора, мало живых → копирование дешёво. Старое поколение (долгоживущие объекты)
обрабатывается другим алгоритмом реже.

### На практике

Фрагментация проявляется как OOM при достаточном `free` heap. Приложение просит
2 МБ под массив, GC видит суммарно 100 МБ свободных, но крупнейший смежный блок — 1 МБ.
**Решение в лоб — увеличить `-Xmx`** — откладывает проблему но не решает. Настоящее
решение: компактирующий коллектор или снижение объёма long-lived объектов в Old Gen.

---

## 2. Коллекторы HotSpot: кто что умеет

### Теория

**Serial GC** (`-XX:+UseSerialGC`): один поток для Minor и Major GC. Stop-The-World
паузы на все GC-фазы. Нет overhead на координацию потоков. Подходит для небольших heap
(< 256 МБ) и single-core окружений — контейнеры с `--cpus=1`.

**Parallel GC** (`-XX:+UseParallelGC`): Minor и Major GC в несколько потоков (-XX:ParallelGCThreads).
Stop-The-World на всё. Максимальный throughput — нет GC-потоков работающих конкурентно
с приложением, значит нет overhead синхронизации. До Java 8 был default в server JVM.
Хорош для батчей: паузы терпимы, throughput максимален.

**G1 (Garbage First)** (`-XX:+UseG1GC`, default с Java 9): heap делится на равные регионы
(1–32 МБ, обычно ~2048 штук). Регионы помечаются как Eden/Survivor/Old/Humongous. G1
собирает сначала регионы с наибольшим количеством мусора — отсюда название. Minor GC
(evacuation) параллельна и STW. Concurrent marking (поиск живых в Old Gen) — конкурентен
с приложением: несколько GC-потоков параллельно с потоками приложения. Mixed GC собирает
и Young, и выбранные Old-регионы одновременно. Цель: пауза не превышает `-XX:MaxGCPauseMillis`
(default 200ms), но это цель, а не гарантия.

**ZGC** (`-XX:+UseZGC`, production-grade с Java 15, generational с Java 21): все затратные
фазы конкурентны. STW-паузы в пределах 1–2 мс независимо от размера heap (тестировали до
16 ТБ). Использует цветные указатели (colored pointers) — метаданные GC хранятся в
неиспользованных битах адреса, не в object header. Load barrier переадресует ссылки на
перемещённые объекты без STW. Цена: throughput на 5–15% ниже G1 при малом heap, больше
памяти на структуры GC.

**Shenandoah** (`-XX:+UseShenandoahGC`, Red Hat, OpenJDK 12+): также sub-millisecond
паузы, concurrent compaction. Архитектурно отличается от ZGC: использует Brooks pointer
(forwarding pointer в заголовке каждого объекта) вместо цветных указателей. Схожие
паузы с ZGC, но разный trade-off по throughput и памяти.

```
                  Паузы     Throughput  Overhead памяти
Serial            STW, long     max          min
Parallel          STW, long     max          min
G1            STW, <200ms       высокий      средний
ZGC               <2ms          -5–15%      высокий
Shenandoah        <2ms          средний      средний
```

### На практике

Типичный сервис на Java 21 → G1 по умолчанию, обычно достаточно. Если p99 latency
страдает из-за GC-пауз (видно в GC logs как pause > 50ms) → ZGC. Если heap > 8 ГБ
и latency критична → ZGC или Shenandoah.

**Решение в лоб — перейти на ZGC ради нулевых пауз** — не бесплатно. ZGC потребляет
на 20–30% больше памяти на крупных объектах (forwarding tables, remset). На heap 1–2 ГБ
G1 с настроенным `MaxGCPauseMillis=50` часто лучше по всем метрикам.

Humongous-объекты G1 (> половины размера региона) аллоцируются сразу в Old Gen и
создают давление на concurrent marking. Симптом: частые concurrent mark cycles без
заметного роста Old Gen. Диагноз: включить `-Xlog:gc+humongous`, найти виновника.

---

## 3. GC roots, card table, remembered set

### Теория

**GC Roots** — точки входа в граф живых объектов:
- Локальные переменные всех активных стеков потоков
- Статические поля классов
- JNI Global References
- Системные классы (загруженные bootstrap ClassLoader'ом)

Объект жив если достижим от любого GC root по цепочке ссылок. Недостижимые объекты —
мусор.

Проблема поколений: Minor GC собирает только Young Gen. Но объект в Old Gen может
ссылаться на объект в Young Gen (например, кэш в Old Gen хранит недавно созданные
записи). Если не учитывать такие ссылки, Minor GC неверно посчитает Young-объект мертвым
и удалит его.

**Card Table**: heap делится на карточки по 512 байт. Когда объект в Old Gen записывает
ссылку на Young-объект — карточка, содержащая этот Old-объект, помечается как «грязная»
(dirty). При Minor GC достаточно сканировать только dirty cards, а не весь Old Gen.

**Remembered Set (RemSet)** — обратное: для каждого региона (в G1) хранится множество
регионов-источников, которые имеют ссылки в данный регион. Позволяет собирать регион
независимо без сканирования всего heap.

Write barrier — код, вставляемый JIT-компилятором при каждой записи ссылки. Он обновляет
card table/remembered set. Это overhead аллокации/мутации — десятки наносекунд на каждую
запись ссылки.

### На практике

**Humongous allocation churn** — паттерн, убивающий G1-throughput. Короткоживущие большие
объекты (byte[] под HTTP-ответы, Kafka-сообщения) постоянно создаются в Humongous-регионах
(Old Gen), вынуждая частые concurrent marking cycles. Симптом: GC log полон
`Concurrent Mark` при умеренном heap usage.

Решение: уменьшить размер региона G1 (`-XX:G1HeapRegionSize`) чтобы объект перестал
быть Humongous, или использовать off-heap буферы для крупных временных данных (ByteBuffer).

Большой RemSet = долгий pause для G1 mixed collection. Если регион имеет тысячи входящих
ссылок — его RefSet обновляется при каждой записи и занимает много памяти. `G1SummarizeRSetStats`
покажет самые «популярные» регионы.

---

## 4. Диагностика GC и тюнинг

### Теория

**GC логирование** в Java 9+ через Unified JVM Logging:

```
-Xlog:gc:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

Детальное:
```
-Xlog:gc*:file=gc-detail.log:time,uptime,level,tags
```

Ключевые поля в логе:
- `Pause Young (G1 Evacuation Pause)` — Minor GC
- `Pause Full (G1 Compaction Pause)` — Full GC (аварийный компактор)
- `Concurrent Mark Cycle` — конкурентная фаза G1
- `To-space exhausted` — G1 не смог эвакуировать Young, fallback на Full GC

**Full GC в G1** — почти всегда симптом проблемы, не нормальная работа:
- Allocation rate > скорость concurrent marking → G1 не успевает
- Огромные humongous объекты заполняют Old Gen быстрее evacuation
- RemSet flooding

**Key метрики:**
- GC pause time (p99, max) — главная метрика latency
- Allocation rate (МБ/с) — сколько создаётся в секунду
- Promotion rate (МБ/с) — сколько уходит в Old Gen
- GC overhead (% CPU) — сколько времени JVM тратит на GC

### На практике

Типичный диагностический процесс:

1. Включить GC-лог в production (overhead минимален, < 1% CPU).
2. Парсить через `GCViewer` или `gceasy.io` — визуализирует паузы, throughput,
   allocation rate.
3. Если p99 pause > SLO — смотри какой тип паузы. Evacuation паузы → тюнинг
   Young Gen размера. Full GC → искать причину: promotion failure или Humongous.

**Основные ручки G1:**
- `-XX:MaxGCPauseMillis=100` — целевая пауза. G1 пытается её выполнить уменьшая
  Young Gen. Не уменьшай ниже 50ms без измерений.
- `-XX:G1HeapRegionSize=4m` — размер региона. Увеличение снижает количество регионов,
  уменьшает overhead RemSet.
- `-XX:InitiatingHeapOccupancyPercent=45` — порог occupancy Old Gen для запуска
  concurrent marking. Уменьши если видишь частые Full GC из-за promotion failure.
- `-XX:G1ReservePercent=20` — резерв для evacuation. Увеличь при `To-space exhausted`.

**Решение в лоб — просто увеличить `-Xmx`** — часто откладывает проблему. Больше heap
→ реже Minor GC → медленнее concurrent marking → Full GC всё равно, но реже и дольше.
Настоящее решение начинается с allocation profiling: найти что создаёт мусор и уменьшить
аллокации на горячем пути.

---

## 5. Allocation profiling и снижение GC-давления

### Теория

Лучший GC — тот, которому меньше работы. Снижение allocation rate на горячем пути
уменьшает Minor GC frequency, уменьшает promotion rate, снижает давление на Old Gen.

**async-profiler** в режиме alloc (`-e alloc`) захватывает стек каждого N-го байта
аллоцированного в TLAB. Показывает top аллокаторов — методы, из которых создаётся
больше всего объектов.

Типичные источники избыточных аллокаций:
- Конкатенация строк в цикле (`str + x` → новый `StringBuilder` на каждой итерации)
- Boxing примитивов в коллекции (`List<Integer>` вместо `int[]`)
- `Iterable` + `Iterator` для каждой итерации foreach
- Создание короткоживущих wrapper-объектов на горячем пути (DTO из DTO)

**Escape analysis** (EA) JIT-компилятора может устранять аллокацию объектов, чей lifetime
ограничен методом: вместо heap JIT хранит поля в регистрах или стеке. Это stack allocation
в JVM терминах. Работает для объектов, которые не передаются за пределы метода, не
помещаются в коллекции, не попадают в поля другого объекта.

С **Project Valhalla** (value types, идёт в Java 23+): примитивные value types не имеют
identity и могут встраиваться в массивы без boxing — будущий инструмент против аллокаций.

### На практике

Объект прошёл escape analysis, если в `-XX:+PrintEscapeAnalysis` или в `PrintCompilation`
нет аллокации там, где ты её ожидал. Альтернатива: JMH-микробенчмарк с измерением
`gc.alloc.rate.norm` через `@BenchmarkMode(Mode.AverageTime)` + Blackhole потребляет
объект (чтобы EA не устранил его до Blackhole).

Реальный кейс: Spring Boot приложение с 2 Гб/с allocation rate при 100 RPS. Причина —
каждый запрос проходит через несколько слоёв DTO-маппинга, каждый слой создаёт свои
объекты. async-profiler показал что 60% аллокаций в mapper layer. Решение: передавать
ссылки вместо копировать, убрать промежуточные DTO где возможно. Allocation rate упал до
200 Мб/с, Minor GC frequency снизилась в 5 раз.

---

## Каверзные вопросы к интервью

**В чём разница между Minor GC, Major GC и Full GC?**  
Minor GC собирает Young Generation (STW, быстро). Major GC — Old Generation (в G1 это
concurrent process + mixed collections). Full GC — компактирующий сбор всего heap,
всегда STW, всегда медленный — в G1 это аварийный fallback.

**Почему G1 называется Garbage First?**  
G1 сортирует регионы по соотношению мусора к живым объектам и собирает сначала те, где
мусора больше всего (больше «отдача» при фиксированной паузе). Отсюда — Garbage First.

**Что такое concurrent marking и почему он не может заменить STW?**  
Concurrent marking обходит граф объектов параллельно с работающим приложением — это не
требует STW. Но приложение продолжает создавать и менять ссылки. Финальная фаза —
Remark (STW) — «досматривает» объекты, изменённые во время concurrent marking, через
SATB (Snapshot-At-The-Beginning) барьер.

**Почему ZGC имеет sub-millisecond паузы?**  
ZGC перемещает объекты конкурентно. Для этого используются цветные указатели (metadata
в неиспользованных битах адреса) и load barriers (при каждом чтении ссылки — проверка и
переадресация если объект переехал). STW нужен только для scan GC roots и small pause
в начале/конце фаз — это < 1ms.

**Что такое promotion failure?**  
Minor GC пытается переместить живые Young-объекты в Old Gen, но Old Gen заполнен. G1
fallback'ается на Full GC. Симптом: `(to-space exhausted)` или `(promotion failed)` в
GC логах. Решение: уменьшить IHOP (`InitiatingHeapOccupancyPercent`) чтобы concurrent
marking стартовал раньше, или увеличить `-Xmx`.
