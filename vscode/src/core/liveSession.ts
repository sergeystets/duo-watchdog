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

/** The line's time, then anything but a quote or a brace up to the tag. */
const TIME = String.raw`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\S*\s[^{"]*?`;
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
}

/** One run of the CLI that is still writing its log: the log's name, when it was last written, and its meter. */
export interface Run {
    log: string;
    modified: number;
    meter: Meter | null;
}

/** One session's steps, by the model in use at each ("" before any), and its round trips. */
interface Tally {
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
    /** Prompts sent since the latest step: the next step's session's. */
    private waiting = 0;
    /** The model as last logged, and the first one, for the steps before it. */
    private model: string | null = null;
    private firstModel: string | null = null;

    /** Takes in the log's next line. */
    line(text: string): void {
        const head = text.length > HEAD ? text.slice(0, HEAD) : text;
        let m: RegExpExecArray | null;
        if (head.includes('Action received') && (m = STEP.exec(head)) != null) {
            if (!this.seen.has(m[1] + '/' + m[2])) {
                this.seen.add(m[1] + '/' + m[2]);
                this.step(Number(m[1]));
            }
        } else if (head.includes('SEND_PROMPT') && PROMPT.test(head)) {
            this.waiting++;
        } else if (head.includes('user-selected model') && (m = MODEL.exec(head)) != null) {
            this.model = m[1];
            this.firstModel ??= this.model;
        }
    }

    private step(session: number): void {
        const t = this.sessions.get(session) ?? { steps: 0, roundTrips: 0, byModel: new Map<string, number>() };
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
            credits,
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
 * The run to show for a workspace: the CLI's in one of its folders, else the one written to last; null for none.
 * @param runs most recently written first
 */
export function pickRun(runs: readonly Run[], folderPaths: readonly string[]): Run | null {
    const keys = new Set(folderPaths.map(p => folderKey(p).toLowerCase()).filter(k => k !== ''));
    return runs.find(r => keys.has(logFolder(r.log).toLowerCase())) ?? runs[0] ?? null;
}

/** "42 steps". */
export function stepsText(steps: number): string {
    return steps + (steps === 1 ? ' step' : ' steps');
}

/** "5 round trips". */
export function roundTripsText(trips: number): string {
    return trips + (trips === 1 ? ' round trip' : ' round trips');
}

/** The status bar's "CLI 42 steps ≈ $38.2": a credit costs $1 at GitLab's list price. */
export function liveStatusText(m: Meter): string {
    return 'CLI ' + stepsText(m.steps) + (m.credits != null && m.steps > 0 ? ' ≈ $' + number(m.credits) : '');
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

/** The status bar tooltip's lines on the session. */
export function liveTooltip(m: Meter): { title: string; lines: string[] } {
    return {
        title: 'Duo CLI, now — ' + (m.session != null ? 'session ' + m.session : 'a new session'),
        lines: [liveWhat(m), liveCredits(m) + (m.credits != null ? ', an estimate: steps × the model\'s rate per call' : '')],
    };
}

/** The Sessions view's card on the session now. */
export interface LiveCard {
    session: number | null;
    what: string;
    credits: string;
    /** Whether there is an estimate, shown large; else the line says why not. */
    priced: boolean;
    basis: string;
}

export function liveCard(m: Meter | null): LiveCard | null {
    return m == null ? null : {
        session: m.session, what: liveWhat(m), credits: liveCredits(m), priced: m.credits != null, basis: liveBasis(m),
    };
}
