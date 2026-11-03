# Changelog

## 0.4.0

- **Statistics:** a **Usage | Statistics** switch at the top of the dashboard. Statistics shows the selected account's
  last twelve months: this month so far, last month, the monthly average and the year; a stacked bar a month by
  feature (the five costliest, the rest as Other); every month as a row with its top feature and what GitLab did not
  bill; and each feature's credits over the year. **This account | All accounts** adds up every account that reads
  usage. Read when first shown, kept for an hour; **Refresh** reads it again. When GitLab fails on the whole year
  (gitlab.com can answer HTTP 500), it is read a month at a time, back to where GitLab's data ends.
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
