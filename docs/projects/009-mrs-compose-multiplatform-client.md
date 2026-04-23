---
type: experience
system-type: multiplatform-client
context: it-company
domain: cross-platform
lifecycle-stage: experimental
author-role: builder
period: "2022–н.в."
team-size: "3–7"
tags: [kotlin, compose-multiplatform, kmp, desktop, mobile, web, sqlite, offline]
---

# МРС Compose Multiplatform Client — Экспериментальный кроссплатформенный клиент

## Контекст системы

Экспериментальный клиент для МРС Платформы на базе
**Compose Multiplatform** — единая кодовая база для всех таргетов:

- Desktop: Windows, macOS, Linux
- iOS
- Android
- Web — с **офлайн-режимом на базе SQLite**

## Роль

Разработка и проектирование клиента.

## Что интересно технически

### Все таргеты из одной кодовой базы

Compose Multiplatform позволяет писать UI-логику один раз
и компилировать под каждую платформу. На момент разработки —
ещё экспериментальная технология, особенно в части web-таргета.

### Офлайн-режим на web

Web-таргет с поддержкой офлайн-работы через SQLite (SQLite WASM) —
нетривиальная задача: синхронизация состояния при восстановлении соединения,
локальное хранилище в браузере.

## Контекст

R&D-проект: исследование границ Kotlin Multiplatform
и применимости Compose для enterprise-клиентов.
Результат — понимание реальных ограничений и возможностей стека
до того, как он стал мейнстримом.
