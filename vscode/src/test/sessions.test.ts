import assert from 'node:assert/strict';
import { test } from 'node:test';
import { History } from '../core/history';
import {
    around, Estimate, estimate, estimateCredits, inGroup, lastDays, outside, place, Session, sessionId, Steps, summarize,
    words,
} from '../core/sessions';

const OCT_28 = '2026-10-28';
const OCT_29 = '2026-10-29';

function session(id: number, flow: string | null, project: string | null, created: string, updated: string): Session {
    return {
        id, title: 'Session ' + id, flow, project, group: null, model: null, status: 'Finished',
        created: Date.parse(created), updated: Date.parse(updated), webUrl: null,
    };
}

function steps(id: number, day: string, count: number): Steps {
    return { id, byDay: { [day]: count }, folder: 'home-dana-projects-notes' };
}

/** Oct 28: Agentic Chat 60 credits, Developer Flow 30, and 10 more no feature was priced for. */
function history(): History {
    return {
        start: '2026-10-01', end: '2026-10-31',
        daily: [{ date: OCT_28, credits: 100 }, { date: OCT_29, credits: 20 }],
        flows: [
            { id: 'agentic_chat', title: 'Agentic Chat', daily: [{ date: OCT_28, credits: 60 }, { date: OCT_29, credits: 20 }] },
            { id: 'developer', title: 'Developer Flow', daily: [{ date: OCT_28, credits: 30 }] },
        ],
        nonBillable: [], flowsError: null,
    };
}

function credits(estimates: Estimate[], id: number): number {
    return estimateCredits(estimates.find(e => e.session.id === id)!);
}

const close = (actual: number, expected: number, message?: string) =>
    assert.ok(Math.abs(actual - expected) < 1e-9, (message ?? '') + ' expected ' + expected + ', got ' + actual);

test("a feature's credits are split by the agent's steps when the logs count every session", () => {
    const sessions = [
        session(1, 'chat', 'acme/a', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z'),
        session(2, 'chat', 'acme/b', '2026-10-28T11:00:00Z', '2026-10-28T12:00:00Z')];
    const e = estimate(sessions, new Map([[1, steps(1, OCT_28, 300)], [2, steps(2, OCT_28, 100)]]), history());

    close(credits(e, 1), 45);
    close(credits(e, 2), 15);
    const share = e[0].shares[0];
    assert.equal(share.feature, 'Agentic Chat');
    close(share.dayCredits, 60);
    assert.equal(share.sessions, 2);
    assert.equal(share.bySteps, true);
});

test('without steps for every session they share equally', () => {
    const sessions = [
        session(1, 'chat', 'acme/a', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z'),
        session(2, 'chat', 'acme/b', '2026-10-28T11:00:00Z', '2026-10-28T12:00:00Z'),
        session(3, 'developer/v1', 'acme/a', '2026-10-28T13:00:00Z', '2026-10-28T14:00:00Z')];
    const e = estimate(sessions, new Map([[1, steps(1, OCT_28, 300)]]), history());

    close(credits(e, 1), 30);
    close(credits(e, 2), 30);
    assert.equal(e[1].shares[0].bySteps, false);
    close(credits(e, 3), 30, 'developer/v1 goes with Developer Flow');
});

test('sessions no feature matches share what the matched ones left', () => {
    const sessions = [
        session(1, 'chat', 'acme/a', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z'),
        session(2, 'fix_pipeline/v1', 'acme/b', '2026-10-28T11:00:00Z', '2026-10-28T12:00:00Z')];
    const e = estimate(sessions, new Map(), history());

    close(credits(e, 1), 60);
    close(credits(e, 2), 40, "Developer Flow's 30 and the unpriced 10");
    assert.equal(e[1].shares[0].feature, 'Developer Flow, Other features');
});

test('a session takes part on the days its log counts', () => {
    const sessions = [session(1, 'chat', 'acme/a', '2026-10-28T09:00:00Z', '2026-10-29T10:00:00Z')];
    const e = estimate(sessions, new Map([[1, { id: 1, byDay: { [OCT_29]: 5 }, folder: '' }]]), history());

    close(credits(e, 1), 20, 'only Oct 29, where the log has its steps');
    close(estimateCredits(e[0], { from: OCT_28, to: OCT_28 }), 0);
});

test("the summary lists the range's sessions newest first and what they left out", () => {
    const sessions = [
        session(1, 'chat', 'acme/a', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z'),
        session(2, 'chat', 'acme/b', '2026-10-28T11:00:00Z', '2026-10-28T12:00:00Z'),
        session(3, 'chat', 'acme/b', '2026-10-29T11:00:00Z', '2026-10-29T12:00:00Z')];
    const e = estimate(sessions, new Map(), history());

    const s = summarize(e, history(), { from: OCT_28, to: OCT_28 }, 'acme');

    assert.deepEqual(s.rows.map(r => r.session.id), [2, 1]);
    close(s.total, 100);
    close(s.inSessions, 60);
    close(outside(s), 40);
    assert.deepEqual(s.byProject.map(p => p.title), ['a', 'b']);
});

test('reads the session number in what is typed', () => {
    assert.equal(sessionId(' 10208239 '), 10208239);
    assert.equal(sessionId('#10208239'), 10208239);
    assert.equal(sessionId('gid://gitlab/Ai::DuoWorkflows::Workflow/10208239'), 10208239);
    assert.equal(sessionId('https://gitlab.com/acme/notes/-/automate/agent-sessions/10208239'), 10208239);
    assert.equal(sessionId('notes'), null);
    assert.equal(sessionId(''), null);
});

test('places a session in its project without the group', () => {
    const inProject = session(1, 'chat', 'acme/apps/notes', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z');
    const nowhere = session(2, 'chat', null, '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z');

    assert.equal(place(inProject, null, 'acme'), 'apps/notes');
    assert.equal(place(nowhere, steps(2, OCT_28, 1), 'acme'), 'home-dana-projects-notes');
    assert.equal(place(nowhere, null, 'acme'), '—');
    assert.equal(inGroup(inProject, 'ACME'), true);
    assert.equal(inGroup(nowhere, 'acme'), true);
    assert.equal(inGroup(inProject, 'acm'), false);
});

test('matches flows to features by their words', () => {
    assert.deepEqual([...words('developer/v1')], ['developer']);
    assert.deepEqual([...words('Developer Flow')], ['developer']);
    assert.deepEqual([...words('Agentic Chat')], ['agentic', 'chat']);
});

test('a range is days to today, or the days of a session', () => {
    assert.deepEqual(lastDays(7, '2026-11-03'), { from: '2026-10-28', to: '2026-11-03' });
    assert.deepEqual(lastDays(1, '2026-11-03'), { from: '2026-11-03', to: '2026-11-03' });
    assert.deepEqual(around(session(1, 'chat', null, '2026-10-28T23:00:00Z', '2026-10-30T01:00:00Z')),
        { from: '2026-10-28', to: '2026-10-30' });
});
