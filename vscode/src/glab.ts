import { spawn } from 'child_process';
import { promises as fs, constants } from 'fs';
import * as os from 'os';
import * as path from 'path';
import { duoHasOwnLogin, GLAB_DIRS, glabArgs, glabCandidates, glabOutput } from './core/glab';
import { log } from './log';

/** glab took the token; or it is not installed (missing), or it did not take the token, with what it said. */
export type HandOff = { ok: true } | { ok: false; missing: boolean; message: string };

/** Where glab was looked for, in order, and the first one there; path null when none was. */
export interface GlabLookup {
    path: string | null;
    looked: string[];
}

let lastLookup: string | undefined;

/** Looks for glab on the PATH, then in the usual Homebrew folders. The log says where, when the answer changes. */
export async function glabLookup(): Promise<GlabLookup> {
    const looked: string[] = [];
    let found: string | null = null;
    for (const candidate of glabCandidates(process.env.PATH, process.platform)) {
        looked.push(candidate);
        try {
            await fs.access(candidate, process.platform === 'win32' ? constants.F_OK : constants.X_OK);
            const stat = await fs.stat(candidate);
            if (stat.isFile()) {
                found = candidate;
                break;
            }
        } catch {
            // Not there: try the next one.
        }
    }
    const said = (found != null ? 'glab found: ' + found : 'glab not found') + '; looked in ' + looked.join(', ');
    if (said !== lastLookup) {
        lastLookup = said;
        log.info(said);
    }
    return { path: found, looked };
}

/** glab's path: the first one on the PATH, then in the usual Homebrew folders; null when there is none. */
export async function findGlab(): Promise<string | null> {
    return (await glabLookup()).path;
}

/**
 * Signs glab in to the host with the token, written to glab's standard input and never put on its command line.
 * @param account the account's id, for the log
 */
export async function handOffToGlab(host: string, token: string, account = ''): Promise<HandOff> {
    log.remember(token);
    const glab = await findGlab();
    if (glab == null) {
        log.info('glab hand-off for ' + (account || host) + ': glab is not installed');
        return {
            ok: false,
            missing: true,
            message: 'glab is not installed: Duo Watchdog looked on the PATH'
                + (process.platform === 'win32' ? '' : ' and in ' + GLAB_DIRS.join(', ')) + '.',
        };
    }
    log.info('glab hand-off: ' + glab + ' ' + glabArgs(host).join(' ') + ' for ' + (account || host) + ', the token on stdin');
    return new Promise(resolve => {
        let output = '';
        let settled = false;
        const done = (result: HandOff, code: string) => {
            if (!settled) {
                settled = true;
                const said = glabOutput(output, token);
                if (result.ok) {
                    log.info('glab hand-off for ' + (account || host) + ': ' + code + (said === '' ? '' : ': ' + said));
                } else {
                    log.warn('glab hand-off for ' + (account || host) + ': ' + code + ': ' + result.message);
                }
                resolve(result);
            }
        };
        const child = spawn(glab, glabArgs(host), { stdio: ['pipe', 'pipe', 'pipe'], timeout: 60_000, windowsHide: true });
        child.stdout.on('data', (chunk: Buffer) => output += chunk.toString('utf8'));
        child.stderr.on('data', (chunk: Buffer) => output += chunk.toString('utf8'));
        child.on('error', e => done({ ok: false, missing: false, message: 'glab did not start: ' + glabOutput(e.message, token) },
            'did not start'));
        child.on('close', (code, signal) => {
            if (code === 0) {
                done({ ok: true }, 'exit code 0');
                return;
            }
            const said = glabOutput(output, token);
            done({
                ok: false,
                missing: false,
                message: 'glab could not sign in to ' + host
                    + (signal != null ? ' (stopped: ' + signal + ')' : code != null ? ' (exit code ' + code + ')' : '')
                    + (said === '' ? '.' : ': ' + said),
            }, signal != null ? 'stopped by ' + signal : 'exit code ' + code);
        });
        child.stdin.on('error', () => undefined);
        child.stdin.end(token + '\n');
    });
}

/** Whether the GitLab Duo CLI has a login of its own in ~/.gitlab/storage.json; the file is read, never shown. */
export async function duoHasItsOwnLogin(): Promise<boolean> {
    try {
        return duoHasOwnLogin(await fs.readFile(path.join(os.homedir(), '.gitlab', 'storage.json'), 'utf8'));
    } catch {
        return false;
    }
}
