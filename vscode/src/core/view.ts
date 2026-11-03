import { dayOfMonth, IsoDate, monthName } from './dates';
import { BillingPeriod } from './period';
import { Snapshot, spentOn } from './report';

/** The words and numbers the extension shows: the status bar text, its tooltip, and the dashboard's dates. */

/** Whole numbers bare, the rest to one decimal: 100, 42.4. */
export function number(value: number): string {
    const rounded = Math.round(value * 10) / 10;
    return Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1);
}

/** As number(), with thousands grouped: 2,100, 1,234.5. */
export function grouped(value: number): string {
    const text = number(value);
    const [whole, fraction] = text.split('.');
    const sign = whole.startsWith('-') ? '-' : '';
    const digits = sign === '' ? whole : whole.slice(1);
    return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction != null ? '.' + fraction : '');
}

/** "$21.5 / 300": a credit costs $1 at GitLab's list price. */
export function statusText(s: Snapshot, limit: number): string {
    const r = s.report;
    if (r == null) {
        switch (s.status) {
            case 'notConfigured':
                return 'Duo: set up';
            case 'error':
                return 'Duo: error';
            default:
                return 'Duo: …';
        }
    }
    return (r.blocked ? 'Blocked · ' : '') + '$' + number(r.creditsUsed) + ' / ' + number(limit)
        + (s.status === 'error' ? ' ⚠' : '');
}

/**
 * The tooltip's lines about one account's numbers, as plain text: used and left, spent today, the forecast, the
 * block, and why the last refresh failed.
 */
export function tooltipLines(s: Snapshot, limit: number, today: IsoDate): string[] {
    const lines: string[] = [];
    const r = s.report;
    if (r == null && s.status === 'loading') {
        lines.push('Loading…');
    }
    if (r != null) {
        const period = BillingPeriod.of(r.startDate, r.endDate, today);
        lines.push(usedLine(r.creditsUsed, limit));
        lines.push('Spent today: ' + number(spentOn(r, today)));
        lines.push('Forecast by ' + day(period.end) + ': ' + number(period.project(r.creditsUsed)));
        if (r.blocked) {
            lines.push('Blocked by ' + capName(r.blockedBy));
        }
    }
    if (s.error != null) {
        lines.push('Last refresh failed: ' + s.error);
    }
    return lines;
}

/** "Used 42 of 100 (42%), left 58". */
export function usedLine(used: number, limit: number): string {
    return 'Used ' + number(used) + ' of ' + number(limit) + ' (' + percent(used, limit) + '), left '
        + number(Math.max(limit - used, 0));
}

/** "October 1 – 31 · day 18 of 31"; a period across two months names both. */
export function periodText(period: BillingPeriod): string {
    const { start, end } = period;
    const sameMonth = start.slice(0, 7) === end.slice(0, 7);
    const to = sameMonth ? String(dayOfMonth(end)) : monthDay(end);
    return monthDay(start) + ' – ' + to + ' · day ' + Math.min(period.elapsedDays, period.periodDays)
        + ' of ' + period.periodDays;
}

/** "October", or "September – October" for a period across two months. */
export function monthText(period: BillingPeriod): string {
    const from = monthName(period.start);
    const to = monthName(period.end);
    return from === to ? from : from + ' – ' + to;
}

/** "Oct 31". */
export function day(date: IsoDate): string {
    return monthName(date).slice(0, 3) + ' ' + dayOfMonth(date);
}

function monthDay(date: IsoDate): string {
    return monthName(date) + ' ' + dayOfMonth(date);
}

export function capName(capType: string | null): string {
    if (capType === 'FLAT_USER_CAP') {
        return "GitLab's default per-user cap";
    }
    if (capType === 'USER_OVERRIDE_CAP') {
        return 'your personal GitLab cap';
    }
    return 'a GitLab cap';
}

function percent(used: number, limit: number): string {
    return limit > 0 ? Math.round(used / limit * 100) + '%' : 'no limit';
}
