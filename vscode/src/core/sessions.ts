import { addDays, IsoDate } from './dates';
import { History } from './history';
import { DailyUsage } from './report';

/**
 * The user's GitLab Duo Agent Platform sessions and an estimate of what each cost. GitLab tells a group member their
 * credits per day and per feature, not per session, so a feature's credits on a day are split among that day's
 * sessions of the feature: by the agent's steps when the Duo CLI's logs on this machine count them for every one of
 * those sessions, else equally. Days are UTC days, as GitLab counts usage. A port of Sessions in the plugin's core.
 */

/** GitLab lists a user's sessions for about this long. */
export const KEPT_DAYS = 30;

/** One session as GitLab lists it. Times are in ms since the epoch. */
export interface Session {
    id: number;
    title: string;
    /** The flow type, such as "chat" or "developer/v1". */
    flow: string | null;
    /** The project's full path, else null. */
    project: string | null;
    /** The namespace's full path, else null. */
    group: string | null;
    /** The model, on GitLab 19.2 and later, else null. */
    model: string | null;
    status: string | null;
    created: number;
    updated: number;
    webUrl: string | null;
}

/** What the Duo CLI's logs on this machine say of one session: the agent's steps on each UTC day. */
export interface Steps {
    id: number;
    /** One step for each action the model asked the CLI to run, by day, days in order. */
    byDay: Record<IsoDate, number>;
    /** The folder the CLI ran in, as the log's name gives it. */
    folder: string;
}

/** One day's part of a session's estimate. */
export interface Share {
    day: IsoDate;
    /** The feature whose credits were split, or several joined when no session matched them. */
    feature: string;
    /** That feature's credits that day. */
    dayCredits: number;
    credits: number;
    /** How many sessions shared them. */
    sessions: number;
    /** Split by agent steps, else equally. */
    bySteps: boolean;
}

/** A session, its steps when the logs here count them, and its estimated credits, day by day. */
export interface Estimate {
    session: Session;
    steps: Steps | null;
    shares: Share[];
}

/** From one day to another, both in. */
export interface Range {
    from: IsoDate;
    to: IsoDate;
}

/** The range's sessions and credits. */
export interface Summary {
    range: Range;
    /** The sessions active in the range, last updated first. */
    rows: Estimate[];
    /** The credits GitLab counted in the range. */
    total: number;
    /** The estimated credits of the listed sessions in the range. */
    inSessions: number;
    /** Estimated credits per project, most first. */
    byProject: { title: string; credits: number }[];
}

/** The UTC day of a time. */
export function dayOf(ms: number): IsoDate {
    return new Date(ms).toISOString().slice(0, 10);
}

/** Today, as GitLab counts days. */
export function utcToday(now: Date = new Date()): IsoDate {
    return dayOf(now.getTime());
}

export function stepsTotal(steps: Steps): number {
    return Object.values(steps.byDay).reduce((sum, n) => sum + n, 0);
}

function stepsOn(steps: Steps | null | undefined, day: IsoDate): number {
    return steps?.byDay[day] ?? 0;
}

export function contains(range: Range, day: IsoDate): boolean {
    return day >= range.from && day <= range.to;
}

/** The last n days to today, both in. */
export function lastDays(days: number, today: IsoDate): Range {
    return { from: addDays(today, -(Math.max(days, 1) - 1)), to: today };
}

/** From the day a session started to the day it was last updated: for a session looked up by its ID. */
export function around(s: Session): Range {
    const first = dayOf(s.created);
    const last = dayOf(s.updated);
    return { from: first, to: last < first ? first : last };
}

/** The days it was active on, as far as GitLab says: the day it started and the day it was last updated. */
function sessionDays(s: Session): IsoDate[] {
    return [...new Set([dayOf(s.created), dayOf(s.updated)])];
}

/** The days a session was active on: those its log counts steps on, else those GitLab gives. */
export function activeDays(s: Session, steps: Steps | null | undefined): IsoDate[] {
    const logged = steps == null ? [] : Object.keys(steps.byDay);
    return logged.length > 0 ? logged : sessionDays(s);
}

export function estimateCredits(e: Estimate, range?: Range): number {
    return e.shares.filter(s => range == null || contains(range, s.day)).reduce((sum, s) => sum + s.credits, 0);
}

/** Whether it was active in the range. */
export function isIn(e: Estimate, range: Range): boolean {
    return activeDays(e.session, e.steps).some(d => contains(range, d));
}

/** Where it ran: its project, else the folder the CLI ran in, else its group; without the account's group. */
export function place(s: Session, steps: Steps | null | undefined, group: string | null): string {
    const path = s.project != null ? s.project : steps != null && steps.folder.trim() !== '' ? steps.folder : s.group;
    if (path == null || path.trim() === '') {
        return '—';
    }
    const prefix = group == null || group.trim() === '' ? '' : group.toLowerCase() + '/';
    return prefix !== '' && path.toLowerCase().startsWith(prefix) ? path.slice(prefix.length) : path;
}

/** Whether the session ran in the group, whose credits GitLab bills; one with neither project nor group counts. */
export function inGroup(s: Session, group: string | null): boolean {
    const path = s.project != null ? s.project : s.group;
    if (path == null || path.trim() === '' || group == null || group.trim() === '') {
        return true;
    }
    const p = path.toLowerCase();
    const g = group.toLowerCase();
    return p === g || p.startsWith(g + '/');
}

/**
 * The session number in what the user typed: the number the Duo CLI shows, GitLab's global ID, or the session's
 * address; null when it is none of them.
 */
export function sessionId(text: string | null | undefined): number | null {
    const t = (text ?? '').trim();
    if (/^#?\d{1,18}$/.test(t)) {
        return Number(t.replace('#', ''));
    }
    const global = /Workflow\/(\d+)/.exec(t);
    if (global != null) {
        return Number(global[1]);
    }
    const url = /\/(\d+)\/?(?:[?#].*)?$/.exec(t);
    return t.includes('://') && url != null ? Number(url[1]) : null;
}

/** A feature's credits on one day. */
interface Feature {
    id: string;
    title: string;
    credits: number;
}

/**
 * Each session's estimate. On each day: every session active that day goes with the feature its flow matches; a
 * feature's credits are split among its sessions; the credits of features no session matched are split among the
 * sessions that matched none, or stay outside every session when there are none.
 */
export function estimate(sessions: readonly Session[], steps: ReadonlyMap<number, Steps>, history: History): Estimate[] {
    const byDay = new Map<IsoDate, Session[]>();
    for (const s of sessions) {
        for (const d of activeDays(s, steps.get(s.id))) {
            byDay.set(d, [...(byDay.get(d) ?? []), s]);
        }
    }
    const shares = new Map<number, Share[]>();
    for (const day of [...byDay.keys()].sort()) {
        const features = featuresOn(history, day);
        const matched = new Map<Feature, Session[]>();
        const unmatched: Session[] = [];
        for (const s of byDay.get(day)!) {
            const feature = match(s.flow, features);
            if (feature == null) {
                unmatched.push(s);
            } else {
                matched.set(feature, [...(matched.get(feature) ?? []), s]);
            }
        }
        let leftover = 0;
        const leftoverTitles: string[] = [];
        for (const f of features) {
            const members = matched.get(f);
            if (members != null) {
                split(f.title, f.credits, members, day, steps, shares);
            } else if (f.credits > 0) {
                leftover += f.credits;
                leftoverTitles.push(f.title);
            }
        }
        if (unmatched.length > 0 && leftover > 0) {
            split(leftoverTitles.join(', '), leftover, unmatched, day, steps, shares);
        }
    }
    return sessions.map(s => ({ session: s, steps: steps.get(s.id) ?? null, shares: [...(shares.get(s.id) ?? [])] }));
}

/** The range's sessions, newest first, its credits, and the credits per project. */
export function summarize(estimates: readonly Estimate[], history: History, range: Range, group: string | null): Summary {
    const rows = estimates.filter(e => isIn(e, range)).sort((a, b) => b.session.updated - a.session.updated);
    const total = history.daily.filter(d => contains(range, d.date)).reduce((sum, d) => sum + d.credits, 0);
    const inSessions = rows.reduce((sum, e) => sum + estimateCredits(e, range), 0);
    const projects = new Map<string, number>();
    for (const e of rows) {
        const p = place(e.session, e.steps, group);
        projects.set(p, (projects.get(p) ?? 0) + estimateCredits(e, range));
    }
    const byProject = [...projects.entries()]
        .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
        .map(([title, credits]) => ({ title, credits }));
    return { range, rows, total, inSessions, byProject };
}

/** What the range's sessions did not account for: Code Suggestions, classic Chat, sessions elsewhere. */
export function outside(summary: Summary): number {
    return Math.max(summary.total - summary.inSessions, 0);
}

function split(feature: string, credits: number, members: Session[], day: IsoDate, steps: ReadonlyMap<number, Steps>,
               out: Map<number, Share[]>): void {
    const bySteps = members.every(s => stepsOn(steps.get(s.id), day) > 0);
    const whole = bySteps ? members.reduce((sum, s) => sum + stepsOn(steps.get(s.id), day), 0) : members.length;
    for (const s of members) {
        const weight = bySteps ? stepsOn(steps.get(s.id), day) : 1;
        out.set(s.id, [...(out.get(s.id) ?? []),
            { day, feature, dayCredits: credits, credits: credits * weight / whole, sessions: members.length, bySteps }]);
    }
}

function sumOn(days: readonly DailyUsage[], day: IsoDate): number {
    return days.reduce((sum, d) => d.date === day ? sum + d.credits : sum, 0);
}

/**
 * The day's features with their credits; what the priced features leave of the day's total as one more; the day's
 * total alone when GitLab gave no features.
 */
function featuresOn(history: History, day: IsoDate): Feature[] {
    const total = sumOn(history.daily, day);
    const features: Feature[] = [];
    let priced = 0;
    for (const f of history.flows) {
        const credits = sumOn(f.daily, day);
        features.push({ id: f.id, title: f.title, credits });
        priced += credits;
    }
    if (total - priced > 0.005) {
        features.push({ id: '', title: features.length === 0 ? 'All features' : 'Other features', credits: total - priced });
    }
    return features;
}

/**
 * The feature a flow type is billed under: the one whose id or title has the same words, else one whose id or title
 * holds all of its words ("chat" under "Agentic Chat"); null when none does.
 */
function match(flow: string | null, features: Feature[]): Feature | null {
    const w = words(flow);
    if (w.size === 0) {
        return null;
    }
    let loose: Feature | null = null;
    for (const f of features) {
        if (f.id === '') {
            continue;
        }
        const id = words(f.id);
        const title = words(f.title);
        if (same(id, w) || same(title, w)) {
            return f;
        }
        if (loose == null && (holds(id, w) || holds(title, w))) {
            loose = f;
        }
    }
    return loose;
}

function same(a: Set<string>, b: Set<string>): boolean {
    return a.size === b.size && holds(a, b);
}

function holds(a: Set<string>, b: Set<string>): boolean {
    return [...b].every(x => a.has(x));
}

/** Lower-case words, without versions ("v1") and without "flow", which every flow's name may have. */
export function words(text: string | null | undefined): Set<string> {
    const result = new Set<string>();
    for (const w of (text ?? '').toLowerCase().split(/[^a-z0-9]+/)) {
        if (w !== '' && !/^v\d+$/.test(w) && w !== 'flow') {
            result.add(w);
        }
    }
    return result;
}
