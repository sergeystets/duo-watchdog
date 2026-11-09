# Changelog

## 0.7.2

- **IntelliJ IDEA offers the update itself**: a newer release shows **Update** on Duo Watchdog's page in
  *Settings → Plugins*, with the versions (`0.7.2 → 0.7.3`), as for a plugin from the Marketplace. Each release
  carries an `updatePlugins.xml` for it, and the plugin adds its releases on GitHub to the IDE's plugin repositories
  (*Settings → Plugins → ⚙ → Manage Plugin Repositories…*); turning **Check for Duo Watchdog updates once a day**
  off takes them out again. Duo Watchdog's own notice and **Update to x.y.z** stay.
- The plugin's page in *Settings → Plugins* says what it does and how to start, with a picture of the dashboard and
  a **Plugin homepage** link.
- No changes to the VS Code extension.

## 0.7.1

- **Installers install the release**: `install.sh`, `install.ps1` and `install.bat` download the ready-made plugin and
  extension of the latest release from GitHub and check them against it, so nothing is built and no JDK or Node.js
  is needed. They also run without a clone, in one line (see the README's Installation); `--from-source` builds
  the clone as before.
- Every release is now built and checked by GitHub Actions before it is published: the tests, JetBrains' Plugin
  Verifier on the oldest and the newest IntelliJ IDEA, the extension in VS Code, and the installers on Linux, macOS
  and Windows.
- A build on Windows keeps `·` as `·`: the Java sources are compiled as UTF-8.

## 0.7.0

- **Updates from the editor**: Duo Watchdog asks GitHub for its latest release a little after VS Code starts and then
  once a day, and says when a newer one is out. **Update** downloads its `.vsix` from GitHub, checks its size and
  SHA-256 against the release, and installs it; **Reload Window** loads it. **Release Notes** opens the release, **Skip
  This Version** says nothing more about that one. The dashboard's toolbar shows **Update to x.y.z** while one is
  known. New commands: **Duo Watchdog: Check for Updates** and **Duo Watchdog: Install the Update**. New setting:
  `duoWatchdog.checkForUpdates` (on).
- The Duo CLI's tool approval reads **Manual** or **Auto** (`--dangerously-skip-permissions`).
- Sessions' **By project** puts each project on a line of its own over its bar, cut from the front so its own name
  shows, with the whole path on hover.

## 0.6.0

- **Duo CLI quick actions on Usage**: two drop-downs, `duo` or `glab duo cli`, and **Ask before tools** or
  **Auto-approve tools** (`--dangerously-skip-permissions`), then **New session**, **Doctor** and your **quick
  commands**. Each runs the CLI in a new terminal in the workspace; Doctor then types `/doctor` at its prompt, and a
  quick command its own command, such as `/ab-testing`. **+ Add command** builds one: the command first, then its
  button's name, made from the command for you to change (`AB Testing`); **Edit…** changes, moves or removes them. A
  command is typed only once the CLI has started a log of its own, so a CLI that did not start never gets it typed
  into the shell. Also commands: **Duo Watchdog: New Duo CLI Session**, **Duo CLI Doctor**, **Run a Duo CLI Quick
  Command…**, **Add a Duo CLI Quick Command…** and **Edit Duo CLI Quick Commands…**. New settings: `duoWatchdog.cli`,
  `duoWatchdog.cliMode`, `duoWatchdog.quickCommands`.

## 0.5.3

- Every time shown says its day and its zone, on this machine's clock: the dashboard's and the tooltip's
  `Updated today 11:45 UTC+2`, and the Duo CLI session's `Started today 10:00 UTC+2, 34m 17s, quiet since today 10:34
  UTC+2`, with `yesterday`, or the date for an older one. The zone is its offset from UTC, as the plugin says it.

## 0.5.2

- The Duo CLI session in the status bar always shows its **≈ $**: a step on the group's default model, on a model the
  log does not name, or on one with no known rate is priced as the model set in `duoWatchdog.groupDefaultModel`
  (Sonnet 4.5, 0.5 credits a call, unless you pick another): `34m 17s · 0 round trips · 2 steps · ≈ $1`. The tooltip
  and Sessions say which model the estimate takes.

## 0.5.1

- The session of the interactive Duo CLI (9.30) counts its **round trips**: one `Running workflow` line per prompt,
  with the `SEND_PROMPT` lines of the headless `duo run` and older CLIs used only in a log without them, never both.
  A reply without a tool call now shows the session too.
- A session on the group's default model (`Resolved user-selected model ref "…" is a "use the feature default"
  sentinel`) says **Group default**, without ≈ credits, since the log does not name the model.

## 0.5.0

- **The Duo CLI session, live:** while `duo` runs a session, a second status bar item shows
  `▰▰▱▱▱▱ 12m 4s · 5 round trips · 42 steps · ≈ $38.2`: its round trips toward the recommended most per session
  (`duoWatchdog.roundTripLimit`, 20; amber from 80%, red at it), how long it has run (ticking while the CLI writes its
  log, stopped once the log is quiet for a minute), the model calls and the ≈ credits, each step at its model's credits
  per call. The tooltip adds the model and when it started; a click opens Sessions, which starts with the same
  session. It is read from the CLI's own log every two seconds: only steps, `SEND_PROMPT` lines (the prompt is not
  read) and the model, no more than the start of each line. Tokens are not shown: GitLab bills per model call.
- **Token rotation:** a week before an account's token expires, GitLab rotates it. The new token lives as long as
  the old one (30 to 365 days); it is saved and handed to glab, and the notice that says so has **Copy the New
  Token**, for wherever else you use it. GitLab revokes the old token at once. Needs the `api` or `self_rotate`
  scope; **Create the Token in GitLab** now asks for `self_rotate` too. Only the focused window rotates, and only a
  token GitLab has just accepted, so two windows don't both rotate the same token. On by default; turn it off with
  `duoWatchdog.rotateTokens`. The **Accounts** list says when each token rotates, or why it won't
  (`expires Dec 4 · rotates Nov 27`).
- Released under the MIT License.

## 0.4.0

- **Statistics:** a **Usage | Statistics** switch at the top of the dashboard. Statistics shows the selected account's
  last 3, 6 or 12 months (3 unless you pick another, remembered): this month so far, last month, the monthly average
  and the total; a stacked bar a month by feature (the five costliest, the rest as Other); every month as a row with
  its top feature and what GitLab did not bill; and each feature's credits over the range. **This account | All
  accounts** adds up every account that reads usage. GitLab is asked a month at a time, at most three at once;
  settled months (two days after their end) are kept on disk and never asked again, so **Reload** asks only for the
  months that can still change.
- **Sessions:** a **Sessions** switch on the dashboard, and **Duo Watchdog: Sessions**. The selected account's Duo
  Agent Platform sessions for today, yesterday, the last 7 or 30 days or two dates of your own: how many, the credits
  in them and outside them, the top project and the credits per project, and one row a session with its estimated
  credits and, when the Duo CLI's logs on this machine count them, the agent's steps. A session's number, gid or
  address finds it (the days switch to its days when needed); **Last session** finds the last one; a row opens its
  details, with how its credits were estimated and **Open in GitLab**. The credits are estimates: each feature's
  credits on a day are split among that day's sessions of it, by agent steps when every one of them has steps
  counted, else equally. Sessions outside the account's group are left out and counted. Only step counts are read
  from the Duo CLI's logs, and they are kept, so they outlast the temporary folder.
- **What's New:** after an update, a notification links to the new **What's New in Duo Watchdog** page; after a fresh
  install, it says how to start. **Duo Watchdog: What's New** shows the page any time.
- **Troubleshooting:** a *Duo Watchdog* log (**Duo Watchdog: Show Log**) with each refresh, the statistics requests,
  the glab lookups, hand-offs and decisions, and the What's New decision; **Duo Watchdog: Copy Diagnostics** puts the
  versions, the platform, where glab is, every account's settings and last refresh, and the last 100 log lines on the
  clipboard. Neither ever holds a token: tokens are masked as `glpat-•••`.
- **Accounts:** one token per account; an account is one GitLab user on one GitLab. GitLab says whose a token is
  when you add it: a token for an account already listed replaces its token, and its group and limit stay. Each
  account has its own group path and limit; alert percents, the forecast alert and the refresh interval are shared.
- **Duo Watchdog: Add or Replace a Token** replaces *Set GitLab Token* (the old command still works). It asks for the
  GitLab address and the token, asks GitLab whose it is, and says what will happen before saving: a new account, a
  replaced token, or a new account whose usage can't be tracked. A new account gets its group from Find my group and
  a limit.
- **Duo Watchdog: Accounts:** every account, most credits left first, with what is left, the masked token
  (`•••••••`) and when it expires. Filters **Has credits** and **Expired**; buttons to use the token for glab or the
  GitLab Duo CLI, set the group path or the limit, replace the token, and remove the account.
- The **dashboard** has an account picker at the top, and says what to fix for an account without numbers: no token,
  a token GitLab turns down, usage not tracked, no group.
- The **status bar** shows the picked account, with a bell when another account needs attention; the tooltip lists
  every account.
- **Alerts** are per account and name it. New one-time popups: a token that expires within 3 days, and a token GitLab
  turns down.
- **glab and the GitLab Duo CLI:** an account can be allowed to hand its token to glab for its GitLab
  (`glab auth login --stdin`, the token on standard input). glab keeps one token per GitLab and follows the account you
  pick, among those allowed: picking an account, adding or replacing a token, allowing glab and each refresh hand glab
  the picked account's token when it doesn't have it (`glab now signs in to gitlab.com as @dana.k.`). A token glab
  turns down is not tried again until it changes; without glab, switching accounts says nothing. The Duo CLI signs
  in through glab. Both are off unless you turn them on, and nothing is ever signed out.
- **Create the Token in GitLab** ticks `api`, `write_repository` and `ai_features` for an account that glab uses, and
  `read_api` and `ai_features` otherwise.
- The token saved by 0.3 becomes the first account, with the old group path, limit and last alert. The
  `duoWatchdog.groupPath` and `duoWatchdog.limit` settings are deprecated; `duoWatchdog.gitlabUrl` is the default
  GitLab for a new token.

## 0.3.0

- **Spent today**: a new tile on the dashboard, and a line in the status bar tooltip.
- The **Forecast** tile shows its date beside the number (`37 by Oct 31`), so the four tiles fit side by side;
  a narrow view shows them two by two.
- The installers (`install.sh`, `install.bat`): choose IntelliJ IDEA, VS Code or both with the arrow keys, and
  follow the install on a progress bar.
