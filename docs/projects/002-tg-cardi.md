---
type: project
status: active
domain: telegram-mini-app
period: "2024–н.в."
tags: [telegram, mini-app, bun, hono, sqlite, react, typescript]
---

# TG Cardi — Цифровые визитки в Telegram

## Что это

Telegram Mini App для создания и публикации цифровых визиток.

Пользователь создаёт визитку через бота [@tgcardi_bot](https://t.me/tgcardi_bot),
редактирует её в Mini App и делится ссылкой.

## Ключевая идея

Визитка собирается из готовых блоков — пользователь не верстает,
а компонует страницу как конструктор:

- Текст
- Изображение
- Видео
- Календарь
- Таймер
- Магазин
- Таблицы
- и другие секции

## Стек

| Слой | Технология |
|------|-----------|
| Runtime | Bun |
| Backend | Hono |
| БД | SQLite (bun:sqlite, WAL-режим) |
| Frontend | React 18, Redux Toolkit, MUI, TypeScript |
| Бот | Telegram webhook (встроен в сервер) |

## Архитектурные решения

**Bun + Hono** — минимальный overhead, быстрый старт, единый рантайм
для сервера и бота без Node.js.

**SQLite в WAL-режиме** — достаточно для нагрузки Mini App,
нет необходимости в отдельном сервере БД, простое резервное копирование.

**Бот встроен в сервер** — webhook обрабатывается тем же Hono-приложением,
что и API. Один процесс, один деплой.
