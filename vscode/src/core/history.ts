import { firstOfMonth, IsoDate, plusMonths } from './dates';
import { DailyUsage } from './report';

/**
 * One account's usage over months, as creditsUsage gives it for a date range: the credits billed each day, the same
 * for each feature, and what GitLab did not bill. A group member may read this much of their own usage; per project,
 * per model or per session takes the group's Owner.
 */
export interface History {
    /** The first day GitLab has, which it moves to the subscription's start. */
    start: IsoDate;
    /** The last day, at most today. */
    end: IsoDate;
    /** Billed credits per day. */
    daily: DailyUsage[];
    /** Billed credits per day of each feature used in the range. */
    flows: FlowHistory[];
    /** Credits per day GitLab did not bill (promotional, beta); empty when it did not say. */
    nonBillable: DailyUsage[];
    /** Why the per-feature figures are missing, else null. */
    flowsError: string | null;
}

export interface FlowHistory {
    id: string;
    title: string;
    daily: DailyUsage[];
}

/** One calendar month: "2026-10", its billed credits, the same per feature (most credits first) and what was not billed. */
export interface Month {
    month: string;
    credits: number;
    byFlow: { id: string; credits: number }[];
    nonBillable: number;
}

/** How many months the Statistics view asks for: GitLab answers at most a year at once. */
export const HISTORY_MONTHS = 12;

/** The first day of the month eleven months before today's: what the Statistics view asks from. */
export function historyFrom(today: IsoDate): IsoDate {
    return plusMonths(firstOfMonth(today), -(HISTORY_MONTHS - 1));
}

export function sumOf(days: readonly DailyUsage[]): number {
    return days.reduce((sum, d) => sum + d.credits, 0);
}

function inMonth(days: readonly DailyUsage[], month: string): number {
    return days.reduce((sum, d) => d.date.slice(0, 7) === month ? sum + d.credits : sum, 0);
}

/** Features by their credits over the whole range, most first; ties by title. */
export function flowsByCredits(history: History): FlowHistory[] {
    return [...history.flows].sort((a, b) => sumOf(b.daily) - sumOf(a.daily) || (a.title < b.title ? -1 : a.title > b.title ? 1 : 0));
}

/** Every calendar month from the start's to the end's, oldest first; months without use are zero. */
export function months(history: History): Month[] {
    const order = flowsByCredits(history);
    const result: Month[] = [];
    const last = history.end.slice(0, 7);
    for (let day = firstOfMonth(history.start); day.slice(0, 7) <= last; day = plusMonths(day, 1)) {
        const month = day.slice(0, 7);
        result.push({
            month,
            credits: inMonth(history.daily, month),
            byFlow: order.map(flow => ({ id: flow.id, credits: inMonth(flow.daily, month) })),
            nonBillable: inMonth(history.nonBillable, month),
        });
    }
    return result;
}

export function total(history: History): number {
    return sumOf(history.daily);
}

export function nonBillableTotal(history: History): number {
    return sumOf(history.nonBillable);
}

/**
 * Every account's history as one: the earliest start to the latest end, the days of all of them, and features of the
 * same title as one feature. The reasons features are missing are kept, each once.
 */
export function mergeHistories(histories: readonly History[]): History | null {
    if (histories.length === 0) {
        return null;
    }
    const flows = new Map<string, FlowHistory>();
    for (const history of histories) {
        for (const flow of history.flows) {
            const same = flows.get(flow.title);
            if (same == null) {
                flows.set(flow.title, { id: flow.id, title: flow.title, daily: [...flow.daily] });
            } else {
                same.daily.push(...flow.daily);
            }
        }
    }
    const errors = [...new Set(histories.map(h => h.flowsError).filter((e): e is string => e != null))];
    return {
        start: histories.map(h => h.start).reduce((a, b) => a < b ? a : b),
        end: histories.map(h => h.end).reduce((a, b) => a > b ? a : b),
        daily: histories.flatMap(h => h.daily),
        flows: [...flows.values()],
        nonBillable: histories.flatMap(h => h.nonBillable),
        flowsError: errors.length === 0 ? null : errors.join('; '),
    };
}
