import assert from 'node:assert/strict';
import { test } from 'node:test';
import { historyFrom, History, mergeHistories, months, total } from '../core/history';
import { HistoryEntry, historyKey, statisticsModel, statisticsView } from '../core/statistics';
import { grouped } from '../core/view';
import { account, state, TODAY } from './fixtures';

const day = (date: string, credits: number) => ({ date, credits });

/** A year to Oct 18, 2026: September and October spent across six features, January without a breakdown. */
function year(change: Partial<History> = {}): History {
    const flow = (id: string, title: string, oct: number, sep: number) =>
        ({ id, title, daily: [day('2026-10-02', oct), day('2026-09-10', sep)] });
    return {
        start: '2025-11-01',
        end: '2026-10-18',
        daily: [day('2026-10-02', 100), day('2026-10-15', 48.6), day('2026-09-10', 201), day('2026-01-05', 10)],
        flows: [
            flow('fix', 'Fix', 2, 1),
            flow('chat', 'Duo Chat', 40, 50),
            flow('review', 'Code Review', 61.2, 100),
            flow('agent', 'Agent', 20, 30),
            flow('docs', 'Docs', 5, 5),
            flow('tests', 'Tests', 10, 10),
        ],
        nonBillable: [day('2026-10-02', 2.5), day('2026-09-10', 10)],
        flowsError: null,
        ...change,
    };
}

test('a year runs from the first of the month eleven months back', () => {
    assert.equal(historyFrom('2026-10-18'), '2025-11-01');
    assert.equal(historyFrom('2026-01-31'), '2025-02-01');
    assert.equal(historyFrom('2026-12-01'), '2026-01-01');
});

test('every month is there, zeros included, features by their credits', () => {
    const all = months(year());
    assert.equal(all.length, 12);
    assert.deepEqual(all.map(m => m.month).slice(0, 3), ['2025-11', '2025-12', '2026-01']);
    assert.equal(all[0].credits, 0);
    assert.equal(all[2].credits, 10);
    assert.ok(Math.abs(all[11].credits - 148.6) < 1e-9);
    assert.deepEqual(all[11].byFlow.map(f => f.id), ['review', 'chat', 'agent', 'tests', 'docs', 'fix']);
    assert.equal(all[11].nonBillable, 2.5);
});

test('numbers group their thousands', () => {
    assert.equal(grouped(2100), '2,100');
    assert.equal(grouped(1234.56), '1,234.6');
    assert.equal(grouped(999), '999');
    assert.equal(grouped(1_000_000), '1,000,000');
    assert.equal(grouped(-1234), '-1,234');
});

test('the statistics read as in the brief', () => {
    const s = statisticsModel(year(), TODAY);

    // The average starts at the first month with use: the two empty months before it don't count.
    assert.equal(s.summary, 'This month so far 148.6 · Last month 201 · Monthly average 23.4 · 12 months 359.6');
    assert.deepEqual(s.legend, [
        { label: 'Code Review', slot: '1' }, { label: 'Duo Chat', slot: '2' }, { label: 'Agent', slot: '3' },
        { label: 'Tests', slot: '4' }, { label: 'Docs', slot: '5' }, { label: 'Other', slot: 'other' }]);

    assert.equal(s.bars.length, 12);
    assert.deepEqual([s.bars[0].label, s.bars[0].year], ['Nov', null]);
    assert.deepEqual([s.bars[2].label, s.bars[2].year], ['Jan', '2026'], 'the year on January');
    const october = s.bars[11];
    assert.equal(october.title, 'October 2026');
    assert.equal(october.totalText, '148.6 credits');
    assert.deepEqual(october.segments.map(x => x.slot), ['1', '2', '3', '4', '5', 'other']);
    assert.equal(october.segments[0].text, '61.2 credits');
    // Other: Fix, and what GitLab gave no feature for.
    assert.ok(Math.abs(october.segments[5].value - 12.4) < 1e-9, String(october.segments[5].value));
    assert.deepEqual(s.bars[2].segments.map(x => [x.slot, x.label, x.value]), [['other', 'Other', 10]],
        'January has no breakdown');
    assert.deepEqual(s.bars[0].segments, [], 'nothing used in November');
    assert.ok(s.axisMax >= 201);

    assert.deepEqual(s.rows[0], { month: 'October 2026', credits: '148.6', topFeature: 'Code Review', notBilled: '2.5' });
    assert.deepEqual(s.rows[11], { month: 'November 2025', credits: '0', topFeature: '—', notBilled: '—' });
    assert.deepEqual(s.features.map(f => [f.title, f.value]).slice(0, 2), [['Code Review', '161.2'], ['Duo Chat', '90.0']]);
    assert.equal(s.featuresError, null);
    assert.equal(s.notBilled,
        "Not billed (promotional, beta): 12.5 credits. GitLab's own usage page counts these in its total.");
    assert.equal(s.note, "From Nov 1, 2025: GitLab keeps usage from the subscription's start and can lag a few hours. "
        + "A group member sees usage per day and per feature; per project, model or session takes the group's Owner.");
});

test('the statistics without features, without the not billed, and in a first month', () => {
    const plain = statisticsModel(year({ flows: [], flowsError: 'GitLab returned HTTP 502 from gitlab.com.', nonBillable: [] }), TODAY);
    assert.deepEqual(plain.legend, [{ label: 'All features', slot: 'other' }]);
    assert.equal(plain.featuresError, 'Credits per feature did not load: GitLab returned HTTP 502 from gitlab.com.');
    assert.equal(plain.notBilled, null);

    const first = statisticsModel(year({ start: '2026-10-05', daily: [day('2026-10-06', 3)], flows: [], nonBillable: [] }), TODAY);
    assert.equal(first.summary, 'This month so far 3 · Last month 0 · Monthly average 3 · 1 month 3',
        'in the first month, the average is that month so far');
});

test('all accounts add up, features of the same title as one', () => {
    const a = year({ start: '2026-03-15', flowsError: 'x' });
    const b: History = {
        start: '2025-11-01', end: '2026-10-17', daily: [day('2026-10-03', 20)],
        flows: [{ id: 'code_review', title: 'Code Review', daily: [day('2026-10-03', 15)] },
            { id: 'ask', title: 'Ask', daily: [day('2026-10-03', 5)] }],
        nonBillable: [], flowsError: 'x',
    };
    const merged = mergeHistories([a, b])!;
    assert.equal(merged.start, '2025-11-01');
    assert.equal(merged.end, '2026-10-18');
    assert.ok(Math.abs(total(merged) - (total(a) + 20)) < 1e-9);
    assert.equal(merged.flows.length, 7);
    const review = merged.flows.find(f => f.title === 'Code Review')!;
    assert.equal(review.daily.reduce((s, d) => s + d.credits, 0), 161.2 + 15);
    assert.equal(merged.flowsError, 'x', 'a reason once');
    assert.equal(mergeHistories([a, { ...b, flowsError: 'y' }])!.flowsError, 'x; y');
    assert.equal(mergeHistories([]), null);
});

test('a history is cached per account, group and token', () => {
    const a = account();
    assert.equal(historyKey(a), 'gitlab.com/dana.k|acme|1759318692000');
    assert.notEqual(historyKey({ ...a, groupPath: 'other' }), historyKey(a));
    assert.notEqual(historyKey({ ...a, tokenSavedAt: 1 }), historyKey(a));
    assert.equal(historyKey({ ...a, groupPath: 'https://gitlab.com/groups/acme' }), historyKey(a));
});

test('the Statistics view for one account', () => {
    const acme = state(account(), 148.6);
    const entries = new Map<string, HistoryEntry>();
    const entry = (a: typeof acme.account) => entries.get(historyKey(a));

    assert.equal(statisticsView('account', state(account(), null, 'notTracked'), [], entry, TODAY).useNotice, true,
        'not tracked, no token, turned down: the same notice as Usage');
    assert.equal(statisticsView('account', acme, [acme], entry, TODAY).loading, true);

    entries.set(historyKey(acme.account), { history: null, error: 'Cannot reach gitlab.com', warning: null, loading: false });
    const failed = statisticsView('account', acme, [acme], entry, TODAY);
    assert.deepEqual(failed.errors, ['Statistics did not load: Cannot reach gitlab.com']);
    assert.equal(failed.loading, false);

    entries.set(historyKey(acme.account), { history: year(), error: null, warning: null, loading: false });
    const shown = statisticsView('account', acme, [acme], entry, TODAY);
    assert.equal(shown.stats!.bars.length, 12);
    assert.equal(shown.stats!.accounts, null);
});

test('the Statistics view for all accounts adds up the tracked ones', () => {
    const acme = state(account(), 148.6);
    const platform = state(account({ gitlabUrl: 'https://gitlab.acme.dev', groupPath: 'platform' }), 10);
    const lab = state(account({ username: 'dana-lab' }), 20);
    const untracked = state(account({ username: 'bob', scopes: ['api'] }), null, 'notTracked');
    const entries = new Map<string, HistoryEntry>([
        [historyKey(acme.account), { history: year(), error: null, warning: null, loading: false }],
        [historyKey(platform.account), { history: null, error: 'boom', warning: null, loading: false }],
        [historyKey(lab.account), { history: null, error: null, warning: null, loading: true }],
    ]);
    const entry = (a: typeof acme.account) => entries.get(historyKey(a));

    const view = statisticsView('all', acme, [untracked, platform, lab, acme], entry, TODAY);
    assert.deepEqual(view.errors, ['Not included: @dana.k on gitlab.acme.dev · platform: boom']);
    assert.equal(view.stats!.accounts, '1 account: @dana.k on gitlab.com · acme. Loading the others…');
    assert.equal(view.loading, false);

    entries.set(historyKey(lab.account), { history: year({ daily: [day('2026-10-01', 1.4)], flows: [], nonBillable: [] }), error: null, warning: null, loading: false });
    const both = statisticsView('all', acme, [untracked, platform, lab, acme], entry, TODAY);
    assert.equal(both.stats!.accounts, '2 accounts: @dana-lab on gitlab.com · acme, @dana.k on gitlab.com · acme.',
        'most credits left first, as everywhere');
    assert.ok(both.stats!.summary.startsWith('This month so far 150 · '), both.stats!.summary);

    assert.equal(statisticsView('all', untracked, [untracked], entry, TODAY).empty,
        'No account can show its usage yet: All accounts adds up the accounts with a token that reads Duo usage.');
    assert.equal(statisticsView('all', acme, [lab], () => undefined, TODAY).loading, true);
});
