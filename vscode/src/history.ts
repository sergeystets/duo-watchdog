import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { Account, accountGroup, idOf } from './core/accounts';
import { CreditsClient, GitLabError, Transport } from './core/client';
import { localToday } from './core/dates';
import { sumOf } from './core/history';
import {
    cacheFile, CachedMonth, isSettled, Limiter, MonthAnswer, monthFromJson, monthsOfRange, monthsOutcome, monthToJson,
    planMonthsOf, StatisticsRange,
} from './core/monthly';
import { History } from './core/history';
import { HistoryEntry, historyKey } from './core/statistics';
import { log } from './log';

/** A history is asked again after an hour; a failure after five minutes, or on Refresh. */
export const HISTORY_TTL_MS = 60 * 60_000;
export const HISTORY_RETRY_MS = 5 * 60_000;

/** The Statistics range the user picked, in months: 3, 6 or 12. */
export const RANGE_KEY = 'duoWatchdog.statisticsMonths';

/** How many months are asked of GitLab at once, across all accounts. */
export const MONTHS_IN_FLIGHT = 3;

interface Cached extends HistoryEntry {
    fetchedAt: number;
}

/**
 * Each account's last months of usage, fetched when the Statistics view first shows them and kept for an hour. A month
 * is asked of GitLab on its own, at most three at once; a settled month is kept on the disk and never asked again, so
 * a reload asks only for the months that can still change. While new figures are fetched, the last ones stay.
 */
export class HistoryCache implements vscode.Disposable {

    private readonly entries = new Map<string, Cached>();
    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;
    private readonly limiter = new Limiter(MONTHS_IN_FLIGHT);

    /** @param storage the extension's global storage, where settled months are kept */
    constructor(private readonly store: AccountStore, private readonly transport: Transport,
                private readonly storage: vscode.Uri) {
    }

    entry(account: Account, range: StatisticsRange): HistoryEntry | undefined {
        return this.entries.get(historyKey(account) + '|' + range);
    }

    /** Fetches the histories of these accounts that are missing or old; the view hears when each arrives. */
    ensure(accounts: readonly Account[], range: StatisticsRange): void {
        const now = Date.now();
        for (const account of accounts) {
            const key = historyKey(account) + '|' + range;
            const cached = this.entries.get(key);
            const age = cached == null ? Infinity : now - cached.fetchedAt;
            if (cached?.loading || age < (cached?.error != null ? HISTORY_RETRY_MS : HISTORY_TTL_MS)) {
                continue;
            }
            this.entries.set(key, { history: cached?.history ?? null, error: null, warning: null, loading: true, fetchedAt: now });
            void this.fetch(key, account, range);
        }
    }

    /** Refresh: every history is asked again the next time it is shown; settled months still come from the disk. */
    invalidate(): void {
        for (const cached of this.entries.values()) {
            if (!cached.loading) {
                cached.fetchedAt = 0;
            }
        }
    }

    private async fetch(key: string, account: Account, range: StatisticsRange): Promise<void> {
        const id = idOf(account);
        let entry: Cached;
        try {
            const loaded = await this.loadMonths(account, monthsOfRange(range, localToday()), 'statistics');
            entry = { history: loaded.history, error: null, warning: loaded.warning, loading: false, fetchedAt: Date.now() };
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('statistics ' + id + ': ' + message);
            // The message is the user's to read; it never carries the token.
            entry = {
                history: this.entries.get(key)?.history ?? null, error: message, warning: null, loading: false,
                fetchedAt: Date.now(),
            };
        }
        this.entries.set(key, entry);
        this.changed.fire();
    }

    /**
     * The account's usage in these months, as one history: settled months from the disk, the rest from GitLab, at most
     * three at once. Also what Sessions estimates with. Throws when GitLab refuses, or no month could be read.
     * @param purpose for the log: statistics, sessions
     */
    async loadMonths(account: Account, months: readonly string[], purpose: string): Promise<{ history: History; warning: string | null }> {
        const id = idOf(account);
        const token = await this.store.token(id);
        if (!token) {
            throw new Error('No token for @' + account.username + '.');
        }
        const today = localToday();
        const group = accountGroup(account);
        const kept: CachedMonth[] = [];
        for (const month of months.filter(m => isSettled(m, today))) {
            const cached = await this.read(id, group, month);
            if (cached != null) {
                kept.push(cached);
            }
        }
        const plan = planMonthsOf(months, today, month => kept.some(k => k.month === month));
        log.info(purpose + ' ' + id + ': ' + months.length + (months.length === 1 ? ' month' : ' months') + '; kept on disk: '
            + (plan.fromCache.join(', ') || 'none') + '; asking: ' + (plan.fetch.join(', ') || 'none'));
        const client = new CreditsClient(account.gitlabUrl, token, this.transport, log);
        const answers = await Promise.all(plan.fetch.map(month => this.limiter.run(() => this.ask(client, id, group, month, today))));
        const outcome = monthsOutcome(today, kept, answers);
        for (const month of outcome.keep) {
            await this.write(id, group, month);
        }
        if (outcome.refused != null) {
            throw new Error(outcome.refused);
        }
        if (outcome.history == null) {
            throw new Error(outcome.failed.join('; ') || 'GitLab returned nothing for these months.');
        }
        log.info(purpose + ' ' + id + ': from ' + outcome.history.start + ' to ' + outcome.history.end + ', '
            + Math.round(sumOf(outcome.history.daily) * 10) / 10 + ' credits; kept now: '
            + (outcome.keep.map(k => k.month + (k.history == null ? ' (nothing)' : '')).join(', ') || 'none'));
        return {
            history: outcome.history,
            warning: outcome.failed.length === 0 ? null : 'Some months did not load: ' + outcome.failed.join('; '),
        };
    }

    /** One month from GitLab: the current one as the Usage view asks it, a past one from its first to its last day. */
    private async ask(client: CreditsClient, id: string, group: string, month: string, today: string): Promise<MonthAnswer> {
        try {
            const history = await client.historyOfMonth(group, month, today);
            log.info('statistics ' + id + ' ' + month + (month === today.slice(0, 7) ? ' (no dates, as Usage asks)' : '')
                + ': ' + Math.round(sumOf(history.daily) * 10) / 10 + ' credits, ' + history.flows.length + ' features'
                + (history.flowsError != null ? ' (features: ' + history.flowsError + ')' : ''));
            return { month, history };
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            const status = e instanceof GitLabError ? e.status : 0;
            log.warn('statistics ' + id + ' ' + month + ': ' + message);
            return { month, error: message, status };
        }
    }

    private async read(id: string, group: string, month: string): Promise<CachedMonth | undefined> {
        try {
            const bytes = await vscode.workspace.fs.readFile(vscode.Uri.joinPath(this.storage, ...cacheFile(id, group, month)));
            const cached = monthFromJson(Buffer.from(bytes).toString('utf8'));
            return cached?.month === month ? cached : undefined;
        } catch {
            return undefined;
        }
    }

    private async write(id: string, group: string, month: CachedMonth): Promise<void> {
        const file = vscode.Uri.joinPath(this.storage, ...cacheFile(id, group, month.month));
        try {
            await vscode.workspace.fs.createDirectory(vscode.Uri.joinPath(file, '..'));
            await vscode.workspace.fs.writeFile(file, Buffer.from(monthToJson(month.month, month.history), 'utf8'));
        } catch (e) {
            log.warn('statistics ' + id + ': could not keep ' + month.month + ': ' + (e instanceof Error ? e.message : String(e)));
        }
    }

    dispose(): void {
        this.changed.dispose();
    }
}
