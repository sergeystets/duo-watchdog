import {
    Account, AccountState, accountGroup, accountHost, allowsGlab, attention, canRotate, daysLeft, glabHas, glabHolder,
    glabOwner, glabRefusedIt, duoScopeNote, glabScopeNote, idOf, inDays, missingUsageScopes, ROTATE_DAYS, sortStates,
    usageReadable,
} from './accounts';
import { Alert } from './alert';
import { DashboardModel, dashboardModel } from './dashboard';
import { addDays, clockText, IsoDate, LOCAL_ZONE, ZoneOffset } from './dates';
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

/**
 * "151.4 left · ••••••• · expires Feb 1, 2027 · glab signs in to gitlab.com with this token": the line under an
 * account in the Accounts list; glab is glabCaption() for it.
 */
export function accountDetail(state: AccountState, today: IsoDate, glab: string | null = null, rotate = false): string {
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
    const rotation = rotate && problem !== 'rejected' ? rotationDetail(account, today) : null;
    if (rotation != null) {
        parts.push(rotation);
    }
    if (glab != null) {
        parts.push(glab);
    } else if (allowsGlab(account)) {
        parts.push(account.duo ? 'glab and the Duo CLI allowed' : 'glab allowed');
    }
    if (account.duo && duoScopeNote(account.scopes) != null) {
        parts.push('the Duo CLI needs api and ai_features');
    }
    return parts.join(' · ');
}

/**
 * When the token rotates, as the Accounts list says it while rotating is on: "rotates Nov 27", "rotates at the next
 * refresh", or why it won't; null when it never expires or has expired. CreditsView.rotation in the plugin says the
 * same in sentences.
 */
export function rotationDetail(account: Account, today: IsoDate): string | null {
    const days = daysLeft(account, today);
    if (days == null || days <= 0) {
        return null;
    }
    if (!canRotate(account.scopes)) {
        return "can't rotate itself (no api or self_rotate scope)";
    }
    if (account.rotateRefused === account.tokenSavedAt) {
        return 'GitLab would not rotate it';
    }
    return days <= ROTATE_DAYS ? 'rotates at the next refresh'
        : 'rotates ' + shortDate(addDays(account.expiresAt!, -ROTATE_DAYS), today);
}

/**
 * What glab does with an account's token, for an account glab is allowed for, or null: "glab signs in to
 * gitlab.com with this token", "glab switches to this token when you pick @dana.k. It has @bob's now", glab turned
 * it down, or glab is not on this computer (installed null: not known yet).
 */
export function glabCaption(account: Account, accounts: readonly Account[], picked: string | null,
                            installed: boolean | null): string | null {
    if (!allowsGlab(account)) {
        return null;
    }
    const h = accountHost(account.gitlabUrl);
    if (glabHas(account)) {
        return (account.duo ? 'glab and the Duo CLI sign' : 'glab signs') + ' in to ' + h + ' with this token';
    }
    if (installed === false) {
        return "glab isn't found on this computer";
    }
    if (glabRefusedIt(account)) {
        return 'glab turned this token down';
    }
    const owner = glabOwner(accounts, picked, h);
    if (owner != null && idOf(owner) === idOf(account)) {
        return 'glab gets this token next';
    }
    const holder = glabHolder(accounts, h);
    return 'glab switches to this token when you pick @' + account.username
        + (holder != null && idOf(holder) !== idOf(account) ? ". It has @" + holder.username + "'s now" : '');
}

/** After a hand-off: "Duo Watchdog: glab now signs in to gitlab.com as @dana.k.", and what its scopes stop. */
export function glabNowText(account: Account): string {
    const note = glabScopeNote(account.scopes);
    return 'Duo Watchdog: glab now signs in to ' + accountHost(account.gitlabUrl) + ' as @' + account.username + '.'
        + (note != null ? ' ' + note : '');
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
                             today: IsoDate, zone: ZoneOffset = LOCAL_ZONE): TooltipModel {
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
    const footer = [(updated != null ? 'Updated ' + clockText(updated, today, zone) + ' · ' : '') + "$ = credits at GitLab's $1 list price",
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
                actionLabel: 'Replace Token…',
                text: why + (glabHas(account) ? ' glab still signs in with it.' : allowsGlab(account) ? ' glab may still use it.' : ''),
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

/** The popup after a token rotated: the account as it is now, with the new token's expiry. */
export function rotatedText(account: Account): string {
    return 'Duo Watchdog rotated the token of ' + accountName(account) + ' before it expired. The new one expires on '
        + (account.expiresAt != null ? longDate(account.expiresAt) : 'no date')
        + '. GitLab revoked the old one: if you use it anywhere else, use the new one there.';
}

/** The popup when GitLab would not rotate a token that is about to expire. */
export function rotateRefusedText(account: Account, why: string): string {
    return 'Duo Watchdog could not rotate the token of ' + accountName(account) + ', which expires on '
        + longDate(account.expiresAt!) + '. ' + why + ' Replace it before then.';
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

export const GLAB_FOLLOWS = 'glab keeps one token per GitLab and follows the account you pick, among those allowed here.';

/** The note under "Also use it for glab": glab follows the picked account, and what the token's scopes stop. */
export function glabNote(scopes: string[] | null): string {
    const note = glabScopeNote(scopes);
    return GLAB_FOLLOWS + (note != null ? ' ' + note : '');
}

/** The note under "Also use it for the GitLab Duo CLI": it signs in through glab. */
export function duoNote(glab: boolean, scopes: readonly string[] | null = null): string {
    const note = duoScopeNote(scopes);
    return (glab ? 'The Duo CLI signs in through glab, which follows the account you pick.'
        : 'The Duo CLI signs in through glab, so Yes allows glab for this account too. ' + GLAB_FOLLOWS)
        + (note != null ? ' ' + note : '');
}

/** The notification after a token is added: what happened, and whether glab got it. */
export function addedText(kind: AddPlan['kind'], account: Account, cli: boolean, scopeNote: string | null): string {
    const tools = account.duo ? 'glab and the Duo CLI use' : 'glab uses';
    const readOnly = cli && scopeNote != null ? ' ' + scopeNote : '';
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

/**
 * After glab or the Duo CLI is allowed for an account whose token glab did not just take: what glab does with it
 * (glabCaption), and that the Duo CLI turned glab on.
 */
export function cliOnText(account: Account, cli: 'glab' | 'duo', glabWasOn: boolean, caption: string | null): string {
    const h = accountHost(account.gitlabUrl);
    const allowed = cli === 'glab'
        ? 'glab may use the token of @' + account.username + ' on ' + h + '.'
        : 'the GitLab Duo CLI may use the token of @' + account.username + ' on ' + h + ', through glab.'
        + (glabWasOn ? '' : ' That allows glab too.');
    const duo = cli === 'duo' ? duoScopeNote(account.scopes) : null;
    return 'Duo Watchdog: ' + allowed + (caption != null ? ' ' + caption + '.' : '') + (duo != null ? ' ' + duo : '');
}

/** After glab or the Duo CLI is turned off for an account: nothing is signed out. */
export function cliOffText(account: Account, cli: 'glab' | 'duo'): string {
    const h = accountHost(account.gitlabUrl);
    if (cli === 'glab') {
        return 'Duo Watchdog: glab no longer gets the token of @' + account.username
            + (account.duo ? ', nor the Duo CLI' : '') + '. Nothing is signed out: glab stays signed in to ' + h + '.';
    }
    return 'Duo Watchdog: the token of @' + account.username + ' is no longer marked for the GitLab Duo CLI. '
        + 'Nothing is signed out: the Duo CLI keeps using glab\'s login for ' + h + '.';
}
