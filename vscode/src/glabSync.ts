import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { Account, accountHost, afterHandOff, GlabAction, glabDecisions, idOf } from './core/accounts';
import { glabNowText } from './core/accountsView';
import { duoHasItsOwnLogin, findGlab, handOffToGlab, HandOff } from './glab';
import { log } from './log';
import { CreditsService } from './service';

export const DUO_OWN_LOGIN = 'Duo Watchdog: the Duo CLI has its own login in ~/.gitlab/storage.json and keeps using it. '
    + "Delete its settings there to make it use glab's.";

const WHY: Record<GlabAction, string> = {
    hand: 'handing its token to glab',
    has: 'glab has its token already',
    refused: 'glab turned this token down; not tried again until it changes',
    notInstalled: 'glab is not installed; nothing said',
};

export interface Handed {
    account: Account;
    result: HandOff;
}

/**
 * Keeps glab on the picked account: for each GitLab, the token of the account glab should have (glabOwner) is handed
 * to glab when glab does not have it yet and did not turn it down. Runs when an account is picked, after a token is
 * added or replaced, after glab or the Duo CLI is allowed, and after each refresh; one run at a time. Without glab on
 * this computer it says nothing: the Accounts list says glab isn't found.
 */
export class GlabSync {

    private tail: Promise<unknown> = Promise.resolve();

    constructor(private readonly store: AccountStore, private readonly service: CreditsService) {
    }

    /** @param quiet an account whose success the caller says itself */
    sync(quiet?: string): Promise<Handed[]> {
        const run = this.tail.then(() => this.run(quiet));
        this.tail = run.catch(() => undefined);
        return run;
    }

    private async run(quiet?: string): Promise<Handed[]> {
        const overview = this.service.overview();
        const accounts = overview.states.map(s => s.account);
        const picked = overview.selected?.id ?? null;
        let decisions = glabDecisions(accounts, picked, true);
        if (decisions.some(d => d.action === 'hand') && await findGlab() == null) {
            decisions = glabDecisions(accounts, picked, false);
        }
        const handed: Handed[] = [];
        for (const decision of decisions) {
            const owner = accounts.find(a => idOf(a) === decision.owner)!;
            const id = decision.owner;
            const token = decision.action === 'hand' ? await this.store.token(id) : undefined;
            const why = decision.action === 'hand' && !token ? 'it has no token' : WHY[decision.action];
            log.info('glab sync ' + decision.host + ': allowed ' + decision.allowed.join(', ') + '; picked ' + (picked ?? 'none')
                + '; owner ' + id + '; ' + why);
            if (decision.action !== 'hand' || !token) {
                continue;
            }
            const host = accountHost(owner.gitlabUrl);
            const result = await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Window, title: 'Signing glab in to ' + host },
                () => handOffToGlab(host, token, id));
            if (!result.ok && result.missing) {
                break;
            }
            await this.store.saveAll(afterHandOff(this.store.list(), id, result.ok));
            handed.push({ account: owner, result });
            if (!result.ok) {
                void vscode.window.showWarningMessage('Duo Watchdog: ' + result.message);
            } else if (id !== quiet) {
                void vscode.window.showInformationMessage(glabNowText(owner));
            }
            if (result.ok && owner.duo && await duoHasItsOwnLogin()) {
                void vscode.window.showWarningMessage(DUO_OWN_LOGIN);
            }
        }
        if (handed.length > 0) {
            this.service.changedLocally();
        }
        return handed;
    }
}
