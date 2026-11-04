import { createReadStream, promises as fs } from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as readline from 'readline';
import * as vscode from 'vscode';
import { addSteps, isLog, LogRead, logFolders, parseLogs, StepCounter, writeLogs, wslDistro } from './core/duoCliLogs';
import { Steps } from './core/sessions';
import { log } from './log';

/** What the last scan found, for Copy Diagnostics. */
export interface LogScan {
    folders: { path: string; found: boolean; logs: number }[];
    /** Sessions with steps counted, in every log read so far. */
    sessions: number;
}

/**
 * The agent's steps per session from the Duo CLI's logs on this machine. Each log is read once, line by line, and read
 * again only when its size or time changes; its sessions' steps are kept in the extension's storage, since the CLI's
 * logs live in the temporary folder, which gets cleaned. Only counts are kept and logged: never a line of a log.
 */
export class DuoCliLogScanner {

    private reads: Map<string, LogRead> | undefined;
    private last: LogScan | undefined;
    private running: Promise<Map<number, Steps>> | undefined;

    /** @param storage the extension's global storage, where the counts are kept */
    constructor(private readonly storage: vscode.Uri) {
    }

    lastScan(): LogScan | undefined {
        return this.last;
    }

    /** Every session's steps in the logs read so far, after reading the logs that are new or changed. */
    scan(): Promise<Map<number, Steps>> {
        this.running ??= this.read().finally(() => this.running = undefined);
        return this.running;
    }

    private get file(): vscode.Uri {
        return vscode.Uri.joinPath(this.storage, 'duo-cli-logs.json');
    }

    private async read(): Promise<Map<number, Steps>> {
        if (this.reads == null) {
            try {
                this.reads = parseLogs(Buffer.from(await vscode.workspace.fs.readFile(this.file)).toString('utf8'));
            } catch {
                this.reads = new Map();
            }
        }
        const reads = this.reads;
        const distros = process.platform === 'win32'
            ? (vscode.workspace.workspaceFolders ?? []).map(f => wslDistro(f.uri.fsPath)).filter((d): d is string => d != null)
            : [];
        const folders: LogScan['folders'] = [];
        let changed = 0;
        for (const folder of logFolders(process.platform, os.tmpdir(), distros)) {
            const found = await firstFolder(folder.paths);
            if (found == null) {
                folders.push({ path: folder.paths[0], found: false, logs: 0 });
                continue;
            }
            const names = (await fs.readdir(found).catch(() => [] as string[])).filter(isLog);
            folders.push({ path: found, found: true, logs: names.length });
            for (const name of names) {
                const file = path.join(found, name);
                try {
                    const stat = await fs.stat(file);
                    const before = reads.get(name);
                    if (before != null && before.size === stat.size && before.modified === Math.round(stat.mtimeMs)) {
                        continue;
                    }
                    reads.set(name, { size: stat.size, modified: Math.round(stat.mtimeMs), steps: await countSteps(file, name) });
                    changed++;
                } catch {
                    // Gone, or not readable: what was read of it before stays.
                }
            }
        }
        if (changed > 0) {
            try {
                await vscode.workspace.fs.createDirectory(this.storage);
                await vscode.workspace.fs.writeFile(this.file, Buffer.from(writeLogs(reads), 'utf8'));
            } catch (e) {
                log.warn('Duo CLI logs: could not keep the counts: ' + (e instanceof Error ? e.message : String(e)));
            }
        }
        let steps = new Map<number, Steps>();
        for (const read of reads.values()) {
            steps = addSteps(steps, read.steps);
        }
        this.last = { folders, sessions: steps.size };
        log.info('Duo CLI logs: ' + folders.map(f => f.path + (f.found ? ' (' + f.logs + ' logs)' : ' (not there)')).join(', ')
            + '; ' + changed + ' read now, ' + reads.size + ' known; steps counted for ' + steps.size + ' sessions');
        return steps;
    }
}

/** The first of the paths that is a folder. */
export async function firstFolder(paths: readonly string[]): Promise<string | null> {
    for (const p of paths) {
        try {
            if ((await fs.stat(p)).isDirectory()) {
                return p;
            }
        } catch {
            // Not there: the next one.
        }
    }
    return null;
}

/** One log's steps, read a line at a time; nothing of a line but its time, session and request is looked at. */
async function countSteps(file: string, name: string): Promise<Map<number, Steps>> {
    const counter = new StepCounter();
    const lines = readline.createInterface({ input: createReadStream(file, { encoding: 'utf8' }), crlfDelay: Infinity });
    for await (const line of lines) {
        counter.line(line);
    }
    return counter.steps(name);
}
