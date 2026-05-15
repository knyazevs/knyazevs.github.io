# Project Loom: virtual threads, pinning, structured concurrency

До Project Loom масштабирование Java-сервисов упиралось в одну стену: поток OS.
Каждый `Thread` — это OS-поток (1–2 МБ стека), планируемый ядром. Под нагрузкой
тысячи заблокированных IO-потоков — тысячи OS-потоков. Это ограничение и обходили
реактивным программированием (CompletableFuture, WebFlux, Reactor) с ценой нечитаемого
кода и broken stack traces. Loom убирает это ограничение.

---

## 1. Virtual threads: механика

### Теория

**Virtual thread** (виртуальный поток) — легковесная единица конкурентности JVM. Не
отображается 1:1 на OS-поток. JVM планирует виртуальные потоки поверх пула
**carrier threads** (OS-потоки, обычно по числу ядер CPU).

Когда виртуальный поток блокируется на IO (socket read, lock wait, `Thread.sleep`), JVM:
1. Сохраняет его стек на heap (не в стеке OS)
2. Отмонтирует (unmounts) виртуальный поток с carrier thread'а
3. Carrier thread подхватывает другой виртуальный поток из очереди

Когда IO завершается — виртуальный поток монтируется снова на свободный carrier thread.

Это cooperative scheduling внутри JVM, без syscall'ов. Стоимость переключения контекста
для виртуальных потоков — несколько микросекунд против ~1–10 мкс для OS-потоков.

**Стоимость создания**: виртуальный поток — объект на heap ~200–300 байт плюс динамически
растущий стек. Миллион виртуальных потоков — несколько сотен МБ heap. Миллион OS-потоков
— несколько ТБ RAM и падение ядра.

```java
// Java 21: создать и запустить virtual thread
Thread vt = Thread.ofVirtual().start(() -> {
    // любой блокирующий IO здесь — не блокирует OS-поток
    var result = httpClient.send(request, bodyHandler);
});

// ExecutorService из virtual threads
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    exec.submit(() -> processRequest(req));
}
```

### На практике

«Thread per request» модель возвращается. Вместо реактивного кода с callback'ами и
`flatMap` — прямолинейный синхронный код, который масштабируется как реактивный.

Сервис принимает 10 000 RPS, каждый запрос делает 2 IO-вызова по 50ms. С OS-потоками
нужно ~20 000 потоков (2 IO × 50ms = 100ms конкуренции) — это ~20 ГБ RAM только на стеки.
С virtual threads — достаточно carrier-пула из 8–16 потоков, все 20 000 параллельных
задач на heap.

**Что НЕ меняется**:
- Синхронизация данных (JMM, races, deadlocks) — те же проблемы
- CPU-bound работа не ускоряется — виртуальный поток не даёт больше CPU
- Реальный параллелизм ограничен числом carrier threads (cores)

---

## 2. Pinning — главная ловушка

### Теория

**Pinning** (прикрепление) — ситуация когда виртуальный поток не может отмонтироваться
с carrier thread'а. Carrier thread блокируется вместе с виртуальным.

Два случая pinning:

**1. `synchronized` блок или метод**. При входе в `synchronized` виртуальный поток
монтируется на carrier и держит его до выхода из блока. JDK не может отмонтировать поток
внутри монитора — состояние intrinsic lock хранится в object header, привязанном к OS-потоку.

```java
synchronized (lock) {
    Thread.sleep(1000); // carrier thread заблокирован на 1 секунду!
}
```

**2. Native методы (JNI)**. JNI-код предполагает, что работает на конкретном OS-потоке.
JVM не может переключить виртуальный поток с carrier'а во время JNI-вызова.

Pinning превращает virtual thread в обычный OS-поток в части масштабирования. Если все
carrier threads заняты pinned virtual threads — новые задачи ждут, несмотря на то что
«висят на IO».

JVM предупреждает о pinning через JFR-событие `jdk.VirtualThreadPinned` или через
системное свойство `-Djdk.tracePinnedThreads=full`.

### На практике

Реальная проблема: популярные библиотеки используют `synchronized` внутри. До Java 24
JDBC-драйверы, некоторые HTTP-клиенты, Caffeine cache — всё pinning на каждом IO-вызове.

Spring Boot 3.2+ добавил конфигурацию `spring.threads.virtual.enabled=true`. Но если
используемый JDBC-драйвер не переписан — каждый database query pinning carrier thread.

**Решение**: перейти с `synchronized` на `ReentrantLock` (которые поддерживают
корректное отмонтирование виртуальных потоков). Java 24 переписала ряд JDK-классов
чтобы убрать pinning (включая `synchronized` в некоторых путях).

```java
// Плохо с virtual threads:
synchronized (this) { db.query(); }

// Хорошо:
lock.lock();
try { db.query(); }
finally { lock.unlock(); }
```

---

## 3. Structured Concurrency

### Теория

**Structured Concurrency** (структурированная конкурентность) — принцип: поток (задача)
не может пережить scope (область), в которой она была создана. Аналог структурированного
программирования для конкурентности: как `if/for/try` имеют чёткие границы в коде,
так и параллельные задачи должны иметь явный lifetime.

В Java 21 (preview, стабилизируется в Java 25): `StructuredTaskScope`.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> userTask = scope.fork(() -> fetchUser(id));
    Subtask<Orders> ordersTask = scope.fork(() -> fetchOrders(id));

    scope.join();           // ждём оба форка
    scope.throwIfFailed();  // пробрасываем первую ошибку

    return new UserProfile(userTask.get(), ordersTask.get());
}
```

**Гарантии**:
- `scope.join()` не возвращается пока все форкнутые задачи не завершены
- При выходе из `try` (успех или исключение) — все незавершённые задачи отменяются
- Никаких «осиротевших» потоков (orphan threads) — нарушение инварианта lifetime

**Два стандартных scope**:
- `ShutdownOnFailure`: первая ошибка — отмена остальных
- `ShutdownOnSuccess`: первый успех — отмена остальных (полезно для «попробуй два
  источника, возьми первый ответ»)

### На практике

Structured concurrency решает классическую проблему компенсации в fan-out запросах.
Старый код: запускаешь `CompletableFuture` для N внешних вызовов, один падает — нужно
руками отменить остальные, поймать их ошибки, собрать результат. С `StructuredTaskScope`
это встроено в контракт scope.

Debugging улучшается: thread dump показывает иерархию задач («этот вирт-поток создан в
scope X, который живёт в методе Y вызванном из Z»). С CompletableFuture цепочка
лямбд теряет контекст.

**Ограничение**: Structured Concurrency требует явного scope. Если задача должна жить
дольше текущего метода (fire-and-forget, background task) — Structured Concurrency не
подходит. Используй обычный `ExecutorService` или планировщик.

---

## 4. Scoped Values — замена ThreadLocal

### Теория

`ThreadLocal` плохо работает с virtual threads по двум причинам:
1. **Масштабирование**: миллион виртуальных потоков → миллион `ThreadLocal` записей.
   `ThreadLocal` хранит значение в `Thread`-объекте, каждый виртуальный поток имеет
   свой `Thread` объект → большой overhead памяти.
2. **Наследование**: `InheritableThreadLocal` не работает корректно с fork/join паттернами
   внутри `StructuredTaskScope` — форкнутые задачи могут нечаянно видеть mutable state
   родителя.

**ScopedValue** (Java 21, finalized в Java 25) — иммутабельная привязка значения к
области выполнения.

```java
static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

// Установить значение для области:
ScopedValue.where(CURRENT_USER, user).run(() -> {
    processRequest();       // внутри — CURRENT_USER.get() возвращает user
    nestedMethod();         // доступно глубже в стеке
});
// здесь CURRENT_USER снова не определён
```

Иммутабельность: нельзя изменить значение ScopedValue внутри scope — только вложить
новый scope с другим значением. Это исключает класс багов ThreadLocal (случайное
загрязнение state соседних задач).

Форкнутые задачи в `StructuredTaskScope` автоматически наследуют ScopedValue родителя —
корректно и безопасно благодаря иммутабельности.

### На практике

Типичный use case: request-scoped контекст (текущий пользователь, tracing span, tenant ID).
Раньше передавался через `ThreadLocal` или явным параметром. С ScopedValue — устанавливается
на входе в request handler, доступен глубоко в стеке без явной передачи.

**Когда ThreadLocal всё ещё нужен**: mutable per-thread state, где каждый поток ведёт
свой буфер, счётчик или RNG. ScopedValue — для read-only контекста; ThreadLocal — для
mutable per-thread данных.

---

## Каверзные вопросы к интервью

**В чём разница между виртуальным потоком и платформенным (OS) потоком?**  
Платформенный поток — 1:1 с OS-потоком, планируется ядром, стек в нативной памяти
(1–2 МБ). Виртуальный поток — M:N к OS-потокам, планируется JVM-шедулером поверх carrier
threads, стек на heap (сотни байт). Создание виртуального потока в 100+ раз дешевле.

**Что такое pinning и когда он происходит?**  
Виртуальный поток прикрепляется к carrier thread и не может отмонтироваться при
блокировке. Происходит внутри `synchronized` блоков и JNI-вызовов. Carrier thread
блокируется вместе с виртуальным — деградация к поведению OS-потока.

**Зачем StructuredTaskScope если есть CompletableFuture?**  
CompletableFuture не имеет гарантии lifetime: форкнутые задачи могут пережить scope.
При ошибке нужно вручную отменять остальные. Stack traces теряют контекст. StructuredTaskScope
гарантирует: дочерние задачи не переживают родительский scope, отмена встроена в контракт,
debugging через иерархию потоков.

**Помогут ли virtual threads CPU-bound задачам?**  
Нет. Virtual threads помогают IO-bound workloads: когда потоки большую часть времени
ждут (IO, sleep, lock). CPU-bound задачи нужно распараллеливать через ForkJoinPool /
`parallelStream()` — реальный параллелизм, а не конкурентность.

**Совместим ли Spring Boot с virtual threads?**  
Да, с Spring Boot 3.2+ через `spring.threads.virtual.enabled=true`. Tomcat использует
virtual thread per request. Важно: если JDBC-драйвер использует `synchronized` — pinning
на каждом DB-запросе. PostgreSQL JDBC (42.7+) переписан на ReentrantLock. Для MySQL
нужно проверить версию.
