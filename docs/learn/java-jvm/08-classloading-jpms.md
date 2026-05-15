# Class Loading, JPMS, jlink

Class loading — первое что JVM делает с классом. До Java 9 модели были простые и дырявые:
classpath — это просто список JAR'ов, любой класс мог достучаться до любого другого. JPMS
(Java Platform Module System, Project Jigsaw) добавил настоящую инкапсуляцию на уровне JVM.
Понимание обеих систем объясняет `NoClassDefFoundError`, `ClassCastException` при передаче
объектов между загрузчиками и странности при деплое в контейнерах.

---

## 1. Иерархия ClassLoader'ов и делегирование

### Теория

JVM никогда не загружает класс «в вакуум» — каждый класс принадлежит конкретному
ClassLoader'у. Это его **пространство имён**: два класса с одним именем, загруженные
разными ClassLoader'ами, — это разные классы с разными объектами `Class<?>`.

Стандартная иерархия HotSpot:

```
Bootstrap ClassLoader       (C++, встроен в JVM)
    ↓ делегирует вверх
Platform ClassLoader        (Java 9+, бывший Extension CL)
    ↓ делегирует вверх
Application ClassLoader     (загружает classpath)
    ↓ делегирует вверх
Custom ClassLoader          (твой код: плагины, hot reload)
```

**Parent delegation model** — ключевой алгоритм:
1. ClassLoader получает запрос загрузить `com.example.Foo`
2. Делегирует родителю: «сначала попробуй ты»
3. Родитель делегирует своему родителю, и так до Bootstrap
4. Bootstrap ищет класс в rt.jar (Java 8) или java.base (Java 9+)
5. Если не нашёл — отдаёт обратно потомку, тот ищет сам

Зачем: `java.lang.String` всегда загружается Bootstrap'ом — нельзя подменить
системный класс загружая свою версию из classpath.

**Bootstrap ClassLoader** загружает ядро JVM: `java.lang.*`, `java.util.*`, `sun.*`.
До Java 9 это `rt.jar`. После — модуль `java.base`.

**Platform ClassLoader** (Java 9+): загружает остальные JDK-модули не входящие в
`java.base` (`java.sql`, `java.xml` и т.д.).

**Application (System) ClassLoader**: читает `-classpath` / `--module-path`. Это «твои»
классы и зависимости.

### На практике

`ClassCastException: com.example.Foo cannot be cast to com.example.Foo` — классический
признак что один класс загружен двумя разными ClassLoader'ами. Несмотря на одинаковое
имя — это разные типы для JVM.

Реальный сценарий: OSGi, Tomcat, или plugin-система где каждый плагин имеет свой
ClassLoader. Объект создан в plugin CL, передан в host-приложение, кастован к «тому же»
типу из другого CL — ClassCastException.

Диагноз: `obj.getClass().getClassLoader()` у обоих объектов покажет разные CL.

---

## 2. Создание кастомного ClassLoader

### Теория

Зачем создавать свой ClassLoader:
- Hot reload: перезагрузить изменённый класс без рестарта JVM
- Изоляция: две версии одной библиотеки в одном процессе (Tomcat делает это для WAR'ов)
- Нестандартный источник байткода: DB, сеть, шифрованный архив

Минимальная реализация — переопределить `findClass`:

```java
class MyLoader extends ClassLoader {
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = loadBytesFromSomewhere(name);
        return defineClass(name, bytes, 0, bytes.length);
    }
}
```

`defineClass` — нативный метод JVM, создаёт `Class<?>` объект из байткода. После этого
класс принадлежит данному ClassLoader'у навсегда — нельзя «разгрузить» отдельный класс.

**Разгрузка класса**: класс выгружается из Metaspace только когда GC собирает его
ClassLoader. Поэтому hot reload реализуется созданием нового ClassLoader с новой версией
класса — старый CL становится unreachable (если нет утечек), GC его собирает, Metaspace
освобождается.

Metaspace OOM при hot reload = утечка ClassLoader'а. Типичные причины: статические поля
со ссылками на старый CL, ThreadLocal не очищенный перед разгрузкой, JNI GlobalReference.

### На практике

**Tomcat** создаёт WebAppClassLoader для каждого WAR-приложения. Каждый WAR имеет свои
зависимости изолированно. При hot-deploy — старый WebAppClassLoader должен стать GC-able.
Metaspace OOM в Tomcat после нескольких hot-deploy — классика: где-то утечка CL.

`-XX:+TraceClassLoading` и `-XX:+TraceClassUnloading` — диагностика загрузки/выгрузки.

---

## 3. JPMS: модули и инкапсуляция

### Теория

До Java 9 у JVM была одна проблема видимости: пакет — единственная граница. Но
`sun.misc.Unsafe`, внутренние API JDK были доступны всем через reflection. Library-авторы
использовали internal JDK API → обновление JDK ломало библиотеки.

**JPMS** (Java 9) добавил модули как первоклассную концепцию. Модуль — это:
- JAR с `module-info.class` в корне
- Объявление что он экспортирует (`exports`)
- Объявление от чего зависит (`requires`)
- Объявление что открыто для reflection (`opens`)

```java
// module-info.java
module com.example.service {
    requires java.net.http;        // зависимость
    requires transitive com.example.api;  // транзитивная зависимость

    exports com.example.service.api;      // публичный API
    // com.example.service.impl не экспортирован → недоступен снаружи

    opens com.example.service.config to spring.core;  // reflection только для spring
}
```

**Типы доступа в JPMS**:
- `exports package` — другие модули могут компилироваться против пакета и вызывать
  public классы/методы
- Без `exports` — пакет недоступен даже если классы public
- `opens package` — разрешает deep reflection (включая private поля) для всех
- `opens package to M` — только для модуля M

**Classpath vs module path**:
- `--class-path`: legacy режим. JAR без `module-info` — unnamed module. Он видит все
  named modules, но named modules не видят его по умолчанию. Для совместимости с
  pre-JPMS кодом.
- `--module-path`: named modules. Строгая проверка зависимостей при запуске.
- Смешанный режим: большинство реальных приложений используют оба.

### На практике

`InaccessibleObjectException: Unable to make ... accessible` — это JPMS-инкапсуляция.
Reflection пытается достучаться до внутреннего API JDK или закрытого пакета. Решения:

1. `--add-opens java.base/java.lang=ALL-UNNAMED` — открыть пакет для unnamed module
   (classpath код). Workaround, не решение.
2. Обновить библиотеку до версии с явной поддержкой JPMS.
3. Не использовать internal API — заменить на public API.

Фреймворки (Spring, Hibernate) используют deep reflection для injection и proxying.
Spring 6+ настроен под JPMS через `spring.core` с нужными `opens`. Hibernate 6+ аналогично.

---

## 4. jlink: кастомный JRE

### Теория

**jlink** (Java 9+) создаёт минимальный custom JRE, содержащий только те модули JDK,
которые нужны приложению. Вместо полного JDK (250+ МБ) — runtime из нужных модулей.

```bash
jlink \
  --module-path $JAVA_HOME/jmods \
  --add-modules java.base,java.net.http,java.logging \
  --output custom-jre \
  --strip-debug \
  --compress=2 \
  --no-header-files \
  --no-man-pages
```

Результат: `custom-jre/bin/java` — полноценная JVM с только нужными модулями.

**Типичные размеры**:
- Полный JDK 21: ~300 МБ
- `java.base` только: ~40 МБ
- Типичный сервис с HTTP-клиентом, logging: ~60–80 МБ

В сочетании с Docker: `FROM scratch` + custom JRE → Docker image 80–100 МБ вместо
500 МБ+ с openjdk base image.

`jdeps` — анализатор зависимостей, показывает какие модули нужны jar-файлу:

```bash
jdeps --module-deps app.jar
```

### На практике

Ограничение jlink: работает только с named modules. Если приложение или зависимости
используют unnamed module (обычный classpath JAR) — jlink их не видит напрямую.
Workaround: `--add-modules ALL-MODULE-PATH` или создать wrapper module.

Реальный паттерн для контейнеров:

```dockerfile
# Stage 1: Build custom JRE
FROM eclipse-temurin:21 AS jre-builder
RUN jlink --add-modules $(java -jar app.jar --list-modules) \
          --output /custom-jre --strip-debug --compress=2

# Stage 2: Minimal runtime
FROM debian:bookworm-slim
COPY --from=jre-builder /custom-jre /opt/jre
COPY app.jar /app.jar
ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app.jar"]
```

**`--release` флаг для cross-compilation**: jlink можно запустить для другой
целевой платформы если указать нужный `jmods` каталог. Полезно для сборки Linux-образа
на Mac в CI.

---

## 5. --add-opens, --add-exports: безопасный переход

### Теория

При миграции существующего кода на Java 9+ часто встречаются `InaccessibleObjectException`
и `WARNING: An illegal reflective access operation has occurred`. Это legacy код,
использующий internal JDK API.

Флаги JVM для совместимости:
- `--add-opens M/package=ALL-UNNAMED`: открыть пакет модуля M для reflection из classpath
- `--add-exports M/package=ALL-UNNAMED`: экспортировать пакет (компиляция и вызов)
- `--add-reads M=N`: объявить что M читает N (зависимость в runtime)

```
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Эти флаги — временное решение. С каждым релизом JDK всё больше internal API
инкапсулируется сильнее. Java 17 строже Java 11. Java 21 строже Java 17. Откладывать
миграцию = накапливать технический долг.

### На практике

Стратегия миграции:
1. Запустить с `--add-opens` для всех нужных пакетов (позволит запустить)
2. Включить `--illegal-access=warn` (Java 11–16) для поиска нарушителей
3. Обновить зависимости до JPMS-совместимых версий
4. Убирать `--add-opens` по одному, убеждаясь что ничего не сломалось

Инструменты: `jdeprscan --release 17 app.jar` показывает использование deprecated API
которые могут исчезнуть в следующей версии.

---

## Каверзные вопросы к интервью

**Почему один и тот же класс может вызвать ClassCastException при приведении?**  
Если два ClassLoader'а загрузили один и тот же класс независимо — JVM считает их разными
типами. `instanceof` вернёт `false`, cast вызовет `ClassCastException`. Диагноз через
`obj.getClass().getClassLoader()`.

**Как работает hot reload в приложениях типа JRebel?**  
Создаётся новый ClassLoader с обновлёнными байткодами. Существующие объекты остаются
на старом CL, но новые экземпляры создаются из нового. JRebel дополнительно использует
bytecode instrumentation для переадресации вызовов методов к новой версии класса.

**Что такое unnamed module в JPMS?**  
JAR без `module-info.class` на classpath становится unnamed module. Он видит все named
modules (`requires` не нужен). Named modules unnamed module не видят напрямую, если не
используют `--add-reads` или пакет не открыт. Unnamed module — совместимость с pre-Java 9
кодом.

**Почему нельзя разгрузить отдельный класс?**  
Класс выгружается только вместе со своим ClassLoader'ом. Нельзя сделать отдельный класс
GC-able, оставив CL alive — другие классы этого CL ссылаются на него через константный
пул. Единственный способ «заменить» класс — создать новый ClassLoader.

**Чем jlink отличается от fat JAR?**  
fat JAR (uber JAR) — все классы в одном JAR, но требует полного JRE/JDK на машине.
jlink создаёт custom minimal JRE — включает только нужные модули JDK. Итоговый артефакт:
директория с java-бинарником, никакого внешнего JRE не нужно. Меньший размер и меньшая
attack surface.
