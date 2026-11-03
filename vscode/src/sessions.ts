import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { Account, accountGroup, idOf } from './core/accounts';
import { CreditsClient, Transport } from './core/client';
import { monthsBetween } from './core/monthly';
import { activeDays, contains, estimate, inGroup, Range, Session, summarize } from './core/sessions';
import { SessionsEntry } from './core/sessionsView';
import { historyKey } from './core/statistics';
import { DuoCliLogScanner } from './duoCliLogs';
import { HistoryCache } from './history';
import { log } from './log';

/** A range's sessions are asked again after five minutes, a failure after one, or on Refresh. */
export const SESSIONS_TTL_MS = 5 * 60_000;
export const SESSIONS_RETRY_MS = 60_000;

interface Cached extends SessionsEntry {
    fetchedAt: number;
}

/**
 * The selected account's Agent Platform sessions in a range, with their estimated credits: the sessions from GitLab,
 * the agent's steps from the Duo CLI's logs here, and the range's credits per day and feature from the same months
 * Statistics keeps. Sessions outside the account's group are left out and counted.
 */
export class SessionsService implements vscode.Disposable {

    private readonly entries = new Map<string, Cached>();
    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;

    constructor(private readonly store: AccountStore, private readonly transport: Transport,
                private readonly history: HistoryCache, private readonly logs: DuoCliLogScanner) {
    }

    private key(account: Account, range: Range): string {
        return historyKey(account) + '|' + range.from + '|' + range.to;
    }

    entry(account: Account, range: Range): SessionsEntry | undefined {
        return this.entries.get(this.key(account, range));
    }

    /** Fetches the range's sessions when they are missing or old; the view hears when they arrive. */
    ensure(account: Account, range: Range): void {
        const key = this.key(account, range);
        const cached = this.entries.get(key);
        const age = cached == null ? Infinity : Date.now() - cached.fetchedAt;
        if (cached?.loading || age < (cached?.error != null ? SESSIONS_RETRY_MS : SESSIONS_TTL_MS)) {
            return;
        }
        this.entries.set(key, {
            summary: cached?.summary ?? null, leftOut: cached?.leftOut ?? 0, error: null, warning: null, loading: true,
            fetchedAt: Date.now(),
        });
        void this.fetch(key, account, range);
    }

    /** Refresh: every range is asked again the next time it is shown. */
    invalidate(): void {
        for (const cached of this.entries.values()) {
            if (!cached.loading) {
                cached.fetchedAt = 0;
            }
        }
    }

    /** One session by its number, or null when the account has none such. */
    async find(account: Account, id: number): Promise<Session | null> {
        return (await this.client(account)).session(id);
    }

    /** The account's session last updated, or null when it has none. */
    async last(account: Account): Promise<Session | null> {
        return (await this.client(account)).lastSession();
    }

    private async client(account: Account): Promise<CreditsClient> {
        const token = await this.store.token(idOf(account));
        if (!token) {
            throw new Error('No token for @' + account.username + '.');
        }
        return new CreditsClient(account.gitlabUrl, token, this.transport, log);
    }

    private async fetch(key: string, account: Account, range: Range): Promise<void> {
        const id = idOf(account);
        let entry: Cached;
        try {
            const client = await this.client(account);
            const [sessions, steps, months] = await Promise.all([
                client.sessions(Date.parse(range.from + 'T00:00:00Z')),
                this.logs.scan(),
                this.history.loadMonths(account, monthsBetween(range.from, range.to), 'sessions'),
            ]);
            const group = accountGroup(account);
            const mine = sessions.filter(s => inGroup(s, group));
            const elsewhere = sessions.filter(s => !inGroup(s, group)
                && activeDays(s, steps.get(s.id)).some(d => contains(range, d))).length;
            const summary = summarize(estimate(mine, steps, months.history), months.history, range, group);
            entry = { summary, leftOut: elsewhere, error: null, warning: months.warning, loading: false, fetchedAt: Date.now() };
            log.info('sessions ' + id + ' ' + range.from + ' to ' + range.to + ': ' + summary.rows.length + ' in the range, '
                + elsewhere + ' outside the group; ' + summary.rows.filter(e => e.steps != null).length + ' with steps counted here');
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('sessions ' + id + ': ' + message);
            const before = this.entries.get(key);
            entry = {
                summary: before?.summary ?? null, leftOut: before?.leftOut ?? 0, error: message, warning: null,
                loading: false, fetchedAt: Date.now(),
            };
        }
        this.entries.set(key, entry);
        this.changed.fire();
    }

    dispose(): void {
        this.changed.dispose();
    }
}
