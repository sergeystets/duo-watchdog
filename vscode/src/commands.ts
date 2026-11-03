import * as vscode from 'vscode';
import { AccountStore } from './accounts';
import { groupPath, tokenPage, trimSlashes } from './core/address';
import {
    accountGroup, accountHost, accountId, AccountState, Cli, DEFAULT_LIMIT, Filter, glabScopeNote, hasCredits, idOf,
    isExpired, matchesFilter, withCli, withToken,
} from './core/accounts';
import {
    accountDetail, accountName, addPlan, addedText, cliOffText, cliOnText, duoNote, glabCaption, glabNote,
} from './core/accountsView';
import { CreditsClient, endpoint, groupLabel, Groups, TokenInfo, Transport } from './core/client';
import { localToday } from './core/dates';
import { findGlab } from './glab';
import { GlabSync } from './glabSync';
import { CreditsService } from './service';
import { settings } from './settings';

/** What the Accounts list's buttons do. */
type Action = 'credits' | 'expired' | Cli | 'group' | 'limit' | 'replace' | 'remove';

interface ActionButton extends vscode.QuickInputButton {
    action: Action;
}

interface AccountItem extends vscode.QuickPickItem {
    id?: string;
    add?: boolean;
    showAll?: boolean;
}

/** The commands that add, replace and manage accounts. */
export class Commands {

    constructor(private readonly store: AccountStore, private readonly service: CreditsService,
                private readonly transport: Transport, private readonly glab: GlabSync) {
    }

    /** Shows the account in the status bar and the dashboard, and glab follows it. */
    async pick(id: string): Promise<void> {
        await this.service.select(id);
        await this.glab.sync();
    }

    register(): vscode.Disposable[] {
        return [
            vscode.commands.registerCommand('duoWatchdog.accounts', () => this.accounts()),
            vscode.commands.registerCommand('duoWatchdog.addToken', (target?: unknown) => this.addToken(target)),
            // The command's name before accounts, kept for keybindings and links that use it.
            vscode.commands.registerCommand('duoWatchdog.setToken', (target?: unknown) => this.addToken(target)),
            vscode.commands.registerCommand('duoWatchdog.findGroup',
                (id?: unknown) => this.findGroup(typeof id === 'string' ? id : undefined)),
            vscode.commands.registerCommand('duoWatchdog.createToken', () => this.createToken()),
        ];
    }

    /** The account the status bar and the dashboard show. */
    private selected(): AccountState | null {
        return this.service.overview().selected;
    }

    /** Opens GitLab's new-token page for the selected account, with the scopes glab needs when it uses the token. */
    createToken(): Thenable<boolean> {
        const account = this.selected()?.account;
        const url = account?.gitlabUrl ?? settings().gitlabUrl;
        return vscode.env.openExternal(vscode.Uri.parse(tokenPage(url, account != null && account.glab)));
    }

    /** Replaces the token of the selected account, or adds one when there is no account. */
    replaceSelected(): Promise<void> {
        const account = this.selected()?.account;
        return this.addToken(account ? { gitlabUrl: account.gitlabUrl } : undefined);
    }

    /**
     * Asks for a GitLab and a token, asks GitLab whose the token is, says what will happen, and then adds the
     * account or replaces the token of the one already listed. A new account gets its group (Find my group) and its
     * limit; glab and the Duo CLI are offered, No unless the user says Yes. Cancelling any step saves nothing.
     */
    async addToken(target?: unknown): Promise<void> {
        const preset = target != null && typeof target === 'object' && typeof (target as { gitlabUrl?: unknown }).gitlabUrl === 'string'
            ? (target as { gitlabUrl: string }).gitlabUrl : undefined;
        const gitlabUrl = await askUrl(preset ?? settings().gitlabUrl);
        if (gitlabUrl === undefined) {
            return;
        }
        const token = await askToken(gitlabUrl);
        if (token === undefined) {
            return;
        }
        let who: { username: string; info: TokenInfo | null };
        try {
            who = await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: 'Asking GitLab whose the token is' },
                async () => {
                    const client = new CreditsClient(gitlabUrl, token, this.transport);
                    const username = await client.username();
                    return { username, info: await client.tokenInfo().catch(() => null) };
                });
        } catch (e) {
            void vscode.window.showErrorMessage('Duo Watchdog: ' + messageOf(e));
            return;
        }

        const host = accountHost(gitlabUrl);
        const id = accountId(gitlabUrl, who.username);
        const existing = this.store.get(id);
        const scopes = who.info?.scopes ?? null;
        const plan = addPlan(existing, gitlabUrl, who.username, who.info?.expiresAt ?? null, scopes);
        const confirm = plan.kind === 'untracked' ? vscode.window.showWarningMessage : vscode.window.showInformationMessage;
        if (await confirm(plan.message, { modal: true, detail: plan.detail }, plan.button) !== plan.button) {
            return;
        }

        let fresh = { groupPath: '', limit: DEFAULT_LIMIT };
        if (plan.kind === 'new') {
            const group = await this.chooseGroup(gitlabUrl, token, who.username, true);
            if (group === undefined) {
                return;
            }
            const limit = await askLimit(DEFAULT_LIMIT, '@' + who.username + ' on ' + host);
            if (limit === undefined) {
                return;
            }
            fresh = { groupPath: group, limit };
        }

        // glab and the Duo CLI are opt-ins: only a Yes allows them. An account that already has them keeps them.
        let glab = existing?.glab ?? false;
        let duo = existing?.duo ?? false;
        if (!glab) {
            glab = await askYesNo('Also use it for glab on ' + host + '?', 'allow glab to sign in to ' + host + ' with it',
                glabNote(scopes));
        }
        if (!duo) {
            duo = await askYesNo('Also use it for the GitLab Duo CLI on ' + host + '?', 'the Duo CLI signs in through glab',
                duoNote(glab));
        }

        await this.store.storeToken(id, token);
        let saved = withToken(existing, { gitlabUrl, username: who.username, info: who.info, savedAt: Date.now() }, fresh);
        if (glab || duo) {
            saved = withCli([saved], id, duo ? 'duo' : 'glab', true)[0];
        }
        await this.store.put(saved);
        await this.service.select(id);
        this.service.refreshSoon();

        // The picked account now: glab follows it, and takes the new token.
        const handed = (await this.glab.sync(id)).find(h => idOf(h.account) === id);
        const account = this.store.get(id)!;
        const text = addedText(plan.kind, account, handed?.result.ok === true, glabScopeNote(scopes));
        const open = 'Open Dashboard';
        const shown = plan.kind === 'untracked' ? vscode.window.showWarningMessage(text, open)
            : vscode.window.showInformationMessage(text, open);
        void shown.then(choice => {
            if (choice === open) {
                void vscode.commands.executeCommand('duoWatchdog.openDashboard');
            }
        });
    }

    /**
     * Allows glab or the Duo CLI for an account, or not. Allowing it hands the token to glab when the account is the
     * one glab follows; not allowing it only stops the hand-offs, and signs nothing out.
     */
    async toggleCli(id: string, cli: Cli): Promise<void> {
        const account = this.store.get(id);
        if (account == null) {
            return;
        }
        if (account[cli]) {
            await this.store.saveAll(withCli(this.store.list(), id, cli, false));
            this.service.changedLocally();
            void vscode.window.showInformationMessage(cliOffText(account, cli));
            // Another allowed account may be the one glab follows now.
            await this.glab.sync();
            return;
        }
        if (!await this.store.token(id)) {
            const add = 'Add Token';
            const choice = await vscode.window.showWarningMessage('Duo Watchdog: ' + accountName(account)
                + ' has no token to give glab. Add one first.', add);
            if (choice === add) {
                await this.addToken({ gitlabUrl: account.gitlabUrl });
            }
            return;
        }
        await this.store.saveAll(withCli(this.store.list(), id, cli, true));
        this.service.changedLocally();
        const handed = (await this.glab.sync()).find(h => idOf(h.account) === id);
        if (handed?.result.ok && (cli === 'glab' || account.glab)) {
            return; // "glab now signs in to <host> as @<user>." said it.
        }
        if (handed != null && !handed.result.ok) {
            return; // glab's own words said it.
        }
        const now = this.store.get(id)!;
        const installed = await findGlab() != null;
        const caption = handed?.result.ok ? null : glabCaption(now, this.service.overview().states.map(s => s.account),
            this.service.overview().selected?.id ?? null, installed);
        void vscode.window.showInformationMessage(cliOnText(now, cli, account.glab, caption));
    }

    /**
     * Every account, most credits left first, with Add or Replace a Token on top; Has credits and Expired filter
     * the list. Picking an account shows it in the status bar and the dashboard.
     */
    accounts(filter: Filter = 'all'): void {
        const pick = vscode.window.createQuickPick<AccountItem>();
        pick.placeholder = 'Search accounts, or pick one to show';
        pick.matchOnDescription = true;
        pick.matchOnDetail = true;
        pick.keepScrollPosition = true;
        let current = filter;
        let first = true;
        let open = true;
        let installed: boolean | null = null;

        const render = () => {
            if (!open) {
                return;
            }
            const { states, selected } = this.service.overview();
            const today = localToday();
            pick.title = 'Duo Watchdog: Accounts' + (current === 'credits' ? ' with credits left'
                : current === 'expired' ? ' with an expired token' : '');
            pick.buttons = [
                filterButton('credits', current, states.filter(hasCredits).length),
                filterButton('expired', current, states.filter(s => isExpired(s, today)).length),
            ];
            const shown = states.filter(s => matchesFilter(current, s, today));
            const items: AccountItem[] = [{
                label: '$(add) Add or Replace a Token…',
                description: 'GitLab says whose it is; one token per account',
                add: true,
                alwaysShow: true,
            }];
            if (states.length > 0) {
                items.push({ label: 'accounts, most credits left first', kind: vscode.QuickPickItemKind.Separator });
            }
            const accounts = states.map(s => s.account);
            items.push(...shown.map(s => accountItem(s, s.id === selected?.id, today,
                glabCaption(s.account, accounts, selected?.id ?? null, installed))));
            if (shown.length === 0 && states.length > 0) {
                items.push({
                    label: '$(filter) ' + (current === 'credits' ? 'No account has credits left.' : 'No expired tokens.'),
                    description: 'Show all accounts',
                    showAll: true,
                    alwaysShow: true,
                });
            }
            pick.items = items;
            if (first) {
                first = false;
                pick.activeItems = items.filter(i => i.id != null && i.id === selected?.id);
            }
        };

        pick.onDidTriggerButton(button => {
            const action = (button as ActionButton).action;
            if (action === 'credits' || action === 'expired') {
                current = current === action ? 'all' : action;
                render();
            }
        });
        pick.onDidTriggerItemButton(event => {
            const id = event.item.id;
            const action = (event.button as ActionButton).action;
            if (id == null) {
                return;
            }
            if (action === 'glab' || action === 'duo') {
                void this.toggleCli(id, action).then(render);
                return;
            }
            pick.hide();
            void (async () => {
                if (action === 'replace') {
                    const account = this.store.get(id);
                    await this.addToken(account ? { gitlabUrl: account.gitlabUrl } : undefined);
                    return;
                }
                if (action === 'group') {
                    await this.setGroup(id);
                } else if (action === 'limit') {
                    await this.setLimit(id);
                } else if (action === 'remove') {
                    await this.remove(id);
                }
                this.accounts(current);
            })();
        });
        pick.onDidAccept(() => {
            const item = pick.selectedItems[0];
            if (item?.showAll) {
                current = 'all';
                render();
                return;
            }
            pick.hide();
            if (item?.add) {
                void this.addToken();
            } else if (item?.id != null) {
                void this.pick(item.id);
            }
        });
        const changes = this.service.onDidChange(render);
        pick.onDidHide(() => {
            open = false;
            changes.dispose();
            pick.dispose();
        });
        render();
        pick.show();
        void findGlab().then(found => {
            installed = found != null;
            render();
        });
    }

    async setGroup(id: string): Promise<void> {
        const account = this.store.get(id);
        if (account == null) {
            return;
        }
        const typed = await askGroupPath(account.gitlabUrl, account.groupPath, accountName(account), null);
        if (typed === 'find') {
            await this.findGroup(id);
        } else if (typed !== undefined) {
            await this.store.update(id, { groupPath: typed });
            this.service.refreshSoon();
        }
    }

    async setLimit(id: string): Promise<void> {
        const account = this.store.get(id);
        if (account == null) {
            return;
        }
        const limit = await askLimit(account.limit, accountName(account));
        if (limit !== undefined) {
            await this.store.update(id, { limit });
            this.service.refreshSoon();
        }
    }

    async remove(id: string): Promise<void> {
        const account = this.store.get(id);
        if (account == null) {
            return;
        }
        const remove = 'Remove Account';
        const choice = await vscode.window.showWarningMessage('Remove ' + accountName(account) + '?', {
            modal: true,
            detail: "Duo Watchdog forgets the account, its group and limit, and deletes its token from VS Code's "
                + 'secret storage. The token still works in GitLab, and glab keeps what it was given.',
        }, remove);
        if (choice === remove) {
            await this.store.remove(id);
            this.service.changedLocally();
            this.service.refreshSoon();
        }
    }

    /** Asks GitLab which of the account's top-level groups give its credits, and fills in the one, or offers the few. */
    async findGroup(id?: string): Promise<void> {
        const account = id != null ? this.store.get(id) : this.selected()?.account;
        if (account == null) {
            const add = 'Add or Replace a Token';
            const choice = await vscode.window.showWarningMessage(
                'Duo Watchdog: add a token first: Find my group asks GitLab with it.', add);
            if (choice === add) {
                await this.addToken();
            }
            return;
        }
        const accountKey = idOf(account);
        const token = await this.store.token(accountKey);
        if (!token) {
            const add = 'Add Token';
            const choice = await vscode.window.showWarningMessage('Duo Watchdog: ' + accountName(account)
                + ' has no token. Add one first: Find my group asks GitLab with it.', add);
            if (choice === add) {
                await this.addToken({ gitlabUrl: account.gitlabUrl });
            }
            return;
        }
        const path = await this.chooseGroup(account.gitlabUrl, token, account.username, false);
        if (path) {
            await this.store.update(accountKey, { groupPath: path });
            this.service.refreshSoon();
            void vscode.window.showInformationMessage('Duo Watchdog: the group of @' + account.username + ' on '
                + accountHost(account.gitlabUrl) + ' is ' + path + ' now.');
        }
    }

    /**
     * The group path Find my group gives: the one group with credits, or the one picked of several. When it finds
     * none, it says why, and, while adding an account, lets the user type one (empty for none yet). Undefined when
     * the user cancels.
     */
    private async chooseGroup(gitlabUrl: string, token: string, username: string,
                              adding: boolean): Promise<string | undefined> {
        let groups: Groups | null = null;
        let failure: string | null = null;
        try {
            groups = await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: 'Finding the group of @' + username },
                () => new CreditsClient(gitlabUrl, token, this.transport).findGroups());
        } catch (e) {
            failure = messageOf(e);
        }
        const found = groups?.withCredits ?? [];
        if (found.length === 1) {
            return found[0].path;
        }
        if (found.length > 1) {
            const pick = await vscode.window.showQuickPick(
                found.map(match => ({
                    label: match.path,
                    description: groupLabel(match) === match.path ? undefined : match.name ?? undefined,
                })),
                { title: 'Groups of @' + username + ' with Duo Credits', ignoreFocusOut: true });
            return pick?.label;
        }
        const host = accountHost(gitlabUrl);
        const why = failure ?? (groups!.checked.length === 0
            ? '@' + username + ' is not a member of any group on ' + host + '.'
            : 'GitLab gave no Duo credits for @' + username + ' in any of its groups: ' + groups!.checked.join(', ')
            + ". Check the token's read_api and ai_features scopes.");
        if (!adding) {
            void vscode.window.showWarningMessage('Duo Watchdog: ' + why);
            return undefined;
        }
        const typed = await askGroupPath(gitlabUrl, '', '@' + username + ' on ' + host, why);
        return typed === 'find' ? undefined : typed;
    }
}

function accountItem(state: AccountState, selected: boolean, today: string, glab: string | null): AccountItem {
    const account = state.account;
    const host = accountHost(account.gitlabUrl);
    const button = (icon: string, tooltip: string, action: Action): ActionButton =>
        ({ iconPath: new vscode.ThemeIcon(icon), tooltip, action });
    return {
        id: state.id,
        label: (selected ? '$(check) ' : '$(account) ') + '@' + account.username,
        description: [host, accountGroup(account)].filter(part => part !== '').join(' · '),
        detail: accountDetail(state, today, glab),
        buttons: [
            button('terminal', account.glab ? 'Stop allowing glab to use this token (nothing is signed out)'
                : 'Use for glab on ' + host + ': glab follows the account you pick, among those allowed', 'glab'),
            button('hubot', account.duo ? 'Stop allowing the GitLab Duo CLI to use this token (nothing is signed out)'
                : 'Use for the GitLab Duo CLI on ' + host + ' (it signs in through glab)', 'duo'),
            button('organization', 'Set Group Path', 'group'),
            button('dashboard', 'Set Limit', 'limit'),
            button('key', state.problem === 'noToken' ? 'Add Token' : 'Replace Token', 'replace'),
            button('trash', 'Remove Account', 'remove'),
        ],
    };
}

function filterButton(filter: 'credits' | 'expired', current: Filter, count: number): ActionButton {
    const on = current === filter;
    const name = filter === 'credits' ? 'Has credits' : 'Expired';
    const icon = filter === 'credits' ? (on ? 'pass-filled' : 'pass') : (on ? 'error' : 'circle-slash');
    return {
        iconPath: new vscode.ThemeIcon(icon),
        tooltip: name + ' ' + count + (on ? ': click to show all accounts' : ''),
        action: filter,
    };
}

/** The GitLab the token is for; undefined when cancelled. */
function askUrl(value: string): Thenable<string | undefined> {
    return vscode.window.showInputBox({
        title: 'Duo Watchdog: Add or Replace a Token (1/2)',
        prompt: 'The GitLab the token is for: https://gitlab.com or the address of your self-managed GitLab.',
        value,
        ignoreFocusOut: true,
        validateInput: text => {
            try {
                endpoint(text);
                return undefined;
            } catch (e) {
                return messageOf(e);
            }
        },
    }).then(url => url === undefined ? undefined : trimSlashes(url));
}

/** The token, in a password box with buttons to GitLab's new-token page; undefined when cancelled. */
function askToken(gitlabUrl: string): Promise<string | undefined> {
    return new Promise(resolve => {
        const host = accountHost(gitlabUrl);
        const input = vscode.window.createInputBox();
        input.title = 'Duo Watchdog: Add or Replace a Token (2/2)';
        input.prompt = 'A personal access token for ' + host + '. To read usage it needs read_api and ai_features; '
            + 'for glab too, api, write_repository and ai_features. GitLab says whose it is. '
            + "Kept in VS Code's secret storage.";
        input.placeholder = 'glpat-…';
        input.password = true;
        input.ignoreFocusOut = true;
        const usage: vscode.QuickInputButton = {
            iconPath: new vscode.ThemeIcon('link-external'),
            tooltip: 'Create the token in GitLab (read_api, ai_features)',
        };
        const glab: vscode.QuickInputButton = {
            iconPath: new vscode.ThemeIcon('terminal'),
            tooltip: 'Create a token glab can use too (api, write_repository, ai_features)',
        };
        input.buttons = [usage, glab];
        let token: string | undefined;
        input.onDidTriggerButton(button =>
            void vscode.env.openExternal(vscode.Uri.parse(tokenPage(gitlabUrl, button === glab))));
        input.onDidChangeValue(() => input.validationMessage = undefined);
        input.onDidAccept(() => {
            const value = input.value.trim();
            if (value === '') {
                input.validationMessage = 'Paste the token.';
                return;
            }
            token = value;
            input.hide();
        });
        input.onDidHide(() => {
            input.dispose();
            resolve(token);
        });
        input.show();
    });
}

/** A limit in credits; undefined when cancelled. */
function askLimit(value: number, who: string): Thenable<number | undefined> {
    return vscode.window.showInputBox({
        title: 'Duo Watchdog: Limit of ' + who,
        prompt: "The limit in credits: your GitLab cap, or a lower budget of your own. GitLab shows a user's cap only to "
            + 'the group owner, so you keep it here.',
        value: String(value),
        ignoreFocusOut: true,
        validateInput: text => {
            const n = Number(text.trim());
            return text.trim() !== '' && Number.isFinite(n) && n > 0 ? undefined : 'Enter a number of credits above 0.';
        },
    }).then(text => text === undefined ? undefined : Number(text.trim()));
}

/**
 * The group path typed, normalised; "" for none yet, "find" when the user asks for Find my group, undefined when
 * cancelled.
 */
function askGroupPath(gitlabUrl: string, value: string, who: string, why: string | null): Promise<string | undefined> {
    return new Promise(resolve => {
        const input = vscode.window.createInputBox();
        input.title = 'Duo Watchdog: Group of ' + who;
        input.prompt = (why != null ? why + ' ' : '') + 'The path of the top-level group that owns the Duo subscription, '
            + "e.g. my-company: the path from the group's address, not its name. A pasted group URL works too. "
            + 'Leave it empty to set it later.';
        input.value = value;
        input.ignoreFocusOut = true;
        const find: vscode.QuickInputButton = { iconPath: new vscode.ThemeIcon('search'), tooltip: 'Find My Group' };
        if (why == null) {
            input.buttons = [find];
        }
        let result: string | undefined;
        input.onDidTriggerButton(() => {
            result = 'find';
            input.hide();
        });
        input.onDidChangeValue(() => input.validationMessage = undefined);
        input.onDidAccept(() => {
            const typed = input.value.trim();
            const path = groupPath(typed, gitlabUrl);
            if (typed !== '' && path === '') {
                input.validationMessage = "Enter the group path from the group's address, e.g. my-company.";
                return;
            }
            result = path;
            input.hide();
        });
        input.onDidHide(() => {
            input.dispose();
            resolve(result);
        });
        input.show();
    });
}

/** An opt-in: No first, and Escape is No too. */
async function askYesNo(question: string, yes: string, note: string): Promise<boolean> {
    const picked = await vscode.window.showQuickPick([
        { label: 'No', description: 'leave it as it is' },
        { label: 'Yes', description: yes, detail: note },
    ], { title: 'Duo Watchdog: ' + question, placeHolder: question, ignoreFocusOut: true });
    return picked?.label === 'Yes';
}

function messageOf(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
}
