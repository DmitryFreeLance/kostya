# Telegram Age Gate Bot (Java + SQLite)

Бот для подтверждения 18+, выдачи invite-ссылки в закрытую группу, реферальной аналитики и управления администраторами.

## Что реализовано

- `Java` + `telegrambots 6.9.7.1`
- `SQLite` для хранения пользователей, админов, переходов и ссылок
- Только `inline`-кнопки с эмодзи
- `/start` с поддержкой параметров (`/start ref=landing`)
- Защита от повторной выдачи ссылки (по `user_id`)
- Админ-панель с несколькими админами:
- Добавление админа
- Удаление админа
- Изменение `groupId`
- Генерация реферальных ссылок на бота
- Статистика переходов и подтверждений по source
- `Dockerfile` и `pom.xml`

## Переменные окружения

- `BOT_TOKEN` (обязательно)
- `BOT_USERNAME` (обязательно, без `@`)
- `ADMIN_IDS` (опционально, CSV: `12345,67890`)
- `TARGET_GROUP_ID` (опционально, например `-1001234567890`)
- `DB_PATH` (опционально, по умолчанию `./data/bot.db`)

## Локальный запуск

```bash
mvn -DskipTests package
BOT_TOKEN=xxx \
BOT_USERNAME=my_bot \
ADMIN_IDS=123456789 \
TARGET_GROUP_ID=-1001234567890 \
java -jar target/age-gate-bot.jar
```

## Docker запуск

```bash
docker build -t age-gate-bot .
docker run -d --name age-gate-bot \
  -e BOT_TOKEN=xxx \
  -e BOT_USERNAME=my_bot \
  -e ADMIN_IDS=123456789 \
  -e TARGET_GROUP_ID=-1001234567890 \
  -e DB_PATH=/app/data/bot.db \
  -v $(pwd)/data:/app/data \
  age-gate-bot
```

## Важно для invite-ссылок

У бота должны быть права администратора в целевой группе/канале и право создавать пригласительные ссылки.
