import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { FRESH_TEXT, releaseNotice, upgradeText, WHATS_NEW_MISSING, whatsNewHtml } from './core/release';
import { OLD_TOKEN_KEY, SECTION } from './settings';

/** The version the user last saw a notice for, so it shows once a version. */
export const LAST_SEEN_KEY = 'duoWatchdog.lastSeenVersion';

const SETTINGS = ['gitlabUrl', 'groupPath', 'limit', 'alertPercents', 'forecastAlert', 'refreshMinutes'];

/**
 * Once after an update, "Duo Watchdog 0.4.0: several accounts, statistics and more.", or after a fresh install, how
 * to start. Call it before the first refresh, which may move the token saved by 0.3 to an account.
 */
export async function announceRelease(context: vscode.ExtensionContext, store: AccountStore): Promise<void> {
    const version = String(context.extension.packageJSON.version ?? '');
    const lastSeen = context.globalState.get<string>(LAST_SEEN_KEY);
    if (lastSeen === version) {
        return;
    }
    const oldToken = await context.secrets.get(OLD_TOKEN_KEY);
    const config = vscode.workspace.getConfiguration(SECTION);
    const configured = store.list().length > 0 || (oldToken != null && oldToken.trim() !== '')
        || SETTINGS.some(key => config.inspect(key)?.globalValue !== undefined);
    const notice = releaseNotice(version, lastSeen, configured);
    await context.globalState.update(LAST_SEEN_KEY, version);

    const whatsNew = "What's New";
    if (notice === 'upgrade') {
        const choice = await vscode.window.showInformationMessage(upgradeText(version), whatsNew);
        if (choice === whatsNew) {
            await vscode.commands.executeCommand('duoWatchdog.whatsNew');
        }
    } else if (notice === 'fresh') {
        const add = 'Add a Token';
        const choice = await vscode.window.showInformationMessage(FRESH_TEXT, add, whatsNew);
        if (choice === add) {
            await vscode.commands.executeCommand('duoWatchdog.addToken');
        } else if (choice === whatsNew) {
            await vscode.commands.executeCommand('duoWatchdog.whatsNew');
        }
    }
}

let panel: vscode.WebviewPanel | undefined;

/**
 * "What's New in Duo Watchdog": media/whats-new/whats-new.html in a webview panel, its images from that folder,
 * inline styles allowed, no scripts.
 */
export async function showWhatsNew(extensionUri: vscode.Uri): Promise<void> {
    if (panel != null) {
        panel.reveal();
        return;
    }
    const folder = vscode.Uri.joinPath(extensionUri, 'media', 'whats-new');
    const shown = vscode.window.createWebviewPanel('duoWatchdog.whatsNew', "What's New in Duo Watchdog",
        vscode.ViewColumn.Active, { enableScripts: false, enableCommandUris: false, localResourceRoots: [folder] });
    panel = shown;
    shown.iconPath = vscode.Uri.joinPath(extensionUri, 'media', 'icon.png');
    shown.onDidDispose(() => panel = undefined);
    let html: string;
    try {
        html = Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.joinPath(folder, 'whats-new.html'))).toString('utf8');
    } catch {
        html = WHATS_NEW_MISSING;
    }
    shown.webview.html = whatsNewHtml(html,
        relative => shown.webview.asWebviewUri(vscode.Uri.joinPath(folder, ...relative.split('/'))).toString(),
        shown.webview.cspSource);
}
