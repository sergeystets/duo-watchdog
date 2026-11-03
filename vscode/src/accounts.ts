import * as vscode from 'vscode';
import { Account, idOf, normalized } from './core/accounts';

/** The accounts, without their tokens, in VS Code's global state. */
export const ACCOUNTS_KEY = 'duoWatchdog.accounts';
/** The account the status bar and the dashboard show. */
export const SELECTED_KEY = 'duoWatchdog.selectedAccount';
/** Each account's token in VS Code's secret storage (the OS keychain), under this prefix and the account's id. */
export const TOKEN_PREFIX = 'duoWatchdog.token/';

export function tokenKey(id: string): string {
    return TOKEN_PREFIX + id;
}

/**
 * The accounts and their tokens. Every change reads the list and writes it back in one go: the global state holds
 * the new list as soon as update() is called, so changes made one after another do not lose each other.
 */
export class AccountStore {

    constructor(private readonly context: vscode.ExtensionContext) {
    }

    list(): Account[] {
        const stored = this.context.globalState.get<unknown>(ACCOUNTS_KEY);
        if (!Array.isArray(stored)) {
            return [];
        }
        const accounts: Account[] = [];
        const ids = new Set<string>();
        for (const entry of stored) {
            if (entry != null && typeof entry === 'object' && typeof entry.gitlabUrl === 'string'
                && typeof entry.username === 'string') {
                const account = normalized(entry);
                if (!ids.has(idOf(account))) {
                    ids.add(idOf(account));
                    accounts.push(account);
                }
            }
        }
        return accounts;
    }

    get(id: string): Account | undefined {
        return this.list().find(a => idOf(a) === id);
    }

    saveAll(accounts: Account[]): Thenable<void> {
        return this.context.globalState.update(ACCOUNTS_KEY, accounts);
    }

    /** Adds the account, or replaces the one with its id. */
    put(account: Account): Thenable<void> {
        const id = idOf(account);
        const accounts = this.list();
        const at = accounts.findIndex(a => idOf(a) === id);
        if (at >= 0) {
            accounts[at] = account;
        } else {
            accounts.push(account);
        }
        return this.saveAll(accounts);
    }

    update(id: string, change: Partial<Account>): Thenable<void> {
        return this.saveAll(this.list().map(a => idOf(a) === id ? { ...a, ...change } : a));
    }

    /** Removes the account and its token. */
    async remove(id: string): Promise<void> {
        await this.saveAll(this.list().filter(a => idOf(a) !== id));
        await this.context.secrets.delete(tokenKey(id));
        if (this.selectedId() === id) {
            await this.context.globalState.update(SELECTED_KEY, undefined);
        }
    }

    async token(id: string): Promise<string | undefined> {
        const token = (await this.context.secrets.get(tokenKey(id)))?.trim();
        return token ? token : undefined;
    }

    storeToken(id: string, token: string): Thenable<void> {
        return this.context.secrets.store(tokenKey(id), token);
    }

    selectedId(): string | undefined {
        return this.context.globalState.get<string>(SELECTED_KEY);
    }

    select(id: string): Thenable<void> {
        return this.context.globalState.update(SELECTED_KEY, id);
    }
}
