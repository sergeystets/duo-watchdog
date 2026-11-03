import { groupPath, host, trimSlashes } from './address';
import { TokenInfo } from './client';
import { level } from './dashboard';
import { daysBetween, IsoDate } from './dates';
import { Snapshot } from './report';
import { day } from './view';

/**
 * The rules of accounts: one GitLab user on one GitLab, one token each.
 *
 * When a token is added, GitLab says whose it is; a token for an account already listed replaces that account's
 * token, and its group and limit stay. Group path and limit belong to each account; the alert percents, the forecast
 * alert and the refresh interval are shared.
 */
export interface Account {
    gitlabUrl: string;
    /** Without the "@". */
    username: string;
    /** The top-level group that owns the Duo subscription, as typed: normalised with groupPath() before use. */
    groupPath: string;
    /** The credits this account means to stay within. */
    limit: number;
    /** When its token was saved, in ms since the epoch. */
    tokenSavedAt: number;
    /** The day GitLab stops taking the token; null when it never expires or GitLab did not say. */
    expiresAt: IsoDate | null;
    /** The token's scopes as GitLab gave them; null when it did not say. */
    scopes: string[] | null;
    /** This account may be handed to glab for its GitLab: glab follows the picked account among those allowed. */
    glab: boolean;
    /** The GitLab Duo CLI may use the token, through glab. Implies glab. */
    duo: boolean;
    /** The tokenSavedAt of the token glab took, while glab has it; null when glab has another account's. */
    glabHanded: number | null;
    /** The tokenSavedAt of a token glab turned down, so it is not tried again until the token changes. */
    glabRefused: number | null;
    /** The last per-period alert shown, so it is not repeated on every refresh. */
    lastAlertKey: string | null;
    /** The one-time popups already shown: the token expiring soon, and GitLab turning the token down. */
    expiryPopupKey: string | null;
    rejectedPopupKey: string | null;
}

export const DEFAULT_LIMIT = 100;

/** Days before its expiry that a token is pointed out, and that it gets its one popup. */
export const ATTENTION_DAYS = 7;
export const EXPIRY_POPUP_DAYS = 3;

/** Why an account has no numbers: no token, GitLab turns it down, it cannot read usage, or no group path yet. */
export type Problem = 'noToken' | 'rejected' | 'notTracked' | 'noGroup';

/** An account with what the last refresh found. */
export interface AccountState {
    id: string;
    account: Account;
    snapshot: Snapshot;
    problem: Problem | null;
}

export type Filter = 'all' | 'credits' | 'expired';

export type Cli = 'glab' | 'duo';

/** "gitlab.com": the host in lower case, or the address itself when it has none. */
export function accountHost(gitlabUrl: string): string {
    return (host(gitlabUrl) ?? trimSlashes(gitlabUrl)).toLowerCase();
}

/** "alice" from "@alice". */
export function bareUsername(username: string): string {
    let u = username.trim();
    while (u.startsWith('@')) {
        u = u.slice(1);
    }
    return u;
}

/** "gitlab.com/alice": the same user on another GitLab is another account. */
export function accountId(gitlabUrl: string, username: string): string {
    return accountHost(gitlabUrl) + '/' + bareUsername(username);
}

export function idOf(account: Account): string {
    return accountId(account.gitlabUrl, account.username);
}

/** The account's group path as GitLab knows it: "acme", also when a group URL was pasted; "" for none. */
export function accountGroup(account: Account): string {
    return groupPath(account.groupPath, account.gitlabUrl);
}

/** An account as stored, with defaults for what an older version did not keep. */
export function normalized(stored: Partial<Account> & { gitlabUrl: string; username: string }): Account {
    return {
        gitlabUrl: stored.gitlabUrl,
        username: bareUsername(stored.username),
        groupPath: stored.groupPath ?? '',
        limit: typeof stored.limit === 'number' && stored.limit > 0 ? stored.limit : DEFAULT_LIMIT,
        tokenSavedAt: stored.tokenSavedAt ?? 0,
        expiresAt: stored.expiresAt ?? null,
        scopes: Array.isArray(stored.scopes) ? stored.scopes : null,
        glab: stored.glab === true || stored.duo === true,
        duo: stored.duo === true,
        glabHanded: typeof stored.glabHanded === 'number' ? stored.glabHanded : null,
        glabRefused: typeof stored.glabRefused === 'number' ? stored.glabRefused : null,
        lastAlertKey: stored.lastAlertKey ?? null,
        expiryPopupKey: stored.expiryPopupKey ?? null,
        rejectedPopupKey: stored.rejectedPopupKey ?? null,
    };
}

/**
 * Whether the token can read Duo usage: ai_features, and read_api or api. Null when its scopes are unknown: then
 * try anyway, and a 403 from GitLab says no.
 */
export function usageReadable(scopes: readonly string[] | null): boolean | null {
    if (scopes == null) {
        return null;
    }
    return scopes.includes('ai_features') && (scopes.includes('read_api') || scopes.includes('api'));
}

/** Of the scopes reading usage needs, those the token lacks: ["ai_features"], or ["read_api", "ai_features"]. */
export function missingUsageScopes(scopes: readonly string[] | null): string[] {
    if (scopes == null) {
        return [];
    }
    const missing: string[] = [];
    if (!scopes.includes('read_api') && !scopes.includes('api')) {
        missing.push('read_api');
    }
    if (!scopes.includes('ai_features')) {
        missing.push('ai_features');
    }
    return missing;
}

/**
 * What a token's scopes leave glab unable to do, or null when nothing (or the scopes are unknown). Without api glab
 * can only read; without write_repository it does all it does through the API, but cannot push over HTTPS, which
 * matters only when Git runs over HTTPS rather than SSH. The same words as Accounts.glabScopeNote in the plugin.
 */
export function glabScopeNote(scopes: readonly string[] | null): string | null {
    if (scopes == null) {
        return null;
    }
    const api = scopes.includes('api');
    const write = scopes.includes('write_repository');
    if (api && write) {
        return null;
    }
    if (!api) {
        return 'The token lacks the api scope, so glab can only read with it'
            + (write ? '.' : ", and can't git push over HTTPS.");
    }
    return "The token lacks write_repository: glab works with the API, but can't git push over HTTPS. Over SSH it can.";
}

/** max(limit - used, 0) when there is a report; null when the credits left are unknown. */
export function creditsLeft(state: AccountState): number | null {
    const report = state.problem == null ? state.snapshot.report : null;
    return report == null ? null : Math.max(state.account.limit - report.creditsUsed, 0);
}

/** Days until the token expires: 0 on the day, negative once past; null when it has no expiry date. */
export function daysLeft(account: Account, today: IsoDate): number | null {
    return account.expiresAt == null ? null : daysBetween(today, account.expiresAt);
}

/** GitLab turned the token down at the last refresh, or its expiry day has come. */
export function isExpired(state: AccountState, today: IsoDate): boolean {
    return state.problem === 'rejected' || (state.account.expiresAt != null && state.account.expiresAt <= today);
}

export function hasCredits(state: AccountState): boolean {
    const left = creditsLeft(state);
    return left != null && left > 0;
}

export function matchesFilter(filter: Filter, state: AccountState, today: IsoDate): boolean {
    switch (filter) {
        case 'credits':
            return hasCredits(state);
        case 'expired':
            return isExpired(state, today);
        default:
            return true;
    }
}

/** Most credits left first, unknown last; ties by username, then by GitLab. */
export function compareStates(a: AccountState, b: AccountState): number {
    const left = (s: AccountState) => creditsLeft(s) ?? -1;
    return left(b) - left(a)
        || compare(a.account.username.toLowerCase(), b.account.username.toLowerCase())
        || compare(accountHost(a.account.gitlabUrl), accountHost(b.account.gitlabUrl));
}

export function sortStates(states: readonly AccountState[]): AccountState[] {
    return [...states].sort(compareStates);
}

function compare(a: string, b: string): number {
    return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Why an account needs the user, as the status bar's tooltip says it, or null: no token, GitLab turns the token
 * down or it expired, Duo blocked, usage at the lowest alert percent, or the token expiring within a week.
 */
export function attention(state: AccountState, percents: number[], today: IsoDate): string | null {
    const { account, problem } = state;
    const days = daysLeft(account, today);
    if (problem === 'noToken') {
        return 'no token';
    }
    if (days != null && days <= 0) {
        return 'token expired on ' + day(account.expiresAt!);
    }
    if (problem === 'rejected') {
        return 'GitLab turns the token down';
    }
    const report = problem == null ? state.snapshot.report : null;
    if (report != null && report.blocked) {
        return 'blocked by GitLab';
    }
    if (report != null && level(report, account.limit, percents) === 'warning') {
        return Math.floor(report.creditsUsed / account.limit * 100) + '% of its limit used';
    }
    if (days != null && days <= ATTENTION_DAYS) {
        return 'token expires ' + inDays(days);
    }
    return null;
}

/** "in 3 days", "tomorrow". */
export function inDays(days: number): string {
    return days === 1 ? 'tomorrow' : 'in ' + days + ' days';
}

/** The key of the popup for a token that expires within three days, when it is due and was not shown yet. */
export function expiryPopupKey(account: Account, today: IsoDate): string | null {
    const days = daysLeft(account, today);
    if (days == null || days <= 0 || days > EXPIRY_POPUP_DAYS) {
        return null;
    }
    const key = 'expiry|' + account.expiresAt;
    return key === account.expiryPopupKey ? null : key;
}

/** The key of the popup for a token GitLab turns down, once per saved token, when it was not shown yet. */
export function rejectedPopupKey(account: Account, rejected: boolean): string | null {
    if (!rejected) {
        return null;
    }
    const key = 'rejected|' + account.tokenSavedAt;
    return key === account.rejectedPopupKey ? null : key;
}

/** The old single token is moved to an account only while there is no account yet. */
export function needsMigration(accounts: number, oldToken: string | null | undefined): boolean {
    return accounts === 0 && oldToken != null && oldToken.trim() !== '';
}

/** What the extension kept before accounts: the one token's GitLab, group, limit and last alert. */
export interface OldSettings {
    gitlabUrl: string;
    groupPath: string;
    limit: number;
    lastAlertKey: string | null;
}

/** The account the old settings become, once GitLab has said whose the old token is. */
export function migratedAccount(old: OldSettings, username: string, info: TokenInfo | null, now: number): Account {
    return {
        ...withToken(undefined, { gitlabUrl: old.gitlabUrl, username, info, savedAt: now },
            { groupPath: groupPath(old.groupPath, old.gitlabUrl), limit: old.limit }),
        lastAlertKey: old.lastAlertKey,
    };
}

/** A token GitLab has said whose it is. */
export interface NewToken {
    gitlabUrl: string;
    username: string;
    info: TokenInfo | null;
    savedAt: number;
}

/**
 * The account a new token gives: the existing one with the new token's details, its group, limit and the rest
 * kept; or a new account with the group and limit given.
 */
export function withToken(existing: Account | undefined, token: NewToken,
                          fresh: { groupPath: string; limit: number }): Account {
    const details = {
        gitlabUrl: token.gitlabUrl.trim(),
        username: bareUsername(token.username),
        tokenSavedAt: token.savedAt,
        expiresAt: token.info?.expiresAt ?? null,
        scopes: token.info ? [...token.info.scopes] : null,
    };
    if (existing != null) {
        return { ...existing, ...details };
    }
    return {
        ...details,
        groupPath: fresh.groupPath,
        limit: fresh.limit > 0 ? fresh.limit : DEFAULT_LIMIT,
        glab: false,
        duo: false,
        glabHanded: null,
        glabRefused: null,
        lastAlertKey: null,
        expiryPopupKey: null,
        rejectedPopupKey: null,
    };
}

/** The account may be handed to glab: glab or the Duo CLI is on for it. */
export function allowsGlab(account: Account): boolean {
    return account.glab || account.duo;
}

/** glab has this account's current token. */
export function glabHas(account: Account): boolean {
    return account.glabHanded != null && account.glabHanded === account.tokenSavedAt;
}

/** glab turned this account's current token down. */
export function glabRefusedIt(account: Account): boolean {
    return account.glabRefused != null && account.glabRefused === account.tokenSavedAt;
}

/** The account on that GitLab whose current token glab has; undefined when none. */
export function glabHolder(accounts: readonly Account[], host: string): Account | undefined {
    return accounts.find(a => accountHost(a.gitlabUrl) === host && glabHas(a));
}

/**
 * The account glab should have for a GitLab: the picked one when glab is allowed for it and it is on that GitLab;
 * else the one whose current token glab has; else the first allowed one, the list being most credits left first.
 * The same rule as Accounts.glabOwner in the plugin.
 */
export function glabOwner(accounts: readonly Account[], picked: string | null, host: string): Account | undefined {
    const allowed = accounts.filter(a => allowsGlab(a) && accountHost(a.gitlabUrl) === host);
    return allowed.find(a => idOf(a) === picked) ?? allowed.find(glabHas) ?? allowed[0];
}

/**
 * The accounts whose token to hand to glab now, one per GitLab: each GitLab's owner, unless glab has its current
 * token already, or turned that token down. Nothing when glab is not installed.
 */
export function glabHandOffs(accounts: readonly Account[], picked: string | null, installed: boolean): Account[] {
    if (!installed) {
        return [];
    }
    const hosts = [...new Set(accounts.filter(allowsGlab).map(a => accountHost(a.gitlabUrl)))];
    return hosts.map(h => glabOwner(accounts, picked, h))
        .filter((owner): owner is Account => owner != null && !glabHas(owner) && !glabRefusedIt(owner));
}

/**
 * The accounts after a hand-off: glab took the token (and no longer has the others of that GitLab), or turned it
 * down (not tried again until the token changes).
 */
export function afterHandOff(accounts: readonly Account[], id: string, ok: boolean): Account[] {
    const handed = accounts.find(a => idOf(a) === id);
    if (handed == null) {
        return [...accounts];
    }
    const h = accountHost(handed.gitlabUrl);
    return accounts.map(a => {
        if (idOf(a) === id) {
            return ok ? { ...a, glabHanded: a.tokenSavedAt, glabRefused: null } : { ...a, glabRefused: a.tokenSavedAt };
        }
        return ok && accountHost(a.gitlabUrl) === h && a.glabHanded != null ? { ...a, glabHanded: null } : a;
    });
}

/**
 * The accounts after allowing glab or the Duo CLI for one, or not. Several accounts of a GitLab may be allowed; glab
 * follows the picked one. The Duo CLI signs in through glab: turning it on turns glab on too, and turning glab off
 * turns it off. Turning one on tries a token glab turned down again. Nothing is ever signed out.
 */
export function withCli(accounts: readonly Account[], id: string, cli: Cli, on: boolean): Account[] {
    return accounts.map(a => {
        if (idOf(a) !== id) {
            return a;
        }
        const retry = on ? { glabRefused: null } : {};
        return cli === 'glab' ? { ...a, glab: on, duo: on && a.duo, ...retry } : { ...a, duo: on, glab: on || a.glab, ...retry };
    });
}
