# Changelog

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
