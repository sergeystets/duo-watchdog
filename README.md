<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.svg">
  <img src="docs/images/logo.svg" alt="Duo Watchdog logo" width="120" height="120">
</picture>

# Duo Watchdog

**Keep an eye on your GitLab Duo credits without leaving the editor.**

A plugin for **IntelliJ IDEA** (and other JetBrains IDEs) and an extension for **VS Code** that track **your own**
GitLab Credits usage for the current billing period, forecast where the month will end, and warn you before you hit your limit.

![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_Platform-2024.3%2B-000000?logo=intellijidea&logoColor=white)
![VS Code](https://img.shields.io/badge/VS_Code-1.74%2B-007ACC?logo=visualstudiocode&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-VS_Code_extension-3178C6?logo=typescript&logoColor=white)
![GitLab](https://img.shields.io/badge/GitLab-Duo-FC6D26?logo=gitlab&logoColor=white)
![Version](https://img.shields.io/badge/version-0.4.0-blue)

</div>

<p align="center">
  <img src="docs/images/dashboard-light.png" alt="Duo Watchdog dashboard, light theme" width="420">
  &nbsp;&nbsp;
  <img src="docs/images/dashboard-dark.png" alt="Duo Watchdog dashboard, dark theme" width="420">
</p>

---

## What's new in 0.4.0

- **Several accounts**, one token each, in IntelliJ IDEA and VS Code. An account is one GitLab user on one GitLab:
  when you add a token, GitLab says whose it is, and a token for an account already listed replaces its token,
  keeping its group and limit. See [Several accounts](#several-accounts).
- **Accounts in Settings** (IntelliJ IDEA): credits left, the token and its expiry date for each, most credits left
  first, with **All / Has credits / Expired** filters and a search. A saved token shows as dots in a locked field, a
  missing one as an empty field. VS Code has the same list in **Duo Watchdog: Accounts**.
- **Add Token** says what OK will do: add an account, replace a token, or add an account whose usage can't be
  tracked (no `ai_features` scope) instead of failing.
- An **account switcher** in the tool window and the dashboard; the status bar follows it, with a dot (a bell in
  VS Code) when another account needs a look, and the tooltip lists every account.
- **Popups** for a token that expires within 3 days and a token GitLab turns down; in IntelliJ IDEA these, the last
  alert threshold, the limit and a block stay on screen until closed.
- **glab and the GitLab Duo CLI** (optional, off by default): an account can hand its token to glab, and so to the
  Duo CLI. See [glab and the GitLab Duo CLI](#glab-and-the-gitlab-duo-cli-optional).
- A **Statistics** tab next to the dashboard: your last twelve months by month and by feature, with what GitLab did
  not bill, for one account or all of them. See [Statistics](#statistics).
- **What's New**: after an update, a window shows what the version brings, with pictures (*Help → What's New in Duo
  Watchdog* in IntelliJ IDEA, **Duo Watchdog: What's New** in VS Code).
- Upgrading from 0.3.0 moves the saved token to its account, with its group, limit and last alert.

## Contents

- [What's new in 0.4.0](#whats-new-in-040)
- [Features](#features)
- [VS Code](#vs-code)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Troubleshooting](#troubleshooting)

## Features

### Status bar meter

The status bar shows a doberman icon, a small meter and `$21.5 / 300`: credits used out of your limit
(GitLab's list price is $1 per credit).

- **Normal:** neutral colors while usage is below your lowest alert threshold.
- **Warning:** amber with a warning icon once usage reaches the lowest threshold.
- **Blocked:** red `Blocked · $300 / 300` when GitLab blocks Duo because you hit the cap.

The tooltip shows the remaining credits, the month-end forecast and when the data was last updated.
Clicking the meter opens the dashboard.

### Dashboard tool window

<img align="right" src="docs/images/dashboard-warning.png" alt="Dashboard in the warning state" width="300">

- A toolbar with **Refresh** and **Settings**, plus `@user · group · Updated HH:mm`
- The headline: credits used, an **Active** / **Blocked** status and the period with the current day
- A usage bar with a dashed forecast marker and ticks at each alert threshold
- Tiles for **Left**, **Forecast**, **Pace** (credits per day) and **Spent today**: four abreast in a wide tool window,
  two by two in a narrow one
- A **daily usage** chart covering the whole month, with the rest of the month shaded, a "today" line and tooltips on the bars
- A **by feature** breakdown (Developer Flow, Code Review Flow, …)
- **Shift+Esc** hides it and **Esc** goes back to the editor, as in the IDE's own tool windows

<br clear="right">

### Statistics

<img align="right" src="vscode/media/whats-new/statistics.png" alt="The Statistics tab" width="300">

The tool window's **Statistics** tab shows the last twelve months of the chosen account, or of **All Accounts** added
up (features merged by name):

- **This month**, **Last month**, the monthly **Average** and the year's total
- A bar a month, split by the five costliest features of the year; the rest is folded into *Other*. Hover a bar for
  the month's figures.
- Every month as a row, newest first, with its costliest feature and what GitLab did not bill
- The features over the year
- **Not billed**: credits GitLab counted as promotional or beta. GitLab's own usage page includes them in its total,
  so it can show more than Duo Watchdog's billed figures.

It reads `creditsUsage` for a date range, which a group member may read about themselves: per day and per feature.
Per project, per model and per session take the group's Owner role. GitLab keeps usage from the subscription's start;
the view asks once when shown and keeps the answer for an hour (**Reload Statistics** asks again).

<br clear="right">

### Several accounts

Duo Watchdog keeps **one token per account**. An account is one GitLab user on one GitLab: when you add a token,
GitLab says whose it is, so the same `@user` on gitlab.com and on your company's GitLab are two accounts. A token for
an account already in the list **replaces** that account's token; its group and limit stay.

- The list in Settings puts the **most credits left first**, accounts without numbers last. **Show: All / Has credits /
  Expired** filters it, and the search box finds an account by user, GitLab or group.
- Every account shows its token's **expiry date**. The token itself is never shown: a saved one shows as dots in a
  locked field, a missing one leaves the field empty with *No token saved*, so you can tell them apart.
- Each account has its own **group path** and **limit**; the alert percents, the forecast alert and the refresh
  interval are shared.
- The tool window shows **one account at a time**: pick it in the switcher next to Refresh and Settings (type to
  filter). The status bar follows it, a **dot** on the meter says another account needs a look, and the tooltip
  lists every account.
- A token GitLab takes but that **cannot read usage** (no `ai_features` scope) is still added, as *not tracked*, so
  glab and the Duo CLI can use it.

### glab and the GitLab Duo CLI (optional)

Each account has two boxes, **off unless you tick them**: *glab* and *the GitLab Duo CLI*. With glab ticked, Duo
Watchdog hands the account's token to [glab](https://docs.gitlab.com/cli/) for its GitLab
(`glab auth login --hostname <host> --stdin`, the token on glab's input) when you click OK or Apply, and again whenever
you replace the token. glab keeps one token per GitLab, so it **follows the account you pick** in the tool window,
among those with the box ticked: picking another one hands glab its token ("glab now signs in to gitlab.com as
@olena"), and the Duo CLI follows, since it signs in through glab.

The [GitLab Duo CLI](https://docs.gitlab.com/user/gitlab_duo_cli/) signs in through glab (glab 1.85.2 and duo 8.68.0 or
later), so ticking it turns glab on too. If the Duo CLI already has a login of its own in `~/.gitlab/storage.json`, it
keeps using that; Duo Watchdog never writes that file.

Where glab is looked for: on the PATH (and in Homebrew on macOS). **On Windows with the project in WSL**, IntelliJ IDEA
uses the glab inside that WSL distribution (`wsl.exe -d <distro> -e bash -lc '…glab auth login …'`, the token on its
input), since that is where Git and glab run; otherwise `glab.exe` on the Windows PATH, then WSL's default
distribution. VS Code needs nothing of the kind: with its WSL remote the extension runs inside WSL. Once glab is
installed, the next refresh hands it the token.

glab uses the token for its API calls with `api`; `write_repository` only matters for `git push` over HTTPS, not SSH.
So a token for everything needs `api`, `write_repository` and `ai_features`. **The Duo CLI needs `api` and
`ai_features`**: with Duo Watchdog's own `read_api` it reports its GitLab Duo access as unavailable, so the settings
say so when the Duo CLI box is ticked and the token lacks one of them. glab keeps the token in the system
keyring when there is one; WSL usually has none, so there it goes to `~/.config/glab-cli/config.yml` as plain text,
as `glab auth status` says.

### Alerts

Balloon notifications with an **Open Duo Watchdog** link, for every account, named under the title. Each one fires
**at most once per billing period**:

| Alert | When it fires |
|---|---|
| Forecast | the month-end forecast goes over your limit (you can turn this one off) |
| Threshold | usage reaches each configured percentage of the limit (default `80, 95`) |
| Limit reached | usage reaches 100% of the limit |
| Blocked | GitLab has blocked Duo because of the cap |

The **last threshold**, the limit and a block stay on screen until you close them, as do two notices about tokens,
each shown once:

| Notice | When it shows |
|---|---|
| Token expires in N days | an account's token expires within 3 days (**Replace Token…**, **Create a New Token in GitLab**) |
| The token no longer works | GitLab turns an account's token down: it expired or was revoked (**Replace Token…**) |

## VS Code

The VS Code extension in [`vscode/`](vscode) is a port of the same features, with the same GitLab query, forecast,
alerts and texts:

| | IntelliJ IDEA | VS Code |
|---|---|---|
| Status bar | Doberman icon, meter, `$21.5 / 300` | `▰▰▱▱▱▱ $21.5 / 300` with a pulse / warning / error icon and VS Code's warning and error backgrounds |
| Dashboard | *Duo Watchdog* tool window | *Duo Watchdog* view in the activity bar (doberman icon) |
| Settings | *Settings → Tools → Duo Watchdog* | VS Code Settings, search for `duoWatchdog` |
| Token storage | IDE Password Safe | VS Code Secret Storage (the OS keychain) |
| Alerts | Balloon notifications | Notifications with an **Open Duo Watchdog** button |

Commands (*Ctrl/Cmd+Shift+P*, type "Duo Watchdog"): **Accounts**, **Add or Replace a Token**, **Create the Token in
GitLab**, **Find My Group**, **Refresh**, **Open Dashboard**, **Open Settings**. Several accounts work as in IntelliJ
IDEA: **Accounts** lists them, most credits left first, with the Has credits and Expired filters in its title bar and
buttons on each account for glab, the Duo CLI, its group path, its limit, its token and removing it.

The extension has no runtime dependencies. Its requests use Node's `https` module, so they go through VS Code's
proxy settings (`http.proxy`) and the system certificates.

## How it works

Duo Watchdog queries GitLab's GraphQL API for
`currentUser.creditsUsage(namespacePath:)`, which returns the spend of the user who owns the token.
You only need to be a **member** of the top-level group; the **Owner** role is not required.

> [!NOTE]
> GitLab marks `creditsUsage` as an **Experiment** (available since GitLab 19.4), so its shape may change.

**Forecast** is a straight run-rate projection to the end of the billing period:

```
forecast = used / elapsed_days × period_days
```

**You set the limit.** GitLab shows a user's cap only to group owners, so the plugin can't read it.
Enter your cap, or a smaller budget of your own.

Requests go through the IDE's proxy and trusted certificates
(*Settings → Appearance & Behavior → System Settings → HTTP Proxy / Server Certificates*).

## Requirements

### Using the plugin

| Component | Minimum version | Notes |
|---|---|---|
| JetBrains IDE | **2024.3** (build `243`) | IntelliJ IDEA Community or Ultimate, or any other JetBrains IDE. There is no upper bound. |
| VS Code | **1.74** | Also VS Code Insiders, VSCodium and Cursor. |
| GitLab | **19.4** | The first version with `currentUser.creditsUsage`. Works on GitLab.com and self-managed. |
| GitLab account | Member of the top-level group | The group must own a GitLab Duo subscription. You don't need the Owner role. |
| Personal access token | Scopes `read_api` + `ai_features` | One per account. For glab and the Duo CLI too: `api`, `write_repository` + `ai_features`. |
| OS | Windows, macOS, Linux | Any OS the editor runs on. |

### Building from source

| Tool | Minimum version | Notes |
|---|---|---|
| JDK (to run Gradle) | **17** | Gradle 9.8 needs Java 17+. Any JDK 17, 21 or 25 works. |
| JDK (to compile) | 21 / 25 | Gradle downloads it for you: 21, or 25 when compiling against IDEA 2026.2+. |
| Gradle | — | No install needed: the wrapper (`gradlew` / `gradlew.bat`) downloads Gradle 9.8.1. |
| IntelliJ IDEA | **2024.3**, installed | The build compiles against your installed IDEA (Community or Ultimate) and never downloads one. |
| Node.js + npm | **20** | VS Code extension only. The packaging tool (`@vscode/vsce` 3) needs Node.js 20+. |
| Internet access | first build | Needed to download Gradle, the compile JDK and small dependencies. They are cached afterwards. |

## Installation

### With the install script (recommended)

Clone the repository and run the script for your OS. It installs Duo Watchdog for the first time, and also updates it later
(run `git pull` first to get the latest version).

```bash
./install.sh                 # macOS / Linux
```

```bat
install.bat                  :: Windows (or double-click it in Explorer)
```

The script is interactive:

1. It finds every **IntelliJ IDEA** (2024.3+) and **VS Code** (also VS Code Insiders, VSCodium and Cursor) on the machine
   and shows, for each, where it is installed, which Duo Watchdog version it has, and whether the IDE is running.
   If it finds no IntelliJ IDEA or no VS Code, it says so.
2. Choose with the **arrow keys** and **Enter**: **Both** (IntelliJ IDEA and VS Code), **IntelliJ IDEA only**,
   **VS Code only**, or **Exit** (`q` and `Esc` exit too). With several of one kind, *Some of them* takes their
   numbers (`1 3`). *An IntelliJ IDEA or VS Code that isn't listed* adds one it didn't find: you type its folder.
   A folder you add is **remembered** and listed again on the next run; *Forget a folder you added* drops it. The list
   is kept in `~/.config/duo-watchdog/folders` (macOS, Linux) or `%APPDATA%\duo-watchdog\folders.txt` (Windows).
   Without a terminal (answers from a pipe) the choices are numbered and it reads a number.
3. It checks that the tools it needs are installed: a JDK 17+ for IntelliJ IDEA, Node.js 20+ for VS Code.
4. It shows the plan (install, update from one version to another, or reinstall) and asks you to confirm. If an
   IntelliJ IDEA it is about to update is running, it asks you to close it (or skip it) now, so nothing interrupts
   the install.
5. It installs, one step a line, with a **progress bar** for the whole install under the step that is running.
   **IntelliJ IDEA**: Gradle builds the plugin against that IDE and puts it into the IDE's plugins folder (replacing an
   older Duo Watchdog); the bar follows Gradle task by task. **VS Code**: it packages the extension once and installs
   it with `code --install-extension`; run *Developer: Reload Window* in open VS Code windows to load the new version.
   The tools' own output goes to a log; a step that fails shows the end of it and where the whole log is.
6. It sums up and offers to start the IntelliJ IDEAs it installed into.

The script greets you with the Duo Watchdog doberman and title from [`docs/images/logo.ascii`](docs/images/logo.ascii),
printed as drawn; edit that file to change it. Any `version x.y.z` in it is replaced with the version being installed.
In a terminal narrower than the drawing (114 columns) it keeps the title on the right of the dog with the subtitle on two
lines (about 80 columns); narrower still, the title goes under the dog, and below the dog's width only the title shows,
so nothing wraps.

```
   (docs/images/logo.ascii: the doberman, with "D U O   W A T C H D O G", the subtitle and
    "Installer, version 0.4.0" beside it)

Looking for IntelliJ IDEA and VS Code...

Found on this computer:

  [1] VS Code 1.130.0
      D:\Tools\Microsoft VS Code
      Duo Watchdog is not installed yet.
      You added this folder; it is remembered until you forget it in the menu below.

  [2] IntelliJ IDEA 2026.2.0.1
      C:\Program Files\JetBrains\IntelliJ IDEA 2026.2
      Duo Watchdog 0.3.0 is installed; 0.4.0 is available.
      It is running now; you'll be asked to close it before installing.

Where should Duo Watchdog go?
  ↑/↓ to move, Enter to choose
  ❯ Both: IntelliJ IDEA 2026.2.0.1 and VS Code 1.130.0
    IntelliJ IDEA only: IntelliJ IDEA 2026.2.0.1
    VS Code only: VS Code 1.130.0
    An IntelliJ IDEA or VS Code that isn't listed (you type its folder)...
    Forget a folder you added...
    Exit without installing

Plan:
  - IntelliJ IDEA 2026.2.0.1: update Duo Watchdog 0.3.0 -> 0.4.0 (you'll be asked to close it first)
  - VS Code 1.130.0: install Duo Watchdog 0.4.0
Continue? [Y/n]

Installing (5 steps)
  ✓ Preparing Gradle (the first run downloads Gradle and a JDK)  0:12
  ⠼ Building and installing the plugin into IntelliJ IDEA 2026.2.0.1 · compileJava  0:21
    ██████████████░░░░░░░░░░░░░░░░░░░░░░░░░░  35%  step 2 of 5
```

You can also pass the folder of an IntelliJ IDEA or VS Code that the script doesn't find on its own (it's remembered too):
`./install.sh "/path/to/IntelliJ IDEA"`, `install.bat "D:\Tools\IntelliJ IDEA 2025.3"` or `install.bat "D:\Tools\Microsoft VS Code"`.
For IntelliJ IDEA that's the folder with `product-info.json`; for VS Code, the folder with `bin\code.cmd` (Windows),
`bin/code` (Linux) or the `.app` (macOS).

### Without the script

**IntelliJ IDEA**, in one command: build the plugin and put it into the plugins folder of the IDE it was compiled against.
Close the IDE first:

```bash
./gradlew installPlugin                                      # the IDEA the build finds
./gradlew installPlugin -PideaPath="/path/to/IntelliJ IDEA"  # a specific one
```

Add `-PpluginsDir=...` if your IDE keeps its plugins somewhere else (`idea.plugins.path` in `idea.properties`).

Or the IDE's own way:

1. [Build the plugin](#building-from-source), or get `duo-watchdog-<version>.zip` from a teammate.
2. In the IDE, open *Settings → Plugins → ⚙ → Install Plugin from Disk…*
3. Select `intellij/build/distributions/duo-watchdog-0.4.0.zip` and restart if the IDE asks you to.

**Compatibility:** IntelliJ IDEA and any other JetBrains IDE from **2024.3** onward. This holds whichever IDE version the plugin was built against.

## Configuration

Open *Settings → Tools → Duo Watchdog*. Nothing is saved, and no token written or handed to glab, until you click
**OK** or **Apply**.

**Accounts.** Click **+** (*Add Token…*), pick the GitLab URL and paste a token. As soon as it is pasted, GitLab says
whose it is, and the dialog says what OK will do:

| Box | Means |
|---|---|
| Green, **A new account** | OK adds it. Find my group fills in its group when GitLab gives it credits in one group; set its limit. |
| Blue, **Already in the list** | OK replaces that account's token. Its group and limit stay. |
| Amber, **usage can't be tracked** | GitLab takes the token, but it lacks `ai_features`. OK adds the account as *not tracked*. |
| Red | GitLab turned the token down, or can't be reached: OK stays off. |

The **Create the token in GitLab** link opens GitLab's new-token page with `read_api` and `ai_features` selected, or with
`api`, `write_repository` and `ai_features` when glab or the Duo CLI box is ticked. Tokens are stored in the IDE's
password safe (the macOS Keychain by default on a Mac), one entry per account, never in plain settings.
**−** removes the chosen account and deletes its token from the password safe; glab keeps any login it has.

Under the list, for the chosen account:

| Setting | What to enter |
|---|---|
| **Group path** | The path of the top-level group that owns the Duo subscription, e.g. `my-company`. Not sure? Click **Find my group**: it asks GitLab, with this account's token, which of its groups gives it Duo credits and fills in its path. Pasting the group's URL also works; the plugin keeps only the path. |
| **Limit (credits)** | This account's limit. Default: `100` |
| **Personal access token** | Dots in a locked field when a token is saved, empty with *No token saved* when not. **Replace token…** opens the add dialog with this account's GitLab filled in. |
| **Also use for** | **glab** and **the GitLab Duo CLI**, off unless ticked: see [glab and the GitLab Duo CLI](#glab-and-the-gitlab-duo-cli-optional). |

Shared by every account:

| Setting | What to enter |
|---|---|
| **Alert at % of limit** | Comma-separated thresholds, e.g. `50, 80, 95`, each of the account's own limit |
| **Alert when the month-end forecast passes the limit** | Turns the forecast alert on or off |
| **Refresh every … minutes** | Polling interval. Default: `15`. GitLab's data can lag a few minutes behind actual use. |

The box under the token field shows the token's state:

| Box | Means |
|---|---|
| Green, ✓ **Token saved** | It says when the token was saved (`Saved on Oct 1, 14:38 EEST`) and, as GitLab reports it, when it expires and its scopes. |
| Amber | No token for this account, the token expires within a week, it lacks a scope, or its usage is not tracked. |
| Red | GitLab turns the saved token down (expired or revoked), or **Check connection** failed, with GitLab's reason. |

**Check connection** asks GitLab whose the account's token is (a new one not saved yet, or else the saved one), when
it expires, and for the group's credits, without saving anything.

Upgrading from 0.3.0: on its first refresh Duo Watchdog asks GitLab whose your saved token is and moves it, with your
group, limit and last alert, to that account.

### VS Code

1. Run **Duo Watchdog: Create the Token in GitLab** to open GitLab's new-token page with the scopes selected. Then run
   **Duo Watchdog: Add or Replace a Token** and paste the token. GitLab says whose it is: a new account, or a
   replacement for an account already listed (its group and limit stay). For a new account it finds the group and
   asks for the limit, then asks whether to use the token for glab and the Duo CLI (No unless you say Yes).
2. **Duo Watchdog: Accounts** picks the account the dashboard and the status bar show, and changes its group path
   and limit. The dashboard has the same choice in a list at its top.
3. Adjust the shared settings if you need to:

| Setting | Default | Meaning |
|---|---|---|
| `duoWatchdog.gitlabUrl` | `https://gitlab.com` | The GitLab address a new token starts with |
| `duoWatchdog.alertPercents` | `"80, 95"` | Thresholds in percent of each account's limit, comma-separated |
| `duoWatchdog.forecastAlert` | `true` | Alert when the month-end forecast passes the limit |
| `duoWatchdog.refreshMinutes` | `15` | Polling interval in minutes |

`duoWatchdog.groupPath` and `duoWatchdog.limit` are deprecated: each account has its own now. They are read once,
when the token saved by 0.3.0 moves to its account. Tokens are kept in VS Code's secret storage, one per account.

## Building from source

**Requirements:** JDK 17+ to run Gradle. Gradle downloads the compile JDK itself: 21, or 25 when building
against IDEA 2026.2 or later. The plugin's classes target Java 21 in both cases.

The repository root is a Gradle build with three modules: the IntelliJ plugin (`:intellij`, `:intellij:core`) and the
VS Code extension (`:vscode`, which runs its npm build). Open the root folder in IntelliJ IDEA and run Gradle from it:

```bash
./gradlew test            # all tests: the plugin's core and the VS Code extension
./gradlew buildPlugin     # → intellij/build/distributions/duo-watchdog-0.4.0.zip
./gradlew runIde          # launch a sandbox IDE with the plugin installed
```

### Which IDE it compiles against

The build compiles against the IntelliJ IDEA **already installed** on your machine and never downloads one. It looks in the usual places
(`/Applications`, `~/Applications`, `C:/Program Files/JetBrains`, `%LOCALAPPDATA%/Programs`, `/opt`, and Toolbox on Linux)
and logs which IDE it picked. If it can't find yours, or picks the wrong one, give it the path yourself.

#### Find your IntelliJ IDEA folder

You need the IDEA **install folder**: the one that contains `product-info.json` (on macOS, the `.app` bundle itself).
It is not `bin`, and not `idea64.exe` / `idea.sh`.

**Windows (PowerShell):** list every IntelliJ install in the standard and Toolbox locations:

```powershell
Get-ChildItem "C:\Program Files\JetBrains", "$env:LOCALAPPDATA\Programs", "$env:LOCALAPPDATA\JetBrains\Toolbox\apps" `
  -Filter product-info.json -Recurse -Depth 3 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty DirectoryName
# e.g. C:\Program Files\JetBrains\IntelliJ IDEA 2025.3
```

Or use the Start menu: right-click **IntelliJ IDEA** → *More* → *Open file location*. If that opens a shortcut, right-click it →
*Open file location* again. You end up in the IDE's `bin` folder. The folder you need is **its parent**.

**macOS:**

```bash
mdfind "kMDItemCFBundleIdentifier == 'com.jetbrains.intellij*'"
# e.g. /Applications/IntelliJ IDEA.app
```

**Linux:**

```bash
dirname "$(dirname "$(readlink -f "$(command -v idea || command -v idea.sh)")")"
# or look in the usual places:
ls -d /opt/idea* ~/.local/share/JetBrains/Toolbox/apps/*/ 2>/dev/null
```

**JetBrains Toolbox (any OS):** click **⋮** next to IntelliJ IDEA → *Settings* → *Configuration*. The *Install location* there is
the path you need. *Show in Explorer / Finder* in the same menu opens the folder.

#### Pass the path to the build

To choose the IDE yourself:

```bash
./gradlew buildPlugin -PideaPath="/path/to/IntelliJ IDEA.app"
# or
export IDEA_HOME="/path/to/IntelliJ IDEA.app"
```

If no IDEA is found, or `-PideaPath` points to a folder without `product-info.json`, the build stops with an error
that says so. Install IntelliJ IDEA 2024.3 or later, or pass the right path.

### Step by step on Windows

Run these commands in **PowerShell** (Windows 10 or 11). Each step says what to do when you already have that tool.

**1. Install Git, a JDK and Node.js** (skip the ones you already have: `git --version`, `java -version` with Java 17 or later,
`node --version` with Node.js 20 or later). The JDK is for IntelliJ IDEA, Node.js for VS Code; skip what you don't need.

```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id OpenJS.NodeJS.LTS -e
```

Then **close the terminal and open a new one** so it picks up the new `PATH`. Check:

```powershell
git --version
java -version      # must print 17 or later
node --version     # must print v20 or later
```

> [!TIP]
> If `java -version` shows an old Java (8 or 11) first, point Gradle at the new JDK for this session:
> `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.<version>-hotspot"` (use the folder name you actually have:
> `dir "C:\Program Files\Eclipse Adoptium"`).

**2. Clone the repository:**

```powershell
cd $HOME\IdeaProjects     # or any folder you like
git clone https://github.com/sergeystets/duo-watchdog.git
cd duo-watchdog
```

**3. Run the installer** and answer its questions (see [With the install script](#with-the-install-script-recommended)):

```powershell
.\install.bat
```

That's all. Steps 4 and 5 are for building by hand instead.

**4. Build the plugin by hand:**

```powershell
.\gradlew.bat buildPlugin
```

The build finds IntelliJ IDEA in `C:\Program Files\JetBrains` or `%LOCALAPPDATA%\Programs` (Toolbox) on its own.
If IDEA is installed somewhere else, [find its folder](#find-your-intellij-idea-folder) and pass it. Keep the quotes,
because the path contains spaces:

```powershell
.\gradlew.bat buildPlugin "-PideaPath=D:\Tools\IntelliJ IDEA 2025.3"
```

**5. Find the result:**

```powershell
dir intellij\build\distributions    # → duo-watchdog-0.4.0.zip
```

Then follow [Installation](#installation): *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick
`intellij\build\distributions\duo-watchdog-0.4.0.zip`.

**Optional:** run the tests or try the plugin in a sandbox IDE:

```powershell
.\gradlew.bat test
.\gradlew.bat runIde
```

<details>
<summary>Using the classic Command Prompt (<code>cmd.exe</code>) instead of PowerShell</summary>

```bat
cd %USERPROFILE%\IdeaProjects
git clone https://github.com/sergeystets/duo-watchdog.git
cd duo-watchdog

rem Only if IDEA is not in a standard location (no quotes around the value in "set"):
set IDEA_HOME=D:\Tools\IntelliJ IDEA 2025.3

gradlew.bat buildPlugin
dir intellij\build\distributions
```
</details>

<details>
<summary>Windows troubleshooting</summary>

- **`'java' is not recognized` / `JAVA_HOME is set to an invalid directory`**: open a new terminal after installing the JDK, or set
  `$env:JAVA_HOME` to the JDK folder (the one that contains `bin\java.exe`).
- **Gradle says it requires JVM 17 or later**: `java -version` is older than 17. Install a newer JDK as in step 1.
- **`git clone` asks for credentials**: the repository is private. Sign in with your GitHub account in the window that Git
  Credential Manager opens, or run `winget install --id GitHub.cli -e`, then `gh auth login`.
- **`no installed IntelliJ IDEA found`**: IDEA is installed somewhere the build doesn't look. Pass `-PideaPath=...` as in step 3.
- **`no IntelliJ IDEA at ...`**: the path is wrong. Pass the IDEA install folder itself (the one with `bin` and `product-info.json`), not `bin\idea64.exe`.
- **`running scripts is disabled on this system`**: run `install.bat`, not `install.ps1` directly. The `.bat` starts PowerShell
  with `-ExecutionPolicy Bypass` for that one run.
- **The script can't find VS Code**: it looks for `code` on the `PATH` and in the default install folders. Choose *An IntelliJ IDEA or VS Code
  that isn't listed* and type the VS Code folder (e.g. `D:\Tools\Microsoft VS Code`), or reinstall VS Code with *Add to PATH* checked.
- **Corporate proxy**: Gradle doesn't use the IDE's proxy settings. Add your proxy to `%USERPROFILE%\.gradle\gradle.properties`:
  ```properties
  systemProp.https.proxyHost=proxy.example.com
  systemProp.https.proxyPort=8080
  ```
</details>

## Project structure

```
duo-watchdog/
├── settings.gradle.kts       Root Gradle build: the modules :intellij, :intellij:core and :vscode
├── gradlew, gradle/          Gradle wrapper (run everything from the root)
├── intellij/                 IntelliJ plugin, module :intellij (Java)
│   ├── core/                 Module :intellij:core: plain Java, no IntelliJ dependencies
│   │   ├── src/main/…/core/  GraphQL client, billing period & forecast, alert rules, account rules, texts
│   │   ├── src/main/…/ui/    Pure Swing components: dashboard, meter, chart, feature bars, status chip
│   │   └── src/test/         JUnit 5 tests; also paints the UI headless to core/build/ui-previews/*.png
│   ├── src/                  Thin IntelliJ layer: settings and accounts, add-token dialog, refresh service,
│   │                         status bar widget, tool window, notifications, glab hand-off
│   └── build.gradle.kts      Finds the installed IDEA; buildPlugin, installPlugin, runIde
├── vscode/                   VS Code extension, module :vscode (TypeScript, npm, no runtime dependencies)
│   ├── src/core/             Port of intellij/core: GraphQL client, period & forecast, alerts, texts
│   ├── src/                  Extension layer: status bar, webview dashboard, settings, secret storage
│   ├── src/test/             node:test tests mirroring the Java tests
│   └── media/                Dashboard script and styles, icons
├── docs/images/              Logo and screenshots for this README
├── install.sh                Interactive installer for macOS / Linux
└── install.bat               Interactive installer for Windows (runs install.ps1)
```

The UI components get their colors from the plugin as light/dark pairs, so they follow the IDE theme.
Running `./gradlew test` also renders every dashboard state to PNG (in `intellij/core/build/ui-previews/`). You can review UI changes from those images without starting an IDE.

The VS Code extension builds with npm (Node.js 20+), from the root through Gradle or in `vscode/` directly:

```bash
./gradlew :vscode:test               # 88 tests, no VS Code needed          (npm ci && npm test)
./gradlew :vscode:packageExtension   # → vscode/duo-watchdog-0.4.0.vsix      (npm run package)
./gradlew :vscode:installExtension   # into VS Code; -PcodePath=codium for another CLI
```

The IntelliJ module needs an installed IntelliJ IDEA; without one only its own tasks fail, so the VS Code tasks
still run.

## Troubleshooting

**Diagnostics to send.** *Tools → Duo Watchdog: Copy Diagnostics* (also in the tool window's ⋮ menu) copies a report:
the IDE, OS and plugin version, where the tokens are kept, the open projects and their WSL distribution, where glab
was looked for and found (or what WSL printed), each account with its group, scopes, expiry, glab state and last
refresh, and the last 100 events. It holds no tokens: anything that looks like a GitLab token is cut to its prefix.
The same events go to the IDE's log (*Help → Show Log*, lines with `#duowatchdog`). In VS Code: **Duo Watchdog: Copy
Diagnostics** and **Duo Watchdog: Show Log** (the *Duo Watchdog* output channel).

<details>
<summary><b>"resource … does not exist or you don't have permission"</b></summary>

The **Group path** field most likely holds the group's display *name* instead of its *path* (the part after
`gitlab.com/` in the URL). Click **Find my group** in the settings and the plugin will find the right path.
</details>

<details>
<summary><b>WSL / Linux: <code>/bin/sh^M: bad interpreter</code>, or every file shows as changed right after cloning</b></summary>

Your git has `core.autocrlf=true` and checked the files out with Windows line endings (CRLF). Linux can't run
`gradlew` like that. Switch the setting off and check the files out again (this discards local edits in that clone):

```bash
git config --global core.autocrlf input
git rm -r -q --cached .
git reset --hard
git status        # nothing to commit, working tree clean
```
</details>

<details>
<summary><b>The numbers are behind what I just used</b></summary>

GitLab's usage data can lag a few minutes behind actual use. Click **Refresh** in the tool window, or use
*Tools → Refresh Duo Credits*, to fetch again now.
</details>

<details>
<summary><b>Requests time out behind a corporate proxy</b></summary>

Duo Watchdog uses the IDE's own HTTP proxy and certificate settings. Check
*Settings → Appearance & Behavior → System Settings → HTTP Proxy* and *Server Certificates*.
</details>

<details>
<summary><b>The plugin doesn't show my cap</b></summary>

That's expected: GitLab shows a user's cap only to group owners. Enter your limit in the settings.
</details>
