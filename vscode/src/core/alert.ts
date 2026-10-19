import { IsoDate } from './dates';
import { BillingPeriod } from './period';
import { CreditsReport } from './report';
import { day, number } from './view';

export type AlertKind = 'forecast' | 'threshold' | 'limit' | 'blocked';

/**
 * The notice a refresh raises: the most severe one that holds now.
 *
 * Within a period usage only grows, so the notices come in order (forecast, each threshold, the limit, GitLab's
 * block) and each has its own key. The extension remembers the last key it showed, so every notice appears once a
 * period, and again only after the limit is changed.
 */
export interface Alert {
    key: string;
    kind: AlertKind;
    title: string;
    message: string;
}

export const DEFAULT_PERCENTS = '80, 95';

/**
 * @param percents ascending thresholds, as parsePercents returns them
 * @param forecast whether to warn when the month-end projection passes the limit before any threshold is crossed
 */
export function alertFor(report: CreditsReport, limit: number, percents: number[], forecast: boolean,
                         today: IsoDate): Alert | null {
    const period = String(report.startDate);
    const used = report.creditsUsed;
    if (report.blocked) {
        return {
            key: 'blocked|' + period, kind: 'blocked', title: 'GitLab Duo is blocked',
            message: 'GitLab stopped your Duo requests: your credits cap for this period is reached ('
                + number(used) + ' used). Ask the group owner to raise it.',
        };
    }
    if (limit <= 0) {
        return null;
    }
    const scope = period + '|' + number(limit);
    const billing = BillingPeriod.of(report.startDate, report.endDate, today);
    const reach = billing.reachDate(used, limit, today);
    const pace = ' At this pace: ' + number(billing.project(used)) + ' by ' + day(billing.end)
        + (reach != null ? ', the limit is reached around ' + day(reach) : '') + '.';
    if (used >= limit) {
        return {
            key: 'limit|' + scope, kind: 'limit', title: 'Duo credits: limit reached',
            message: 'Used ' + number(used) + ' of your ' + number(limit) + '-credit limit.',
        };
    }
    for (let i = percents.length - 1; i >= 0; i--) {
        const percent = percents[i];
        if (used >= limit * percent / 100) {
            return {
                key: 'threshold|' + scope + '|' + percent, kind: 'threshold',
                title: 'Duo credits: ' + percent + '% of your limit',
                message: 'Used ' + number(used) + ' of ' + number(limit) + '.' + pace,
            };
        }
    }
    if (forecast && billing.project(used) > limit) {
        return {
            key: 'forecast|' + scope, kind: 'forecast', title: 'Duo credits: on pace to pass your limit',
            message: 'Used ' + number(used) + ' of ' + number(limit) + ' so far.' + pace,
        };
    }
    return null;
}

/** "80, 95" to [80, 95]: whole percents from 1 to 100, sorted, repeats dropped; blank means no thresholds. */
export function parsePercents(text: string | null | undefined): number[] {
    const percents = new Set<number>();
    for (const part of (text ?? '').split(/[,;\s]+/)) {
        if (part.trim() === '') {
            continue;
        }
        const digits = part.endsWith('%') ? part.slice(0, -1) : part;
        if (!/^[+-]?\d+$/.test(digits)) {
            throw new Error('Not a whole percent: ' + part);
        }
        const percent = Number(digits);
        if (percent < 1 || percent > 100) {
            throw new Error('A percent must be from 1 to 100: ' + part);
        }
        percents.add(percent);
    }
    return [...percents].sort((a, b) => a - b);
}

/** The alert percents, or the default ones while the text does not parse. */
export function percentsOrDefault(text: string | null | undefined): number[] {
    try {
        return parsePercents(text);
    } catch {
        return parsePercents(DEFAULT_PERCENTS);
    }
}
