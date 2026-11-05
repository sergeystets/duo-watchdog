import { promises as fs } from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { newLog } from './core/cliLaunch';
import { isLog, logFolders, wslDistro } from './core/duoCliLogs';
import { LiveLog } from './core/liveLog';
import { Meter, pickRun, Run } from './core/liveSession';
import { firstFolder } from './duoCliLogs';
import { log } from './log';
import { settings } from './settings';

const POLL_MS = 2000;
/** A log not written to for this long is of a run that ended, or that has waited for its user long enough. */
const ACTIVE_MS = 30 * 60_000;
/** Of each folder, the logs the CLI made last: its runs that may still be going. */
const NEWEST = 5;

/**
 * The Duo CLI session in progress, for the status bar and the Sessions view. Every two seconds the CLI's newest logs,
 * on the machine the extension runs on (and, on Windows, in the workspace's WSL distributions), are looked at, and the
 * lines a log gained since are read. It is a poll rather than a file watcher: a folder in WSL, reached over \\wsl$,
 * does not report its changes. The CLI appends each line to its log as it logs it, so a step shows within the two
 * seconds. Of a line only its first characters are kept for reading (LiveLog); nothing of it is logged or stored. A
 * step on a model the log does not name or price is priced as the group's default model in the settings, read each poll.
 */
export class LiveSessionWatcher implements vscode.Disposable {

    /** The logs being followed. */
    private readonly tails = new Map<string, LiveLog>();
    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;
    private runs: Run[] = [];
    private polling = false;
    private readonly timer: NodeJS.Timeout;

    constructor() {
        this.timer = setInterval(() => void this.poll(), POLL_MS);
        void this.poll();
    }

    /**
     * The run to show, with something counted: the CLI's in a workspace folder, else the one written to last; null for
     * none.
     */
    run(): Run | null {
        const folders = (vscode.workspace.workspaceFolders ?? []).map(f => f.uri.fsPath);
        return pickRun(this.runs, folders);
    }

    /** The session to show, as run() picks it; null for none. */
    meter(): Meter | null {
        return this.run()?.meter ?? null;
    }

    /** The CLI's logs written to in the last half hour, as of the last poll. */
    logs(): string[] {
        return this.runs.map(r => r.log);
    }

    /** The first log not in `before` that a poll finds: a run started since; null when none comes within `ms`. */
    waitForNewLog(before: ReadonlySet<string>, ms: number): Promise<string | null> {
        const found = newLog(before, this.logs());
        if (found != null) {
            return Promise.resolve(found);
        }
        return new Promise(resolve => {
            const done = (name: string | null) => {
                listener.dispose();
                clearTimeout(timer);
                resolve(name);
            };
            const listener = this.onDidChange(() => {
                const name = newLog(before, this.logs());
                if (name != null) {
                    done(name);
                }
            });
            const timer = setTimeout(() => done(null), ms);
        });
    }

    /** For Copy Diagnostics: the runs followed now, in counts. */
    describe(): string {
        if (this.runs.length === 0) {
            return 'none';
        }
        return this.runs.map(r => r.log + ': ' + (r.meter == null ? 'nothing counted yet'
            : (r.meter.session != null ? 'session ' + r.meter.session : 'a new session') + ', ' + r.meter.steps + ' steps, '
            + r.meter.roundTrips + ' round trips, model ' + (r.meter.model ?? 'not logged'))).join('; ');
    }

    private async poll(): Promise<void> {
        if (this.polling) {
            return;
        }
        this.polling = true;
        try {
            const now = Date.now();
            const distros = process.platform === 'win32'
                ? (vscode.workspace.workspaceFolders ?? []).map(f => wslDistro(f.uri.fsPath)).filter((d): d is string => d != null)
                : [];
            const found: Run[] = [];
            const assumed = settings().groupDefaultModel;
            const followed = new Set<string>();
            for (const folder of logFolders(process.platform, os.tmpdir(), distros)) {
                const dir = await firstFolder(folder.paths);
                if (dir == null) {
                    continue;
                }
                const names = (await fs.readdir(dir).catch(() => [] as string[])).filter(isLog).sort().reverse().slice(0, NEWEST);
                for (const name of names) {
                    const file = path.join(dir, name);
                    try {
                        const stat = await fs.stat(file);
                        if (now - stat.mtimeMs > ACTIVE_MS) {
                            continue;
                        }
                        const tail = this.tails.get(file) ?? new LiveLog();
                        this.tails.set(file, tail);
                        await tail.read(file);
                        followed.add(file);
                        found.push({ log: name, modified: stat.mtimeMs, meter: tail.meter(assumed) });
                    } catch {
                        // Gone, or not readable: not followed.
                    }
                }
            }
            for (const file of [...this.tails.keys()]) {
                if (!followed.has(file)) {
                    this.tails.delete(file);
                }
            }
            found.sort((a, b) => b.modified - a.modified);
            const before = JSON.stringify(this.runs.map(r => [r.log, r.meter]));
            this.runs = found;
            if (JSON.stringify(found.map(r => [r.log, r.meter])) !== before) {
                this.changed.fire();
            }
        } catch (e) {
            log.warn('Duo CLI session in progress: ' + (e instanceof Error ? e.message : String(e)));
        } finally {
            this.polling = false;
        }
    }

    dispose(): void {
        clearInterval(this.timer);
        this.changed.dispose();
    }
}
