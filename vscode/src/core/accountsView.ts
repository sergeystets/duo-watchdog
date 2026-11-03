import {
    Account, AccountState, accountGroup, accountHost, attention, daysLeft, glabCanWrite, inDays, missingUsageScopes, sortStates,
    usageReadable,
} from './accounts';
import { Alert } from './alert';
import { DashboardModel, dashboardModel } from './dashboard';
import { IsoDate, time } from './dates';
import { Snapshot, Snapshots } from './report';
import { day, number, statusText, tooltipLines, usedLine } from './view';

/** The words the extension shows about accounts: the list, the tooltip, the dashboard's notices and the popups. */

/** Where a saved token is listed: it is never shown. */
export const MASK = '•••••••';

/** "@dana.k · gitlab.com · acme". */
export function accountLabel(account: Account): string {
    return ['@' + account.username, accountHost(account.gitlabUrl), accountGroup(account)]
        .filter(part => part !== '').join(' · ');
}

/** "@dana.k on gitlab.com · acme". */
export function accountName(account: Account): string {
    const group = accountGroup(account);
    return '@' + account.username + ' on ' + accountHost(account.gitlabUrl) + (group === '' ? '' : ' · ' + group);
}

/** "Oct 20" this year, "Feb 1, 2027" another. */
export function shortDate(date: IsoDate, today: IsoDate): string {
    return date.slice(0, 4) === today.slice(0, 4) ? day(date) : longDate(date);
}

/** "Oct 27, 2026". */
export function longDate(date: IsoDate): string {
    return day(date) + ', ' + Number(date.slice(0, 4));
}

/** "151.4 left · ••••••• · expires Feb 1, 2027 · glab uses it": the line under an account in the Accounts list. */
export function accountDetail(state: AccountState, today: IsoDate): string {
    const { account, problem } = state;
    if (problem === 'noToken') {
        return 'no token';
    }
    const parts: string[] = [];
    const report = problem == null ? state.snapshot.report : null;
    if (report != null) {
        parts.push(number(Math.max(account.limit - report.creditsUsed, 0)) + ' left');
    } else if (problem === 'notTracked') {
        parts.push('not tracked');
    } else if (problem === 'noGroup') {
        parts.push('no group');
    }
    parts.push(MASK);
    const days = daysLeft(account, today);
    if (days != null && days <= 0) {
        parts.push('expired ' + shortDate(account.expiresAt!, today));
    } else if (problem === 'rejected') {
        parts.push('turned down by GitLab');
    } else if (days != null && days <= 7) {
        parts.push('expires ' + inDays(days));
    } else if (account.expiresAt != null) {
        parts.push('expires ' + shortDate(account.expiresAt, today));
    }
    if (account.duo) {
        parts.push('glab and the Duo CLI use it');
    } else if (account.glab) {
        parts.push('glab uses it');
    }
    return parts.join(' · ');
}

/** The status bar's text for the account it shows, or for the setup while there is none. */
export function accountStatusText(state: AccountState | null, setup: Snapshot): string {
    if (state == null) {
        return statusText(setup, 0);
    }
    switch (state.problem) {
        case 'noToken':
        case 'noGroup':
            return 'Duo: set up';
        case 'rejected':
            return 'Duo: error';
        case 'notTracked':
            return 'Duo: not tracked';
        default:
            return statusText(state.snapshot, state.account.limit);
    }
}

/** Why an account has no numbers, in the tooltip; null when it has them or is loading. */
export function problemLine(state: AccountState, today: IsoDate): string | null {
    const { account } = state;
    switch (state.problem) {
        case 'noToken':
            return 'No token';
        case 'rejected': {
            const days = daysLeft(account, today);
            return days != null && days <= 0
                ? 'Token expired on ' + day(account.expiresAt!) + ': credits unknown'
                : 'GitLab turns the token down: credits unknown';
        }
        case 'notTracked': {
            const missing = missingUsageScopes(account.scopes);
            return missing.length > 0
                ? 'Not tracked: the token has no ' + missing.join(' or ') + ' scope'
                : 'Not tracked: GitLab does not show this account\'s Duo usage to the token';
        }
        case 'noGroup':
            return 'No group path yet: run Duo Watchdog: Find My Group';
        default:
            return null;
    }
}

export interface TooltipBlock {
    /** "@dana.k · gitlab.com · acme", with " (in the status bar)" for the account it shows. */
    title: string;
    lines: string[];
}

export interface TooltipModel {
    /** "2 other accounts need attention", or null. */
    attention: string | null;
    blocks: TooltipBlock[];
    footer: string[];
}

/**
 * The status bar's tooltip: every account, most credits left first, the one in the status bar in full and the others
 * in a line or two: used and left, or why there are no numbers, and what needs the user.
 */
export function tooltipModel(states: AccountState[], selectedId: string | null, setup: Snapshot, percents: number[],
                             today: IsoDate): TooltipModel {
    if (states.length === 0) {
        const lines = setup.status === 'notConfigured'
            ? ['Add a token with Duo Watchdog: Add or Replace a Token. GitLab says whose it is.']
            : tooltipLines(setup, 0, today);
        return { attention: null, blocks: [], footer: [...lines, 'Click for details.'] };
    }
    const blocks: TooltipBlock[] = [];
    let others = 0;
    let updated: Date | null = null;
    for (const state of sortStates(states)) {
        const selected = state.id === selectedId;
        const problem = problemLine(state, today);
        const why = attention(state, percents, today);
        const lines: string[] = [];
        if (problem != null) {
            lines.push(problem);
        } else if (selected) {
            lines.push(...tooltipLines(state.snapshot, state.account.limit, today));
            updated = state.snapshot.fetchedAt;
        } else if (state.snapshot.report != null) {
            lines.push(usedLine(state.snapshot.report.creditsUsed, state.account.limit));
        } else {
            lines.push(...tooltipLines(state.snapshot, state.account.limit, today));
        }
        const days = daysLeft(state.account, today);
        if (days != null && days > 0 && days <= 7) {
            lines.push('Token expires ' + inDays(days) + ', on ' + day(state.account.expiresAt!));
        }
        if (!selected && why != null) {
            others++;
        }
        blocks.push({ title: accountLabel(state.account) + (selected ? ' (in the status bar)' : ''), lines });
    }
    const footer = [(updated != null ? 'Updated ' + time(updated) + ' · ' : '') + "$ = credits at GitLab's $1 list price",
        'Click for details.'];
    return {
        attention: others === 0 ? null : others + (others === 1 ? ' other account needs' : ' other accounts need') + ' attention',
        blocks,
        footer,
    };
}

/** Whether an account other than the one in the status bar needs the user: the status bar then shows a bell. */
export function othersNeedAttention(states: AccountState[], selectedId: string | null, percents: number[],
                                    today: IsoDate): boolean {
    return states.some(s => s.id !== selectedId && attention(s, percents, today) != null);
}

/** What the dashboard says about an account without numbers, with the button that fixes it. */
export interface Notice {
    title: string;
    text: string;
    tone: 'warning' | 'error';
    /** What the button does: replaceToken or findGroup. */
    action: 'replaceToken' | 'findGroup';
    actionLabel: string;
}

export function notice(state: AccountState, today: IsoDate): Notice | null {
    const { account } = state;
    const who = '@' + account.username + ' on ' + accountHost(account.gitlabUrl);
    switch (state.problem) {
        case 'noToken':
            return {
                title: 'No token for this account', tone: 'warning', action: 'replaceToken', actionLabel: 'Add Token…',
                text: "VS Code's secret storage has no token for " + who + '.',
            };
        case 'rejected':
            return {
                title: 'The saved token no longer works', tone: 'error', action: 'replaceToken', actionLabel: 'Replace Token…',
                text: 'GitLab turns down the token of ' + who + ': ' + whyRejected(account, today)
                    + '. Replace it to see this account again.',
            };
        case 'notTracked': {
            const missing = missingUsageScopes(account.scopes);
            const why = missing.length > 0
                ? 'The token of ' + who + ' has no ' + missing.join(' or ') + ' scope, so GitLab does not show its Duo usage.'
                : 'GitLab does not show the Duo usage of ' + who + ' to its token (HTTP 403). It needs the read_api and ai_features scopes.';
            return {
                title: 'Usage is not tracked for this account', tone: 'warning', action: 'replaceToken',
                actionLabel: 'Replace Token…', text: why + (account.glab ? ' glab still uses it.' : ''),
            };
        }
        case 'noGroup':
            return {
                title: 'No group for this account', tone: 'warning', action: 'findGroup', actionLabel: 'Find My Group',
                text: 'Find my group asks GitLab which of the groups of ' + who + ' gives it Duo credits.',
            };
        default:
            return null;
    }
}

/** "it expired on Oct 20, 2026", or "it is mistyped, expired or revoked" when its expiry has not come. */
function whyRejected(account: Account, today: IsoDate): string {
    const days = daysLeft(account, today);
    return days != null && days <= 0 ? 'it expired on ' + longDate(account.expiresAt!) : 'it is mistyped, expired or revoked';
}

export interface AccountOption {
    id: string;
    label: string;
    selected: boolean;
}

export interface AccountsDashboardModel extends DashboardModel {
    /** The account picker at the top, most credits left first. */
    accounts: AccountOption[];
    notice: Notice | null;
}

/** The dashboard for the account it shows, with the account picker; the setup while there is no account. */
export function accountsDashboard(states: AccountState[], selectedId: string | null, setup: Snapshot,
                                  percents: number[], today: IsoDate): AccountsDashboardModel {
    const sorted = sortStates(states);
    const selected = sorted.find(s => s.id === selectedId) ?? sorted[0] ?? null;
    const accounts = sorted.map(s => ({ id: s.id, label: accountLabel(s.account), selected: s === selected }));
    if (selected == null) {
        return { ...dashboardModel(setup, 0, percents, today, ''), accounts, notice: null };
    }
    const n = notice(selected, today);
    const model = dashboardModel(n != null ? Snapshots.notConfigured() : selected.snapshot, selected.account.limit, percents,
        today, accountGroup(selected.account));
    return { ...model, notConfigured: null, loading: n == null && model.loading, accounts, notice: n };
}

/** The text of a per-period alert, naming the account it is about. */
export function alertText(alert: Alert, account: Account): string {
    return alert.title + ' for ' + accountName(account) + '. ' + alert.message;
}

/** The popup for a token that expires within three days. */
export function expiryText(account: Account, today: IsoDate): string {
    const days = daysLeft(account, today) ?? 0;
    return 'Duo Watchdog: the token of ' + accountName(account) + ' expires ' + inDays(days) + ', on '
        + longDate(account.expiresAt!) + ". After that, this account's credits are no longer tracked.";
}

/** The popup for a token GitLab turns down. */
export function rejectedText(account: Account, today: IsoDate): string {
    return 'Duo Watchdog: the token of ' + accountName(account) + ' no longer works. GitLab turns it down: '
        + whyRejected(account, today) + ". This account's credits are not tracked until you replace it.";
}

/** "Expires on Mar 3, 2027 · Scopes: read_api, ai_features". */
export function tokenFacts(expiresAt: IsoDate | null, scopes: string[] | null): string {
    const parts: string[] = [];
    if (expiresAt != null) {
        parts.push('Expires on ' + longDate(expiresAt));
    }
    if (scopes != null) {
        parts.push('Scopes: ' + (scopes.length === 0 ? 'none' : scopes.join(', ')));
    }
    return parts.join(' · ');
}

export interface AddPlan {
    kind: 'new' | 'replace' | 'untracked';
    message: string;
    detail: string;
    button: string;
}

/**
 * What adding a token will do, said before it is done: a new account, the token of an account already listed
 * replaced (its group and limit stay), or a new account whose usage cannot be tracked.
 */
export function addPlan(existing: Account | undefined, gitlabUrl: string, username: string, expiresAt: IsoDate | null,
                        scopes: string[] | null): AddPlan {
    const who = '@' + username + ' on ' + accountHost(gitlabUrl);
    const facts = tokenFacts(expiresAt, scopes);
    const readable = usageReadable(scopes);
    const missing = missingUsageScopes(scopes);
    const cannotRead = 'No ' + missing.join(' or ') + " scope: GitLab doesn't show this account's Duo usage to the token.";
    if (existing != null) {
        const group = accountGroup(existing);
        return {
            kind: 'replace',
            message: 'Already in the list: ' + who,
            detail: [
                'OK replaces its token. ' + (group === '' ? 'Its limit, ' : 'Its group, ' + group + ', and its limit, ')
                + number(existing.limit) + ', stay.',
                facts === '' ? '' : 'The new token: ' + facts,
                readable === false ? cannotRead + ' The account will show "not tracked".' : '',
            ].filter(line => line !== '').join('\n\n'),
            button: 'Replace Token',
        };
    }
    if (readable === false) {
        return {
            kind: 'untracked',
            message: "A new account, but its usage can't be tracked: " + who,
            detail: [facts, cannotRead + ' Duo Watchdog keeps it anyway, for glab and the Duo CLI, and marks the account "not tracked".']
                .filter(line => line !== '').join('\n\n'),
            button: 'Add Account',
        };
    }
    return {
        kind: 'new',
        message: 'A new account: ' + who,
        detail: [facts, 'OK adds it to the list. Next: its group and its limit.'].filter(line => line !== '').join('\n\n'),
        button: 'Add Account',
    };
}

/** The note under "Also use it for glab": who glab uses now, and whether it can write with this token. */
export function glabNote(owner: Account | undefined, username: string, gitlabUrl: string,
                         scopes: string[] | null): string {
    const h = accountHost(gitlabUrl);
    const parts = [owner == null
        ? 'glab keeps one token per GitLab. Duo Watchdog has not given glab a token for ' + h + ' yet.'
        : "glab keeps one token per GitLab. It uses @" + owner.username + "'s for " + h + ' now; Yes switches it to @'
        + username + '.'];
    if (glabCanWrite(scopes) === false) {
        parts.push('This token lacks the api and write_repository scopes, so glab could only read with it.');
    }
    return parts.join(' ');
}

/** The note under "Also use it for the GitLab Duo CLI": it signs in through glab. */
export function duoNote(glab: boolean, owner: Account | undefined, username: string, gitlabUrl: string): string {
    const h = accountHost(gitlabUrl);
    if (glab) {
        return 'The Duo CLI signs in through glab, which gets this token for ' + h + '.';
    }
    return 'The Duo CLI signs in through glab, so Yes also signs glab in to ' + h + ' with this token'
        + (owner == null ? '.' : ", instead of @" + owner.username + "'s.") + ' @' + username + ' then uses it for both.';
}

const READ_ONLY = ' glab can only read with this token: it needs the api and write_repository scopes.';

/** The notification after a token is added: what happened, and whether glab got it. */
export function addedText(kind: AddPlan['kind'], account: Account, cli: boolean, canWrite: boolean | null): string {
    const tools = account.duo ? 'glab and the Duo CLI use' : 'glab uses';
    const readOnly = cli && canWrite === false ? READ_ONLY : '';
    if (kind === 'replace') {
        return 'Duo Watchdog: replaced the token of @' + account.username + ' on ' + accountHost(account.gitlabUrl)
            + '. Its group and limit stay' + (cli ? '; ' + tools + ' the new token too.' : '.') + readOnly;
    }
    const added = 'Duo Watchdog: added ' + accountName(account) + '.' + (cli ? ' ' + tools + ' its token too.' : '');
    if (kind === 'untracked') {
        return added + " Its usage is not tracked: GitLab doesn't show it to this token." + readOnly;
    }
    return added + readOnly;
}

/** After glab or the Duo CLI is turned on for an account. */
export function cliOnText(account: Account, cli: 'glab' | 'duo', previous: Account | undefined,
                          canWrite: boolean | null): string {
    const h = accountHost(account.gitlabUrl);
    const instead = previous == null ? '' : ", instead of @" + previous.username + "'s";
    const text = cli === 'glab'
        ? 'Duo Watchdog: glab uses the token of @' + account.username + ' for ' + h + ' now' + instead + '.'
        : 'Duo Watchdog: the GitLab Duo CLI uses the token of @' + account.username + ' for ' + h + ' now, through glab'
        + instead + '.' + (account.glab ? '' : ' This turned on glab for this account too.');
    return text + (canWrite === false ? READ_ONLY : '');
}

/** After glab or the Duo CLI is turned off for an account: nothing is signed out. */
export function cliOffText(account: Account, cli: 'glab' | 'duo'): string {
    const h = accountHost(account.gitlabUrl);
    if (cli === 'glab') {
        return 'Duo Watchdog: the token of @' + account.username + ' is no longer handed to glab'
            + (account.duo ? ' or the Duo CLI' : '') + '. glab stays signed in to ' + h + '.';
    }
    return 'Duo Watchdog: the token of @' + account.username + ' is no longer marked for the GitLab Duo CLI. '
        + 'Nothing is signed out: the Duo CLI keeps using glab\'s login for ' + h + '.';
}
