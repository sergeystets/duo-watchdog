import * as vscode from 'vscode';
import { host, tokenPage } from './core/address';
import { CreditsClient, GitLabError, groupLabel, Groups } from './core/client';
import { DashboardView } from './dashboardView';
import { nodeTransport } from './httpTransport';
import { CreditsService } from './service';
import { SECTION, settings, TOKEN_KEY } from './settings';
import { StatusBar } from './statusBar';
import { parsePercents } from './core/alert';

export function activate(context: vscode.ExtensionContext): void {
    const service = new CreditsService(context, nodeTransport);
    const statusBar = new StatusBar();
    const dashboard = new DashboardView(context.extensionUri, service);

    context.subscriptions.push(
        service,
        statusBar,
        vscode.window.registerWebviewViewProvider(DashboardView.ID, dashboard),
        service.onDidChange(snapshot => {
            statusBar.update(snapshot);
            dashboard.update();
        }),
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration(SECTION)) {
                warnAboutPercents();
                service.reschedule();
            }
        }),
        context.secrets.onDidChange(e => {
            if (e.key === TOKEN_KEY) {
                service.reschedule();
            }
        }),
        vscode.commands.registerCommand('duoWatchdog.refresh', () => service.refresh()),
        vscode.commands.registerCommand('duoWatchdog.openDashboard',
            () => vscode.commands.executeCommand(DashboardView.ID + '.focus')),
        vscode.commands.registerCommand('duoWatchdog.openSettings',
            () => vscode.commands.executeCommand('workbench.action.openSettings', '@ext:local.duo-watchdog')),
        vscode.commands.registerCommand('duoWatchdog.createToken',
            () => vscode.env.openExternal(vscode.Uri.parse(tokenPage(settings().gitlabUrl)))),
        vscode.commands.registerCommand('duoWatchdog.setToken', () => setToken(context)),
        vscode.commands.registerCommand('duoWatchdog.findGroup', () => findGroup(context)),
    );

    statusBar.update(service.snapshot);
    service.reschedule();
}

export function deactivate(): void {
}

/** Asks for the token in a password box, with a button to GitLab's new-token page; empty keeps the saved one. */
function setToken(context: vscode.ExtensionContext): Promise<void> {
    return new Promise(resolve => {
        const input = vscode.window.createInputBox();
        input.title = 'Duo Watchdog: GitLab Token';
        input.prompt = 'The token needs two scopes: read_api and ai_features. Leave everything else unticked: '
            + 'api can change code. Kept in VS Code\'s secret storage; leave empty to keep the saved one.';
        input.placeholder = 'glpat-…';
        input.password = true;
        input.ignoreFocusOut = true;
        const create: vscode.QuickInputButton = {
            iconPath: new vscode.ThemeIcon('link-external'),
            tooltip: 'Create the token in GitLab',
        };
        input.buttons = [create];
        input.onDidTriggerButton(() => void vscode.commands.executeCommand('duoWatchdog.createToken'));
        input.onDidAccept(async () => {
            const token = input.value.trim();
            input.hide();
            if (token !== '') {
                await context.secrets.store(TOKEN_KEY, token);
            }
        });
        input.onDidHide(() => {
            input.dispose();
            resolve();
        });
        input.show();
    });
}

/** Asks GitLab which of the user's top-level groups give their credits, and fills in the one, or offers the few. */
async function findGroup(context: vscode.ExtensionContext): Promise<void> {
    const { gitlabUrl } = settings();
    const token = (await context.secrets.get(TOKEN_KEY))?.trim();
    if (!token) {
        const set = 'Set GitLab Token';
        const choice = await vscode.window.showWarningMessage(
            'Enter the personal access token first: Find my group asks GitLab with it.', set);
        if (choice === set) {
            await vscode.commands.executeCommand('duoWatchdog.setToken');
        }
        return;
    }
    let groups: Groups;
    try {
        groups = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification, title: 'Finding your group' },
            () => new CreditsClient(gitlabUrl, token, nodeTransport).findGroups());
    } catch (e) {
        void vscode.window.showErrorMessage(e instanceof GitLabError ? e.message : String(e));
        return;
    }

    const found = groups.withCredits;
    let path: string | undefined;
    if (found.length === 1) {
        path = found[0].path;
    } else if (found.length === 0) {
        void vscode.window.showWarningMessage(groups.checked.length === 0
            ? "The token's user is not a member of any group on " + host(gitlabUrl) + '.'
            : 'GitLab gave no Duo credits for you in any of your groups: ' + groups.checked.join(', ')
            + ". Check the token's read_api and ai_features scopes.");
    } else {
        const pick = await vscode.window.showQuickPick(
            found.map(match => ({ label: match.path, description: groupLabel(match) === match.path ? undefined : match.name ?? undefined })),
            { title: 'Your Groups with Duo Credits' });
        path = pick?.label;
    }
    if (path) {
        await vscode.workspace.getConfiguration(SECTION).update('groupPath', path, vscode.ConfigurationTarget.Global);
        void vscode.window.showInformationMessage('Duo Watchdog: group set to ' + path + '.');
    }
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
