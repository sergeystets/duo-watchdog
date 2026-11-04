import * as vscode from 'vscode';
import { AccountStore, TOKEN_PREFIX } from './accounts';
import { Commands } from './commands';
import { parsePercents } from './core/alert';
import { DashboardView } from './dashboardView';
import { DuoCliLogScanner } from './duoCliLogs';
import { SessionsService } from './sessions';
import { copyDiagnostics } from './diagnostics';
import { GlabSync } from './glabSync';
import { HistoryCache } from './history';
import { nodeTransport } from './httpTransport';
import { LiveSessionWatcher } from './liveSession';
import { log } from './log';
import { CreditsService } from './service';
import { OLD_TOKEN_KEY, SECTION, settings } from './settings';
import { StatusBar } from './statusBar';
import { announceRelease, showWhatsNew } from './whatsNew';

export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(log.attach(vscode.window.createOutputChannel('Duo Watchdog', { log: true })));
    log.info('Duo Watchdog ' + String(context.extension.packageJSON.version ?? '') + ' on VS Code ' + vscode.version + ', '
        + process.platform + ' ' + process.arch + (vscode.env.remoteName != null ? ', remote ' + vscode.env.remoteName : ''));
    const store = new AccountStore(context);
    const service = new CreditsService(context, store, nodeTransport);
    const statusBar = new StatusBar();
    const glab = new GlabSync(store, service);
    const commands = new Commands(store, service, nodeTransport, glab);
    const history = new HistoryCache(store, nodeTransport, context.globalStorageUri);
    const logs = new DuoCliLogScanner(context.globalStorageUri);
    const sessions = new SessionsService(store, nodeTransport, history, logs);
    const live = new LiveSessionWatcher();
    const dashboard = new DashboardView(context.extensionUri, service, commands, history, context.globalState, sessions,
        live);

    context.subscriptions.push(
        service,
        statusBar,
        history,
        history.onDidChange(() => dashboard.update()),
        sessions,
        sessions.onDidChange(() => dashboard.update()),
        live,
        live.onDidChange(() => {
            statusBar.live(live.meter());
            dashboard.liveChanged();
        }),
        // Which run of the Duo CLI is the workspace's.
        vscode.workspace.onDidChangeWorkspaceFolders(() => statusBar.live(live.meter())),
        // glab follows the picked account; a refresh catches up with tokens added or glab installed since.
        service.onDidRefresh(() => void glab.sync()),
        vscode.window.registerWebviewViewProvider(DashboardView.ID, dashboard),
        service.onDidChange(() => {
            statusBar.update(service.overview());
            dashboard.update();
        }),
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration(SECTION)) {
                warnAboutPercents();
                service.reschedule();
            }
        }),
        // A token saved or deleted here or in another window.
        context.secrets.onDidChange(e => {
            if (e.key === OLD_TOKEN_KEY || e.key.startsWith(TOKEN_PREFIX)) {
                service.refreshSoon();
            }
        }),
        vscode.commands.registerCommand('duoWatchdog.refresh', () => {
            dashboard.refreshStatistics();
            return service.refresh();
        }),
        vscode.commands.registerCommand('duoWatchdog.whatsNew', () => showWhatsNew(context.extensionUri)),
        vscode.commands.registerCommand('duoWatchdog.copyDiagnostics', () => copyDiagnostics(context, store, service, logs, live)),
        vscode.commands.registerCommand('duoWatchdog.sessions', () => dashboard.show('sessions')),
        vscode.commands.registerCommand('duoWatchdog.showLog', () => log.show()),
        vscode.commands.registerCommand('duoWatchdog.openDashboard',
            () => vscode.commands.executeCommand(DashboardView.ID + '.focus')),
        vscode.commands.registerCommand('duoWatchdog.openSettings',
            () => vscode.commands.executeCommand('workbench.action.openSettings', '@ext:local.duo-watchdog')),
        ...commands.register(),
    );

    statusBar.update(service.overview());
    // Before the first refresh, which may move the token saved by 0.3 to an account.
    announceRelease(context, store).catch(e => console.error('[duo-watchdog] the release notice failed', e));
    service.reschedule();
}

export function deactivate(): void {
}

/** Settings take any text; a percents list that does not parse is pointed out, and the default ones are used. */
function warnAboutPercents(): void {
    try {
        parsePercents(settings().alertPercents);
    } catch (e) {
        void vscode.window.showWarningMessage('Duo Watchdog: ' + (e as Error).message
            + '. Use whole percents, e.g. 80, 95. Until then it alerts at 80 and 95%.');
    }
}
