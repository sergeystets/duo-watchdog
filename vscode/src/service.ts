import * as vscode from 'vscode';
import { groupPath } from './core/address';
import { alertFor, AlertKind } from './core/alert';
import { CreditsClient, Transport } from './core/client';
import { localToday } from './core/dates';
import { Snapshot, Snapshots } from './core/report';
import { LAST_ALERT_KEY, settings, TOKEN_KEY } from './settings';

/** Refreshes the user's credits on a schedule and on demand; one per window, since the numbers are the user's. */
export class CreditsService implements vscode.Disposable {

    private readonly changed = new vscode.EventEmitter<Snapshot>();
    readonly onDidChange = this.changed.event;
    private current: Snapshot = Snapshots.loading();
    private timer: ReturnType<typeof setInterval> | undefined;
    private loading = false;

    constructor(private readonly context: vscode.ExtensionContext, private readonly transport: Transport) {
    }

    get snapshot(): Snapshot {
        return this.current;
    }

    /** Restarts the schedule with the current settings and refreshes now. */
    reschedule(): void {
        if (this.timer !== undefined) {
            clearInterval(this.timer);
        }
        this.timer = setInterval(() => void this.refresh(), settings().refreshMinutes * 60_000);
        void this.refresh();
    }

    async refresh(): Promise<void> {
        if (this.loading) {
            return;
        }
        this.loading = true;
        try {
            this.publish(await this.load());
        } finally {
            this.loading = false;
        }
    }

    private async load(): Promise<Snapshot> {
        const s = settings();
        const token = (await this.context.secrets.get(TOKEN_KEY))?.trim();
        if (s.groupPath.trim() === '' || !token) {
            return Snapshots.notConfigured();
        }
        const group = groupPath(s.groupPath, s.gitlabUrl);
        if (group === '') {
            return Snapshots.failed(this.current, "Enter the group path from the group's address, e.g. my-company, "
                + 'or run the command Duo Watchdog: Find My Group.');
        }
        try {
            const client = new CreditsClient(s.gitlabUrl, token, this.transport);
            return Snapshots.ok(await client.fetch(group), new Date());
        } catch (e) {
            // The message is the user's to read; it never carries the token.
            return Snapshots.failed(this.current, e instanceof Error ? e.message : String(e));
        }
    }

    private publish(next: Snapshot): void {
        this.current = next;
        if (next.status === 'ok') {
            this.notifyOnce(next);
        }
        this.changed.fire(next);
    }

    private notifyOnce(s: Snapshot): void {
        const { limit, percents, forecastAlert } = settings();
        const alert = s.report && alertFor(s.report, limit, percents, forecastAlert, localToday());
        if (!alert || alert.key === this.context.globalState.get<string>(LAST_ALERT_KEY)) {
            return;
        }
        void this.context.globalState.update(LAST_ALERT_KEY, alert.key);
        const open = 'Open Duo Watchdog';
        void show(alert.kind, alert.title + '. ' + alert.message, open).then(choice => {
            if (choice === open) {
                void vscode.commands.executeCommand('duoWatchdog.openDashboard');
            }
        });
    }

    dispose(): void {
        if (this.timer !== undefined) {
            clearInterval(this.timer);
        }
        this.changed.dispose();
    }
}

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
