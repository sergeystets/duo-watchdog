import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { groupPath, tokenPage } from './core/address';
import {
    Account, AccountState, expiryPopupKey, idOf, migratedAccount, needsMigration, Problem, rejectedPopupKey,
    rotatedExpiry, rotationDue, sortStates, usageReadable, withToken,
} from './core/accounts';
import { accountName, alertText, expiryText, rejectedText, rotatedText, rotateRefusedText } from './core/accountsView';
import { alertFor, AlertKind } from './core/alert';
import { CreditsClient, GitLabError, Rotated, TokenInfo, Transport } from './core/client';
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

    private async loadAccount(id: string, stored: Account): Promise<Result> {
        const previous = this.results.get(id)?.snapshot ?? Snapshots.loading();
        const saved = await this.store.token(id);
        if (!saved) {
            return { snapshot: Snapshots.notConfigured(), problem: 'noToken' };
        }
        const token = await this.rotateIfDue(id, stored, saved);
        const account = this.store.get(id) ?? stored;
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
            if (e instanceof GitLabError && e.status === 401 && await this.store.token(id) !== token) {
                // Replaced or rotated meanwhile, perhaps in another window: the refresh that follows reads the new one.
                return { snapshot: previous, problem: null };
            }
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
     * When rotating is on and the token expires within a week, has GitLab rotate it, saves the new one at once (GitLab
     * has revoked the old one) and says so. Only the focused window rotates, and only a token GitLab still takes, so
     * two windows or IDEs with the same token do not both rotate it: rotating a revoked token makes GitLab revoke the
     * new one too. Returns the token to read the credits with.
     */
    private async rotateIfDue(id: string, account: Account, token: string): Promise<string> {
        const today = localToday();
        if (!settings().rotateTokens || !vscode.window.state.focused || !rotationDue(account, today)) {
            return token;
        }
        const client = new CreditsClient(account.gitlabUrl, token, this.transport);
        let info: TokenInfo;
        try {
            info = await client.tokenInfo();
        } catch (e) {
            // Turned down, which the refresh says, or GitLab out of reach: the next refresh looks again.
            log.info('rotate ' + id + ': not now, ' + (e instanceof Error ? e.message : String(e)));
            return token;
        }
        const current = { ...account, expiresAt: info.expiresAt, scopes: info.scopes };
        if (!rotationDue(current, today)) {
            await this.store.update(id, { expiresAt: info.expiresAt, scopes: info.scopes });
            return token;
        }
        const expiresAt = rotatedExpiry(info.createdAt, info.expiresAt!, today);
        let rotated: Rotated;
        try {
            rotated = await client.rotate(expiresAt);
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('rotate ' + id + ': ' + message);
            if (e instanceof GitLabError && e.status >= 400 && e.status < 500 && e.status !== 401) {
                // Asked again only for another token: GitLab said why, and the expiry popup still comes.
                await this.store.update(id, { rotateRefused: account.tokenSavedAt });
                this.offerReplace(vscode.window.showWarningMessage(rotateRefusedText(current, message), REPLACE, CREATE),
                    current);
            }
            return token;
        }
        const copy = 'Copy the New Token';
        try {
            await this.store.storeToken(id, rotated.token);
        } catch (e) {
            log.warn('rotate ' + id + ': GitLab rotated the token, but it could not be saved: ' + String(e));
            void vscode.window.showErrorMessage('Duo Watchdog: GitLab rotated the token of ' + accountName(account)
                + ', but the new one could not be saved. Copy it and add it with Duo Watchdog: Add or Replace a Token.', copy)
                .then(choice => choice === copy ? vscode.env.clipboard.writeText(rotated.token) : undefined);
            return rotated.token;
        }
        const now = Date.now();
        const renewed = withToken(this.store.get(id) ?? account,
            { gitlabUrl: account.gitlabUrl, username: account.username, info: rotated.info, savedAt: now },
            { groupPath: account.groupPath, limit: account.limit });
        await this.store.put(renewed);
        log.info('rotate ' + id + ': done, the new token expires on ' + (rotated.info.expiresAt ?? 'no date'));
        void vscode.window.showInformationMessage(rotatedText(renewed), copy)
            .then(choice => choice === copy ? vscode.env.clipboard.writeText(rotated.token) : undefined);
        return rotated.token;
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
