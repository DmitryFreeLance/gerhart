# Telegram MLM Bot (Java + SQLite)

Бот для продажи уровней автотоваров с механикой ограничений/разблокировки, подтверждением чеков наставником или админом.

## Стек
- Java 17
- telegrambots `6.9.7.1`
- SQLite
- Docker

## Функции
- Регистрация по реферальной ссылке (`/start ref_<tgId>`)
- Inline-кнопки для всех сценариев
- Ограничение 3 приглашения на 1 уровне до покупки 2 уровня
- Покупка следующего уровня с выдачей контактов наставника
- Загрузка чека, подтверждение/отклонение наставником или админом
- Админ-панель: новые оплаты, список участников, статистика
- Платежные данные участника (e-mail + реквизиты)

## Переменные окружения
- `BOT_TOKEN` - токен бота
- `BOT_USERNAME` - username бота без `@`
- `ADMIN_IDS` - список Telegram ID админов через запятую, например `12345,67890`
- `SUPPORT_CONTACT` - контакт поддержки, например `@my_support`
- `MAX_LEVEL` - максимальный уровень (по умолчанию `8`)
- `DB_PATH` - путь до SQLite файла (по умолчанию `data/bot.db`)

## Локальная сборка
```bash
mvn -DskipTests package
```

## Docker
### Build
```bash
docker build -t telegram-mlm-bot .
```

### Run
```bash
docker run -d \
  --name telegram-mlm-bot \
  --restart unless-stopped \
  -e BOT_TOKEN="<TOKEN>" \
  -e BOT_USERNAME="<BOT_USERNAME>" \
  -e ADMIN_IDS="123456789" \
  -e SUPPORT_CONTACT="@support" \
  -e MAX_LEVEL="8" \
  -v "$(pwd)/data:/app/data" \
  telegram-mlm-bot
```

## Главное меню (inline)
- Моя команда
- Пригласить человека
- Мой прогресс
- Купить уровень
- Контакты наставника
- Мои платежные данные
- Новые оплаты
- Поддержка
- Админ-панель (только у админа)
