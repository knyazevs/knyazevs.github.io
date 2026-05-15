---
test_id: jvm-01-mcq
topic: java-jvm
type: multiple_choice
questions:
  - id: jvm-q1
    text: "Что происходит с объектом если его ссылка доступна только из SoftReference?"
    options:
      - "Собирается сразу при следующем Minor GC"
      - "Собирается только при нехватке памяти (перед OutOfMemoryError)"
      - "Никогда не собирается — SoftReference защищает навсегда"
      - "Собирается при следующем Full GC независимо от памяти"
    answer: "Собирается только при нехватке памяти (перед OutOfMemoryError)"
    remediation: jvm-q1

  - id: jvm-q2
    text: "Какая GC-фаза G1 является Stop-The-World и происходит наиболее часто?"
    options:
      - "Concurrent Mark"
      - "Remark"
      - "Evacuation Pause (Young GC)"
      - "Concurrent Cleanup"
    answer: "Evacuation Pause (Young GC)"
    remediation: jvm-q2

  - id: jvm-q3
    text: "Что такое TLAB и зачем он нужен?"
    options:
      - "Thread-Local Allocation Buffer — каждый поток аллоцирует в своём регионе без синхронизации"
      - "Type Layout Alignment Buffer — буфер для выравнивания объектов в памяти"
      - "Transactional Lock-free Allocation Block — блокировка-свободная аллокация"
      - "Top-Level Abstract Buffer — буфер для крупных объектов (> 1MB)"
    answer: "Thread-Local Allocation Buffer — каждый поток аллоцирует в своём регионе без синхронизации"
    remediation: jvm-q3

  - id: jvm-q4
    text: "Volatile гарантирует атомарность операции counter++?"
    options:
      - "Да — volatile делает операцию атомарной"
      - "Нет — counter++ это три операции (read/increment/write), volatile не делает их атомарными"
      - "Только на 64-bit JVM"
      - "Да, но только для int, не для long"
    answer: "Нет — counter++ это три операции (read/increment/write), volatile не делает их атомарными"
    remediation: jvm-q4

  - id: jvm-q5
    text: "Какая инструкция байткода используется для вызова метода интерфейса?"
    options:
      - "invokevirtual"
      - "invokespecial"
      - "invokeinterface"
      - "invokedynamic"
    answer: "invokeinterface"
    remediation: jvm-q5

  - id: jvm-q6
    text: "Что такое pinning виртуального потока (Project Loom) и когда он возникает?"
    options:
      - "Виртуальный поток прикреплён к конкретному CPU-ядру для cache locality"
      - "Виртуальный поток не может отмонтироваться с carrier thread внутри synchronized блока или JNI-вызова"
      - "Поток заблокирован на IO и ждёт освобождения carrier thread"
      - "Виртуальный поток завершился и ждёт GC для освобождения стека"
    answer: "Виртуальный поток не может отмонтироваться с carrier thread внутри synchronized блока или JNI-вызова"
    remediation: jvm-q6

  - id: jvm-q7
    text: "Почему double-checked locking без volatile сломан на некоторых архитектурах?"
    options:
      - "JVM может переупорядочить запись ссылки и инициализацию объекта — другой поток видит ненулевую ссылку на неинициализированный объект"
      - "synchronized блок не гарантирует видимость изменений на других CPU"
      - "null-check не атомарен на многоядерных системах"
      - "JIT оптимизирует двойную проверку в одну, нарушая логику"
    answer: "JVM может переупорядочить запись ссылки и инициализацию объекта — другой поток видит ненулевую ссылку на неинициализированный объект"
    remediation: jvm-q7

  - id: jvm-q8
    text: "Что такое safepoint-bias и как async-profiler его избегает?"
    options:
      - "Профилировщик видит только методы с аннотацией @Safe — async-profiler игнорирует аннотации"
      - "JVMTI-профилировщики сэмплируют только в safepoints и не видят tight loops; async-profiler использует сигналы для прерывания потока в произвольный момент"
      - "Профилировщик смещён к безопасным методам без исключений"
      - "GC pauses искажают стеки — async-profiler их фильтрует"
    answer: "JVMTI-профилировщики сэмплируют только в safepoints и не видят tight loops; async-profiler использует сигналы для прерывания потока в произвольный момент"
    remediation: jvm-q8

  - id: jvm-q9
    text: "Что происходит с классом в Metaspace когда его ClassLoader собирается GC?"
    options:
      - "Класс остаётся в Metaspace навсегда — это статические данные"
      - "Класс выгружается из Metaspace вместе с ClassLoader'ом"
      - "Класс перемещается в Bootstrap ClassLoader"
      - "Класс перезагружается Application ClassLoader'ом автоматически"
    answer: "Класс выгружается из Metaspace вместе с ClassLoader'ом"
    remediation: jvm-q9

  - id: jvm-q10
    text: "Что происходит при мегаморфном callsite в JIT-компиляторе C2?"
    options:
      - "C2 генерирует switch-dispatch по всем известным типам"
      - "C2 отказывается от девиртуализации и инлайнинга — вызов идёт через vtable"
      - "C2 компилирует отдельную специализацию для каждого типа"
      - "C2 переключает callsite на invokedynamic для динамической привязки"
    answer: "C2 отказывается от девиртуализации и инлайнинга — вызов идёт через vtable"
    remediation: jvm-q10
---

# Тест: JVM internals (multiple choice)

10 вопросов по ключевым темам: память, GC, JMM, virtual threads, bytecode, JIT.

Распределение по главам:
- TLAB, SoftReference, Metaspace → [02-memory.md](../02-memory.md)
- G1 фазы, safepoints → [03-garbage-collection.md](../03-garbage-collection.md)
- volatile, double-checked locking, JMM → [05-jmm.md](../05-jmm.md)
- Loom pinning → [07-loom.md](../07-loom.md)
- invokeinterface, bytecode → [09-bytecode-indy.md](../09-bytecode-indy.md)
- JIT мегаморфный callsite → [04-jit.md](../04-jit.md)
- ClassLoader, Metaspace → [08-classloading-jpms.md](../08-classloading-jpms.md)
- safepoint-bias, async-profiler → [10-performance.md](../10-performance.md)
