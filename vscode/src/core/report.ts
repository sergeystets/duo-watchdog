import { IsoDate } from './dates';

export interface DailyUsage {
    date: IsoDate;
    credits: number;
}

/** credits is null when the per-feature figure was not fetched. */
export interface FlowUsage {
    id: string;
    title: string;
    credits: number | null;
}

/** One user's GitLab Credits for the billing period GitLab reports. */
export interface CreditsReport {
    username: string | null;
    startDate: IsoDate | null;
    endDate: IsoDate | null;
    creditsUsed: number;
    blocked: boolean;
    /** GitLab's cap type when blocked (FLAT_USER_CAP, USER_OVERRIDE_CAP), else null. */
    blockedBy: string | null;
    daily: DailyUsage[];
    flows: FlowUsage[];
    /** Why the per-feature credits are missing when GitLab did not give them, else null. */
    flowsError: string | null;
}

export type Status = 'notConfigured' | 'loading' | 'ok' | 'error';

/** What the editor currently knows: the last good report, and why the latest refresh failed if it did. */
export interface Snapshot {
    status: Status;
    report: CreditsReport | null;
    error: string | null;
    fetchedAt: Date | null;
}

export const Snapshots = {
    notConfigured(): Snapshot {
        return { status: 'notConfigured', report: null, error: null, fetchedAt: null };
    },
    loading(): Snapshot {
        return { status: 'loading', report: null, error: null, fetchedAt: null };
    },
    ok(report: CreditsReport, fetchedAt: Date): Snapshot {
        return { status: 'ok', report, error: null, fetchedAt };
    },
    /** Keeps the last good report, so a failed refresh shows stale numbers marked as such rather than nothing. */
    failed(previous: Snapshot, error: string): Snapshot {
        return { status: 'error', report: previous.report, error, fetchedAt: previous.fetchedAt };
    },
};
