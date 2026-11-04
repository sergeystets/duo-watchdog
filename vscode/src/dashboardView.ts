import { randomBytes } from 'crypto';
import * as vscode from 'vscode';
import { Commands } from './commands';
import { accountsDashboard } from './core/accountsView';
import { localToday } from './core/dates';
import { RANGE_KEY } from './history';
import { rangeOrDefault, StatisticsRange } from './core/monthly';
import { Scope, statisticsView, trackedStates } from './core/statistics';
import { accountGroup } from './core/accounts';
import { around, contains, dayOf, Range, Session, sessionId, utcToday } from './core/sessions';
import { DEFAULT_KIND, RANGE_KINDS, RangeKind, rangeOf, sessionsView } from './core/sessionsView';
import { liveCard } from './core/liveSession';
import { HistoryCache } from './history';
import { LiveSessionWatcher } from './liveSession';
import { log } from './log';
import { CreditsService } from './service';
import { SessionsService } from './sessions';
import { settings } from './settings';

/** The commands the page's buttons and links run. */
const ACTIONS: Record<string, string> = {
    refresh: 'duoWatchdog.refresh',
    settings: 'duoWatchdog.openSettings',
    accounts: 'duoWatchdog.accounts',
    addToken: 'duoWatchdog.addToken',
    findGroup: 'duoWatchdog.findGroup',
    createToken: 'duoWatchdog.createToken',
};

export type Tab = 'usage' | 'statistics' | 'sessions';
const TABS: readonly Tab[] = ['usage', 'statistics', 'sessions'];

/**
 * The Duo Watchdog view in its own activity bar container. The extension works out the numbers and texts
 * (core/dashboard.ts, core/accountsView.ts, core/statistics.ts) and posts them; media/dashboard.js lays them out and
 * draws the meter and the charts. The account picker at the top switches the account the view and the status bar
 * show; Usage and Statistics switch between this period and the last twelve months. Sessions starts with the Duo CLI
 * session in progress, when there is one.
 */
export class DashboardView implements vscode.WebviewViewProvider {

    static readonly ID = 'duoWatchdog.dashboard';
    private view: vscode.WebviewView | undefined;
    private tab: Tab = 'usage';
    private scope: Scope = 'account';
    /** The Sessions view's range, the session shown in full, and what it last had to say. */
    private sessionsKind: RangeKind = DEFAULT_KIND;
    private sessionsCustom: Range | null = null;
    private focused: number | null = null;
    private sessionsMessage: string | null = null;

    /** @param state where the Statistics range is remembered */
    constructor(private readonly extensionUri: vscode.Uri, private readonly service: CreditsService,
                private readonly commands: Commands, private readonly history: HistoryCache,
                private readonly state: vscode.Memento, private readonly sessions: SessionsService,
                private readonly live: LiveSessionWatcher) {
    }

    /** The Duo CLI session now changed: shown on Sessions only. */
    liveChanged(): void {
        if (this.tab === 'sessions') {
            this.update();
        }
    }

    /** Opens the view on a tab: what Duo Watchdog: Sessions does. */
    async show(tab: Tab): Promise<void> {
        this.tab = tab;
        await vscode.commands.executeCommand(DashboardView.ID + '.focus');
        this.update();
    }

    private sessionsRange(): Range {
        return rangeOf(this.sessionsKind, utcToday(), this.sessionsCustom);
    }

    /**
     * Shows a session in full: within the range when it is there, else with the range switched to its days. Typed IDs,
     * the Last session button and the rows come here.
     */
    private async focus(found: Session | null, id: number | null): Promise<void> {
        const selected = this.service.overview().selected;
        if (found == null) {
            this.sessionsMessage = id == null ? 'No sessions yet' + (selected ? ' for @' + selected.account.username : '') + '.'
                : 'No session #' + id + (selected ? ' for @' + selected.account.username : '') + '.';
            this.update();
            return;
        }
        this.sessionsMessage = null;
        this.focused = found.id;
        const range = this.sessionsRange();
        if (!contains(range, dayOf(found.updated)) && !contains(range, dayOf(found.created))) {
            this.sessionsKind = 'custom';
            this.sessionsCustom = around(found);
        }
        this.update();
    }

    /** The session typed in the text box, by its number, gid or address: found in the range, or asked of GitLab. */
    private async find(text: string, last: boolean): Promise<void> {
        const account = this.service.overview().selected?.account;
        if (account == null) {
            return;
        }
        const id = last ? null : sessionId(text);
        if (!last && id == null) {
            return; // Not an ID: the page filters by title and project itself.
        }
        const entry = this.sessions.entry(account, this.sessionsRange());
        const listed = entry?.summary?.rows.find(e => e.session.id === id)?.session ?? null;
        try {
            await this.focus(listed ?? (last ? await this.sessions.last(account) : await this.sessions.find(account, id!)), id);
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('sessions: ' + message);
            this.sessionsMessage = message;
            this.update();
        }
    }

    private get range(): StatisticsRange {
        return rangeOrDefault(this.state.get(RANGE_KEY));
    }

    resolveWebviewView(view: vscode.WebviewView): void {
        this.view = view;
        const media = vscode.Uri.joinPath(this.extensionUri, 'media');
        view.webview.options = { enableScripts: true, localResourceRoots: [media] };
        view.webview.html = this.html(view.webview, media);
        view.webview.onDidReceiveMessage((message: Record<string, unknown>) => {
            if (message.type === 'ready') {
                this.update();
            } else if (message.type === 'tab' && TABS.includes(message.tab as Tab)) {
                this.tab = message.tab as Tab;
                this.update();
            } else if (message.type === 'sessionsRange' && RANGE_KINDS.includes(message.kind as RangeKind)) {
                this.sessionsKind = message.kind as RangeKind;
                if (this.sessionsKind === 'custom' && typeof message.from === 'string' && typeof message.to === 'string') {
                    this.sessionsCustom = { from: message.from, to: message.to };
                } else if (this.sessionsKind === 'custom' && this.sessionsCustom == null) {
                    this.sessionsCustom = this.sessionsRange();
                }
                this.sessionsMessage = null;
                this.update();
            } else if (message.type === 'sessionsFind' && typeof message.text === 'string') {
                void this.find(message.text, false);
            } else if (message.type === 'lastSession') {
                void this.find('', true);
            } else if (message.type === 'sessionsFocus' && typeof message.id === 'number') {
                this.focused = this.focused === message.id ? null : message.id;
                this.update();
            } else if (message.type === 'open' && typeof message.url === 'string' && /^https?:\/\//i.test(message.url)) {
                void vscode.env.openExternal(vscode.Uri.parse(message.url));
            } else if (message.type === 'range' && rangeOrDefault(message.months) === message.months) {
                void this.state.update(RANGE_KEY, message.months).then(() => this.update());
            } else if (message.type === 'scope' && (message.scope === 'account' || message.scope === 'all')) {
                this.scope = message.scope;
                this.update();
            } else if (message.type === 'select' && typeof message.id === 'string') {
                void this.commands.pick(message.id);
            } else if (message.type === 'replaceToken') {
                void this.commands.replaceSelected();
            } else if (typeof message.type === 'string' && ACTIONS[message.type]) {
                void vscode.commands.executeCommand(ACTIONS[message.type]);
            }
        });
        view.onDidChangeVisibility(() => this.update());
        view.onDidDispose(() => this.view = undefined);
    }

    /** Refresh: the statistics and the sessions are asked again too, at once when they are shown. */
    refreshStatistics(): void {
        this.history.invalidate();
        this.sessions.invalidate();
        this.update();
    }

    /** Posts the current numbers, while the view is open; Statistics fetches what it shows first. */
    update(): void {
        if (!this.view?.visible) {
            return;
        }
        const overview = this.service.overview();
        const today = localToday();
        const usage = accountsDashboard(overview.states, overview.selected?.id ?? null, overview.setup,
            settings().percents, today);
        let statistics = null;
        if (this.tab === 'statistics') {
            const wanted = this.scope === 'all' ? trackedStates(overview.states)
                : overview.selected != null && overview.selected.problem == null ? [overview.selected] : [];
            const range = this.range;
            this.history.ensure(wanted.map(s => s.account), range);
            statistics = statisticsView(this.scope, overview.selected, overview.states,
                account => this.history.entry(account, range), today, range);
        }
        let sessions = null;
        if (this.tab === 'sessions' && overview.selected != null) {
            const selected = overview.selected;
            const range = this.sessionsRange();
            if (selected.problem == null) {
                this.sessions.ensure(selected.account, range);
            }
            sessions = sessionsView(this.sessionsKind, range, selected.problem != null,
                this.sessions.entry(selected.account, range), accountGroup(selected.account), this.focused, this.sessionsMessage);
        }
        const live = this.tab === 'sessions' ? liveCard(this.live.meter(), settings().roundTripLimit) : null;
        const model = { ...usage, tab: this.tab, statistics, sessions, live };
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
  <button class="icon" data-action="accounts" title="Accounts" aria-label="Accounts">
    <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="5.5" r="2.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M2.8 14c.5-2.8 2.6-4.4 5.2-4.4s4.7 1.6 5.2 4.4" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>
  </button>
  <button class="icon" data-action="settings" title="Settings" aria-label="Settings">
    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 5.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Zm5.6 3.3-.1-1.8 1.3-1-1.3-2.3-1.6.6-1.5-.9L10.2 2H7.6l-.3 1.5-1.5.9-1.6-.6-1.3 2.3 1.3 1-.1 1.8-1.2 1 1.3 2.3 1.6-.6 1.5.9.3 1.5h2.6l.3-1.5 1.5-.9 1.6.6 1.3-2.3Z" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/></svg>
  </button>
  <span class="byline" id="byline"></span>
</div>
<div class="account" id="account-row" hidden>
  <label for="account">Account</label>
  <select id="account"></select>
</div>
<div class="switch tabs" id="tabs" role="tablist" aria-label="View" hidden>
  <button role="tab" data-tab="usage" aria-selected="true">Usage</button>
  <button role="tab" data-tab="statistics" aria-selected="false">Statistics</button>
  <button role="tab" data-tab="sessions" aria-selected="false">Sessions</button>
</div>
<main id="page"></main>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
    }
}
