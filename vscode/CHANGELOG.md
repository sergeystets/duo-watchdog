# Changelog

## Unreleased

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
- **glab and the GitLab Duo CLI:** an account can hand its token to glab for its GitLab (`glab auth login --stdin`,
  the token on standard input), one account per GitLab. The Duo CLI signs in through glab. Both are off unless you
  turn them on.
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
