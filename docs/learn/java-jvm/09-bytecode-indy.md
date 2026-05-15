# Bytecode, invokedynamic, MethodHandle, VarHandle

Байткод — промежуточное представление между исходным кодом и машинными инструкциями.
Большинство разработчиков никогда его не читают — и это нормально пока всё работает.
Понимание байткода становится нужным когда надо разобраться почему JIT ведёт себя
неожиданно, как работают lambda под капотом, почему мегаморфный callsite ломает
производительность, и как писать AOP-агенты без рефлексии.

---

## 1. Байткод: стек-машина и формат .class

### Теория

JVM — стековая машина. В отличие от регистровых архитектур (x86, ARM), вычисления
в байткоде работают через операндный стек. Нет «регистров» — есть стек значений и
массив локальных переменных.

**Структура .class файла:**
```
magic (0xCAFEBABE)
minor_version, major_version         ← Java 21 = version 65
constant_pool[]                       ← таблица символьных ссылок
access_flags, this_class, super_class
interfaces[], fields[], methods[]
attributes[]                          ← Code, LineNumberTable, SourceFile, ...
```

Атрибут `Code` содержит байткод метода, max_stack (размер операндного стека),
max_locals (размер массива локальных переменных), таблицу exception handlers.

**Примеры инструкций:**

```
iload_0        — загрузить int из local var 0 на стек
iload_1        — загрузить int из local var 1 на стек
iadd           — снять два int со стека, положить сумму
istore_2       — снять int со стека, сохранить в local var 2
```

```java
// Java:
int c = a + b;

// Bytecode:
iload_0    // a → стек
iload_1    // b → стек
iadd       // [a,b] → [a+b]
istore_2   // a+b → c
```

**Типы данных в байткоде**: `int`, `long`, `float`, `double`, `reference`, `returnAddress`.
Заметно, что нет `byte`, `short`, `char`, `boolean` — в операциях они расширяются до `int`.
Разные префиксы для типов: `i` (int), `l` (long), `f` (float), `d` (double), `a` (reference).

**Четыре инструкции вызова методов:**

| Инструкция | Когда |
|---|---|
| `invokestatic` | статические методы |
| `invokespecial` | конструкторы, private методы, super-вызовы |
| `invokevirtual` | виртуальные методы классов |
| `invokeinterface` | методы интерфейсов |
| `invokedynamic` | динамически привязанные вызовы |

`invokevirtual` и `invokeinterface` требуют vtable lookup — поэтому они медленнее
`invokestatic` и `invokespecial` при отсутствии JIT-оптимизаций.

### На практике

Читать байткод: `javap -c -verbose MyClass.class`. Флаг `-c` показывает байткод,
`-verbose` добавляет константный пул и metadata.

Зачем это нужно на практике: понять почему инлайнинг не работает, что именно JIT видит
в методе, какой overhead создаёт конкретный паттерн кода. Например, `javap` покажет что
`String.format()` создаёт `Object[]` для varargs — это аллокация на каждый вызов.

---

## 2. invokedynamic и bootstrap method

### Теория

`invokedynamic` (Java 7, активно используется с Java 8) — инструкция байткода, чья
семантика не задана статически. При первом выполнении JVM вызывает **bootstrap method**
— специальный метод, который возвращает `CallSite`. `CallSite` содержит `MethodHandle` —
конкретную привязку метода. При последующих вызовах той же `invokedynamic` — используется
уже привязанный `MethodHandle`.

```
invokedynamic #5:0  ← bootstrap method #5, static args #0
                    ↓ (первый вызов)
              Bootstrap Method выполняется
                    ↓
              CallSite с MethodHandle
                    ↓ (все последующие вызовы)
              напрямую вызывает MethodHandle.target
```

Три вида `CallSite`:
- `ConstantCallSite`: target не меняется никогда — самый быстрый, JIT инлайнит
- `MutableCallSite`: target можно поменять, но нужна явная синхронизация через `syncAll`
- `VolatileCallSite`: target volatile, любой поток видит изменения сразу

**Зачем**: `invokedynamic` позволяет языкам на JVM реализовывать динамические вызовы
(Groovy `obj.method()` без знания типа объекта), optional typing, duck typing — без
overhead рефлексии и с возможностью JIT-оптимизации. JIT видит `ConstantCallSite` как
обычный вызов и инлайнит его.

### На практике

**Lambda в Java — это `invokedynamic`**. javac не генерирует анонимный класс для lambda
(как в Java 7 и ранее). Вместо этого:

1. Компилятор создаёт private static метод с телом lambda
2. Генерирует `invokedynamic` вместо `new AnonymousClass()`
3. Bootstrap method (`LambdaMetafactory.metafactory`) создаёт в runtime класс,
   реализующий функциональный интерфейс, делегирующий к статическому методу

Преимущества: не создаётся .class файл на этапе компиляции, JVM может оптимизировать
представление lambda под конкретный JIT-профиль, нет overhead загрузки класса при первом
использовании.

`javap -c -p MyClass.class` покажет `invokedynamic` на месте lambda:
```
invokedynamic #7,0  // InvokeDynamic #0:apply:()Ljava/util/function/Function;
```

---

## 3. MethodHandle: быстрая рефлексия

### Теория

`MethodHandle` (java.lang.invoke, Java 7) — типизированная, напрямую исполняемая ссылка
на метод, конструктор или поле. По семантике — как указатель на функцию в C.

```java
// Получить MethodHandle на метод
MethodHandles.Lookup lookup = MethodHandles.lookup();
MethodHandle mh = lookup.findVirtual(
    String.class,
    "substring",
    MethodType.methodType(String.class, int.class)
);

// Вызвать — возможны несколько форм:
String result = (String) mh.invoke("hello world", 6);  // полиморфный вызов
String result2 = (String) mh.invokeExact("hello world", 6); // строгий тип
```

**Отличие от рефлексии:**
- `Method.invoke()`: упаковывает аргументы в `Object[]`, боксирует примитивы, проверяет
  access control на каждый вызов
- `MethodHandle.invokeExact()`: нет боксинга примитивов, нет `Object[]`, access control
  проверяется один раз при создании. JIT может инлайнить MethodHandle как обычный вызов.

**Адаптеры:** `MethodHandles` предоставляет набор комбинаторов:

```java
// Частичное применение (bind первый аргумент):
MethodHandle bound = mh.bindTo("hello world");
// Теперь bound принимает только int

// Фильтрация аргументов:
MethodHandle filtered = MethodHandles.filterArguments(mh, 0, upperCaseMH);

// Фолдинг (compute arg from existing):
MethodHandle folded = MethodHandles.foldArguments(mh, lengthMH);
```

**MethodType** — неизменяемый дескриптор типов метода (возвращаемый тип + параметры).
Используется для поиска и верификации MethodHandle:

```java
MethodType mt = MethodType.methodType(String.class, int.class, int.class);
// → (int,int)String
```

### На практике

MethodHandle используется в:
- **Serialization frameworks**: Jackson, Kryo — через MethodHandle к полям вместо
  рефлексии в горячем пути
- **IoC containers**: Micronaut генерирует MethodHandle-based injection вместо рефлексии
- **Expression evaluators**: SpEL, OGNL заменили рефлексию на MethodHandle

Производительность: холодный старт медленнее (создание MethodHandle дорого), горячий
путь — сравним с прямым вызовом (JIT инлайнит `ConstantCallSite`). Не создавай
MethodHandle в цикле — кэшируй в `static final`.

---

## 4. VarHandle: замена sun.misc.Unsafe

### Теория

`VarHandle` (Java 9) — аналог MethodHandle для полей переменных, элементов массивов.
Пришёл как публичная замена `sun.misc.Unsafe` для атомарных и volatile операций.

**Режимы доступа** (ordered semantics):
- `get()` / `set()`: обычный read/write, без барьеров
- `getVolatile()` / `setVolatile()`: volatile-семантика (happens-before)
- `getAcquire()` / `setRelease()`: acquire/release семантика (слабее volatile, быстрее)
- `getOpaque()` / `setOpaque()`: только видимость на текущем потоке, нет HB-гарантий
- `compareAndSet()`: CAS-операция

```java
class Counter {
    private volatile int value;
    private static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup()
                .findVarHandle(Counter.class, "value", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public boolean compareAndIncrement(int expected) {
        return VALUE.compareAndSet(this, expected, expected + 1);
    }
}
```

**acquire/release vs volatile**: на x86 acquire read = обычный read (x86 TSO), release
write = обычный write + StoreLoad barrier только для следующего volatile. На ARM —
разница существенная: release не требует `dmb` (full memory fence), только `stlr` (store
release). Это быстрее на слабых архитектурах при одностороннем ordering.

### На практике

`AtomicInteger`, `AtomicReference` внутри реализованы через VarHandle (в JDK 9+).
`LongAdder` — через VarHandle на array cells. Если пишешь lock-free структуру данных —
VarHandle правильный инструмент вместо `Unsafe`.

Когда нужен VarHandle:
- CAS-операции над полями объектов
- Volatile доступ к элементам примитивных массивов
- Acquire/release семантика для lock-free алгоритмов

Не используй VarHandle для обычного volatile поля — `volatile` ключевое слово чище.
VarHandle нужен когда поле нужно читать в разных режимах (иногда volatile, иногда plain)
или операция должна быть атомарной (CAS).

---

## 5. Инструментирование байткода: Java агенты

### Теория

Java Instrumentation API (Java 5) позволяет модифицировать байткод классов во время
загрузки или даже после. Это основа AOP-фреймворков, profiler'ов (async-profiler, JFR),
APM-агентов (Datadog, NewRelic).

**Java agent** — JAR с `Premain-Class` в MANIFEST.MF. Запускается перед `main()`:

```
java -javaagent:my-agent.jar -jar app.jar
```

```java
public class MyAgent {
    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer((loader, className, classBeingRedefined,
                             protectionDomain, classfileBuffer) -> {
            // Получаем байткод, трансформируем, возвращаем новый
            return transformBytes(classfileBuffer);
        });
    }
}
```

**Bytecode manipulation libraries:**
- **ASM**: низкоуровневый, быстрый, visitor-паттерн. Используется внутри Hibernate,
  Spring, Mockito, Groovy
- **Byte Buddy**: высокоуровневый API, DSL для генерации классов. Проще ASM, мощнее
  cglib. Используется в Mockito 2+, Jackson
- **javassist**: работает с исходным кодом, компилирует в runtime. Проще для простых
  случаев, медленнее

### На практике

Типичный use case: перехватить все вызовы методов с аннотацией `@Traced` и добавить
span для distributed tracing.

```java
// Byte Buddy: инструментировать класс
new AgentBuilder.Default()
    .type(isAnnotatedWith(MyService.class))
    .transform((builder, type, classLoader, module, pd) ->
        builder.method(isAnnotatedWith(Traced.class))
               .intercept(MethodDelegation.to(TracingInterceptor.class))
    )
    .installOn(instrumentation);
```

Без агентов то же самое требует AOP-прокси (Spring AOP) или AspectJ compile-time weaving.
Агент — единственный способ перехватить метод в классе, который создаётся не Spring
(например, сторонняя библиотека).

**attach API**: с Java 9+ можно подключить агент к уже работающей JVM без `-javaagent`:
```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("/path/to/agent.jar");
```
Используется для hot-attach profiler'ов (async-profiler attach mode).

---

## Каверзные вопросы к интервью

**Как Java lambda реализована на уровне байткода?**  
javac генерирует `invokedynamic` вместо анонимного класса. Bootstrap method
(`LambdaMetafactory`) при первом вызове создаёт в runtime класс реализующий
функциональный интерфейс. Тело lambda компилируется в private static метод класса.
Преимущества: нет .class файла на диске, JIT может оптимизировать под профиль.

**Чем MethodHandle быстрее рефлексии?**  
`Method.invoke()` паковывает аргументы в `Object[]`, боксирует примитивы, проверяет
access control на каждый вызов. MethodHandle при вызове через `invokeExact()` — без
боксинга, access control проверяется один раз при создании, JIT может инлайнить как
обычный вызов.

**Зачем нужен VarHandle если есть AtomicInteger?**  
`AtomicInteger` — отдельный объект-обёртка. VarHandle позволяет делать atomic операции
прямо над полем объекта без отдельного wrapper. Для lock-free структур данных это важно:
можно CAS поле `next` в узле списка напрямую, без `AtomicReference<Node>` обёртки и
её memory overhead.

**Что такое ConstantCallSite и почему он быстрее MutableCallSite?**  
`ConstantCallSite` содержит `MethodHandle`, который никогда не меняется. JIT видит это
и может инлайнить target метод как если бы это был статический вызов. `MutableCallSite`
может измениться, JIT должен защищаться от этого — target вызывается через indirect
dispatch, инлайнинг невозможен.

**Что происходит с байткодом при загрузке класса java-агентом?**  
`ClassFileTransformer.transform()` получает оригинальный байткод и может вернуть
модифицированный. JVM использует модифицированный байткод для создания `Class<?>`.
Трансформация через ASM/Byte Buddy работает на уровне байткодовых инструкций, не
исходного кода. Ограничение при retransformation: нельзя добавлять/убирать поля и методы.
