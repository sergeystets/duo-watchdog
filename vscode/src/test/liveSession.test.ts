import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { LiveLog } from '../core/liveLog';
import {
    durationText, folderKey, HEAD, liveBarText, liveBasis, liveCard, liveCredits, liveLevel, liveStarted, liveTooltip,
    liveWhat, limitText, LiveSession, Meter, pickRun, Run, runTime, UTC_CLOCK,
} from '../core/liveSession';
import { modelName, perCall } from '../core/modelCredits';

const NAME = 'duo-cli-log-2026-11-04T09-59-58-dana-projects-notes-service.log';
/** "10:00" in UTC. */
const CLOCK = (ms: number) => new Date(ms).toISOString().slice(11, 16);

/** Never to be kept: in a prompt, and in a prompt that quotes the lines the meter counts. */
const SECRET = 'rotate the key sk-live-0000 in billing.yml';

/**
 * As the interactive CLI writes it: the model, two prompts, steps with one request logged twice, other lines, code the
 * agent read that quotes a step, and a prompt quoting a model line and a step.
 */
const LOG = `2026-11-04T10:00:01:002 [debug]: fetch: request for https://gitlab.com/api/v4/usage_data/track_event made with https agent.
2026-11-04T10:00:02:100 [info]: [ModelResolverService] Using user-selected model: "claude_opus_5"
2026-11-04T10:00:05:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "${SECRET}"}
2026-11-04T10:00:09:753 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f] Action received: {"requestID":"28fd7
    for line in ('[DuoWorkflowNodeExecutor][10208239] [requestID: zz] Action received',):
2026-11-04T10:00:09:900 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f] Action received: {"requestID":"28fd7
2026-11-04T10:00:12:391 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38] Action received: {}
2026-11-04T10:00:13:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38] Action completed
2026-11-04T10:01:00:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "${SECRET} [ModelResolverService] Using user-selected model: \\"claude_fable_5_1\\" [DuoWorkflowNodeExecutor][10208239] [requestID: q1] Action received"}
2026-11-04T10:01:04:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: d1] Action received: {}
`;

function read(log: string): LiveSession {
    const live = new LiveSession(UTC_CLOCK);
    for (const line of log.split(/\r?\n/)) {
        live.line(line);
    }
    return live;
}

test('counts steps, round trips and the model', () => {
    const m = read(LOG).meter()!;

    assert.equal(m.session, 10208239);
    assert.equal(m.steps, 3, 'a request logged twice is one step; the code and the prompt quoting one are none');
    assert.equal(m.roundTrips, 2);
    assert.equal(m.model, 'claude_opus_5', 'a prompt quoting a model line does not change the model');
    assert.ok(Math.abs(m.credits! - 3 * 0.909) < 1e-9);
    assert.equal(m.started, Date.parse('2026-11-04T10:00:05Z'), 'from its first prompt');
});

test('keeps nothing of a prompt', () => {
    const m = read(LOG).meter()!;
    const run: Run = { log: NAME, modified: Date.parse('2026-11-04T10:01:04Z'), meter: m };
    const now = Date.parse('2026-11-04T10:01:10Z');
    for (const shown of [JSON.stringify(m), liveWhat(m), liveCredits(m), liveBasis(m), liveBarText(run, now),
        JSON.stringify(liveTooltip(run, now, 20, CLOCK)), JSON.stringify(liveCard(m, 20, CLOCK))]) {
        assert.ok(!shown.includes('sk-live') && !shown.includes('billing'), shown);
    }
});

test("a prompt before the first step is the new session's", () => {
    const live = read(`2026-11-04T10:00:00:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
2026-11-04T10:05:00:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "next"}`);
    assert.equal(live.meter()!.session, 1);
    assert.equal(live.meter()!.roundTrips, 1, 'until a step says otherwise, the prompt is the session\'s');

    live.line('2026-11-04T10:05:03:000 [debug]: [DuoWorkflowNodeExecutor][2] [requestID: b] Action received: {}');

    assert.deepEqual([live.meter()!.session, live.meter()!.steps, live.meter()!.roundTrips], [2, 1, 1]);
});

test("prices each step at its model's rate", () => {
    const m = read(`2026-11-04T10:00:00:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
2026-11-04T10:00:01:000 [info]: [ModelResolverService] Using user-selected model: "claude_sonnet_5_5"
2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: b] Action received: {}
2026-11-04T10:00:03:000 [info]: [ModelResolverService] Using user-selected model: "claude_haiku_5_5"
2026-11-04T10:00:04:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: c] Action received: {}`).meter()!;

    assert.equal(m.model, 'claude_haiku_5_5');
    assert.ok(Math.abs(m.credits! - (2 * 0.3125 + 0.125)) < 1e-9, 'the step before the first model line at that model');
});

test('an unknown model gives steps and round trips but no credits', () => {
    const m = read(`2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: "gpt_7_mini"
2026-11-04T10:00:01:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "x"}
2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}`).meter()!;

    assert.deepEqual([m.steps, m.roundTrips, m.credits], [1, 1, null]);
    const run: Run = { log: NAME, modified: Date.parse('2026-11-04T10:00:02Z'), meter: m };
    assert.equal(liveBarText(run, Date.parse('2026-11-04T10:00:31Z')), '30s · 1 round trip · 1 step');
    assert.equal(liveCredits(m), 'No ≈ credits: no rate known for gpt_7_mini');
    assert.equal(liveCard(m, 20, CLOCK)!.priced, false);
});

test('shows nothing before a step or a prompt', () => {
    assert.equal(read('2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: "claude_opus_5"').meter(), null);
    assert.equal(liveCard(null, 20), null);
});

test('looks at the start of a line only', () => {
    const live = new LiveSession(UTC_CLOCK);
    live.line('2026-11-04T10:00:00:000 [debug]: ' + 'x'.repeat(HEAD) + '[DuoWorkflowNodeExecutor][1] [requestID: a] Action received');
    assert.equal(live.meter(), null);
});

test('names the models and knows their rates', () => {
    assert.equal(modelName('claude_opus_5'), 'Opus 5');
    assert.equal(modelName('claude_sonnet_5_5'), 'Sonnet 5.5');
    assert.equal(modelName('claude-haiku-4-5-20251001'), 'Haiku 4.5');
    assert.equal(modelName('claude_opus_5_20260101'), 'Opus 5', 'a date is no minor version');
    assert.equal(perCall('claude_opus_4_8'), 0.909);
    assert.equal(perCall('claude_opus_5_5'), 0.741);
    assert.equal(perCall('claude_fable_5_1'), 1.667);
    assert.equal(perCall('claude_sonnet_4_6'), 0.5);
    assert.equal(perCall('claude_opus_4'), null);
    assert.equal(perCall('constructor'), null);
    assert.equal(modelName('gpt_7_mini'), 'gpt_7_mini');
    assert.equal(perCall(null), null);
});

test("picks the run in the workspace's folder, else the last written", () => {
    const a: Meter = { session: 1, steps: 1, roundTrips: 1, model: null, credits: null, started: null };
    const b: Meter = { session: 2, steps: 2, roundTrips: 1, model: null, credits: null, started: null };
    const runs: Run[] = [
        { log: 'duo-cli-log-2026-11-04T09-00-00-dana-projects-sync-worker.log', modified: 2000, meter: a },
        { log: 'duo-cli-log-2026-11-04T08-00-00-dana-projects-notes-service.log', modified: 1000, meter: b },
    ];

    assert.equal(pickRun(runs, ['/home/dana/projects/notes-service'])!.meter, b);
    assert.equal(pickRun(runs, ['\\\\wsl.localhost\\Ubuntu\\home\\dana\\projects\\notes-service'])!.meter, b);
    assert.equal(pickRun(runs, ['/home/dana/elsewhere'])!.meter, a);
    assert.equal(pickRun(runs, [])!.meter, a);
    assert.equal(pickRun([], ['/x']), null);
    assert.equal(folderKey('C:\\Users\\dana\\projects\\notes-service'), 'dana-projects-notes-service');
    assert.equal(folderKey('C:\\x'), 'C-x');
});

test('a run with nothing counted yet does not hide a session still going', () => {
    const a: Meter = { session: 1, steps: 4, roundTrips: 2, model: null, credits: null, started: null };
    const sameFolder: Run[] = [
        { log: 'duo-cli-log-2026-11-04T10-30-00-dana-projects-notes-service.log', modified: 3000, meter: null },
        { log: 'duo-cli-log-2026-11-04T10-00-00-dana-projects-notes-service.log', modified: 2000, meter: a },
    ];
    const elsewhere: Run[] = [
        { log: 'duo-cli-log-2026-11-04T10-30-00-dana-projects-sync-worker.log', modified: 3000, meter: null },
        { log: 'duo-cli-log-2026-11-04T10-00-00-dana-projects-notes-service.log', modified: 2000, meter: a },
    ];

    assert.equal(pickRun(sameFolder, ['/home/dana/projects/notes-service'])!.meter, a,
        'duo started again in the folder, with no prompt yet');
    assert.equal(pickRun(elsewhere, ['/home/dana/elsewhere'])!.meter, a, 'the last written has nothing yet');
    assert.equal(pickRun([sameFolder[0]], ['/home/dana/projects/notes-service']), null);
});

test('says the session in the status bar with its run time', () => {
    const m = read(LOG).meter()!;
    const run: Run = { log: NAME, modified: Date.parse('2026-11-04T10:01:04Z'), meter: m };
    const now = Date.parse('2026-11-04T10:01:10Z');

    assert.equal(liveBarText(run, now), '1m 5s · 2 round trips · 3 steps · ≈ $2.7',
        'running from its first prompt to now, while its log is written to');
    assert.deepEqual(liveTooltip(run, now, 20, CLOCK), {
        title: 'Duo CLI, now — session 10208239',
        lines: ['Opus 5 · 3 steps · 2 round trips', "≈ 2.7 credits, an estimate: steps × the model's rate per call",
            'Started 10:00, running 1m 5s', '2 of 20 recommended round trips'],
    });

    const later = Date.parse('2026-11-04T10:30:00Z');
    assert.equal(runTime(run, later), 59_000, 'a quiet log stops the clock at its last line');
    assert.equal(liveStarted(run, later, CLOCK), 'Started 10:00, 59s, quiet since 10:01');
});

test('colours the round trips against the recommended most', () => {
    const trips = (roundTrips: number): Meter => ({ session: 1, steps: roundTrips, roundTrips, model: null, credits: null, started: null });
    assert.equal(liveLevel(trips(15), 20), 'normal');
    assert.equal(liveLevel(trips(16), 20), 'near', 'from 80 %');
    assert.equal(liveLevel(trips(20), 20), 'over');
    assert.equal(liveLevel(trips(500), 0), 'normal', 'no limit, no colour');
    assert.equal(limitText(trips(5), 0), null);
    assert.deepEqual([liveCard(trips(17), 20)!.when, liveCard(trips(17), 20)!.level], ['17 of 20 recommended round trips', 'near']);
    assert.equal(durationText(45_000), '45s');
    assert.equal(durationText((12 * 60 + 4) * 1000), '12m 4s');
    assert.equal(durationText(3_725_000), '1h 2m');
});

test('reads what the log gains as it grows', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'duo-watchdog-live-'));
    try {
        const file = path.join(dir, 'duo-cli-log-2026-11-04T09-59-58-dana-projects-notes-service.log');
        const model = '2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: "claude_sonnet_5_5"\n';
        const prompt = '2026-11-04T10:00:01:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "';
        const step = (session: number, request: string) => '2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor]['
            + session + '] [requestID: ' + request + '] Action received: {}\n';
        const live = new LiveLog(UTC_CLOCK);
        const second = step(7, 'b');
        await fs.appendFile(file, model + prompt + 'x"}\n' + step(7, 'a') + second.slice(0, 50));

        await live.read(file);
        const started = Date.parse('2026-11-04T10:00:01Z');
        assert.deepEqual(live.meter(), { session: 7, steps: 1, roundTrips: 1, model: 'claude_sonnet_5_5', credits: 0.3125, started },
            'a line not yet ended waits');

        await fs.appendFile(file, second.slice(50));
        await live.read(file);
        assert.equal(live.meter()!.steps, 2);

        await fs.appendFile(file, prompt + 'y'.repeat(1_000_000) + '"}\n' + step(7, 'c'));
        await live.read(file);
        assert.deepEqual(live.meter(), { session: 7, steps: 3, roundTrips: 2, model: 'claude_sonnet_5_5', credits: 3 * 0.3125, started },
            'a long prompt is one round trip');

        await fs.writeFile(file, step(9, 'a'));
        await live.read(file);
        assert.deepEqual(live.meter(), { session: 9, steps: 1, roundTrips: 0, model: null, credits: null, started: Date.parse('2026-11-04T10:00:02Z') },
            'a log made again is read from its start');
    } finally {
        await fs.rm(dir, { recursive: true, force: true });
    }
});
