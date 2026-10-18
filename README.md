# Duo Watchdog

Плагін для IntelliJ IDEA (і будь-якої IDE JetBrains від 2024.3), який стежить за **вашими власними** витратами GitLab Credits (GitLab Duo) за поточний розрахунковий період і попереджає, коли ви наближаєтесь до ліміту.

- **Status bar:** доберман, міні-шкала і `$21.5 / 300` — витрачено / ліміт ($ — кредити за прейскурантною ціною GitLab, $1 за кредит). Від найнижчого порогу алертів — бурштиновий колір і значок попередження, коли GitLab блокує Duo — червоний і `Blocked · $300 / 300`. У підказці: залишок, прогноз на кінець періоду, час оновлення. Клік відкриває вікно з деталями.
- **Tool window «Duo Watchdog»:** у заголовку `@користувач · група`, кнопки Refresh і Settings та час оновлення; велике число витраченого зі статусом Active / Blocked, період і день; шкала з пунктирною позначкою прогнозу і поділками на порогах алертів; плитки Left, Forecast і Pace; стовпчики за кожен день місяця (решта місяця затінена, лінія «today», підказка на стовпчику); розбивка за фічами (Developer Flow, Code Review Flow…).
- **Алерти** (balloon з посиланням Open Duo Watchdog, кожен — один раз за період):
  - прогноз на кінець місяця перевищує ліміт (можна вимкнути);
  - кожен поріг зі списку, за замовчуванням `80, 95` % ліміту;
  - ліміт досягнуто;
  - GitLab заблокував Duo через cap.

## Звідки дані

`currentUser.creditsUsage(namespacePath:)` — витрати власника токена. Достатньо бути членом top-level групи, права Owner не потрібні. У GitLab це поле має статус *Experiment* (з 19.4).

Якщо GitLab відповідає «resource … does not exist or you don't have permission», найчастіше в полі групи стоїть назва групи, а не її шлях з адреси (`gitlab.com/<шлях>`). Кнопка **Find my group** у налаштуваннях знайде шлях сама.

Прогноз — проста екстраполяція на кінець періоду: `used / elapsed_days * period_days`.

**Ліміт задаєте ви.** GitLab показує cap користувача лише власнику групи, тому плагін його не знає. Впишіть свій cap або менший власний бюджет.

## Налаштування

*Settings → Tools → Duo Watchdog*

| Поле | Що вписати |
|---|---|
| GitLab URL | `https://gitlab.com` або адреса вашого GitLab |
| Group path | шлях top-level групи, якій належить підписка Duo, напр. `my-company`. **Find my group** перебирає ваші групи і питає GitLab, у якій є ваші кредити. Можна вставити й адресу групи — плагін залишить лише шлях |
| Personal access token | токен зі scopes **`read_api`** і **`ai_features`**, більше нічого. Посилання *Create the token in GitLab* відкриває сторінку нового токена з обома scopes. Зберігається в Password Safe IDE, а не в налаштуваннях |
| Limit (credits) | ваш ліміт, за замовчуванням 100 |
| Alert at % of limit | пороги через кому, напр. `50, 80, 95` |
| Alert when the month-end forecast passes the limit | попередження за прогнозом |
| Refresh every … minutes | як часто оновлювати, за замовчуванням 15. Дані GitLab можуть запізнюватися |

Запити йдуть через проксі та довірені сертифікати IDE (*Settings → Appearance & Behavior → System Settings → HTTP Proxy / Server Certificates*).

## Збірка й встановлення

Потрібен JDK 17+ для запуску Gradle. JDK для компіляції Gradle завантажить сам: 21, а для IDEA 2026.2 і новіших — 25 (класи плагіна однаково залишаються Java 21).

Збірка компілює плагін проти IntelliJ IDEA, яка вже встановлена: вона шукає її у стандартних місцях (`/Applications`, `~/Applications`, `C:/Program Files/JetBrains`, `%LOCALAPPDATA%/Programs`, `/opt`, Toolbox на Linux) і пише в лог, яку саме знайшла. Шлях можна вказати явно: `-PideaPath="/шлях/до/IDEA"` або змінна `IDEA_HOME`. Лише якщо IDEA не знайдено, Gradle завантажує IntelliJ IDEA 2024.3 (~1 ГБ, один раз, далі з кешу). Готовий плагін ставиться на будь-яку IDE від 2024.3, хоч би проти якої версії його зібрали.

```bash
./gradlew :core:test      # тести логіки (без IDE)
./gradlew buildPlugin     # → build/distributions/duo-watchdog-0.2.0.zip
./gradlew runIde          # запуск IDE-пісочниці з плагіном
```

Встановлення: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → `build/distributions/duo-watchdog-0.2.0.zip`.

## Структура

- `core/` — чиста Java без залежностей від IntelliJ: GraphQL-клієнт, період і прогноз, правила алертів, тексти. Покрито тестами.
- `core/…/ui/` — компоненти на чистому Swing: панель вікна, шкала, графік, смуги фіч, чип для status bar. Кольори приходять від плагіна парами для світлої і темної теми. `./gradlew :core:test` малює їх без екрана в `core/build/ui-previews/*.png`.
- `src/` — тонкий шар IntelliJ: налаштування, сервіс оновлення, status bar widget, tool window, сповіщення.
