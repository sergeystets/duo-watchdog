import { addDays, daysBetween, dayOfMonth, IsoDate, time } from './dates';
import { BillingPeriod } from './period';
import { CreditsReport, FlowUsage, Snapshot } from './report';
import { capName, day, monthText, number, periodText } from './view';

/**
 * Everything the dashboard webview draws, worked out here so the webview only lays it out: the headline, the
 * meter, the Left, Forecast and Pace tiles, the month's daily bars, the per-feature bars, and the notes.
 */
export interface DashboardModel {
    /** "@alice · acme · Updated 14:05". */
    byline: string;
    /** "Last refresh failed: …", or null. */
    error: string | null;
    /** What to do while the extension is not set up, or null. */
    notConfigured: string | null;
    loading: boolean;
    report: ReportModel | null;
}

export type Level = 'normal' | 'warning' | 'blocked';
export type Tone = 'accent' | 'warning' | 'error';

export interface ReportModel {
    used: string;
    caption: string;
    blocked: boolean;
    /** "Blocked by GitLab's default per-user cap", or null. */
    blockedBy: string | null;
    period: string;
    meter: MeterModel | null;
    tiles: { caption: string; value: string; unit: string | null }[];
    daily: DailyModel;
    features: FeatureModel[];
    /** "Credits per feature did not load: …", or null. */
    featuresError: string | null;
    note: string;
}

export interface MeterModel {
    limitText: string;
    /** Used and forecast as fractions of the limit, kept to 0..1. */
    used: number;
    forecast: number;
    forecastText: string;
    forecastOver: boolean;
    percents: number[];
    tone: Tone;
}

export interface DailyModel {
    caption: string;
    days: number;
    /** Days from the period's start to today; past the end once the period is over. */
    todayIndex: number;
    /** The last day there can be data for: today, or the period's end once it is over. */
    lastShownDay: number;
    values: number[];
    /** Per day: "Oct 16" and "20.5 credits", for the callout. */
    dates: string[];
    amounts: string[];
    ticks: { value: number; label: string }[];
    axisMax: number;
    /** A day-of-month label every seven days. */
    xLabels: { index: number; label: string }[];
}

export interface FeatureModel {
    title: string;
    /** "20.5", or "—" when unpriced. */
    value: string;
    /** Against the costliest feature, 0..1; null when unpriced. */
    fraction: number | null;
}

export const NOT_CONFIGURED_TEXT = 'Not set up yet. Enter the GitLab URL, your group\'s path and a personal access '
    + 'token with the read_api and ai_features scopes.';

/** Normal, amber from the lowest alert percent, red once GitLab blocks Duo. */
export function level(report: CreditsReport | null, limit: number, percents: number[]): Level {
    if (report == null) {
        return 'normal';
    }
    if (report.blocked) {
        return 'blocked';
    }
    const warnAt = percents.length === 0 ? 100 : percents[0];
    return limit > 0 && report.creditsUsed >= limit * warnAt / 100 ? 'warning' : 'normal';
}

export function clamp(fraction: number): number {
    return Number.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : 0;
}

/** Gridline spacing: 1, 2 or 5 times a power of ten, two or three lines over the tallest bar. */
export function step(max: number): number {
    const raw = (max > 0 ? max : 1) / 2.5;
    const power = Math.pow(10, Math.floor(Math.log10(raw)));
    const n = raw / power;
    return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * power;
}

export function dashboardModel(snapshot: Snapshot, limit: number, percents: number[], today: IsoDate,
                               group: string): DashboardModel {
    const report = snapshot.report;
    const parts: string[] = [];
    if (report != null && report.username != null) {
        parts.push('@' + report.username);
    }
    if (report != null && group.trim() !== '') {
        parts.push(group.trim());
    }
    if (snapshot.fetchedAt != null) {
        parts.push('Updated ' + time(snapshot.fetchedAt));
    }
    return {
        byline: parts.join(' · '),
        error: snapshot.error != null ? 'Last refresh failed: ' + snapshot.error : null,
        notConfigured: snapshot.status === 'notConfigured' ? NOT_CONFIGURED_TEXT : null,
        loading: snapshot.status !== 'notConfigured' && report == null && snapshot.error == null,
        report: report != null ? reportModel(report, limit, percents, today) : null,
    };
}

function reportModel(report: CreditsReport, limit: number, percents: number[], today: IsoDate): ReportModel {
    const period = BillingPeriod.of(report.startDate, report.endDate, today);
    const used = report.creditsUsed;
    const forecast = period.project(used);
    const tone: Tone = { normal: 'accent', warning: 'warning', blocked: 'error' }[level(report, limit, percents)] as Tone;
    return {
        used: number(used),
        caption: 'of ' + number(limit) + ' credits used',
        blocked: report.blocked,
        blockedBy: report.blocked ? 'Blocked by ' + capName(report.blockedBy) : null,
        period: periodText(period),
        meter: limit > 0 ? {
            limitText: 'limit ' + number(limit),
            used: clamp(used / limit),
            forecast: clamp(forecast / limit),
            forecastText: 'forecast ' + number(forecast),
            forecastOver: forecast > limit,
            percents,
            tone,
        } : null,
        tiles: [
            { caption: 'Left', value: number(Math.max(limit - used, 0)), unit: null },
            { caption: 'Forecast · ' + day(period.end), value: number(forecast), unit: null },
            { caption: 'Pace', value: number(period.pace(used)), unit: 'a day' },
        ],
        daily: dailyModel(period, today, report),
        features: features(report.flows),
        featuresError: report.flowsError != null ? 'Credits per feature did not load: ' + report.flowsError : null,
        note: "GitLab's usage data can lag a few minutes behind actual use.",
    };
}

function dailyModel(period: BillingPeriod, today: IsoDate, report: CreditsReport): DailyModel {
    const days = Math.max(period.periodDays, 1);
    const byDate = new Map<IsoDate, number>();
    for (const d of report.daily) {
        byDate.set(d.date, (byDate.get(d.date) ?? 0) + d.credits);
    }
    const values: number[] = [];
    const dates: string[] = [];
    const amounts: string[] = [];
    for (let i = 0; i < days; i++) {
        const date = addDays(period.start, i);
        const value = byDate.get(date) ?? 0;
        values.push(value);
        dates.push(day(date));
        amounts.push(number(value) + (value === 1 ? ' credit' : ' credits'));
    }
    const max = Math.max(0, ...byDate.values());
    const gridStep = step(max);
    const axisMax = Math.max(max * 1.2, gridStep);
    const ticks: { value: number; label: string }[] = [];
    for (let v = 0; v <= axisMax + 1e-9; v += gridStep) {
        ticks.push({ value: v, label: number(v) });
    }
    const xLabels: { index: number; label: string }[] = [];
    for (let i = 0; i < days; i += 7) {
        xLabels.push({ index: i, label: String(dayOfMonth(addDays(period.start, i))) });
    }
    const todayIndex = daysBetween(period.start, today);
    return {
        caption: 'credits per day, ' + monthText(period),
        days,
        todayIndex,
        lastShownDay: Math.min(todayIndex, days - 1),
        values,
        dates,
        amounts,
        ticks,
        axisMax,
        xLabels,
    };
}

/** One row a feature, the costliest first. */
function features(flows: FlowUsage[]): FeatureModel[] {
    const sorted = [...flows].sort((a, b) => (b.credits ?? -1) - (a.credits ?? -1));
    const top = Math.max(0, ...sorted.map(f => f.credits ?? 0));
    return sorted.map(f => ({
        title: f.title,
        value: f.credits == null ? '—' : f.credits.toFixed(1),
        fraction: f.credits == null ? null : top > 0 ? clamp(f.credits / top) : 0,
    }));
}
