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
![Version](https://img.shields.io/badge/version-0.2.1-blue)

</div>

<p align="center">
  <img src="docs/images/dashboard-light.png" alt="Duo Watchdog dashboard, light theme" width="420">
  &nbsp;&nbsp;
  <img src="docs/images/dashboard-dark.png" alt="Duo Watchdog dashboard, dark theme" width="420">
</p>

---

## Contents

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
- Tiles for **Left**, **Forecast** and **Pace** (credits per day)
- A **daily usage** chart covering the whole month, with the rest of the month shaded, a "today" line and tooltips on the bars
- A **by feature** breakdown (Developer Flow, Code Review Flow, …)

<br clear="right">

### Alerts

Balloon notifications with an **Open Duo Watchdog** link. Each one fires **at most once per billing period**:

| Alert | When it fires |
|---|---|
| Forecast | the month-end forecast goes over your limit (you can turn this one off) |
| Threshold | usage reaches each configured percentage of the limit (default `80, 95`) |
| Limit reached | usage reaches 100% of the limit |
| Blocked | GitLab has blocked Duo because of the cap |

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

Commands (*Ctrl/Cmd+Shift+P*, type "Duo Watchdog"): **Set GitLab Token**, **Create the Token in GitLab**, **Find My Group**,
**Refresh**, **Open Dashboard**, **Open Settings**.

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
| Personal access token | Scopes `read_api` + `ai_features` | No other scopes are needed. |
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

1. It finds every **IntelliJ IDEA** (2024.3+) and **VS Code** (also VS Code Insiders, VSCodium and Cursor) on the machine and lists them,
   with the Duo Watchdog version each one already has and whether the IDE is running.
2. You pick one or more by number (`1 2`), `a` for all, or `p` to type the folder of an IntelliJ IDEA it didn't find.
3. It checks that the tools it needs are installed: a JDK 17+ for IntelliJ IDEA, Node.js 20+ for VS Code.
4. It shows what it will install and asks you to confirm.
5. **IntelliJ IDEA**: if the IDE is running, it asks you to close it and waits. Then it builds the plugin against that IDE,
   puts it into the IDE's plugins folder (replacing an older Duo Watchdog) and offers to start the IDE.
6. **VS Code**: it packages the extension once and installs it with `code --install-extension`. Run
   *Developer: Reload Window* in open VS Code windows to load the new version.

```
Duo Watchdog installer: plugin 0.2.1, VS Code extension 0.2.1
Looking for IntelliJ IDEA and VS Code...

Where should Duo Watchdog go?
  1) IntelliJ IDEA 2025.3.1  /Applications/IntelliJ IDEA.app  (Duo Watchdog 0.2.0 installed)  [running]
  2) VS Code 1.130.0  /usr/local/bin/code  (not installed)
  a) all of the above
  p) another IntelliJ IDEA folder
Numbers separated by spaces, a, p or q to quit [1]:
```

You can also pass an IntelliJ IDEA folder the script doesn't find on its own:
`./install.sh "/path/to/IntelliJ IDEA"` or `install.bat "D:\Tools\IntelliJ IDEA 2025.3"`.

### Without the script

**IntelliJ IDEA**, in one command: build the plugin and put it into the plugins folder of the IDE it was compiled against.
Close the IDE first:

```bash
cd intellij
./gradlew installPlugin                                      # the IDEA the build finds
./gradlew installPlugin -PideaPath="/path/to/IntelliJ IDEA"  # a specific one
```

Add `-PpluginsDir=...` if your IDE keeps its plugins somewhere else (`idea.plugins.path` in `idea.properties`).

Or the IDE's own way:

1. [Build the plugin](#building-from-source), or get `duo-watchdog-<version>.zip` from a teammate.
2. In the IDE, open *Settings → Plugins → ⚙ → Install Plugin from Disk…*
3. Select `intellij/build/distributions/duo-watchdog-0.2.1.zip` and restart if the IDE asks you to.

**Compatibility:** IntelliJ IDEA and any other JetBrains IDE from **2024.3** onward. This holds whichever IDE version the plugin was built against.

## Configuration

Open *Settings → Tools → Duo Watchdog*.

| Setting | What to enter |
|---|---|
| **GitLab URL** | `https://gitlab.com` or the address of your self-managed GitLab |
| **Group path** | The path of the top-level group that owns the Duo subscription, e.g. `my-company`. **Find my group** goes through your groups and asks GitLab which one holds your credits. Pasting the group's URL also works; the plugin keeps only the path. |
| **Personal access token** | A token with the **`read_api`** and **`ai_features`** scopes and nothing more. The *Create the token in GitLab* link opens GitLab's new-token page with both scopes already selected. The token is stored in the IDE's **Password Safe**, not in plain settings. |
| **Limit (credits)** | Your limit. Default: `100` |
| **Alert at % of limit** | Comma-separated thresholds, e.g. `50, 80, 95` |
| **Alert when the month-end forecast passes the limit** | Turns the forecast alert on or off |
| **Refresh every … minutes** | Polling interval. Default: `15`. GitLab's data can lag a few minutes behind actual use. |

### VS Code

1. Run **Duo Watchdog: Create the Token in GitLab** to open GitLab's new-token page with both scopes selected. Then run
   **Duo Watchdog: Set GitLab Token** and paste the token.
2. Run **Duo Watchdog: Find My Group**, or set `duoWatchdog.groupPath` yourself.
3. Adjust the rest in Settings if you need to:

| Setting | Default | Meaning |
|---|---|---|
| `duoWatchdog.gitlabUrl` | `https://gitlab.com` | Your GitLab address |
| `duoWatchdog.groupPath` | — | Path of the top-level group that owns the Duo subscription |
| `duoWatchdog.limit` | `100` | Your limit in credits |
| `duoWatchdog.alertPercents` | `"80, 95"` | Thresholds in percent of the limit, comma-separated |
| `duoWatchdog.forecastAlert` | `true` | Alert when the month-end forecast passes the limit |
| `duoWatchdog.refreshMinutes` | `15` | Polling interval in minutes |

## Building from source

**Requirements:** JDK 17+ to run Gradle. Gradle downloads the compile JDK itself: 21, or 25 when building
against IDEA 2026.2 or later. The plugin's classes target Java 21 in both cases.

The IntelliJ plugin lives in [`intellij/`](intellij), a Gradle project of its own; run Gradle there:

```bash
cd intellij
./gradlew :core:test      # unit tests for the core logic
./gradlew buildPlugin     # → intellij/build/distributions/duo-watchdog-0.2.1.zip
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
cd intellij
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
cd intellij
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
dir build\distributions    # → duo-watchdog-0.2.1.zip (in intellij\)
```

Then follow [Installation](#installation): *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick
`intellij\build\distributions\duo-watchdog-0.2.1.zip`.

**Optional:** run the tests or try the plugin in a sandbox IDE (still in `intellij\`):

```powershell
.\gradlew.bat :core:test
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

cd intellij
gradlew.bat buildPlugin
dir build\distributions
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
- **The script can't find VS Code**: it looks for `code` on the `PATH` and in the default install folders. Reinstall VS Code with
  *Add to PATH* checked, or open a new terminal after installing.
- **Corporate proxy**: Gradle doesn't use the IDE's proxy settings. Add your proxy to `%USERPROFILE%\.gradle\gradle.properties`:
  ```properties
  systemProp.https.proxyHost=proxy.example.com
  systemProp.https.proxyPort=8080
  ```
</details>

## Project structure

```
duo-watchdog/
├── intellij/                 IntelliJ plugin (Java, Gradle)
│   ├── core/                 Plain Java, no IntelliJ dependencies
│   │   ├── src/main/…/core/  GraphQL client, billing period & forecast, alert rules, texts
│   │   ├── src/main/…/ui/    Pure Swing components: dashboard, meter, chart, feature bars, status chip
│   │   └── src/test/         JUnit 5 tests; also paints the UI headless to core/build/ui-previews/*.png
│   ├── src/                  Thin IntelliJ layer: settings, refresh service, status bar widget,
│   │                         tool window, notifications
│   └── build.gradle.kts      Finds the installed IDEA; buildPlugin, installPlugin, runIde
├── vscode/                   VS Code extension (TypeScript, npm, no runtime dependencies)
│   ├── src/core/             Port of intellij/core: GraphQL client, period & forecast, alerts, texts
│   ├── src/                  Extension layer: status bar, webview dashboard, settings, secret storage
│   ├── src/test/             node:test tests mirroring the Java tests
│   └── media/                Dashboard script and styles, icons
├── docs/images/              Logo and screenshots for this README
├── install.sh                Interactive installer for macOS / Linux
└── install.bat               Interactive installer for Windows (runs install.ps1)
```

The UI components get their colors from the plugin as light/dark pairs, so they follow the IDE theme.
Running `./gradlew :core:test` in `intellij/` also renders every dashboard state to PNG. You can review UI changes from those images without starting an IDE.

The VS Code extension builds and tests on its own:

```bash
cd vscode
npm ci
npm test          # 32 tests, no VS Code needed
npm run package   # → vscode/duo-watchdog-0.2.1.vsix
code --install-extension duo-watchdog-0.2.1.vsix
```

## Troubleshooting

<details>
<summary><b>"resource … does not exist or you don't have permission"</b></summary>

The **Group path** field most likely holds the group's display *name* instead of its *path* (the part after
`gitlab.com/` in the URL). Click **Find my group** in the settings and the plugin will find the right path.
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
