import assert from 'node:assert/strict';
import { test } from 'node:test';
import { diagnosticsReport } from '../core/diagnostics';
import { History } from '../core/history';
import { monthsBetween } from '../core/monthly';
import { estimate, Session, summarize } from '../core/sessions';
import {
    leftOutText, rangeLabel, rangeOf, SESSIONS_FOOTER, sessionsModel, sessionsView, shareLine,
} from '../core/sessionsView';
import { account, AT } from './fixtures';

const TODAY = '2026-11-03';

function session(id: number, title: string, flow: string, project: string | null, created: string, updated: string): Session {
    return {
        id, title, flow, project, group: null, model: id === 1 ? 'Claude Sonnet' : null, status: 'Finished',
        created: Date.parse(created), updated: Date.parse(updated), webUrl: 'https://gitlab.com/acme/a/-/automate/agent-sessions/' + id,
    };
}

function history(): History {
    return {
        start: '2026-10-01', end: '2026-10-31', daily: [{ date: '2026-10-28', credits: 100 }],
        flows: [{ id: 'agentic_chat', title: 'Agentic Chat', daily: [{ date: '2026-10-28', credits: 60 }] }],
        nonBillable: [], flowsError: null,
    };
}

function summary() {
    const sessions = [
        session(1, 'Review the notes app', 'chat', 'acme/apps/notes', '2026-10-28T09:00:00Z', '2026-10-28T10:00:00Z'),
        session(2, 'Fix the build', 'chat', 'acme/web', '2026-10-28T11:00:00Z', '2026-10-28T12:00:00Z')];
    const steps = new Map([[1, { id: 1, byDay: { '2026-10-28': 300 }, folder: '' }], [2, { id: 2, byDay: { '2026-10-28': 100 }, folder: '' }]]);
    return summarize(estimate(sessions, steps, history()), history(), { from: '2026-10-28', to: '2026-10-28' }, 'acme');
}

test('the range choices are UTC days to today, a custom one in order', () => {
    assert.deepEqual(rangeOf('today', TODAY, null), { from: TODAY, to: TODAY });
    assert.deepEqual(rangeOf('yesterday', TODAY, null), { from: '2026-11-02', to: '2026-11-02' });
    assert.deepEqual(rangeOf('7', TODAY, null), { from: '2026-10-28', to: TODAY });
    assert.deepEqual(rangeOf('30', TODAY, null), { from: '2026-10-05', to: TODAY });
    assert.deepEqual(rangeOf('custom', TODAY, { from: '2026-10-30', to: '2026-10-20' }), { from: '2026-10-20', to: '2026-10-30' });
    assert.deepEqual(rangeOf('custom', TODAY, { from: 'soon', to: TODAY }), { from: '2026-10-28', to: TODAY }, 'not dates: the last 7 days');
    assert.equal(rangeLabel({ from: '2026-10-28', to: TODAY }), 'Oct 28 – Nov 3');
    assert.equal(rangeLabel({ from: TODAY, to: TODAY }), 'Nov 3');
    assert.deepEqual(monthsBetween('2026-10-05', TODAY), ['2026-11', '2026-10']);
    assert.deepEqual(monthsBetween(TODAY, TODAY), ['2026-11']);
});

test("a share says how a feature's credits were split", () => {
    assert.equal(shareLine({ day: '2026-10-28', feature: 'Agentic Chat', dayCredits: 60, credits: 45, sessions: 2, bySteps: true }),
        'Oct 28 · Agentic Chat 60.0 credits, split by agent steps among 2 sessions → 45.0');
    assert.equal(shareLine({ day: '2026-10-28', feature: 'Developer Flow', dayCredits: 30, credits: 10, sessions: 3, bySteps: false }),
        'Oct 28 · Developer Flow 30.0 credits, split equally among 3 sessions → 10.0');
    assert.equal(shareLine({ day: '2026-10-28', feature: 'Agentic Chat', dayCredits: 60, credits: 60, sessions: 1, bySteps: false }),
        'Oct 28 · Agentic Chat 60.0 credits, all to this session → 60.0');
});

test('the Sessions view reads as asked: tiles, projects, rows and the focused one in full', () => {
    const m = sessionsModel(summary(), 'acme', 1, 2, ms => new Date(ms).toISOString().slice(0, 16));

    assert.deepEqual(m.tiles.map(t => [t.caption, t.value]), [
        ['Sessions', '2'], ['Credits', '100'], ['≈ In sessions', '60'], ['Other use', '40']]);
    assert.deepEqual(m.byProject.map(p => [p.title, p.value]), [['apps/notes', '45.0'], ['web', '15.0']]);
    assert.deepEqual(m.rows.map(r => [r.id, r.title, r.meta, r.credits, r.steps]), [
        [2, 'Fix the build', 'web · chat · Finished', '≈ 15.0', '100 steps'],
        [1, 'Review the notes app', 'apps/notes · chat · Finished', '≈ 45.0', '300 steps']]);
    assert.ok(m.rows[1].search.includes('review the notes app') && m.rows[1].search.includes('acme/apps/notes'));
    assert.deepEqual(m.details!.lines, [
        { label: 'Project', value: 'apps/notes' }, { label: 'Flow', value: 'chat' }, { label: 'Model', value: 'Claude Sonnet' },
        { label: 'Status', value: 'Finished' }, { label: 'Started', value: '2026-10-28T09:00' }, { label: 'Updated', value: '2026-10-28T10:00' },
        { label: 'Steps on this machine', value: '300' }, { label: '≈ Credits', value: '≈ 45.0' }]);
    assert.deepEqual(m.details!.shares, ['Oct 28 · Agentic Chat 60.0 credits, split by agent steps among 2 sessions → 45.0']);
    assert.equal(m.details!.url, 'https://gitlab.com/acme/a/-/automate/agent-sessions/1');
    assert.equal(m.leftOut, '2 sessions outside acme are left out: GitLab bills their credits to another group.');
    assert.equal(m.footer, SESSIONS_FOOTER);
    assert.ok(SESSIONS_FOOTER.startsWith('≈ estimates. GitLab tells a group member credits per day and feature, not per session'));
    assert.equal(sessionsModel(summary(), 'acme', 99, 0).details, null, 'a session not in the range is not shown in full');
    assert.equal(leftOutText(0, 'acme'), null);
    assert.equal(leftOutText(1, 'acme'), '1 session outside acme is left out: GitLab bills their credits to another group.');
});

test('the Sessions view while loading, after a failure, and for an account with a problem', () => {
    const range = { from: '2026-10-28', to: TODAY };
    const loading = sessionsView('7', range, false, undefined, 'acme', null, null);
    assert.equal(loading.loading, true);
    assert.equal(loading.label, 'Oct 28 – Nov 3');
    const failed = sessionsView('7', range, false, { summary: null, leftOut: 0, error: 'HTTP 502', warning: null, loading: false },
        'acme', null, 'No session #43 for @dana.k.');
    assert.deepEqual(failed.errors, ['Sessions did not load: HTTP 502']);
    assert.equal(failed.message, 'No session #43 for @dana.k.');
    assert.equal(failed.loading, false);
    assert.equal(sessionsView('7', range, true, undefined, 'acme', null, null).useNotice, true);
    const shown = sessionsView('custom', range, false, { summary: summary(), leftOut: 0, error: null, warning: 'Some months did not load: x', loading: true },
        'acme', null, null);
    assert.equal(shown.loading, false, 'the last figures stay while new ones load');
    assert.deepEqual(shown.errors, ['Some months did not load: x']);
    assert.equal(shown.model!.rows.length, 2);
});

test("the diagnostics say where the Duo CLI's logs were looked for", () => {
    const base = {
        now: AT, extensionVersion: '0.4.0', vscodeVersion: '1.105.1', platform: 'win32', arch: 'x64', remoteName: undefined,
        remoteHost: false, glab: { path: null, looked: [] }, selectedId: null, accounts: [], log: [],
    };
    const report = diagnosticsReport({
        ...base, duoCliLogs: {
            folders: [{ path: 'C:\\Temp\\gitlab-duo-cli', found: true, logs: 3 }, { path: '\\\\wsl.localhost\\Ubuntu\\tmp\\gitlab-duo-cli', found: false, logs: 0 }],
            sessions: 7,
        },
    });
    assert.ok(report.includes('Duo CLI logs: C:\\Temp\\gitlab-duo-cli (3 logs), \\\\wsl.localhost\\Ubuntu\\tmp\\gitlab-duo-cli (not there)'), report);
    assert.ok(report.includes('Sessions counted in the Duo CLI logs: 7'));
    assert.ok(diagnosticsReport(base).includes('Duo CLI logs: not looked for'));
    assert.equal(account().username, 'dana.k');
});
