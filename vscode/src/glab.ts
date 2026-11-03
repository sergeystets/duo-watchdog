import { spawn } from 'child_process';
import { promises as fs, constants } from 'fs';
import * as os from 'os';
import * as path from 'path';
import { duoHasOwnLogin, GLAB_DIRS, glabArgs, glabCandidates, glabOutput } from './core/glab';

/** glab took the token; or it is not installed (missing), or it did not take the token, with what it said. */
export type HandOff = { ok: true } | { ok: false; missing: boolean; message: string };

/** glab's path: the first one on the PATH, then in the usual Homebrew folders; null when there is none. */
export async function findGlab(): Promise<string | null> {
    for (const candidate of glabCandidates(process.env.PATH, process.platform)) {
        try {
            await fs.access(candidate, process.platform === 'win32' ? constants.F_OK : constants.X_OK);
            const stat = await fs.stat(candidate);
            if (stat.isFile()) {
                return candidate;
            }
        } catch {
            // Not there: try the next one.
        }
    }
    return null;
}

/** Signs glab in to the host with the token, written to glab's standard input and never put on its command line. */
export async function handOffToGlab(host: string, token: string): Promise<HandOff> {
    const glab = await findGlab();
    if (glab == null) {
        return {
            ok: false,
            missing: true,
            message: 'glab is not installed: Duo Watchdog looked on the PATH'
                + (process.platform === 'win32' ? '' : ' and in ' + GLAB_DIRS.join(', ')) + '.',
        };
    }
    return new Promise(resolve => {
        let output = '';
        let settled = false;
        const done = (result: HandOff) => {
            if (!settled) {
                settled = true;
                resolve(result);
            }
        };
        const child = spawn(glab, glabArgs(host), { stdio: ['pipe', 'pipe', 'pipe'], timeout: 60_000, windowsHide: true });
        child.stdout.on('data', (chunk: Buffer) => output += chunk.toString('utf8'));
        child.stderr.on('data', (chunk: Buffer) => output += chunk.toString('utf8'));
        child.on('error', e => done({ ok: false, missing: false, message: 'glab did not start: ' + glabOutput(e.message, token) }));
        child.on('close', (code, signal) => {
            if (code === 0) {
                done({ ok: true });
                return;
            }
            const said = glabOutput(output, token);
            done({
                ok: false,
                missing: false,
                message: 'glab could not sign in to ' + host
                    + (signal != null ? ' (stopped: ' + signal + ')' : code != null ? ' (exit code ' + code + ')' : '')
                    + (said === '' ? '.' : ': ' + said),
            });
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
