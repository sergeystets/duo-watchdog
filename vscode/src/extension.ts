import * as vscode from 'vscode';
import { AccountStore, TOKEN_PREFIX } from './accounts';
import { Commands } from './commands';
import { parsePercents } from './core/alert';
import { DashboardView } from './dashboardView';
import { nodeTransport } from './httpTransport';
import { CreditsService } from './service';
import { OLD_TOKEN_KEY, SECTION, settings } from './settings';
import { StatusBar } from './statusBar';

export function activate(context: vscode.ExtensionContext): void {
    const store = new AccountStore(context);
    const service = new CreditsService(context, store, nodeTransport);
    const statusBar = new StatusBar();
    const commands = new Commands(store, service, nodeTransport);
    const dashboard = new DashboardView(context.extensionUri, service, commands);

    context.subscriptions.push(
        service,
        statusBar,
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
        vscode.commands.registerCommand('duoWatchdog.refresh', () => service.refresh()),
        vscode.commands.registerCommand('duoWatchdog.openDashboard',
            () => vscode.commands.executeCommand(DashboardView.ID + '.focus')),
        vscode.commands.registerCommand('duoWatchdog.openSettings',
            () => vscode.commands.executeCommand('workbench.action.openSettings', '@ext:local.duo-watchdog')),
        ...commands.register(),
    );

    statusBar.update(service.overview());
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
