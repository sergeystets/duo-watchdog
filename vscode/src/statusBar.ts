import * as vscode from 'vscode';
import { clamp, level, Level } from './core/dashboard';
import { localToday } from './core/dates';
import { Snapshot } from './core/report';
import { statusText, tooltipLines } from './core/view';
import { settings } from './settings';

const ICONS: Record<Level, string> = { normal: '$(pulse)', warning: '$(warning)', blocked: '$(error)' };
const SEGMENTS = 6;

/**
 * "$21.5 / 300" with a mini meter in the status bar, amber from the lowest alert percent and red once GitLab blocks
 * Duo; the tooltip summarises, a click opens the dashboard.
 */
export class StatusBar implements vscode.Disposable {

    private readonly item = vscode.window.createStatusBarItem('duoWatchdog.status', vscode.StatusBarAlignment.Right, 100);

    constructor() {
        this.item.name = 'Duo Watchdog';
        this.item.command = 'duoWatchdog.openDashboard';
        this.item.show();
    }

    update(snapshot: Snapshot): void {
        const { limit, percents } = settings();
        const report = snapshot.report;
        const lvl = level(report, limit, percents);
        const meter = report == null ? '' : meterText(lvl === 'blocked' ? 1 : limit > 0 ? report.creditsUsed / limit : 0) + ' ';
        this.item.text = ICONS[lvl] + ' ' + meter + statusText(snapshot, limit);
        this.item.backgroundColor = lvl === 'warning' ? new vscode.ThemeColor('statusBarItem.warningBackground')
            : lvl === 'blocked' ? new vscode.ThemeColor('statusBarItem.errorBackground')
                : undefined;
        const tip = new vscode.MarkdownString('**Duo Watchdog**');
        for (const line of tooltipLines(snapshot, limit, localToday())) {
            tip.appendMarkdown('  \n');
            tip.appendText(line);
        }
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
