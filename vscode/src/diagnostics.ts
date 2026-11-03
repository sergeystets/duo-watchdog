import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { diagnosticsReport, LOG_LINES_IN_REPORT } from './core/diagnostics';
import { glabLookup } from './glab';
import { log } from './log';
import { CreditsService } from './service';

/**
 * Copy Diagnostics: the extension's and the editor's versions, where glab is, every account's settings and last
 * refresh, and the last log lines, to the clipboard. Whether an account has a token is said; the token never is.
 */
export async function copyDiagnostics(context: vscode.ExtensionContext, store: AccountStore,
                                      service: CreditsService): Promise<void> {
    const overview = service.overview();
    const tokens: string[] = [];
    const accounts = await Promise.all(overview.states.map(async s => {
        const token = await store.token(s.id);
        if (token) {
            tokens.push(token);
        }
        return {
            id: s.id, account: s.account, hasToken: token != null, status: s.snapshot.status, problem: s.problem,
            creditsUsed: s.problem == null ? s.snapshot.report?.creditsUsed ?? null : null, error: s.snapshot.error,
        };
    }));
    const report = diagnosticsReport({
        now: new Date(),
        extensionVersion: String(context.extension.packageJSON.version ?? ''),
        vscodeVersion: vscode.version,
        platform: process.platform,
        arch: process.arch,
        remoteName: vscode.env.remoteName,
        remoteHost: vscode.env.remoteName != null && context.extension.extensionKind === vscode.ExtensionKind.Workspace,
        glab: await glabLookup(),
        selectedId: overview.selected?.id ?? null,
        accounts,
        log: log.lines(LOG_LINES_IN_REPORT),
        secrets: [...tokens, ...log.knownSecrets()],
    });
    await vscode.env.clipboard.writeText(report);
    log.info('diagnostics copied: ' + accounts.length + (accounts.length === 1 ? ' account, ' : ' accounts, ')
        + report.split('\n').length + ' lines');
    const show = 'Show Log';
    if (await vscode.window.showInformationMessage('Duo Watchdog: diagnostics copied. They hold no tokens.', show) === show) {
        log.show();
    }
}
