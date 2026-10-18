# Duo Watchdog

Плагін для IntelliJ IDEA (і будь-якої IDE JetBrains від 2024.3), який стежить за **вашими власними** витратами GitLab Credits (GitLab Duo) за поточний розрахунковий період і попереджає, коли ви наближаєтесь до ліміту.

- **Status bar:** `Duo 42.4/100` — витрачено / ліміт. У підказці: залишок, прогноз на кінець періоду, час оновлення. Клік відкриває вікно з деталями.
- **Tool window «Duo Watchdog»:** період і день, витрачено, залишок, прогноз, темп за день і дата, коли за цього темпу буде досягнуто ліміт, розбивка за фічами (Chat, Code Review…), витрати за останні 14 днів.
- **Алерти** (balloon, кожен — один раз за період):
  - прогноз на кінець місяця перевищує ліміт (можна вимкнути);
  - кожен поріг зі списку, за замовчуванням `80, 95` % ліміту;
  - ліміт досягнуто;
  - GitLab заблокував Duo через cap.

## Звідки дані

1. `currentUser.creditsUsage(namespacePath:)` — витрати власника токена. Достатньо бути членом top-level групи, права Owner не потрібні. У GitLab це поле має статус *Experiment* (з 19.4).
2. Якщо GitLab не віддає його, плагін пробує `subscriptionUsage.usersUsage.users(username:)`. Це спрацює лише з токеном власника групи (Owner).

Прогноз — проста екстраполяція на кінець періоду: `used / elapsed_days * period_days`.

**Ліміт задаєте ви.** GitLab показує cap користувача лише власнику групи, тому плагін його не знає. Впишіть свій cap або менший власний бюджет.

## Налаштування

*Settings → Tools → Duo Watchdog*

| Поле | Що вписати |
|---|---|
| GitLab URL | `https://gitlab.com` або адреса вашого GitLab |
| Top-level group | шлях групи, якій належить підписка Duo, напр. `my-company` |
| Personal access token | токен зі scope **`read_api`** (*User Settings → Access tokens*). Зберігається в Password Safe IDE, а не в налаштуваннях |
| Limit (credits) | ваш ліміт, за замовчуванням 100 |
| Alert at % of limit | пороги через кому, напр. `50, 80, 95` |
| Alert when the month-end forecast passes the limit | попередження за прогнозом |
| Refresh every (minutes) | як часто оновлювати, за замовчуванням 15. Дані GitLab можуть запізнюватися |

Запити йдуть через проксі та довірені сертифікати IDE (*Settings → Appearance & Behavior → System Settings → HTTP Proxy / Server Certificates*).

## Збірка й встановлення

Потрібен JDK 17+ для запуску Gradle. JDK 21 для компіляції Gradle завантажить сам. Також потрібен доступ до репозиторіїв JetBrains: перша збірка завантажує IntelliJ IDEA 2024.3 (~1 ГБ).

```bash
./gradlew :core:test      # тести логіки (без IDE)
./gradlew buildPlugin     # → build/distributions/duo-watchdog-0.1.0.zip
./gradlew runIde          # запуск IDE-пісочниці з плагіном
```

Встановлення: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → `build/distributions/duo-watchdog-0.1.0.zip`.

## Структура

- `core/` — чиста Java без залежностей від IntelliJ: GraphQL-клієнт, період і прогноз, правила алертів, тексти й HTML. Покрито тестами.
- `src/` — тонкий шар IntelliJ: налаштування, сервіс оновлення, status bar widget, tool window, сповіщення.
