import { time } from './dates';
import { logFolder } from './duoCliLogs';
import { modelName, perCall } from './modelCredits';
import { number } from './view';

/**
 * The Duo CLI session in progress, from the log of the CLI's run, read line by line as the log grows: the agent's
 * steps (model calls), the prompts sent (round trips), the model, and ≈ credits, each step at its model's rate per
 * call. Three kinds of line are looked at, and of them only the time, the session and one field:
 * - `[DuoWorkflowNodeExecutor][<session>] [requestID: <id>] Action received`: a step, counted once per session and
 *   request, as the Sessions view counts them;
 * - `[RunController] Executing workflow: {"type": "SEND_PROMPT", …}`: a round trip, counted; the prompt itself is not
 *   read;
 * - `[ModelResolverService] Using user-selected model: "<identifier>"`: the model of the steps after it.
 * Each is known by its tag coming before any quote or brace, so a prompt or code that quotes one is not taken for it.
 * No more than the start of a line is looked at, and only counts, session numbers and the model are kept. A prompt
 * names no session: it goes to the session of the next step. Each run of the CLI writes a log of its own, so a session
 * resumed from an earlier run counts what this run did. A port of LiveSession in the plugin's core.
 */

/** No more of a line than this is looked at: a step's session and request, a prompt's type, a model. */
export const HEAD = 400;

/** The round trips per session recommended at most, unless the settings say otherwise. */
export const RECOMMENDED_ROUND_TRIPS = 20;
/** A log that has gone this long without a line is a run waiting for its user, or one that ended. */
export const QUIET_MS = 60_000;

/** The line's time, to the second and its milliseconds, then anything but a quote or a brace up to the tag. */
const TIME = String.raw`^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:[:.](\d{3}))?\S*\s[^{"]*?`;
const STEP = new RegExp(TIME + String.raw`\[DuoWorkflowNodeExecutor\]\[(\d{1,15})\]\s*\[requestID:\s*([^\]\s]+)\s*\]\s*Action received`);
const PROMPT = new RegExp(TIME + String.raw`\[RunController\]\s*Executing workflow:\s*\{\s*"type"\s*:\s*"SEND_PROMPT"`);
const MODEL = new RegExp(TIME + String.raw`\[ModelResolverService\]\s*Using user-selected model:\s*"([\w.:@/-]{1,80})"`);

/** What the meter shows. */
export interface Meter {
    /** The session's number, as the Duo CLI shows it; null before its first step. */
    session: number | null;
    steps: number;
    roundTrips: number;
    /** The model's GitLab identifier as last logged, such as claude_opus_5; null when none was. */
    model: string | null;
    /** ≈ credits: each step at its model's rate per call; null when a model is not in the table. */
    credits: number | null;
    /** Its first prompt or step in this run, in ms since the epoch; null when the log's time does not read. */
    started: number | null;
}

/** A log's local time "2026-11-04T10:00:05" and its milliseconds as ms since the epoch, in the CLI machine's zone. */
export type LogClock = (local: string, ms: number) => number | null;

/** This machine's zone. */
export const LOCAL_CLOCK: LogClock = (local, ms) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/.exec(local);
    const at = m == null ? NaN : new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], ms).getTime();
    return Number.isNaN(at) ? null : at;
};

/** UTC: for tests. */
export const UTC_CLOCK: LogClock = (local, ms) => {
    const at = Date.parse(local + 'Z');
    return Number.isNaN(at) ? null : at + ms;
};

/** One run of the CLI that is still writing its log: the log's name, when it was last written, and its meter. */
export interface Run {
    log: string;
    modified: number;
    meter: Meter | null;
}

/** One session's steps, by the model in use at each ("" before any), its round trips, and when it started. */
interface Tally {
    started: number | null;
    steps: number;
    roundTrips: number;
    byModel: Map<string, number>;
}

/** Reads a log a line at a time. */
export class LiveSession {

    private readonly seen = new Set<string>();
    private readonly sessions = new Map<number, Tally>();
    /** The session of the latest step. */
    private last: number | null = null;
    /** Prompts sent since the latest step: the next step's session's; and when the first of them was. */
    private waiting = 0;
    private waitingSince: number | null = null;
    /** The model as last logged, and the first one, for the steps before it. */
    private model: string | null = null;
    private firstModel: string | null = null;

    /** @param clock the zone the log's times are in: the one of the machine the CLI runs on */
    constructor(private readonly clock: LogClock = LOCAL_CLOCK) {
    }

    /** Takes in the log's next line. */
    line(text: string): void {
        const head = text.length > HEAD ? text.slice(0, HEAD) : text;
        let m: RegExpExecArray | null;
        if (head.includes('Action received') && (m = STEP.exec(head)) != null) {
            if (!this.seen.has(m[3] + '/' + m[4])) {
                this.seen.add(m[3] + '/' + m[4]);
                this.step(Number(m[3]), this.time(m));
            }
        } else if (head.includes('SEND_PROMPT') && (m = PROMPT.exec(head)) != null) {
            if (this.waiting++ === 0) {
                this.waitingSince = this.time(m);
            }
        } else if (head.includes('user-selected model') && (m = MODEL.exec(head)) != null) {
            this.model = m[3];
            this.firstModel ??= this.model;
        }
    }

    private time(m: RegExpExecArray): number | null {
        return this.clock(m[1], m[2] != null ? Number(m[2]) : 0);
    }

    private step(session: number, at: number | null): void {
        const t = this.sessions.get(session) ?? { started: null, steps: 0, roundTrips: 0, byModel: new Map<string, number>() };
        t.started ??= this.waiting > 0 && this.waitingSince != null ? this.waitingSince : at;
        t.steps++;
        t.roundTrips += this.waiting;
        const model = this.model ?? '';
        t.byModel.set(model, (t.byModel.get(model) ?? 0) + 1);
        this.sessions.set(session, t);
        this.waiting = 0;
        this.last = session;
    }

    /** The session of the latest step, with the prompts sent since; null while the log has neither. */
    meter(): Meter | null {
        if (this.last == null && this.waiting === 0) {
            return null;
        }
        const t = this.last == null ? undefined : this.sessions.get(this.last);
        let credits: number | null = 0;
        for (const [model, count] of t?.byModel ?? []) {
            const rate = perCall(model === '' ? this.firstModel : model);
            if (rate == null) {
                credits = null;
                break;
            }
            credits += rate * count;
        }
        if ((t?.steps ?? 0) === 0 && perCall(this.model) == null) {
            credits = null;
        }
        return {
            session: this.last, steps: t?.steps ?? 0, roundTrips: (t?.roundTrips ?? 0) + this.waiting, model: this.model,
            credits, started: this.last == null ? this.waitingSince : t?.started ?? null,
        };
    }
}

/**
 * The folder part of the name of the log the CLI writes when run in a folder: its last three names joined with "-",
 * as the CLI makes it.
 */
export function folderKey(folderPath: string | null | undefined): string {
    const names = (folderPath ?? '').split(/[/\\]/).filter(n => n !== '');
    return names.slice(-3).join('-').replace(/[<>:"/\\|?*]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
}

/**
 * The run to show for a workspace, of those with something counted: the CLI's in one of its folders, else the one
 * written to last; null for none. A run that has counted nothing yet, such as `duo` started again or another `duo`
 * command, does not hide a session still going.
 * @param runs most recently written first
 */
export function pickRun(runs: readonly Run[], folderPaths: readonly string[]): Run | null {
    const keys = new Set(folderPaths.map(p => folderKey(p).toLowerCase()).filter(k => k !== ''));
    const counted = runs.filter(r => r.meter != null);
    return counted.find(r => keys.has(logFolder(r.log).toLowerCase())) ?? counted[0] ?? null;
}

/** "42 steps". */
export function stepsText(steps: number): string {
    return steps + (steps === 1 ? ' step' : ' steps');
}

/** "5 round trips". */
export function roundTripsText(trips: number): string {
    return trips + (trips === 1 ? ' round trip' : ' round trips');
}

/** "Opus 5 · 42 steps · 5 round trips". */
export function liveWhat(m: Meter): string {
    return (m.model != null ? modelName(m.model) + ' · ' : '') + stepsText(m.steps) + ' · ' + roundTripsText(m.roundTrips);
}

/** "≈ 38.2 credits", or why there is no estimate. */
export function liveCredits(m: Meter): string {
    if (m.credits != null) {
        return '≈ ' + number(m.credits) + ' credits';
    }
    return m.model == null ? 'No ≈ credits: the log names no model' : 'No ≈ credits: no rate known for ' + m.model;
}

/** How the estimate is made, and what it is not. */
export function liveBasis(m: Meter): string {
    const rate = perCall(m.model);
    return "An estimate, updated as the session runs: GitLab bills each model call at its model's rate"
        + (rate != null && m.model != null ? ' (' + modelName(m.model) + ': ' + rate + ' credits)' : '')
        + ", and a step is a model call the Duo CLI logged. GitLab's own credits come per day and feature, hours later."
        + ' Tokens are not shown: GitLab bills per call, not per token.';
}

/** How far the session's round trips are from the recommended most: amber from 80 % of it, red at it. */
export type LiveLevel = 'normal' | 'near' | 'over';

/** @param limit the recommended round trips per session; 0 for no colour */
export function liveLevel(m: Meter, limit: number): LiveLevel {
    if (limit <= 0) {
        return 'normal';
    }
    return m.roundTrips >= limit ? 'over' : m.roundTrips >= limit * 0.8 ? 'near' : 'normal';
}

/** How long the session has run, in ms: to now while its log is being written, else to the log's last write. */
export function runTime(run: Run, now: number): number | null {
    const started = run.meter?.started ?? null;
    if (started == null) {
        return null;
    }
    const end = now - run.modified <= QUIET_MS ? now : run.modified;
    return Math.max(end - started, 0);
}

/** "45s", "12m 4s", "1h 2m". */
export function durationText(ms: number): string {
    const s = Math.floor(ms / 1000);
    return s < 60 ? s + 's' : s < 3600 ? Math.floor(s / 60) + 'm ' + s % 60 + 's'
        : Math.floor(s / 3600) + 'h ' + Math.floor(s % 3600 / 60) + 'm';
}

/** The status bar's "12m 4s · 5 round trips · 42 steps · ≈ $38.2": a credit costs $1 at GitLab's list price. */
export function liveBarText(run: Run, now: number): string {
    const m = run.meter!;
    const ran = runTime(run, now);
    return (ran != null ? durationText(ran) + ' · ' : '') + roundTripsText(m.roundTrips) + ' · ' + stepsText(m.steps)
        + (m.credits != null && m.steps > 0 ? ' · ≈ $' + number(m.credits) : '');
}

/** "10:00" on this machine's clock. */
const LOCAL_TIME = (ms: number) => time(new Date(ms));

/** "Started 10:00"; null when its start is not known. */
export function startedAt(m: Meter, clock: (ms: number) => string = LOCAL_TIME): string | null {
    return m.started != null ? 'Started ' + clock(m.started) : null;
}

/** "Started 10:00, running 12m 4s", or "…, 12m 4s, quiet since 10:12"; null when its start is not known. */
export function liveStarted(run: Run, now: number, clock: (ms: number) => string = LOCAL_TIME): string | null {
    const ran = runTime(run, now);
    if (ran == null || run.meter == null) {
        return null;
    }
    return startedAt(run.meter, clock) + (now - run.modified <= QUIET_MS ? ', running ' + durationText(ran)
        : ', ' + durationText(ran) + ', quiet since ' + clock(run.modified));
}

/** "5 of 20 recommended round trips"; null when there is no limit. */
export function limitText(m: Meter, limit: number): string | null {
    return limit <= 0 ? null : m.roundTrips + ' of ' + limit + ' recommended round trips';
}

/** The status bar tooltip on the session: a title and its lines. */
export function liveTooltip(run: Run, now: number, limit: number,
                            clock: (ms: number) => string = LOCAL_TIME): { title: string; lines: string[] } {
    const m = run.meter!;
    const lines = [liveWhat(m), liveCredits(m) + (m.credits != null ? ', an estimate: steps × the model\'s rate per call' : '')];
    const started = liveStarted(run, now, clock);
    if (started != null) {
        lines.push(started);
    }
    const limited = limitText(m, limit);
    if (limited != null) {
        lines.push(limited);
    }
    return { title: 'Duo CLI, now — ' + (m.session != null ? 'session ' + m.session : 'a new session'), lines };
}

/** The Sessions view's card on the session now. */
export interface LiveCard {
    session: number | null;
    what: string;
    credits: string;
    /** Whether there is an estimate, shown large; else the line says why not. */
    priced: boolean;
    /** "Started 10:00 · 17 of 20 recommended round trips", in the level's colour; null for neither. */
    when: string | null;
    level: LiveLevel;
    basis: string;
}

/** @param limit the recommended round trips per session; 0 for none */
export function liveCard(m: Meter | null, limit: number, clock: (ms: number) => string = LOCAL_TIME): LiveCard | null {
    if (m == null) {
        return null;
    }
    const when = [startedAt(m, clock), limitText(m, limit)].filter((x): x is string => x != null);
    return {
        session: m.session, what: liveWhat(m), credits: liveCredits(m), priced: m.credits != null,
        when: when.length > 0 ? when.join(' · ') : null, level: liveLevel(m, limit), basis: liveBasis(m),
    };
}
