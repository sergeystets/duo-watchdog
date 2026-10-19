import { CreditsReport } from '../core/report';

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
