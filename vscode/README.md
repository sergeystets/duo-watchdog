# Duo Watchdog for VS Code

Keep an eye on **your own** GitLab Credits (GitLab Duo) for the current billing period without leaving the editor,
for each of your GitLab accounts. This is the VS Code version of the Duo Watchdog IntelliJ plugin. It uses the same
GitLab query, forecast and alerts.

## Features

- **Accounts:** one token per account. An account is one GitLab user on one GitLab, so `@dana.k` on gitlab.com and
  `@dana.k` on a self-managed GitLab are two accounts. When you add a token, GitLab says whose it is: a token for an
  account already listed replaces that account's token, and its group and limit stay. Each account has its own group
  and limit; the alert percents, the forecast alert and the refresh interval are shared.
- **Status bar:** `▰▰▱▱▱▱ $21.5 / 300` for the account you picked, the credits used out of its limit ($1 per credit
  at GitLab's list price). It turns amber at your lowest alert threshold, and red with `Blocked · $300 / 300` when
  GitLab blocks Duo or `Duo: error` when GitLab turns the token down. A bell (`$(bell-dot)`) shows when another account
  needs attention: usage at the lowest alert percent, Duo blocked, a token expired, turned down or expiring within a
  week, or no token. The tooltip lists every account, most credits left first, and says which one needs you.
  Click it to open the dashboard.
- **Dashboard** (the doberman in the activity bar):
  - an **Account** picker at the top, most credits left first; it switches the status bar too
  - credits used with an **Active** / **Blocked** badge
  - a usage meter with the forecast marker and your alert thresholds
  - **Left**, **Forecast**, **Pace** and **Spent today** tiles
  - a daily chart for the month: hover a bar to see that day's credits
  - credits by feature
  - for an account without numbers, what is wrong and a button to fix it: no token, a token GitLab turns down,
    usage not tracked, no group yet
  - **Usage | Statistics** at the top: Usage is this billing period, Statistics the last twelve months
    (see [Statistics](#statistics))
- **Alerts**, per account: a notification when the forecast passes the account's limit, at each threshold, at the
  limit, and when GitLab blocks it, naming the account (`@dana.k on gitlab.com · acme`). Each one shows at most once
  per billing period and account. Also once: a token that expires within 3 days (with **Replace Token** and
  **Create a New Token in GitLab**), and a token GitLab turns down (with **Replace Token**).
- **glab and the GitLab Duo CLI:** an account can hand its token to [glab](https://gitlab.com/gitlab-org/cli) for its
  GitLab. Both are off unless you turn them on.
- **What's New:** after an update, a notification links to what changed; **Duo Watchdog: What's New** shows it any
  time.

## Setup

1. Run **Duo Watchdog: Add or Replace a Token** from the Command Palette. Enter the GitLab address (the
   `duoWatchdog.gitlabUrl` setting is the default) and paste a personal access token. To read usage the token needs
   **`read_api`** and **`ai_features`**; for glab too, **`api`**, **`write_repository`** and **`ai_features`**. The
   buttons in the token box open GitLab's new-token page with those scopes ticked.
2. Duo Watchdog asks GitLab whose the token is and says what will happen: a new account, the token of an account
   already listed replaced (its group and limit stay), or a new account whose usage can't be tracked.
3. For a new account it runs **Find My Group** and asks for the limit (default `100`). Then it offers glab and the
   Duo CLI for that GitLab: answer **Yes** only if you want them.

Tokens are kept in VS Code's secret storage (your OS keychain), one per account, never in `settings.json`, and never
shown: a saved token is listed as `•••••••`.

A token GitLab accepts but that can't read Duo usage (no `ai_features`, or no `read_api` / `api`) is still added,
with a warning, for glab and the Duo CLI. The account shows **not tracked**.

## Accounts

Run **Duo Watchdog: Accounts** (or the person icon on the dashboard). It lists **Add or Replace a Token…** and then
every account, most credits left first (accounts without numbers last), with what is left, the masked token and when
it expires:

```
$(check) @dana.k        gitlab.com · acme
         151.4 left · ••••••• · expires Feb 1, 2027
         @dana.k        gitlab.acme.dev · platform
         9.6 left · ••••••• · expires in 3 days
         @dana-contractor  gitlab.com · northwind
         ••••••• · expired Oct 20
```

- Pick an account to show it in the status bar and the dashboard. Type to search by user, GitLab or group.
- The buttons in the title bar filter the list: **Has credits** (credits left above 0) and **Expired** (GitLab turned
  the token down, or its expiry day has come). Click again to show all.
- The buttons on each account: **Use for glab**, **Use for the GitLab Duo CLI**, **Set Group Path**, **Set Limit**,
  **Replace Token** and **Remove Account** (it asks first, and deletes the account's token from the secret storage).

**Find My Group** and **Create the Token in GitLab** work on the account the status bar shows.

## Statistics

The **Statistics** switch at the top of the dashboard shows the last **3**, **6** or **12 months** of the selected
account (3 unless you pick another; the choice is remembered):

- a summary: `This month so far 148.6 · Last month 201 · Monthly average 175 · 3 months 525` (the average is over
  the complete months; in the first month, that month so far)
- a stacked bar a month, oldest left: the range's five costliest features in colours of their own, the rest as
  **Other** in gray. Hover a segment for its month, feature and credits, and the month's total.
- a table, newest month first: **Month**, **Credits**, **Top feature**, **Not billed**
- **By feature, last N months**: each feature's credits over the range
- **Not billed**: the credits GitLab did not bill (promotional, beta), when there are any. GitLab's own usage page
  counts these in its total. GitLab says this from version 19.6.

**This account | All accounts** switches between the selected account and every account with a token that reads
usage, added up, features of the same name as one.

GitLab keeps usage from the subscription's start, so the range can be shorter, and its figures can lag a few hours.
A group member sees usage per day and per feature; per project, model or session takes the group's Owner.

The statistics are read from GitLab when you first show them for an account, a month at a time and at most three
months at once: the current month as the Usage view asks it, each month before it from its first to its last day.
A month is **settled** two days after its last day; settled months are kept on disk, in the extension's storage, and
never asked again (a month before the subscription, which GitLab answers with a server error, is kept as "nothing").
The rest is kept for an hour. **Reload** (or **Refresh**) asks again only for the months that can still change. A
changed group path starts afresh, and removing an account removes its kept months. If they don't load, the view says
why; the Usage view is not affected.

## Sessions

The **Sessions** switch at the top of the dashboard (or **Duo Watchdog: Sessions**) lists the selected account's
GitLab Duo Agent Platform sessions with an estimate of what each cost:

- **Duo CLI, now**: while `duo` runs a session, its **steps** (model calls), **round trips** (prompts sent), **model**
  and **≈ credits** (each step at the model's credits per call), updated within about two seconds of each step. The
  status bar adds `CLI 42 steps ≈ $38.2`, its tooltip the round trips and the model.
- the days: **Today**, **Yesterday**, **Last 7 days** (the default), **Last 30 days** or **Custom** (two dates). Days are
  UTC days, as GitLab counts usage; GitLab lists sessions for about 30 days.
- a text box: a session's number (`10208239`, `#10208239`), its gid or its address finds that session, and switches
  the days to the session's when it is outside them; other text filters by title or project. **Last session** finds
  the one last updated.
- tiles: **Sessions**, the days' **Credits**, the part **≈ in sessions** and **Other use** (Code Suggestions, classic
  Chat and what no session accounts for), then the credits by project
- one row a session, newest first: its title; project · flow · status; ≈ credits; and the agent's steps when the Duo
  CLI's logs here count them. Click a row for its details: project, flow, model, status, when it started and was last
  updated, its steps on this machine, how its credits were estimated day by day
  (`Oct 28 · Agentic Chat 60.0 credits, split by agent steps among 2 sessions → 45.0`), and **Open in GitLab**.

Sessions outside the account's group are left out, with a note saying how many: GitLab bills their credits to another
group.

The credits are **estimates**. GitLab tells a group member credits per day and feature, not per session: each
feature's credits on a day are split among that day's sessions of the feature, by the agent's steps when the Duo
CLI's logs on this machine count every one of them, else equally. The credits come from the same months Statistics
reads and keeps.

The Duo CLI writes its logs to `<temporary folder>/gitlab-duo-cli` (and `/tmp/gitlab-duo-cli` on Linux and macOS;
on Windows, for a workspace folder in WSL, `\\wsl.localhost\<distro>\tmp\gitlab-duo-cli`). The logs hold prompts
and code: Duo Watchdog reads only the time, the session and the request of each step, and keeps only the number of
steps per session and day, so the counts survive the temporary folder being cleaned.

**Duo CLI, now** follows the newest of those logs, every two seconds, reading only what they gained: the steps, the
`SEND_PROMPT` lines as round trips (the prompt is not read) and `Using user-selected model`, no more than the start of
each line. It shows the run in a workspace folder, else the one written to last, until its log has been quiet for 30
minutes. The credits per call come from GitLab's Credits page (checked 2026-11-04), in one table in
`src/core/modelCredits.ts`; for a model not in it, there are steps and round trips but no credits. Tokens are not
shown: GitLab bills per model call, not per token.

## glab and the GitLab Duo CLI

**Use for glab** allows Duo Watchdog to hand that account's token to glab for its GitLab: it runs
`glab auth login --hostname <host> --stdin` and writes the token to glab's standard input, never on its command line.
glab keeps one token per GitLab and **follows the account you pick**, among those allowed: several accounts of a
GitLab may be allowed. For each GitLab, glab gets the token of the account you picked when it is allowed and on that
GitLab; else it keeps the allowed account it has; else it gets the first allowed one in the list. The hand-off runs
when you pick an account (in **Accounts** or on the dashboard), after you add or replace a token, after you allow glab
or the Duo CLI, and after each refresh, and only when glab doesn't have that token yet. On success:
`Duo Watchdog: glab now signs in to gitlab.com as @dana.k.`

Duo Watchdog looks for glab on the `PATH`, then in `/opt/homebrew/bin`, `/usr/local/bin` and
`/home/linuxbrew/.linuxbrew/bin` (`glab.exe` on the `PATH` on Windows). Without glab it says nothing when you switch
accounts; the **Accounts** list says `glab isn't found on this computer`. If glab turns a token down, a warning shows
glab's own words, never the token, and that token is not tried again until it is replaced (or you allow glab again).
Without the `api` scope glab can only read; without `write_repository` it can't `git push` over HTTPS (over SSH it can).

Each account's line in **Accounts** says what glab does with it: `glab signs in to gitlab.com with this token`, or
`glab switches to this token when you pick @dana.k. It has @bob's now.`

**Use for the GitLab Duo CLI** does the same, since the standalone `duo` CLI signs in through glab when glab is
signed in (glab 1.85.2+, duo 8.68.0+). Allowing it allows glab for that account too. If the Duo CLI has a login of its
own in `~/.gitlab/storage.json`, it keeps using that one: Duo Watchdog says so, and you can delete its settings there
to make it use glab's. Duo Watchdog never writes that file and never shows what is in it.

Turning either off only stops the hand-offs: nothing is ever signed out.

## Settings

Open *Settings → Extensions → Duo Watchdog*:

| Setting | Default | What it does |
|---|---|---|
| `duoWatchdog.gitlabUrl` | `https://gitlab.com` | The GitLab a new token is for, unless you type another |
| `duoWatchdog.alertPercents` | `80, 95` | Percentages of each account's limit that trigger an alert |
| `duoWatchdog.forecastAlert` | `true` | Alert when the month-end forecast passes the limit |
| `duoWatchdog.refreshMinutes` | `15` | How often to fetch new data for every account |

Commands (*Ctrl/Cmd+Shift+P*, type "Duo Watchdog"): **Accounts**, **Add or Replace a Token**, **Find My Group**,
**Create the Token in GitLab**, **Refresh**, **Open Dashboard**, **Sessions**, **Open Settings**, **What's New**,
**Copy Diagnostics**, **Show Log**.

`duoWatchdog.groupPath` and `duoWatchdog.limit` are deprecated: each account has its own group and limit now.

### Upgrading from 0.3

The first refresh after the upgrade asks GitLab whose the saved token is and makes it the first account, with the
old GitLab URL, group path, limit and the last alert shown. The token moves to the account's key in the secret
storage. If GitLab can't be reached, the old token stays and the next refresh tries again.

Requests use VS Code's proxy settings (`http.proxy`) and your system's trusted certificates.

## Troubleshooting

**Duo Watchdog: Show Log** opens the *Duo Watchdog* log (also in *View → Output*). It says what each refresh found
for each account, how the statistics were read (the year at once, or month by month and which months answered),
where glab was looked for, and, for each GitLab, which account glab follows and whether its token was handed over,
with glab's exit code and words.

**Duo Watchdog: Copy Diagnostics** puts a report on the clipboard to send to whoever helps you: the extension's and
VS Code's versions, the platform and whether the extension runs remotely (e.g. in WSL), where glab is, where the Duo
CLI's logs were looked for and how many sessions they count steps for, each account's
group, limit, token dates, scopes and glab settings, its last refresh, and the last 100 log lines. It says whether each
account has a token, never what it is.

Neither holds a token: every line is scrubbed of the tokens Duo Watchdog has read, and of anything shaped like a
GitLab token (`glpat-…`, `gldt-…`), which shows as `glpat-•••`.

## Requirements

- VS Code **1.74** or later.
- GitLab **19.4** or later, which added `currentUser.creditsUsage`. GitLab still marks this field as an Experiment.
- Membership in the top-level group. You don't need the Owner role.
- For the glab hand-off: [glab](https://gitlab.com/gitlab-org/cli), and for the Duo CLI, glab 1.85.2+ and duo 8.68.0+.
