import * as path from 'path';

/**
 * Handing a token to glab, the GitLab CLI: `glab auth login --hostname <host> --stdin` with the token on its standard
 * input, never on the command line. The GitLab Duo CLI signs in through glab when glab is signed in, unless it has a
 * login of its own in ~/.gitlab/storage.json.
 */

/** Where glab is looked for after the PATH: Homebrew on Apple silicon, on Intel and on Linux. */
export const GLAB_DIRS = ['/opt/homebrew/bin', '/usr/local/bin', '/home/linuxbrew/.linuxbrew/bin'];

/** The files glab may be: each PATH directory's glab (glab.exe on Windows), then the usual Homebrew ones. */
export function glabCandidates(pathEnv: string | undefined, platform: string): string[] {
    const windows = platform === 'win32';
    const p = windows ? path.win32 : path.posix;
    const dirs = (pathEnv ?? '').split(windows ? ';' : ':').map(d => d.trim().replace(/^"(.*)"$/, '$1'))
        .filter(d => d !== '');
    const all = (windows ? dirs : [...dirs, ...GLAB_DIRS]).map(d => p.join(d, windows ? 'glab.exe' : 'glab'));
    return [...new Set(all)];
}

export function glabArgs(host: string): string[] {
    return ['auth', 'login', '--hostname', host, '--stdin'];
}

const MAX_OUTPUT = 300;

/**
 * glab's output to show when it failed: colours dropped, the lines joined, the end kept, and the token masked
 * should glab ever echo it.
 */
export function glabOutput(output: string, token: string): string {
    let text = output.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '');
    if (token !== '') {
        text = text.split(token).join('•••••••');
    }
    text = text.split(/\r?\n/).map(line => line.trim()).filter(line => line !== '').join(' ');
    return text.length > MAX_OUTPUT ? '…' + text.slice(text.length - MAX_OUTPUT + 1) : text;
}

/** ~/.gitlab/storage.json, where the Duo CLI keeps a login of its own. */
export const DUO_STORAGE = '~/.gitlab/storage.json';

/** Whether the Duo CLI's storage holds settings of its own (a non-empty JSON object), so it does not use glab's. */
export function duoHasOwnLogin(storage: string | null | undefined): boolean {
    if (storage == null) {
        return false;
    }
    try {
        const parsed: unknown = JSON.parse(storage);
        return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) && Object.keys(parsed).length > 0;
    } catch {
        return false;
    }
}
