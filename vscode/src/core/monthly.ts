import { addDays, IsoDate, parseDate, plusMonths } from './dates';
import { FlowHistory, History, mergeHistories } from './history';
import { DailyUsage } from './report';

/**
 * Statistics a month at a time: which months a range covers, which of them are settled (GitLab will not change them
 * any more) and so kept on disk, and how a kept month is written and read.
 */

/** The ranges the Statistics view offers, in months; 3 unless the user picked another. */
export const STATISTICS_RANGES = [3, 6, 12] as const;
export type StatisticsRange = typeof STATISTICS_RANGES[number];
export const DEFAULT_RANGE: StatisticsRange = 3;

export function rangeOrDefault(value: unknown): StatisticsRange {
    return STATISTICS_RANGES.find(r => r === value) ?? DEFAULT_RANGE;
}

/** "2026-11", "2026-10", "2026-09": today's month and the ones before it, newest first. */
export function monthsOfRange(range: number, today: IsoDate): string[] {
    const first = today.slice(0, 7) + '-01';
    return Array.from({ length: Math.max(range, 1) }, (_, i) => plusMonths(first, -i).slice(0, 7));
}

/** "2026-02-28" for "2026-02". */
export function lastDayOf(month: string): IsoDate {
    return addDays(plusMonths(month + '-01', 1), -1);
}

/** Days after a month's last day before GitLab's figures for it stop changing. */
export const SETTLE_DAYS = 2;

/** GitLab will not change the month any more: today is at least two days after its last day. */
export function isSettled(month: string, today: IsoDate): boolean {
    return today >= addDays(lastDayOf(month), SETTLE_DAYS);
}

export interface MonthPlan {
    /** Asked of GitLab: the current month, the ones not settled yet, and settled ones not kept yet. */
    fetch: string[];
    /** Read from the disk: settled months kept before. */
    fromCache: string[];
}

/**
 * Which months of the range to ask GitLab for and which to read from the disk. A settled month kept on the disk is
 * never asked again; everything else always is, so Reload asks again only what can still change.
 */
export function planMonths(range: number, today: IsoDate, cached: (month: string) => boolean): MonthPlan {
    const plan: MonthPlan = { fetch: [], fromCache: [] };
    for (const month of monthsOfRange(range, today)) {
        (isSettled(month, today) && cached(month) ? plan.fromCache : plan.fetch).push(month);
    }
    return plan;
}

function safe(name: string): string {
    const cleaned = name.replace(/[^A-Za-z0-9._-]/g, '_');
    return cleaned === '' || cleaned.startsWith('.') ? '_' + cleaned : cleaned;
}

/** The folder of an account's kept months, under the extension's storage: ["history", "gitlab.com_dana.k"]. */
export function cacheFolder(accountId: string): string[] {
    return ['history', safe(accountId)];
}

/**
 * Where a month is kept: ["history", "gitlab.com_dana.k", "acme-2026-09.json"]. The group is in the name, so a
 * changed group starts afresh.
 */
export function cacheFile(accountId: string, group: string, month: string): string[] {
    return [...cacheFolder(accountId), safe(group) + '-' + month + '.json'];
}

/** A kept month: GitLab's answer, or null when it had nothing for the month (it is before the subscription). */
export interface CachedMonth {
    month: string;
    history: History | null;
}

const VERSION = 1;

export function monthToJson(month: string, history: History | null): string {
    return JSON.stringify({ version: VERSION, month, history });
}

function days(value: unknown): DailyUsage[] | null {
    if (!Array.isArray(value)) {
        return null;
    }
    const result: DailyUsage[] = [];
    for (const d of value) {
        const date = parseDate(typeof d?.date === 'string' ? d.date : null);
        if (date == null || typeof d.credits !== 'number' || !Number.isFinite(d.credits)) {
            return null;
        }
        result.push({ date, credits: d.credits });
    }
    return result;
}

/** A kept month as written by monthToJson; undefined when the text is not one (it is then asked again). */
export function monthFromJson(text: string): CachedMonth | undefined {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch {
        return undefined;
    }
    const o = parsed as Record<string, unknown> | null;
    if (o == null || typeof o !== 'object' || o.version !== VERSION || typeof o.month !== 'string'
        || !/^\d{4}-\d{2}$/.test(o.month)) {
        return undefined;
    }
    if (o.history === null) {
        return { month: o.month, history: null };
    }
    const h = o.history as Record<string, unknown> | undefined;
    const start = parseDate(typeof h?.start === 'string' ? h.start : null);
    const end = parseDate(typeof h?.end === 'string' ? h.end : null);
    const daily = days(h?.daily);
    const nonBillable = days(h?.nonBillable);
    if (h == null || start == null || end == null || daily == null || nonBillable == null || !Array.isArray(h.flows)
        || !(h.flowsError === null || typeof h.flowsError === 'string')) {
        return undefined;
    }
    const flows: FlowHistory[] = [];
    for (const f of h.flows as Record<string, unknown>[]) {
        const flowDays = days(f?.daily);
        if (typeof f?.id !== 'string' || typeof f.title !== 'string' || flowDays == null) {
            return undefined;
        }
        flows.push({ id: f.id, title: f.title, daily: flowDays });
    }
    return { month: o.month, history: { start, end, daily, flows, nonBillable, flowsError: h.flowsError as string | null } };
}

/**
 * The months that answered, as one history: the same merge as All accounts. Months without use inside it count as
 * zeros; months GitLab had nothing for at the old end are left out, so it starts at the oldest month that answered.
 * Null when no month did.
 */
export function mergeMonths(months: readonly (History | null)[]): History | null {
    return mergeHistories(months.filter((h): h is History => h != null));
}

/** Runs at most `size` tasks at once; the others wait their turn. */
export class Limiter {

    private running = 0;
    private readonly waiting: (() => void)[] = [];

    constructor(readonly size: number) {
    }

    async run<T>(task: () => Promise<T>): Promise<T> {
        if (this.running >= this.size) {
            // The slot of a task that finishes is handed straight to this one.
            await new Promise<void>(resolve => this.waiting.push(resolve));
        } else {
            this.running++;
        }
        try {
            return await task();
        } finally {
            const next = this.waiting.shift();
            if (next != null) {
                next();
            } else {
                this.running--;
            }
        }
    }
}

/** What GitLab said for a month: its history, or an error with its HTTP status (0 when it was not reached). */
export type MonthAnswer = { month: string; history: History } | { month: string; error: string; status: number };

export interface MonthsOutcome {
    /** The months that answered, kept ones included, as one history; null when none did. */
    history: History | null;
    /** Settled months to keep on the disk. */
    keep: CachedMonth[];
    /** A refusal (HTTP 401, 403, …): the same for every month, so it is the answer. */
    refused: string | null;
    /** "2026-10: Cannot reach gitlab.com: …": months that could not be read this time. */
    failed: string[];
}

/**
 * The months asked and the months kept, as one result. A settled month is kept when it answered in full (its
 * features too), or as "nothing" when GitLab failed on it with HTTP 5xx, which it does before the subscription; a
 * month that can still change is never kept, and a server error on it is a failure.
 */
export function monthsOutcome(today: IsoDate, kept: readonly CachedMonth[], answers: readonly MonthAnswer[]): MonthsOutcome {
    const outcome: MonthsOutcome = { history: null, keep: [], refused: null, failed: [] };
    const histories: (History | null)[] = kept.map(k => k.history);
    for (const answer of [...answers].sort((a, b) => b.month.localeCompare(a.month))) {
        const settled = isSettled(answer.month, today);
        if ('history' in answer) {
            histories.push(answer.history);
            if (settled && answer.history.flowsError == null) {
                outcome.keep.push({ month: answer.month, history: answer.history });
            }
        } else if (answer.status >= 500 && settled) {
            outcome.keep.push({ month: answer.month, history: null });
        } else if (answer.status > 0 && answer.status < 500) {
            outcome.refused ??= answer.error;
        } else {
            outcome.failed.push(answer.month + ': ' + answer.error);
        }
    }
    outcome.history = mergeMonths(histories);
    return outcome;
}
