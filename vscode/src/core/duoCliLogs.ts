import * as path from 'path';
import { IsoDate, parseDate } from './dates';
import { Steps } from './sessions';

/**
 * The agent's steps in each session, from the GitLab Duo CLI's logs (`duo log list`): one line
 * `[DuoWorkflowNodeExecutor][<session>] [requestID: <id>] Action received` for each action the model asked the CLI to
 * run. Only those lines are looked at, and of them only the time, the session and the request: the logs also hold
 * prompts and code, and none of it is read on or kept. A port of DuoCliLogs in the plugin's core.
 */

/** The folder, under the system's temporary folder, that the CLI writes its logs to. */
export const FOLDER = 'gitlab-duo-cli';

const ACTION = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\S*\s.*?\[DuoWorkflowNodeExecutor\]\[(\d+)\]\s*\[requestID:\s*([^\]\s]+)\s*\]\s*Action received/;
const NAME = /^duo-cli-log-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-(.+)\.log$/;

/** Whether a file name is one of the CLI's logs. */
export function isLog(fileName: string): boolean {
    return fileName.startsWith('duo-cli-log-') && fileName.endsWith('.log');
}

/** The folder the CLI ran in, as the log's name gives it: its path with "-" for "/", or "" when it gives none. */
export function logFolder(fileName: string): string {
    return NAME.exec(fileName)?.[1] ?? '';
}

/** The UTC day of a log's local time "2026-10-28T15:02:14", in the zone of the machine the CLI ran on. */
export type Zone = (localDateTime: string) => IsoDate | null;

/** This machine's zone. */
export const LOCAL_ZONE: Zone = local => {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/.exec(local);
    if (m == null) {
        return null;
    }
    const at = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
    return Number.isNaN(at.getTime()) ? null : at.toISOString().slice(0, 10);
};

/** A zone a fixed number of hours from UTC, behind it when negative: for tests, and machines without one. */
export function offsetZone(hours: number): Zone {
    return local => {
        const ms = Date.parse(local + 'Z');
        return Number.isNaN(ms) || parseDate(local.slice(0, 10)) == null ? null
            : new Date(ms - hours * 3_600_000).toISOString().slice(0, 10);
    };
}

/** Counts a log's steps line by line, so a long log is never held whole. */
export class StepCounter {

    private readonly byDay = new Map<number, Map<IsoDate, number>>();
    private readonly seen = new Set<string>();

    constructor(private readonly zone: Zone = LOCAL_ZONE) {
    }

    line(text: string): void {
        if (!text.includes('Action received')) {
            return;
        }
        const m = ACTION.exec(text);
        if (m == null || this.seen.has(m[2] + '/' + m[3])) {
            return;
        }
        this.seen.add(m[2] + '/' + m[3]);
        const day = this.zone(m[1]);
        const id = Number(m[2]);
        if (day == null || !Number.isSafeInteger(id)) {
            return; // Not a step after all.
        }
        const days = this.byDay.get(id) ?? new Map<IsoDate, number>();
        days.set(day, (days.get(day) ?? 0) + 1);
        this.byDay.set(id, days);
    }

    /** Each session's steps, by UTC day. */
    steps(fileName: string): Map<number, Steps> {
        const folder = logFolder(fileName);
        const result = new Map<number, Steps>();
        for (const [id, days] of this.byDay) {
            result.set(id, { id, byDay: sortedDays(days), folder });
        }
        return result;
    }
}

function sortedDays(days: Map<IsoDate, number> | [IsoDate, number][]): Record<IsoDate, number> {
    const record: Record<IsoDate, number> = {};
    for (const [day, count] of [...days].sort((a, b) => a[0].localeCompare(b[0]))) {
        record[day] = count;
    }
    return record;
}

/** Each session's steps in one log, by UTC day. */
export function readLog(text: string, fileName: string, zone: Zone = LOCAL_ZONE): Map<number, Steps> {
    const counter = new StepCounter(zone);
    for (const line of text.split(/\r?\n/)) {
        counter.line(line);
    }
    return counter.steps(fileName);
}

/** Two logs' steps added up: a session resumed in another run of the CLI has steps in both. */
export function addSteps(a: ReadonlyMap<number, Steps>, b: ReadonlyMap<number, Steps>): Map<number, Steps> {
    const sum = new Map(a);
    for (const [id, steps] of b) {
        const x = sum.get(id);
        if (x == null) {
            sum.set(id, steps);
            continue;
        }
        const days = new Map(Object.entries(x.byDay));
        for (const [day, count] of Object.entries(steps.byDay)) {
            days.set(day, (days.get(day) ?? 0) + count);
        }
        sum.set(id, { id, byDay: sortedDays(days), folder: x.folder.trim() === '' ? steps.folder : x.folder });
    }
    return sum;
}

/**
 * One log as last read: its size and time, so it is read again only when it has changed, and its sessions' steps,
 * kept after the log is gone, since the CLI's logs live in the temporary folder.
 */
export interface LogRead {
    size: number;
    modified: number;
    steps: Map<number, Steps>;
}

/** The logs read so far as JSON: per log, its size, time and sessions' steps by day. No prompt, no code. */
export function writeLogs(logs: ReadonlyMap<string, LogRead>): string {
    const root: Record<string, unknown> = {};
    for (const [name, read] of logs) {
        const sessions: Record<string, Record<IsoDate, number>> = {};
        for (const [id, steps] of read.steps) {
            sessions[String(id)] = { ...steps.byDay };
        }
        root[name] = { size: read.size, modified: read.modified, sessions };
    }
    return JSON.stringify(root);
}

/** What writeLogs wrote; empty when the text is not that. */
export function parseLogs(json: string): Map<string, LogRead> {
    const logs = new Map<string, LogRead>();
    try {
        const root = JSON.parse(json) as Record<string, { size: unknown; modified: unknown; sessions: Record<string, Record<string, unknown>> }>;
        for (const [name, o] of Object.entries(root)) {
            if (typeof o.size !== 'number' || typeof o.modified !== 'number' || o.sessions == null || typeof o.sessions !== 'object') {
                throw new Error('not a log');
            }
            const steps = new Map<number, Steps>();
            for (const [key, days] of Object.entries(o.sessions)) {
                const id = Number(key);
                const byDay: [IsoDate, number][] = [];
                for (const [day, count] of Object.entries(days)) {
                    if (parseDate(day) == null || typeof count !== 'number' || !Number.isInteger(count)) {
                        throw new Error('not a day');
                    }
                    byDay.push([day, count]);
                }
                if (!Number.isSafeInteger(id)) {
                    throw new Error('not a session');
                }
                steps.set(id, { id, byDay: sortedDays(byDay), folder: logFolder(name) });
            }
            logs.set(name, { size: o.size, modified: o.modified, steps });
        }
        return logs;
    } catch {
        return new Map();
    }
}

/** "Ubuntu" from a folder in WSL as Windows sees it: \\wsl.localhost\Ubuntu\home\… or \\wsl$\Ubuntu\…. */
export function wslDistro(windowsPath: string): string | null {
    return /^\\\\wsl(?:\.localhost|\$)\\([^\\]+)/i.exec(windowsPath)?.[1] ?? null;
}

/** A place the CLI's logs may be: its paths to try in order, the first that exists being the one. */
export interface LogFolder {
    paths: string[];
}

/**
 * Where to look for the CLI's logs: the temporary folder of the machine the extension runs on; on Linux and macOS
 * also /tmp; on Windows, for each WSL distribution a workspace folder lives in, its /tmp, through
 * \\wsl.localhost\, else \\wsl$\.
 */
export function logFolders(platform: string, tmpdir: string, distros: readonly string[]): LogFolder[] {
    const windows = platform === 'win32';
    const p = windows ? path.win32 : path.posix;
    const folders: LogFolder[] = [{ paths: [p.join(tmpdir, FOLDER)] }];
    if (!windows && p.join(tmpdir, FOLDER) !== '/tmp/' + FOLDER) {
        folders.push({ paths: ['/tmp/' + FOLDER] });
    }
    if (windows) {
        for (const distro of [...new Set(distros)]) {
            folders.push({ paths: ['\\\\wsl.localhost\\' + distro + '\\tmp\\' + FOLDER, '\\\\wsl$\\' + distro + '\\tmp\\' + FOLDER] });
        }
    }
    return folders;
}
