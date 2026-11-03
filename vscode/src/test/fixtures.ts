import { Account, AccountState, idOf, Problem } from '../core/accounts';
import { CreditsReport, Snapshots } from '../core/report';

export const TODAY = '2026-10-18';
export const AT = new Date(2026, 9, 18, 14, 5);

export function report(used: number, blocked: boolean): CreditsReport {
    return {
        username: 'alice',
        startDate: '2026-10-01',
        endDate: '2026-10-31',
        creditsUsed: used,
        blocked,
        blockedBy: blocked ? 'FLAT_USER_CAP' : null,
        daily: [{ date: '2026-10-17', credits: 3.5 }, { date: '2026-10-18', credits: 1.25 }],
        flows: [{ id: 'chat', title: 'Duo Chat', credits: 12 }, { id: 'review', title: 'Code <Review>', credits: 30 }],
        flowsError: null,
    };
}

/** An account as Add or Replace a Token saves it; the fields given replace the defaults. */
export function account(change: Partial<Account> = {}): Account {
    return {
        gitlabUrl: 'https://gitlab.com',
        username: 'dana.k',
        groupPath: 'acme',
        limit: 300,
        tokenSavedAt: 1_759_318_692_000,
        expiresAt: '2027-02-01',
        scopes: ['read_api', 'ai_features'],
        glab: false,
        duo: false,
        lastAlertKey: null,
        expiryPopupKey: null,
        rejectedPopupKey: null,
        ...change,
    };
}

/** An account with what a refresh found: credits used, or a problem and no numbers. */
export function state(a: Account, used: number | null, problem: Problem | null = null, blocked = false): AccountState {
    const snapshot = used == null ? (problem == null ? Snapshots.loading() : Snapshots.notConfigured())
        : Snapshots.ok({ ...report(used, blocked), username: a.username }, AT);
    return { id: idOf(a), account: a, snapshot, problem };
}
