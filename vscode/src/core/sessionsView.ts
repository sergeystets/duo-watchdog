import { FeatureModel, features } from './dashboard';
import { addDays, IsoDate, localToday, monthName, parseDate, time } from './dates';
import {
    estimateCredits, lastDays, outside, place, Range, Session, Share, stepsTotal, Summary,
} from './sessions';
import { day, number } from './view';

/** The Sessions view: a range's Agent Platform sessions with what each cost, as far as it can be told. */

export type RangeKind = 'today' | 'yesterday' | '7' | '30' | 'custom';
export const RANGE_KINDS: readonly RangeKind[] = ['today', 'yesterday', '7', '30', 'custom'];
export const DEFAULT_KIND: RangeKind = '7';

export const SESSIONS_FOOTER = "≈ estimates. GitLab tells a group member credits per day and feature, not per session: each "
    + "feature's credits on a day are split among that day's sessions of the feature, by the agent's steps when the Duo "
    + "CLI's logs on this machine count every one of them, else equally.";

/** The days a choice covers, UTC days as GitLab counts them; a custom one with its ends in order. */
export function rangeOf(kind: RangeKind, today: IsoDate, custom: Range | null): Range {
    switch (kind) {
        case 'today':
            return lastDays(1, today);
        case 'yesterday':
            return { from: addDays(today, -1), to: addDays(today, -1) };
        case '30':
            return lastDays(30, today);
        case 'custom':
            if (custom != null && parseDate(custom.from) != null && parseDate(custom.to) != null) {
                return custom.from <= custom.to ? custom : { from: custom.to, to: custom.from };
            }
            return lastDays(7, today);
        default:
            return lastDays(7, today);
    }
}

/** "Oct 28 – Nov 3", or "Nov 3" for one day. */
export function rangeLabel(range: Range): string {
    return range.from === range.to ? day(range.from) : day(range.from) + ' – ' + day(range.to);
}

/** "Oct 28, 15:08" on this machine's clock. */
export function localWhen(ms: number): string {
    const at = new Date(ms);
    return monthName(localToday(at)).slice(0, 3) + ' ' + at.getDate() + ', ' + time(at);
}

/** "Oct 28 · Agentic Chat 60.0 credits, split by agent steps among 2 sessions → 45.0". */
export function shareLine(share: Share): string {
    const how = share.sessions === 1 ? 'all to this session'
        : 'split ' + (share.bySteps ? 'by agent steps' : 'equally') + ' among ' + share.sessions + ' sessions';
    return day(share.day) + ' · ' + share.feature + ' ' + share.dayCredits.toFixed(1) + ' credits, ' + how + ' → '
        + share.credits.toFixed(1);
}

export interface SessionRow {
    id: number;
    title: string;
    /** "apps/notes · chat · Finished": project, feature (flow), status. */
    meta: string;
    /** "≈ 45.0". */
    credits: string;
    /** "300 steps", when the logs here count them. */
    steps: string | null;
    /** What the text box filters on: the title and the project, in lower case. */
    search: string;
}

export interface SessionDetails {
    id: number;
    title: string;
    lines: { label: string; value: string }[];
    shares: string[];
    url: string | null;
}

export interface SessionsModel {
    tiles: { caption: string; value: string; unit: string | null }[];
    byProject: FeatureModel[];
    /** Newest first. */
    rows: SessionRow[];
    details: SessionDetails | null;
    /** "2 sessions outside acme are left out: …", or null. */
    leftOut: string | null;
    footer: string;
}

/**
 * The range's tiles, projects and rows, and the details of the focused session.
 * @param leftOut sessions in the range outside the account's group
 * @param when how a time is shown: on this machine's clock
 */
export function sessionsModel(summary: Summary, group: string | null, focused: number | null, leftOut: number,
                              when: (ms: number) => string = localWhen): SessionsModel {
    const range = summary.range;
    const rows = summary.rows.map(e => {
        const project = place(e.session, e.steps, group);
        const steps = e.steps == null ? 0 : stepsTotal(e.steps);
        return {
            id: e.session.id,
            title: e.session.title,
            meta: [project, e.session.flow, e.session.status].filter((x): x is string => x != null && x.trim() !== '').join(' · '),
            credits: '≈ ' + estimateCredits(e, range).toFixed(1),
            steps: steps > 0 ? steps + (steps === 1 ? ' step' : ' steps') : null,
            search: (e.session.title + ' ' + project + ' ' + (e.session.project ?? '')).toLowerCase(),
        };
    });
    const focusedEstimate = summary.rows.find(e => e.session.id === focused) ?? null;
    let details: SessionDetails | null = null;
    if (focusedEstimate != null) {
        const s: Session = focusedEstimate.session;
        const steps = focusedEstimate.steps == null ? 0 : stepsTotal(focusedEstimate.steps);
        details = {
            id: s.id,
            title: s.title,
            lines: [
                { label: 'Project', value: place(s, focusedEstimate.steps, group) },
                { label: 'Flow', value: s.flow ?? '—' },
                { label: 'Model', value: s.model ?? '—' },
                { label: 'Status', value: s.status ?? '—' },
                { label: 'Started', value: when(s.created) },
                { label: 'Updated', value: when(s.updated) },
                { label: 'Steps on this machine', value: steps > 0 ? String(steps) : 'none counted' },
                { label: '≈ Credits', value: '≈ ' + estimateCredits(focusedEstimate).toFixed(1) },
            ],
            shares: focusedEstimate.shares.map(shareLine),
            url: s.webUrl,
        };
    }
    return {
        tiles: [
            { caption: 'Sessions', value: String(summary.rows.length), unit: null },
            { caption: 'Credits', value: number(summary.total), unit: null },
            { caption: '≈ In sessions', value: number(summary.inSessions), unit: null },
            // Code Suggestions, classic Chat, and what no session accounts for: as the IntelliJ tile says it.
            { caption: 'Other use', value: number(outside(summary)), unit: null },
        ],
        byProject: features(summary.byProject.map(p => ({ id: p.title, title: p.title, credits: p.credits }))),
        rows,
        details,
        leftOut: leftOutText(leftOut, group),
        footer: SESSIONS_FOOTER,
    };
}

/** "2 sessions outside acme are left out: GitLab bills their credits to another group."; null for none. */
export function leftOutText(count: number, group: string | null): string | null {
    if (count <= 0) {
        return null;
    }
    return (count === 1 ? '1 session' : count + ' sessions') + ' outside ' + (group || 'the group')
        + (count === 1 ? ' is' : ' are') + ' left out: GitLab bills their credits to another group.';
}

/** What the host knows of one range: its summary once loaded, or why not. */
export interface SessionsEntry {
    summary: Summary | null;
    leftOut: number;
    error: string | null;
    warning: string | null;
    loading: boolean;
}

export interface SessionsView {
    kind: RangeKind;
    from: IsoDate;
    to: IsoDate;
    /** "Oct 28 – Nov 3". */
    label: string;
    /** For an account with a problem: show the Usage view's notice instead. */
    useNotice: boolean;
    loading: boolean;
    errors: string[];
    /** "No session #43 for @dana.k.", and the like. */
    message: string | null;
    model: SessionsModel | null;
}

/** The Sessions view for the selected account: its range, and its sessions once loaded. */
export function sessionsView(kind: RangeKind, range: Range, problem: boolean, entry: SessionsEntry | undefined,
                             group: string | null, focused: number | null, message: string | null,
                             when?: (ms: number) => string): SessionsView {
    const view: SessionsView = {
        kind, from: range.from, to: range.to, label: rangeLabel(range), useNotice: problem, loading: false, errors: [],
        message, model: null,
    };
    if (problem) {
        return view;
    }
    if (entry?.error != null) {
        view.errors.push('Sessions did not load: ' + entry.error);
    }
    if (entry?.warning != null) {
        view.errors.push(entry.warning);
    }
    if (entry?.summary != null) {
        view.model = sessionsModel(entry.summary, group, focused, entry.leftOut, when);
    }
    view.loading = entry == null || (entry.loading && entry.summary == null);
    return view;
}
