# Duo Watchdog for VS Code

Keep an eye on **your own** GitLab Credits (GitLab Duo) for the current billing period without leaving the editor.
This is the VS Code version of the Duo Watchdog IntelliJ plugin. It uses the same GitLab query, forecast and alerts.

## Features

- **Status bar:** `▰▰▱▱▱▱ $21.5 / 300`, the credits used out of your limit ($1 per credit at GitLab's list price).
  It turns amber at your lowest alert threshold, and red with `Blocked · $300 / 300` when GitLab blocks Duo.
  The tooltip shows what's left, the month-end forecast and when the data was updated. Click it to open the dashboard.
- **Dashboard** (the doberman in the activity bar):
  - credits used with an **Active** / **Blocked** badge
  - a usage meter with the forecast marker and your alert thresholds
  - **Left**, **Forecast** and **Pace** tiles
  - a daily chart for the month: hover a bar to see that day's credits
  - credits by feature
- **Alerts:** a notification when the forecast passes your limit, at each threshold, at the limit, and when GitLab
  blocks you. Each one shows at most once per billing period.

## Setup

1. Run **Duo Watchdog: Set GitLab Token** from the Command Palette. The token needs exactly two scopes, **`read_api`**
   and **`ai_features`**. The ↗ button in the input box opens GitLab's new-token page with both scopes already ticked.
   The token is kept in VS Code's secret storage (your OS keychain), not in `settings.json`.
2. Run **Duo Watchdog: Find My Group** to fill in your group's path, or enter it yourself in the settings.
3. Open *Settings → Extensions → Duo Watchdog* to set the rest:

| Setting | Default | What it does |
|---|---|---|
| `duoWatchdog.gitlabUrl` | `https://gitlab.com` | Your GitLab address |
| `duoWatchdog.groupPath` | | The top-level group that owns the Duo subscription, e.g. `my-company` |
| `duoWatchdog.limit` | `100` | Your limit in credits. GitLab shows a user's cap only to group owners, so you set it here. |
| `duoWatchdog.alertPercents` | `80, 95` | Percentages of the limit that trigger an alert |
| `duoWatchdog.forecastAlert` | `true` | Alert when the month-end forecast passes the limit |
| `duoWatchdog.refreshMinutes` | `15` | How often to fetch new data |

Requests use VS Code's proxy settings (`http.proxy`) and your system's trusted certificates.

## Requirements

- VS Code **1.74** or later.
- GitLab **19.4** or later, which added `currentUser.creditsUsage`. GitLab still marks this field as an Experiment.
- Membership in the top-level group. You don't need the Owner role.
