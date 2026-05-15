# 06. java.util.concurrent: AQS, примитивы, пулы, CompletableFuture

## 1. AbstractQueuedSynchronizer — фундамент всего j.u.c.

### Теория

`AbstractQueuedSynchronizer` (AQS) — абстрактный суперкласс, от которого наследуются почти все синхронизаторы стандартной библиотеки: `ReentrantLock`, `Semaphore`, `CountDownLatch`, `CyclicBarrier`, `ReentrantReadWriteLock`. Понять AQS — значит понять, как работают все эти примитивы на уровне механизма, а не просто «знать API».

Внутри AQS три ключевых элемента:

**`volatile int state`** — единственная переменная состояния. Смысл зависит от конкретного синхронизатора: для `ReentrantLock` это `0 = свободен`, `N > 0 = занят и захвачен N раз` (reentrant-счётчик); для `Semaphore` — количество доступных разрешений; для `CountDownLatch` — обратный счётчик.

**CLH-очередь** (названа по авторам Craig, Landin, Hagersten) — FIFO-очередь потоков, ожидающих захвата. Каждый поток, которому не удалось захватить блокировку, добавляется в очередь и паркуется через `LockSupport.park()`. Пробуждение — `LockSupport.unpark(thread)`.

**`compareAndSetState(expected, update)`** — CAS-операция для атомарного изменения state. Именно здесь происходит «гонка за захват» без традиционного мьютекса.

Как `ReentrantLock.lock()` работает под капотом:
1. CAS: пытаемся записать state из `0` в `1`.
2. Удалось — захватили блокировку, сохраняем `ownerThread = currentThread`.
3. Не удалось, но `ownerThread == currentThread` — это reentrant-вход, `state++`.
4. Иначе — добавляем поток в CLH-очередь, вызываем `LockSupport.park()`.
5. При `unlock()` — `state--`, если стало `0` → `ownerThread = null`, `LockSupport.unpark()` следующего в очереди.

Это объясняет, почему `ReentrantLock` даёт возможности, которых нет у `synchronized`: `tryLock(timeout)` — CAS с таймаутом и выходом из очереди; `lockInterruptibly()` — выброс `InterruptedException` вместо молчаливого ожидания; выбор fair/unfair политики.

### На практике

**Fair vs Unfair**: fair-режим гарантирует FIFO-порядок выдачи блокировки — никакого starvation, но throughput ниже, потому что поток, только что освободивший блокировку, не может перехватить её снова (должен встать в очередь). Unfair (по умолчанию) позволяет «барging» — только разблокированный поток может сразу же захватить lock раньше тех, кто уже ждёт; это снижает latency для happy path.

Типичная ошибка: не вызвать `unlock()` при исключении. Всегда оборачивай в `try/finally`:

```java
lock.lock();
try {
    // критическая секция
} finally {
    lock.unlock();
}
```

**`Condition`-переменные** — замена `wait()/notify()`. Создаются через `lock.newCondition()`. Позволяют иметь несколько условий на один Lock (например, «буфер не пуст» и «буфер не полон» в producer-consumer).

### Когда модель усложняется

Unfair lock в сочетании с высокой конкуренцией порождает starvation: некоторые потоки могут ждать сколь угодно долго, потому что вновь прибывающие постоянно перехватывают lock. Если это проблема — fair-режим или другая структура данных.

---

## 2. Lock-и: ReentrantLock, ReadWriteLock, StampedLock

### Теория

**`synchronized` vs `ReentrantLock`** — популярный вопрос на интервью, но ответ не тривиален.

Performance: начиная с Java 6 JVM умеет biased locking (объект «прикрепляется» к захватившему его потоку — последующие lock/unlock без CAS) и adaptive spinning (спин-ожидание вместо park при коротких критических секциях). Это делает `synchronized` сопоставимым с `ReentrantLock` по производительности в большинстве сценариев. **Biased locking убрали в Java 15** как устаревшую оптимизацию: современные аллокаторы и JIT-компиляторы справляются без него, а revocation при contention обходилась слишком дорого (стоп-ворлд на все потоки).

Выбирай `ReentrantLock` когда нужна функциональность: timeout, interruptible wait, fair ordering, несколько Condition-переменных. В остальных случаях `synchronized` проще и безопаснее (компилятор следит за unlock автоматически).

**`ReentrantReadWriteLock`**: несколько читателей одновременно ИЛИ один писатель. Интуиция — shared/exclusive:

- `readLock().lock()` — shared (много потоков могут держать read lock одновременно)
- `writeLock().lock()` — exclusive (все ждут)

Ловушка — **write starvation**: при постоянном потоке readers писатель может ждать бесконечно, потому что новые readers пролезают раньше ожидающего writer'а (unfair по умолчанию). Решение: fair-режим. Цена — снижение throughput readers.

**`StampedLock` (Java 8+)** — оптимистичное чтение без lock:

```java
long stamp = lock.tryOptimisticRead();  // не блокирует, возвращает stamp
// читаем данные
if (!lock.validate(stamp)) {            // кто-то писал пока мы читали?
    stamp = lock.readLock();            // fallback на настоящий readLock
    try { /* перечитать */ } finally { lock.unlockRead(stamp); }
}
```

При отсутствии конкуренции — нулевой overhead: просто load + validate (проверка версии). При наличии конкуренции — fallback на полноценный readLock.

Ограничения `StampedLock`: **не reentrant** (нельзя взять readLock в потоке у которого уже есть writeLock — deadlock), нет поддержки `Condition`, нет interruptible-версии tryOptimisticRead.

### На практике

Типичный сценарий для `ReadWriteLock` — кеш с редкими обновлениями: на каждое чтение readLock, на инвалидацию или загрузку — writeLock. При очень низкой частоте записи `StampedLock` даст заметный прирост throughput за счёт оптимистичного пути.

`StampedLock` подходит для структур данных с «snapshot-read» семантикой: ты читаешь координаты точки (x, y) — тебе нужна согласованная пара, но при отсутствии конкуренции не хочешь платить за lock.

### Когда модель усложняется

При бурстах writes на `ReadWriteLock` с fair=true readers внезапно начинают ждать — это удивляет, если ты привык к unfair поведению. Тестируй под нагрузкой, близкой к продакшн-распределению.

---

## 3. ConcurrentHashMap: внутренности и ловушки

### Теория

**Java 7**: map делился на `16` сегментов (configurable), каждый — отдельный `ReentrantLock`. Параллелизм ограничен числом сегментов: `put()` на разные сегменты идут параллельно, на один — последовательно.

**Java 8**: полностью переписан. Массив bin'ов, как в обычном `HashMap`. Синхронизация:

- Первая вставка в пустой bin — **CAS** без блокировки.
- Вставка при коллизии — `synchronized` на головном узле bin.
- При длине цепочки > 8 → **TreeBin** (красно-чёрное дерево), как в `HashMap` 8+.
- `size()` — **distributed counter** (аналог `LongAdder`): нет глобального lock, каждый поток обновляет свою ячейку, `size()` суммирует все.
- `get()` — полностью **lock-free**: поле `next` в `Node` помечено `volatile`.

Почему это важно: lock-free reads означают, что читатели никогда не блокируются из-за писателей. Это принципиально отличает `ConcurrentHashMap` от `Collections.synchronizedMap()`, где один mutex на весь map — читатель и писатель конкурируют за тот же lock.

### На практике

**`computeIfAbsent(key, f)`** — держит lock на bin пока выполняется функция `f`. Если `f` делает сеть, БД или другой тяжёлый вычисления — блокируешь весь bin для других операций с ключами в том же bin (bucket collision). Для тяжёлых вычислений используй отдельный `ConcurrentHashMap<K, CompletableFuture<V>>` — ставишь future в map сразу, все получатели ждут на future, а не на map lock.

**Итерация** — weakly consistent: не бросает `ConcurrentModificationException` (в отличие от `HashMap`), но не гарантирует видимость изменений, произошедших после начала итерации.

**Атомарные bulk-операции** (Java 8+): `forEach`, `reduce`, `search` — принимают parallelism threshold. При threshold=1 — полный параллелизм через ForkJoinPool.

### Когда модель усложняется

Каверзный случай: `computeIfAbsent` рекурсивно вызывает `computeIfAbsent` на тот же ключ того же map → **deadlock** (поток держит lock на bin и пытается взять его снова). Задокументировано в Javadoc как undefined behavior. Простой паттерн в коде — функция, которая при первом вызове инициализирует значение, а при инициализации обращается к тому же map с тем же ключом.

---

## 4. ThreadPoolExecutor: sizing и отказоустойчивость

### Теория

Пул потоков — не просто «набор потоков». `ThreadPoolExecutor` реализует конкретную логику роста, которую важно понимать, а не просто знать параметры.

Параметры: `corePoolSize`, `maximumPoolSize`, `keepAliveTime`, `workQueue`, `RejectedExecutionHandler`.

**Логика роста** (важна последовательность):
1. Текущих потоков < `corePoolSize` → **создать новый поток немедленно**, даже если есть idle-потоки.
2. Текущих потоков >= `corePoolSize` → **положить задачу в `workQueue`**.
3. `workQueue` полна И потоков < `maximumPoolSize` → **создать extra-поток**.
4. `workQueue` полна И потоков = `maximumPoolSize` → **RejectedExecutionHandler**.

Ловушка: `LinkedBlockingQueue` без ограничения + высокий `maximumPoolSize` = `maximumPoolSize` **никогда не достигается** — задачи уходят в очередь, которая растёт до OOM. Именно так работает `Executors.newFixedThreadPool` — unbounded queue. Всегда задавай bounded queue в продакшн-коде.

**Типы очередей**:
- `ArrayBlockingQueue(n)` — bounded, FIFO, предсказуемый overhead.
- `LinkedBlockingQueue(n)` — bounded, FIFO, чуть выше overhead из-за динамических нод.
- `SynchronousQueue` — нет буфера вообще: задача передаётся напрямую потоку. Если свободного потока нет — либо создаётся новый (если < max), либо reject. Используется в `Executors.newCachedThreadPool`.
- `PriorityBlockingQueue` — с приоритетом, **unbounded** (осторожно!).

**Rejection policies**:
- `AbortPolicy` (default) — бросает `RejectedExecutionException`.
- `CallerRunsPolicy` — выполняет задачу в вызывающем потоке. Это back-pressure: producer автоматически замедляется, потому что занят выполнением задачи. Часто лучший выбор по умолчанию.
- `DiscardOldestPolicy` — выбрасывает старейшую задачу из очереди.
- `DiscardPolicy` — молча выбрасывает новую задачу. Опасно: потери без сигнала.

### На практике

**CPU-bound задачи**: `corePoolSize = maximumPoolSize = N_CPU` (или `N_CPU + 1`). Больше потоков — только context switching overhead.

**I/O-bound задачи**: потоки большую часть времени блокированы на сети/диске. Типичная оценка: `N_CPU * (1 + wait_time / compute_time)`. Если задача 90% времени ждёт ответа — 10 потоков на ядро. Но это эвристика; реальный sizing — нагрузочное тестирование.

**«Решение в лоб» — увеличить maxPoolSize** — частично верно для I/O-bound, но не главное. Лучший подход: разделить пулы по типам задач (CPU-bound и I/O-bound в разные пулы). Смешивание CPU и I/O в одном пуле приводит к тому, что CPU-задачи ждут из-за I/O-задач, занявших все потоки.

Мониторинг: `getActiveCount()`, `getQueue().size()`, `getCompletedTaskCount()` — метрики для Prometheus/Micrometer.

### Когда модель усложняется

В сервисах с переменной нагрузкой (бурсты) fixed pool ведёт себя предсказуемо, но в пиках очередь растёт. Cached pool (SynchronousQueue + unbounded max) отвечает немедленно, но при долгих задачах порождает тысячи потоков → OOM. Компромисс: bounded pool + CallerRunsPolicy + bounded queue достаточного размера для сглаживания бурстов.

---

## 5. ForkJoinPool и work-stealing

### Теория

`ForkJoinPool` — пул для задач типа divide-and-conquer: задача делится на подзадачи (`fork()`), подзадачи выполняются параллельно, результаты объединяются (`join()`). Каждый рабочий поток имеет свою **deque** (double-ended queue) задач.

**Work-stealing**: если поток опустошил свою deque — он берёт задачи из **хвоста** deque другого потока (steals from the tail). Почему с хвоста? Владелец deque берёт задачи с головы (LIFO — локально для рекурсии), а вор берёт с хвоста (FIFO — более крупные старые задачи, меньше конкуренции с владельцем).

`ForkJoinPool.commonPool()` — статический shared пул для `parallel streams` и `CompletableFuture` (если не указан explicit executor). Размер по умолчанию: `Runtime.getRuntime().availableProcessors() - 1`.

### На практике

`parallel streams` удобны для CPU-bound обработки коллекций. Используй когда: данных много, операция вычислительно тяжёлая, нет I/O, нет shared mutable state.

**Решение в лоб — «сделаю parallel stream и будет быстрее»** — не работает для: маленьких коллекций (overhead на fork/join > выигрыша), I/O-операций (блокирует поток commonPool), задач с синхронизацией (теряется параллелизм).

### Когда модель усложняется

**Blocking в ForkJoinPool** — главная ловушка. Если continuation в `CompletableFuture` или задача в parallel stream делает JDBC/HTTP/файловое I/O — поток commonPool блокируется. При исчерпании потоков пула — деградация или deadlock.

Два решения:
1. `ManagedBlocker` — сигнализирует ForkJoinPool что поток собирается заблокироваться; пул создаёт компенсирующий поток.
2. Отдельный `Executor` для I/O-задач — `thenApplyAsync(f, ioExecutor)`.

Второй подход проще и предпочтительнее в типичном сервисном коде.

---

## 6. CompletableFuture: модель исполнения и ловушки

### Теория

`CompletableFuture<T>` — реализация паттерна Future + Promise. `Future` — обещание результата в будущем (можно `get()` чтобы подождать). `Promise` — ты сам можешь завершить future: `complete(value)` или `completeExceptionally(ex)`. Плюс цепочки трансформаций без callback hell.

**Где выполняется continuation** — самый важный вопрос:

- `thenApply(f)` — в потоке, который завершил предыдущий stage. Если предыдущий stage уже завершён к моменту вызова `thenApply` — в **вызывающем** потоке. Это недетерминировано и порождает трудноотлаживаемое поведение.
- `thenApplyAsync(f)` — в `ForkJoinPool.commonPool()`.
- `thenApplyAsync(f, executor)` — в указанном `executor`.

Правило для продакшн-кода: если continuation делает что-то нетривиальное или I/O — всегда `thenApplyAsync(f, executor)`. Явный executor — явный контроль.

**Exception handling**:

```java
cf.exceptionally(ex -> fallback)           // перехват исключения → значение-замена
  .handle((result, ex) -> ...)             // обработка и результата, и исключения
  .whenComplete((result, ex) -> ...)       // side-effect, не меняет результат/исключение
```

Ловушка: если не добавить exception handler — исключение молча проглатывается. `cf.get()` бросает `ExecutionException`, но если никто не вызывает `get()` (fire-and-forget) — исключение теряется без следа. Всегда добавляй `exceptionally` или `handle`, или логируй через `whenComplete`.

**`allOf` / `anyOf`**:
- `CompletableFuture.allOf(cf1, cf2, cf3)` — возвращает `CompletableFuture<Void>`, который завершается когда завершатся все. Ловушка: результаты нужно вытащить отдельно через `cf1.join()`.
- `CompletableFuture.anyOf(...)` — возвращает `CompletableFuture<Object>` (без generic-типа) с результатом первого завершившегося.

### На практике

Типичный паттерн — параллельные вызовы с агрегацией:

```java
CompletableFuture<UserInfo> userFuture = fetchUser(userId, httpExecutor);
CompletableFuture<OrderList> ordersFuture = fetchOrders(userId, httpExecutor);

CompletableFuture.allOf(userFuture, ordersFuture)
    .thenApply(v -> buildResponse(userFuture.join(), ordersFuture.join()))
    .exceptionally(ex -> errorResponse(ex));
```

Здесь два I/O-запроса идут параллельно, агрегация — в потоке commonPool (допустимо, она быстрая).

**Решение в лоб — цепочка `thenApply` без explicit executor** — часто работает, но ломается под нагрузкой: continuations выполняются в потоках commonPool, I/O блокирует их, деградация нарастает незаметно. Правило: I/O в continuation → `thenApplyAsync(..., ioExecutor)`.

### Когда модель усложняется

**Cancellation** в `CompletableFuture` — слабая. `cf.cancel(true)` устанавливает статус cancelled и размечает downstream-futures как cancelled, но не прерывает поток, который реально выполняет работу. Если хочешь настоящую отмену — используй `CompletableFuture` в связке с `ScheduledExecutorService` и явным флагом отмены, или переходи на реактивные фреймворки (Project Reactor, RxJava).

**`CompletableFuture` + virtual threads (Java 21+)**: если поток внутри executor — virtual thread — blocking I/O уже не блокирует OS-поток. Тогда разделение на ioExecutor/cpuExecutor теряет смысл, достаточно одного executor на virtual threads. Это меняет архитектуру пулов, но пока (Java 21–23) — осторожно: некоторые библиотеки (JDBC, часть Netty) ещё не fully-virtual-thread-friendly.

---

## 7. Остальные примитивы j.u.c.

### Теория и практика

**`CountDownLatch`**: инициализируется числом `N`, `countDown()` — декремент, `await()` — блокирует пока не `0`. Одноразовый — нельзя сбросить. Типичное применение: дождаться завершения N параллельных инициализационных задач перед стартом сервера.

**`CyclicBarrier`**: N потоков ждут друг друга на барьере. После того как все N вызвали `await()` — барьер «срабатывает», можно задать `barrierAction` (выполняется одним потоком перед освобождением всех). Многоразовый — автоматически сбрасывается. Типичное применение: итеративные параллельные алгоритмы (фазы симуляции, bulk-обработка порциями).

**`Semaphore`**: `acquire()` / `release()`. Fair/unfair. Типичное применение: ограничить число параллельных соединений к ресурсу поверх пула (например, «не более 10 одновременных запросов к внешнему API»).

**`Phaser` (Java 7+)**: обобщение CountDownLatch + CyclicBarrier с динамическим числом участников (можно регистрировать/дерегистрировать во время работы) и несколькими фазами. Нужен редко; использовать когда CyclicBarrier не хватает гибкости.

**`LongAdder` (Java 8+)** — высокопроизводительный счётчик для сценария «много concurrent writers, редкие reads». Внутри — массив ячеек `Cell[]`, каждый поток пишет в свою ячейку по hash от thread id → нет contention. `sum()` — суммирует все ячейки. При высокой конкуренции `LongAdder` **в разы быстрее** `AtomicLong` (AtomicLong при contention деградирует из-за постоянных CAS-отказов и retry). Используй `LongAdder` для счётчиков метрик, `AtomicLong` — когда нужны `getAndAdd`, `compareAndSet` или точное текущее значение без промежуточных sum().

---

## Каверзные вопросы

**Почему `HashMap` небезопасен при concurrent доступе?**
В Java 7 два потока, одновременно делающие `put` во время resize, могут создать цикл в linked list → `get()` уходит в бесконечный цикл. В Java 8 цикла нет, но data loss и некорректные состояния структуры — есть. В обоих случаях нет happens-before гарантий между потоками.

**Чем `Collections.synchronizedMap()` хуже `ConcurrentHashMap`?**
`synchronizedMap` — один mutex на весь map. Читатель и писатель конкурируют за тот же lock. `ConcurrentHashMap` — per-bin lock для writes + lock-free reads. При конкуренции N потоков разница в throughput — на порядок.

**Что будет, если `computeIfAbsent` рекурсивно вызовет `computeIfAbsent` на тот же ключ?**
Deadlock: текущий поток держит `synchronized` на головном узле bin и пытается взять его снова (lock не reentrant в данном контексте). Задокументировано в Javadoc как undefined behavior.

**Почему `volatile` недостаточно для счётчика?**
`counter++` — это три операции: read, increment, write. Между read и write другой поток может прочитать то же значение, инкрементировать и записать. `volatile` гарантирует видимость, но не атомарность операции «прочитать-изменить-записать». Нужен CAS: `AtomicInteger.incrementAndGet()` или `LongAdder`.

**Почему убрали biased locking в Java 15?**
Biased locking прикрепляет объект к захватившему его потоку — последующие lock/unlock без CAS. Работало отлично для single-threaded use. Но при contention — revocation требовала safe point (стоп-ворлд для всех потоков), что дорого. С современными JIT и low-overhead CAS выгода biased locking перестала оправдывать сложность реализации и стоимость revocation.
