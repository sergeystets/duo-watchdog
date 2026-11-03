import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { Account, accountGroup, idOf } from './core/accounts';
import { CreditsClient, Transport } from './core/client';
import { localToday } from './core/dates';
import { historyFrom } from './core/history';
import { HistoryEntry, historyKey } from './core/statistics';

/** A history is asked again after an hour; a failure after five minutes, or on Refresh. */
export const HISTORY_TTL_MS = 60 * 60_000;
export const HISTORY_RETRY_MS = 5 * 60_000;

interface Cached extends HistoryEntry {
    fetchedAt: number;
}

/**
 * Each account's year of usage, fetched when the Statistics view first shows it and kept for an hour. While a new
 * one is fetched, the last one stays.
 */
export class HistoryCache implements vscode.Disposable {

    private readonly entries = new Map<string, Cached>();
    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;

    constructor(private readonly store: AccountStore, private readonly transport: Transport) {
    }

    entry(account: Account): HistoryEntry | undefined {
        return this.entries.get(historyKey(account));
    }

    /** Fetches the histories of these accounts that are missing or old; the view hears when each arrives. */
    ensure(accounts: readonly Account[]): void {
        const now = Date.now();
        for (const account of accounts) {
            const key = historyKey(account);
            const cached = this.entries.get(key);
            const age = cached == null ? Infinity : now - cached.fetchedAt;
            if (cached?.loading || age < (cached?.error != null ? HISTORY_RETRY_MS : HISTORY_TTL_MS)) {
                continue;
            }
            this.entries.set(key, { history: cached?.history ?? null, error: null, loading: true, fetchedAt: now });
            void this.fetch(key, account);
        }
    }

    /** Refresh: every history is asked again the next time it is shown. */
    invalidate(): void {
        for (const cached of this.entries.values()) {
            if (!cached.loading) {
                cached.fetchedAt = 0;
            }
        }
    }

    private async fetch(key: string, account: Account): Promise<void> {
        let entry: Cached;
        try {
            const token = await this.store.token(idOf(account));
            if (!token) {
                throw new Error('No token for @' + account.username + '.');
            }
            const today = localToday();
            const history = await new CreditsClient(account.gitlabUrl, token, this.transport)
                .history(accountGroup(account), historyFrom(today), today);
            entry = { history, error: null, loading: false, fetchedAt: Date.now() };
        } catch (e) {
            // The message is the user's to read; it never carries the token.
            entry = {
                history: this.entries.get(key)?.history ?? null, error: e instanceof Error ? e.message : String(e),
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
