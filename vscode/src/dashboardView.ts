import { randomBytes } from 'crypto';
import * as vscode from 'vscode';
import { dashboardModel } from './core/dashboard';
import { localToday } from './core/dates';
import { CreditsService } from './service';
import { settings } from './settings';

/** The commands the page's buttons and links run. */
const ACTIONS: Record<string, string> = {
    refresh: 'duoWatchdog.refresh',
    settings: 'duoWatchdog.openSettings',
    setToken: 'duoWatchdog.setToken',
    findGroup: 'duoWatchdog.findGroup',
    createToken: 'duoWatchdog.createToken',
};

/**
 * The Duo Watchdog view in its own activity bar container. The extension works out the numbers and texts
 * (core/dashboard.ts) and posts them; media/dashboard.js lays them out and draws the meter and the charts.
 */
export class DashboardView implements vscode.WebviewViewProvider {

    static readonly ID = 'duoWatchdog.dashboard';
    private view: vscode.WebviewView | undefined;

    constructor(private readonly extensionUri: vscode.Uri, private readonly service: CreditsService) {
    }

    resolveWebviewView(view: vscode.WebviewView): void {
        this.view = view;
        const media = vscode.Uri.joinPath(this.extensionUri, 'media');
        view.webview.options = { enableScripts: true, localResourceRoots: [media] };
        view.webview.html = this.html(view.webview, media);
        view.webview.onDidReceiveMessage((message: { type?: string }) => {
            if (message.type === 'ready') {
                this.update();
            } else if (message.type && ACTIONS[message.type]) {
                void vscode.commands.executeCommand(ACTIONS[message.type]);
            }
        });
        view.onDidChangeVisibility(() => this.update());
        view.onDidDispose(() => this.view = undefined);
    }

    /** Posts the current numbers, while the view is open. */
    update(): void {
        if (!this.view?.visible) {
            return;
        }
        const s = settings();
        const model = dashboardModel(this.service.snapshot, s.limit, s.percents, localToday(), s.groupPath);
        void this.view.webview.postMessage({ type: 'model', model });
    }

    private html(webview: vscode.Webview, media: vscode.Uri): string {
        const nonce = randomBytes(16).toString('base64');
        const script = webview.asWebviewUri(vscode.Uri.joinPath(media, 'dashboard.js'));
        const style = webview.asWebviewUri(vscode.Uri.joinPath(media, 'dashboard.css'));
        const csp = [
            "default-src 'none'",
            `style-src ${webview.cspSource}`,
            `script-src 'nonce-${nonce}'`,
            `img-src ${webview.cspSource}`,
        ].join('; ');
        return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="${style}">
<title>Duo Watchdog</title>
</head>
<body>
<div class="toolbar">
  <button class="icon" data-action="refresh" title="Refresh" aria-label="Refresh">
    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9M12 1.5v3h-3" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
  </button>
  <button class="icon" data-action="settings" title="Settings" aria-label="Settings">
    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 5.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Zm5.6 3.3-.1-1.8 1.3-1-1.3-2.3-1.6.6-1.5-.9L10.2 2H7.6l-.3 1.5-1.5.9-1.6-.6-1.3 2.3 1.3 1-.1 1.8-1.2 1 1.3 2.3 1.6-.6 1.5.9.3 1.5h2.6l.3-1.5 1.5-.9 1.6.6 1.3-2.3Z" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/></svg>
  </button>
  <span class="byline" id="byline"></span>
</div>
<main id="page"></main>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
    }
}
