import * as vscode from 'vscode';
import { liveBarText, liveLevel, LiveLevel, liveTooltip, Run } from './core/liveSession';
import { settings } from './settings';
import { meterText } from './statusBar';

const ICONS: Record<LiveLevel, string> = { normal: '$(terminal)', near: '$(warning)', over: '$(error)' };

/**
 * The Duo CLI session in progress in the status bar, beside the credits: "▰▰▱▱▱▱ 12m 4s · 5 round trips · 42 steps
 * · ≈ $38.2", its round trips against the recommended most, how long it has run, then what it has used; amber from
 * 80 % of the recommended round trips and red at them. Shown only while the CLI runs a session; the run time ticks
 * every second. The tooltip says the model and when it started; a click opens Sessions.
 */
export class LiveStatusBar implements vscode.Disposable {

    private readonly item = vscode.window.createStatusBarItem('duoWatchdog.cliSession', vscode.StatusBarAlignment.Right, 99);
    private run: Run | null = null;
    private timer: NodeJS.Timeout | undefined;

    constructor() {
        this.item.name = 'Duo Watchdog: Duo CLI Session';
        this.item.command = 'duoWatchdog.sessions';
    }

    /** The run to show, or null to hide. */
    update(run: Run | null): void {
        this.run = run?.meter != null ? run : null;
        if (this.run == null) {
            clearInterval(this.timer);
            this.timer = undefined;
            this.item.hide();
            return;
        }
        this.timer ??= setInterval(() => this.render(), 1000);
        this.render();
        this.item.show();
    }

    private render(): void {
        const run = this.run;
        if (run?.meter == null) {
            return;
        }
        const limit = settings().roundTripLimit;
        const now = Date.now();
        const level = liveLevel(run.meter, limit);
        this.item.text = ICONS[level] + ' ' + (limit > 0 ? meterText(run.meter.roundTrips / limit) + ' ' : '')
            + liveBarText(run, now);
        this.item.backgroundColor = level === 'near' ? new vscode.ThemeColor('statusBarItem.warningBackground')
            : level === 'over' ? new vscode.ThemeColor('statusBarItem.errorBackground')
                : undefined;
        const tip = liveTooltip(run, now, limit);
        const md = new vscode.MarkdownString('**');
        md.appendText(tip.title);
        md.appendMarkdown('**');
        for (const line of tip.lines) {
            md.appendMarkdown('  \n');
            md.appendText(line);
        }
        md.appendMarkdown('\n\n');
        md.appendText('Click for Sessions.');
        this.item.tooltip = md;
    }

    dispose(): void {
        clearInterval(this.timer);
        this.item.dispose();
    }
}
