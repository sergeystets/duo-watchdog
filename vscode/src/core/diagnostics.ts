import { Account, accountGroup, glabHas, glabRefusedIt, Problem } from './accounts';
import { scrub } from './log';
import { number } from './view';

/** Copy Diagnostics: what someone troubleshooting from afar needs, as plain text, without a token in it. */

export interface AccountFacts {
    id: string;
    account: Account;
    /** A token is in the secret storage: whether it is, never what it is. */
    hasToken: boolean;
    /** The last refresh: loading, ok, error, notConfigured. */
    status: string;
    problem: Problem | null;
    creditsUsed: number | null;
    error: string | null;
}

export interface DiagnosticsInput {
    now: Date;
    extensionVersion: string;
    vscodeVersion: string;
    platform: string;
    arch: string;
    /** "wsl", "ssh-remote", …; undefined when local. */
    remoteName: string | undefined;
    /** The extension runs on the remote (WSL, SSH, container) rather than beside the window. */
    remoteHost: boolean;
    glab: { path: string | null; looked: string[] };
    selectedId: string | null;
    accounts: AccountFacts[];
    /** The last lines of the log, oldest first. */
    log: string[];
    /** Where the Duo CLI's logs were looked for, and how many sessions they count steps for; undefined when not looked. */
    duoCliLogs?: { folders: { path: string; found: boolean; logs: number }[]; sessions: number };
    /** Tokens the extension knows: masked wherever they appear, as is anything shaped like a GitLab token. */
    secrets?: Iterable<string>;
}

export const LOG_LINES_IN_REPORT = 100;

const SECRET_STORAGE = "Secret storage: tokens are kept in VS Code's secret storage (the OS keychain, or the remote's "
    + 'when the extension runs there). This report says only whether each account has one; it holds no tokens.';

function time(ms: number): string {
    return ms > 0 ? new Date(ms).toISOString() : 'unknown';
}

function yesNo(value: boolean): string {
    return value ? 'yes' : 'no';
}

/** The report, every line scrubbed of tokens. */
export function diagnosticsReport(input: DiagnosticsInput): string {
    const lines: string[] = [
        'Duo Watchdog diagnostics',
        'Generated: ' + input.now.toISOString(),
        'Extension: ' + input.extensionVersion,
        'VS Code: ' + input.vscodeVersion,
        'Platform: ' + input.platform + ' ' + input.arch,
        'Remote: ' + (input.remoteName ?? 'none') + ' (the extension host is ' + (input.remoteHost ? 'remote' : 'local') + ')',
        SECRET_STORAGE,
        'glab: ' + (input.glab.path ?? 'not found') + '; looked in: '
        + (input.glab.looked.length === 0 ? 'nowhere' : input.glab.looked.join(', ')),
        'Duo CLI logs: ' + (input.duoCliLogs == null ? 'not looked for'
            : input.duoCliLogs.folders.map(f => f.path + (f.found ? ' (' + f.logs + (f.logs === 1 ? ' log)' : ' logs)') : ' (not there)'))
                .join(', ')),
        'Sessions counted in the Duo CLI logs: ' + (input.duoCliLogs?.sessions ?? 'unknown'),
        'Selected account: ' + (input.selectedId ?? 'none'),
        '',
        'Accounts (' + input.accounts.length + '):',
    ];
    for (const facts of input.accounts) {
        const a = facts.account;
        lines.push(
            '- ' + facts.id + (facts.id === input.selectedId ? ' (selected)' : ''),
            '  GitLab: ' + a.gitlabUrl + ' · group: ' + (accountGroup(a) || 'none') + ' · limit: ' + number(a.limit),
            '  token saved: ' + yesNo(facts.hasToken) + ' · saved at: ' + time(a.tokenSavedAt) + ' · expires: '
            + (a.expiresAt ?? 'unknown') + ' · scopes: ' + (a.scopes == null ? 'unknown' : a.scopes.join(', ') || 'none'),
            '  glab: ' + (a.glab ? 'on' : 'off') + ' · Duo CLI: ' + (a.duo ? 'on' : 'off') + ' · glab has it: '
            + yesNo(glabHas(a)) + ' · glab refused it: ' + yesNo(glabRefusedIt(a)),
            '  last refresh: ' + facts.status + (facts.problem != null ? ' (' + facts.problem + ')' : '')
            + (facts.creditsUsed != null ? ' · credits used: ' + number(facts.creditsUsed) : '')
            + (facts.error != null ? ' · ' + facts.error : ''),
        );
    }
    const log = input.log.slice(Math.max(0, input.log.length - LOG_LINES_IN_REPORT));
    lines.push('', 'Last ' + log.length + ' log lines:', ...log);
    const secrets = [...(input.secrets ?? [])];
    return lines.map(line => scrub(line, secrets)).join('\n') + '\n';
}
