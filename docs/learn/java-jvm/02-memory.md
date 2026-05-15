# Память: heap, stack, TLAB, escape analysis, off-heap

Память — самое частое место неожиданных проблем в Java-приложениях. OOM приходит не
только из heap; объекты могут вообще не попасть в heap; а direct memory живёт по своим
правилам и молча растёт пока не прихлопнет процесс. Эта глава — о том как устроена память
JVM и почему «добавь `-Xmx`» — не всегда правильный ответ.

## Структура heap: Generational Hypothesis

### Почему поколения

Heap Java не монолитный буфер — он разделён на поколения. Причина: **Generational Hypothesis**
(эмпирическое наблюдение) — большинство объектов умирают молодыми. Временный результат
запроса, builder, итератор, локальная строка — всё это живёт миллисекунды.

Если это так, выгодно собирать мусор часто в маленьком молодом поколении (быстро, много
мусора → мало копировать), а в большое старое поколение откладывать долгоживущих.

```
┌─────────────────────────────────┬───────────────────────┐
│         Young Generation        │    Old Generation     │
│  ┌──────────────┬──────┬──────┐ │    (Tenured)         │
│  │    Eden      │  S0  │  S1  │ │                      │
└──┴──────────────┴──────┴──────┴─┴──────────────────────┘
```

**Eden**: большинство объектов аллоцируются здесь. Minor GC собирает Eden целиком — это
быстро, потому что живых объектов мало (большинство уже мертвы).

**Survivor S0/S1**: объекты пережившие Minor GC переходят в Survivor. Из Survivor в Survivor
они копируются при каждом Minor GC, накапливая возраст (GC age в object header). При
достижении `-XX:MaxTenuringThreshold` (default 15) — продвигаются в Old Gen.

**Old Generation (Tenured)**: долгоживущие объекты. Major GC (или Full GC) собирает Old Gen —
намного реже, но дольше.

G1, ZGC, Shenandoah делят heap на регионы, но Generational Hypothesis применяют так же —
регионы помечаются как Young/Old, не весь heap монолитно.

### Object Header: почему пустой объект занимает 16 байт

Каждый Java-объект содержит header перед своими полями:

- **Mark Word** (8 байт на 64-bit): хранит hash-код объекта, флаги GC-возраста, bits
  состояния блокировки (biased/lightweight/heavy lock), forwarding pointer при GC-перемещении.
- **Klass Pointer** (4 байта при сжатых указателях, 8 без): ссылка на метаданные класса
  в Metaspace.

Итого: 12 байт. JVM выравнивает объекты по 8 байт → 16 байт минимум.

Поле `int` в объекте: 16 + 4 = 20 → выравнивается до 24. Поле `long`: 16 + 8 = 24.
Сжатые объектные ссылки (`-XX:+UseCompressedOops`, включены по умолчанию при heap < 32GB):
4 байта вместо 8 на ссылку.

## TLAB — Thread Local Allocation Buffer

### Проблема аллокации в shared heap

Naive аллокация в shared heap требует синхронизации: нужно атомарно сдвинуть указатель
аллокации (bump pointer) вперёд на размер нового объекта. При сотнях потоков, каждый из
которых создаёт объекты — это contention на одном указателе.

### Решение: TLAB

Каждый поток получает свой кусок Eden — **TLAB (Thread Local Allocation Buffer)**. Аллокация
внутри TLAB — просто bump pointer без какой-либо синхронизации. Это и есть причина высокой
скорости Java-аллокации: типичная аллокация = инкремент одного указателя.

Когда TLAB заполнен: поток запрашивает новый TLAB у JVM (нужна синхронизация, но редко).
Если объект не помещается ни в один TLAB — аллокация напрямую в Eden с синхронизацией, или
(в G1) в Humongous region.

Посмотреть размер и статистику: `-XX:+PrintTLAB`, `-XX:TLABSize` для явного задания размера.
По умолчанию JVM адаптирует размер TLAB под allocation rate потока.

## Escape Analysis и Scalar Replacement

### Что такое escape analysis

JIT-компилятор анализирует, «вытекает» ли объект за пределы создавшего его метода:
- Сохраняется в поле другого объекта → **heap escape**
- Возвращается из метода → **return escape**
- Передаётся в другой поток → **thread escape**

Если объект **не вытекает** — JIT может применить одну из двух оптимизаций.

### Stack allocation

Если объект не вытекает и достаточно мал — JIT может разместить его на стеке потока.
Стек автоматически освобождается при выходе из метода — GC не нужен вообще.

### Scalar replacement (важнее stack allocation)

JIT разлагает объект на его примитивные поля и хранит их в регистрах или на стеке.
Объект не аллоцируется в heap вообще — он перестаёт существовать как объект, его поля
живут как обычные переменные.

```java
// Исходный код:
double dist(double x1, double y1, double x2, double y2) {
    Point delta = new Point(x2 - x1, y2 - y1); // этот объект
    return Math.sqrt(delta.x * delta.x + delta.y * delta.y);
}

// После scalar replacement (концептуально):
double dist(double x1, double y1, double x2, double y2) {
    double dx = x2 - x1; // поля Point живут как локальные переменные
    double dy = y2 - y1;
    return Math.sqrt(dx * dx + dy * dy);
}
```

`Point` не попадает в heap. Никакого GC pressure. Это работает автоматически — не нужно
вручную оптимизировать создание вспомогательных объектов в горячих методах.

### Когда escape analysis не срабатывает

- Объект передаётся в метод другого класса, который JIT не смог заинлайнить — граница
  inlining-дерева = граница escape analysis.
- Синхронизация на объекте (biased locking в старых JVM требовал heap allocation).
- Объект помещается в коллекцию.
- Метод слишком большой — JIT отказывается от escape analysis.

**Ловушка «ручной object pool»**: многие разработчики создают пулы объектов «чтобы не
нагружать GC». В большинстве случаев это мешает scalar replacement работать — объект
теперь гарантированно в heap (в пуле). Пул нужен только для объектов с реально дорогой
инициализацией (соединения, потоки) — не для DTO и вспомогательных объектов.

## Off-Heap память

### Зачем нужна

Off-heap (за пределами Java heap) полезна когда:
- Кэш с данными больше нескольких гигабайт — GC overhead на большой heap неприемлем
- Нужно гарантированное время жизни данных без зависимости от GC
- Взаимодействие с native-кодом (JNI, файловые дескрипторы, сеть)

### ByteBuffer.allocateDirect()

Выделяет native memory через JNI. Буфер не в Java heap — GC его не трогает. Освобождается
через `Cleaner` — внутренний механизм, срабатывающий когда GC соберёт java-обёртку
`DirectByteBuffer`.

**Ловушка**: освобождение зависит от GC. Если heap маленький и GC редкий — DirectByteBuffer
накапливаются, native memory растёт. Лечится явным вызовом:
```java
((sun.nio.ch.DirectBuffer) buf).cleaner().clean();
```
или использованием автоматически управляемых обёрток (Netty's `ByteBuf` с ref counting).

Ограничение: `-XX:MaxDirectMemorySize` (по умолчанию = `-Xmx`). При превышении:
`OutOfMemoryError: Direct buffer memory`.

### Unsafe (sun.misc.Unsafe)

Прямой доступ к памяти: `allocateMemory()`, `freeMemory()`, `putLong(addr, val)`. Используется
в Netty, Cassandra, Kafka для zero-copy операций и off-heap структур данных.

Danger zone: нет проверок границ, segfault если адрес неверный, JVM не может защититься.
В Java 17+ доступ ограничен, в Java 21 `Unsafe::allocateMemory` и ко. deprecated.

### Foreign Function & Memory API (FFM, Java 22 stable)

Официальная замена Unsafe и JNI. Даёт:
- `MemorySegment` — типизированный указатель с явными границами и проверкой обращений
- `Arena` — управление временем жизни: `Arena.ofConfined()` освобождается при close(),
  `Arena.ofShared()` — thread-safe
- `MemoryLayout` — описание структур C из Java
- `MethodHandle`-based вызов native функций без JNI boilerplate

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = arena.allocate(1024);
    // segment автоматически освобождается при выходе из try
}
```

Это будущее off-heap Java. Unsafe постепенно уходит.

## Диагностика проблем с памятью

### Heap OOM

`OutOfMemoryError: Java heap space` — heap заполнен, GC не освобождает достаточно.
Причины: утечка (объекты удерживаются дольше чем нужно), недостаточный `-Xmx`, резкий
всплеск нагрузки.

Диагностика:
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump.hprof` — автоматический heap dump
- Анализ в Eclipse MAT (Memory Analyzer Tool) или VisualVM — найти Retained Heap самых жирных объектов
- Посмотреть кто держит ссылки через GC roots → dominator tree

### Metaspace OOM

`OutOfMemoryError: Metaspace` — слишком много загруженных классов. Типичные причины:
Hibernate с proxy на каждую сущность, CGLIB-проксирование в Spring без cache, JSP-движки
компилирующие каждый шаблон в класс, или classloader-утечка в app-сервере.

Диагностика: мониторинг `java.lang:type=MemoryPool,name=Metaspace` через JMX;
`-XX:+TraceClassLoading` — список загружаемых классов. Задать `-XX:MaxMetaspaceSize` чтобы
OOM возникал предсказуемо, а не при исчерпании native памяти системы.

### Direct Memory OOM

`OutOfMemoryError: Direct buffer memory` — превышен `-XX:MaxDirectMemorySize`. Мониторить
через `java.lang:type=Memory` (NonHeapMemoryUsage) или Netty metrics если используешь Netty.

### Humongous Objects в G1

G1 делит heap на регионы (1–32MB). Объект > 50% размера региона — **humongous**: аллоцируется
не в Eden, а в специальных Humongous-регионах. Не участвует в Minor GC — только в Mixed/Full.

Типичная история: приложение генерирует большие byte[] (HTTP body, Kafka сообщение) →
G1 создаёт Humongous регионы → они не освобождаются до Mixed GC → Old Gen разрастается →
Full GC. Лечение: уменьшить объекты, или увеличить размер региона `-XX:G1HeapRegionSize`.

## Каверзные вопросы интервью

**Почему `-Xms == -Xmx` рекомендуется в production?** Resize heap — это системный вызов
и пауза. При `-Xms < -Xmx` JVM может несколько раз увеличивать heap пока не достигнет Xmx —
каждый раз пауза. Равные значения: heap выделяется сразу, поведение предсказуемо, OOM
возникает при старте если памяти нет (а не через час в production).

**Может ли объект аллоцироваться не в heap?** Да — через escape analysis: stack allocation
или scalar replacement. JIT принимает решение автоматически.

**Почему `String.intern()` может вызвать OOM?** С Java 7 String pool в heap (был в PermGen
до Java 7). `intern()` кладёт строку в WeakHashMap-подобный пул. При интернировании миллионов
уникальных строк пул растёт неограниченно → OOM в heap.

**Что такое word tearing для long/double?** На 32-bit JVM операции с 64-bit значениями
не атомарны — могут выполняться двумя 32-bit операциями. Один поток может прочитать
половину от одного значения и половину от другого. `volatile long`/`volatile double` гарантируют
атомарность чтения/записи даже на 32-bit. На 64-bit JVM это уже не проблема, но спецификация
всё равно требует volatile для гарантии.
