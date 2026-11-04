import * as vscode from 'vscode';
import { accountStatusText, othersNeedAttention, tooltipModel } from './core/accountsView';
import { clamp, level, Level } from './core/dashboard';
import { localToday } from './core/dates';
import { liveStatusText, liveTooltip, Meter } from './core/liveSession';
import { Overview } from './service';
import { settings } from './settings';

const ICONS: Record<Level, string> = { normal: '$(pulse)', warning: '$(warning)', blocked: '$(error)' };
const SEGMENTS = 6;
/** Appended when an account other than the one shown needs attention; the tooltip says which. */
const BELL = ' $(bell-dot)';

/**
 * "$21.5 / 300" with a mini meter in the status bar for the selected account, amber from the lowest alert percent
 * and red once GitLab blocks Duo or turns the token down, with a bell when another account needs attention. The
 * tooltip lists every account; a click opens the dashboard. While the Duo CLI runs a session, its steps and ≈ credits
 * follow, and the tooltip says its round trips and model too.
 */
export class StatusBar implements vscode.Disposable {

    private readonly item = vscode.window.createStatusBarItem('duoWatchdog.status', vscode.StatusBarAlignment.Right, 100);
    private overview: Overview | undefined;
    private meter: Meter | null = null;

    constructor() {
        this.item.name = 'Duo Watchdog';
        this.item.command = 'duoWatchdog.openDashboard';
        this.item.show();
    }

    update(overview: Overview): void {
        this.overview = overview;
        this.render();
    }

    /** The Duo CLI session now, or null for none. */
    live(meter: Meter | null): void {
        this.meter = meter;
        this.render();
    }

    private render(): void {
        const overview = this.overview;
        if (overview == null) {
            return;
        }
        const live = this.meter;
        const { percents } = settings();
        const today = localToday();
        const selected = overview.selected;
        const report = selected != null && selected.problem == null ? selected.snapshot.report : null;
        const limit = selected?.account.limit ?? 0;
        const lvl: Level = selected?.problem === 'rejected' ? 'blocked' : level(report, limit, percents);
        const meter = report == null ? '' : meterText(lvl === 'blocked' ? 1 : limit > 0 ? report.creditsUsed / limit : 0) + ' ';
        const bell = othersNeedAttention(overview.states, selected?.id ?? null, percents, today) ? BELL : '';
        this.item.text = ICONS[lvl] + ' ' + meter + accountStatusText(selected, overview.setup) + bell
            + (live != null ? ' · ' + liveStatusText(live) : '');
        this.item.backgroundColor = lvl === 'warning' ? new vscode.ThemeColor('statusBarItem.warningBackground')
            : lvl === 'blocked' ? new vscode.ThemeColor('statusBarItem.errorBackground')
                : undefined;

        const model = tooltipModel(overview.states, selected?.id ?? null, overview.setup, percents, today);
        const tip = new vscode.MarkdownString('**Duo Watchdog**');
        if (model.attention != null) {
            tip.appendMarkdown('  \n$(bell-dot) ');
            tip.appendText(model.attention);
        }
        tip.supportThemeIcons = true;
        for (const block of live != null ? [...model.blocks, liveTooltip(live)] : model.blocks) {
            tip.appendMarkdown('\n\n**');
            tip.appendText(block.title);
            tip.appendMarkdown('**');
            for (const line of block.lines) {
                tip.appendMarkdown('  \n');
                tip.appendText(line);
            }
        }
        tip.appendMarkdown('\n\n');
        model.footer.forEach((line, i) => {
            if (i > 0) {
                tip.appendMarkdown('  \n');
            }
            tip.appendText(line);
        });
        this.item.tooltip = tip;
    }

    dispose(): void {
        this.item.dispose();
    }
}

/** "▰▰▱▱▱▱": used against the limit in six segments, any use showing at least one. */
export function meterText(fraction: number): string {
    const f = clamp(fraction);
    const filled = f > 0 ? Math.max(1, Math.round(f * SEGMENTS)) : 0;
    return '▰'.repeat(filled) + '▱'.repeat(SEGMENTS - filled);
}
