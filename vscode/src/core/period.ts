import { addDays, daysBetween, firstOfMonth, IsoDate, plusMonths } from './dates';

/**
 * The billing period and its run-rate projection (used / elapsed_days * period_days).
 *
 * Note: GitLab's endDate is the last day it has data for (today,
 * mid-period), not the end of the period. Taken as the end, the projection would equal what was used. A monthly
 * period runs a month from its start, so a shorter end is stretched to that.
 */
export class BillingPeriod {

    private constructor(
        readonly start: IsoDate,
        readonly end: IsoDate,
        readonly elapsedDays: number,
        readonly periodDays: number) {
    }

    static of(start: IsoDate | null, end: IsoDate | null, today: IsoDate): BillingPeriod {
        const from = start ?? firstOfMonth(today);
        const monthEnd = addDays(plusMonths(from, 1), -1);
        const to = end != null && end > monthEnd ? end : monthEnd;
        const elapsed = Math.max(daysBetween(from, today) + 1, 1);
        const period = Math.max(daysBetween(from, to) + 1, elapsed);
        return new BillingPeriod(from, to, elapsed, period);
    }

    project(used: number): number {
        return used / Math.max(this.elapsedDays, 1) * Math.max(this.periodDays, 1);
    }

    pace(used: number): number {
        return used / Math.max(this.elapsedDays, 1);
    }

    /** The day the run rate reaches the limit; null when it already has, or will not before the period ends. */
    reachDate(used: number, limit: number, today: IsoDate): IsoDate | null {
        const pace = this.pace(used);
        if (limit <= 0 || used >= limit || pace <= 0) {
            return null;
        }
        const day = addDays(today, Math.ceil((limit - used) / pace));
        return day > this.end ? null : day;
    }
}
