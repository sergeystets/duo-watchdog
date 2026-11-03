import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { groupPath, tokenPage } from './core/address';
import {
    Account, AccountState, expiryPopupKey, idOf, migratedAccount, needsMigration, Problem, rejectedPopupKey,
    sortStates, usageReadable,
} from './core/accounts';
import { alertText, expiryText, rejectedText } from './core/accountsView';
import { alertFor, AlertKind } from './core/alert';
import { CreditsClient, GitLabError, Transport } from './core/client';
import { localToday } from './core/dates';
import { Snapshot, Snapshots } from './core/report';
import { number } from './core/view';
import { log } from './log';
import { OLD_LAST_ALERT_KEY, OLD_TOKEN_KEY, oldSettings, settings } from './settings';

/** Every account with what the last refresh found, and the one the status bar and the dashboard show. */
export interface Overview {
    /** Most credits left first. */
    states: AccountState[];
    selected: AccountState | null;
    /** While there is no account: set up, the old token being moved to an account, or why that failed. */
    setup: Snapshot;
}

interface Result {
    snapshot: Snapshot;
    problem: Problem | null;
}

/** Refreshes every account's credits on a schedule and on demand; one per window, since the numbers are the user's. */
export class CreditsService implements vscode.Disposable {

    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;
    private readonly refreshed = new vscode.EventEmitter<void>();
    /** After every account was read: glab then follows the picked account. */
    readonly onDidRefresh = this.refreshed.event;
    private readonly results = new Map<string, Result>();
    private setup: Snapshot = Snapshots.loading();
    private timer: ReturnType<typeof setInterval> | undefined;
    private soon: ReturnType<typeof setTimeout> | undefined;
    private loading = false;
    private again = false;

    constructor(private readonly context: vscode.ExtensionContext, private readonly store: AccountStore,
                private readonly transport: Transport) {
    }

    overview(): Overview {
        const states = sortStates(this.store.list().map(account => {
            const id = idOf(account);
            const result = this.results.get(id) ?? { snapshot: Snapshots.loading(), problem: null };
            return { id, account, ...result };
        }));
        const selectedId = this.store.selectedId();
        return { states, selected: states.find(s => s.id === selectedId) ?? states[0] ?? null, setup: this.setup };
    }

    /** Shows another account in the status bar and the dashboard. */
    async select(id: string): Promise<void> {
        await this.store.select(id);
        this.changed.fire();
    }

    /** Tells the views that an account changed without asking GitLab again, e.g. a glab tick. */
    changedLocally(): void {
        this.changed.fire();
    }

    /** Restarts the schedule with the current settings and refreshes now. */
    reschedule(): void {
        if (this.timer !== undefined) {
            clearInterval(this.timer);
        }
        this.timer = setInterval(() => void this.refresh(), settings().refreshMinutes * 60_000);
        void this.refresh();
    }

    /** Refreshes in a moment: changes that come together (a token saved, then its account) make one refresh. */
    refreshSoon(): void {
        if (this.soon !== undefined) {
            clearTimeout(this.soon);
        }
        this.soon = setTimeout(() => {
            this.soon = undefined;
            void this.refresh();
        }, 500);
    }

    async refresh(): Promise<void> {
        if (this.loading) {
            this.again = true;
            return;
        }
        this.loading = true;
        try {
            do {
                this.again = false;
                await this.load();
            } while (this.again);
        } finally {
            this.loading = false;
        }
    }

    private async load(): Promise<void> {
        await this.migrate();
        const accounts = this.store.list();
        for (const id of [...this.results.keys()]) {
            if (!accounts.some(a => idOf(a) === id)) {
                this.results.delete(id);
            }
        }
        this.changed.fire();
        await Promise.all(accounts.map(async account => {
            const id = idOf(account);
            const result = await this.loadAccount(id, account);
            logResult(id, result);
            this.results.set(id, result);
            this.changed.fire();
            this.notifyOnce(id, result);
        }));
        this.refreshed.fire();
    }

    private async loadAccount(id: string, account: Account): Promise<Result> {
        const previous = this.results.get(id)?.snapshot ?? Snapshots.loading();
        const token = await this.store.token(id);
        if (!token) {
            return { snapshot: Snapshots.notConfigured(), problem: 'noToken' };
        }
        if (usageReadable(account.scopes) === false) {
            return { snapshot: Snapshots.notConfigured(), problem: 'notTracked' };
        }
        if (account.groupPath.trim() === '') {
            return { snapshot: Snapshots.notConfigured(), problem: 'noGroup' };
        }
        const group = groupPath(account.groupPath, account.gitlabUrl);
        if (group === '') {
            return {
                snapshot: Snapshots.failed(previous, "Enter the group path from the group's address, e.g. my-company, "
                    + 'or run the command Duo Watchdog: Find My Group.'),
                problem: null,
            };
        }
        try {
            const client = new CreditsClient(account.gitlabUrl, token, this.transport);
            return { snapshot: Snapshots.ok(await client.fetch(group), new Date()), problem: null };
        } catch (e) {
            // The message is the user's to read; it never carries the token.
            const message = e instanceof Error ? e.message : String(e);
            if (e instanceof GitLabError && e.status === 401) {
                return { snapshot: Snapshots.failed(Snapshots.loading(), message), problem: 'rejected' };
            }
            if (e instanceof GitLabError && e.status === 403) {
                return { snapshot: Snapshots.failed(Snapshots.loading(), message), problem: 'notTracked' };
            }
            return { snapshot: Snapshots.failed(previous, message), problem: null };
        }
    }

    /**
     * Before accounts there was one token: while there is no account yet, GitLab is asked whose it is and it becomes
     * the first account, with the old group, limit and last alert. When GitLab cannot be reached, the old token stays
     * and the next refresh tries again.
     */
    private async migrate(): Promise<void> {
        const secrets = this.context.secrets;
        const oldToken = (await secrets.get(OLD_TOKEN_KEY))?.trim();
        if (!needsMigration(this.store.list().length, oldToken)) {
            this.setup = Snapshots.notConfigured();
            return;
        }
        const old = oldSettings(this.context);
        log.remember(oldToken);
        log.info('migration: asking ' + old.gitlabUrl + " whose the token saved before accounts is");
        try {
            const client = new CreditsClient(old.gitlabUrl, oldToken!, this.transport);
            const username = await client.username();
            const info = await client.tokenInfo().catch(() => null);
            const account = migratedAccount(old, username, info, Date.now());
            const id = idOf(account);
            await this.store.storeToken(id, oldToken!);
            await this.store.put(account);
            await this.store.select(id);
            await secrets.delete(OLD_TOKEN_KEY);
            await this.context.globalState.update(OLD_LAST_ALERT_KEY, undefined);
            this.setup = Snapshots.notConfigured();
            log.info('migration: the token is now account ' + id + ' (group ' + (account.groupPath || 'none') + ', limit '
                + account.limit + ', scopes ' + (account.scopes?.join(', ') ?? 'unknown') + ')');
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('migration failed: ' + message + '; the old token stays, and the next refresh tries again');
            this.setup = Snapshots.failed(Snapshots.loading(), 'The saved token is not moved to an account yet: ' + message);
        }
    }

    /** The one-time popups: GitLab turning a token down, a token about to expire, and each period's alerts. */
    private notifyOnce(id: string, result: Result): void {
        const account = this.store.get(id);
        if (account == null) {
            return;
        }
        const today = localToday();
        const rejected = rejectedPopupKey(account, result.problem === 'rejected');
        if (rejected != null) {
            void this.store.update(id, { rejectedPopupKey: rejected });
            this.offerReplace(vscode.window.showErrorMessage(rejectedText(account, today), REPLACE), account);
        }
        const expiry = result.problem === 'rejected' || result.problem === 'noToken' ? null
            : expiryPopupKey(account, today);
        if (expiry != null) {
            void this.store.update(id, { expiryPopupKey: expiry });
            this.offerReplace(vscode.window.showWarningMessage(expiryText(account, today), REPLACE, CREATE), account);
        }
        const report = result.problem == null && result.snapshot.status === 'ok' ? result.snapshot.report : null;
        if (report == null) {
            return;
        }
        const { percents, forecastAlert } = settings();
        const alert = alertFor(report, account.limit, percents, forecastAlert, today);
        if (!alert || alert.key === account.lastAlertKey) {
            return;
        }
        void this.store.update(id, { lastAlertKey: alert.key });
        const open = 'Open Duo Watchdog';
        void show(alert.kind, alertText(alert, account), open).then(async choice => {
            if (choice === open) {
                await this.select(id);
                void vscode.commands.executeCommand('duoWatchdog.openDashboard');
            }
        });
    }

    private offerReplace(choice: Thenable<string | undefined>, account: Account): void {
        void choice.then(picked => {
            if (picked === REPLACE) {
                void vscode.commands.executeCommand('duoWatchdog.addToken', { gitlabUrl: account.gitlabUrl });
            } else if (picked === CREATE) {
                void vscode.env.openExternal(vscode.Uri.parse(tokenPage(account.gitlabUrl, account.glab)));
            }
        });
    }

    dispose(): void {
        if (this.timer !== undefined) {
            clearInterval(this.timer);
        }
        if (this.soon !== undefined) {
            clearTimeout(this.soon);
        }
        this.changed.dispose();
        this.refreshed.dispose();
    }
}

/** "refresh gitlab.com/dana.k: ok, 148.6 used", "refresh …: error: Cannot reach gitlab.com: …". */
function logResult(id: string, result: Result): void {
    const s = result.snapshot;
    const line = 'refresh ' + id + ': ' + s.status + (result.problem != null ? ' (' + result.problem + ')' : '')
        + (s.report != null ? ', ' + number(s.report.creditsUsed) + ' used' : '') + (s.error != null ? ': ' + s.error : '');
    if (s.status === 'error' || result.problem === 'rejected') {
        log.warn(line);
    } else {
        log.info(line);
    }
}

const REPLACE = 'Replace Token';
const CREATE = 'Create a New Token in GitLab';

function show(kind: AlertKind, message: string, action: string): Thenable<string | undefined> {
    switch (kind) {
        case 'blocked':
            return vscode.window.showErrorMessage(message, action);
        case 'limit':
        case 'threshold':
            return vscode.window.showWarningMessage(message, action);
        default:
            return vscode.window.showInformationMessage(message, action);
    }
}
