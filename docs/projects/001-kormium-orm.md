---
type: project
status: active
visibility: public
domain: open-source-library
period: "2023–н.в."
tags: [kotlin, kotlin-multiplatform, orm, sql, postgresql, sqlite, kotlin-native, coroutines, open-source, maven-central]
links:
  github: "https://github.com/kormium/kormium"
  maven: "https://central.sonatype.com/search?q=g%3Aio.github.kormium"
---

# Kormium — ORM и SQL DSL для Kotlin Multiplatform

## Что это

Open-source ORM и типизированный SQL DSL для Kotlin Multiplatform — мой
проект, который я начал в конце 2023 года и развиваю по сей день. Один из
самых сложных и значимых для меня проектов.

Таблицы, сущности, предикаты, транзакции, джойны и миграции описываются один
раз на Kotlin и работают на JVM и Kotlin/Native. Опубликована на Maven Central
(`io.github.kormium`), лицензия Apache 2.0.

```kotlin
object App : Catalog

object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
}

val adults = db.autocommit {
    Users.find {
        where { Users.age gtEq 18 }
        orderBy DESC Users.age
        limit = 50
    }
}
```

## Зачем

В экосистеме Kotlin есть пробел: Exposed — JVM-only, SQLDelight ориентирован
на мобильный SQLite. Мне хотелось Exposed-подобный typed DSL, не привязанный
к JVM — чтобы нативные Kotlin-сервисы и CLI-утилиты могли работать с
PostgreSQL напрямую. Так появился libpq-бэкенд: PostgreSQL на Kotlin/Native
без JVM и JDBC — ниша, в которой у Kormium пока нет конкурентов.

## Что получилось уникального

Возможности, которых я не нашёл у Exposed, SQLDelight, Hibernate и Room:

- **PostgreSQL на Kotlin/Native** — через libpq, без JVM и JDBC
- **Catalog-типобезопасность** — `Catalog` как фантомный тип:
  `Table<App, User>` нельзя использовать внутри `Database<Cache>`, обращение
  «не к той базе» ловится компилятором. Пригождается при нескольких БД:
  основная + кэш, шардинг
- **Реактивные запросы как в Room, но мультиплатформенно** — `kormium-observe`
  превращает запрос в `Flow`, который переэмитится при изменении читаемых таблиц
- **Честная семантика частичных обновлений** — неприсвоенное поле ≠ `null`:
  отсутствующее поле не попадает в `INSERT`/`UPDATE` (работают дефолты БД),
  явный `null` пишется как SQL `NULL`. В большинстве ORM это болевая точка

## Решения, которыми доволен

- **Блокирующий и suspend API — равноправные.** `transaction {}` для
  блокирующего кода, `suspendTransaction {}` для корутин; на JVM suspend-путь
  офлоадится на виртуальные потоки (JDK 21), r2dbc-бэкенд — честный async
- **Миграции, которые не стреляют в ногу:** raw-SQL с checksum-валидацией,
  advisory lock на Postgres против двойного применения при параллельном
  старте инстансов, журнал применения
- **SQL не прячется.** Значения всегда биндятся параметрами, но raw SQL —
  легальный escape hatch. Я считаю, что пользователь ORM должен понимать
  таблицы, индексы и транзакции
- **Производительность по профилям, а не наугад:** типизированный биндинг
  параметров Postgres ускорил чтения в ~1.7–2x (ушёл лишний round-trip
  протокола), parse-кэш в libpq-драйвере, чтение `BigDecimal` без
  bignum-арифметики на горячем пути материализации строк

## Масштаб

10+ модулей: ядро без зависимостей на бэкенд, PostgreSQL (JDBC/HikariCP на
JVM, libpq на Native), SQLite (JVM, Native, Android, iOS), r2dbc, миграции,
реактивный observe, три варианта Ktor-интеграции, BOM.

Вокруг кода — CI с Native-тестами, JMH-бенчмарки против Exposed и Hibernate
прямо в репозитории, документация (production guide, observability,
compatibility policy, cookbook), runnable-сэмплы, changelog по
Keep a Changelog / SemVer.

## Честный статус

Pre-1.0: API стабилизируется, но может меняться между минорными версиями.
Два диалекта (PostgreSQL и SQLite), нет генерации схемы из определений таблиц
(осознанное решение — схема через миграции), на JVM нужен JDK 21+.
Я сам не рекомендую делать Kormium единственным слоем персистентности
критичных продакшен-систем до 1.0 — и пишу об этом в README проекта.

## Что мне дал этот проект

Опыт, который сложно получить в продуктовой разработке: дизайн публичного API
библиотеки и его эволюция без поломки пользователей, системное программирование
(cinterop с libpq и sqlite3), оптимизация по бенчмаркам и профилям вместо
интуиции, релиз-инжиниринг (Maven Central, BOM, SemVer, политика
совместимости) — на длинной дистанции, через все стадии жизни библиотеки.
