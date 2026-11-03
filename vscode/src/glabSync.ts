import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { Account, accountHost, afterHandOff, glabHandOffs, idOf } from './core/accounts';
import { glabNowText } from './core/accountsView';
import { duoHasItsOwnLogin, findGlab, handOffToGlab, HandOff } from './glab';
import { CreditsService } from './service';

export const DUO_OWN_LOGIN = 'Duo Watchdog: the Duo CLI has its own login in ~/.gitlab/storage.json and keeps using it. '
    + "Delete its settings there to make it use glab's.";

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
        if (glabHandOffs(accounts, overview.selected?.id ?? null, true).length === 0) {
            return [];
        }
        const installed = await findGlab() != null;
        const plan = glabHandOffs(accounts, overview.selected?.id ?? null, installed);
        const handed: Handed[] = [];
        for (const owner of plan) {
            const id = idOf(owner);
            const token = await this.store.token(id);
            if (!token) {
                continue;
            }
            const host = accountHost(owner.gitlabUrl);
            const result = await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Window, title: 'Signing glab in to ' + host },
                () => handOffToGlab(host, token));
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
