import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CreditsClient, Transport } from '../core/client';
import { History, months, total } from '../core/history';
import {
    cacheFile, cacheFolder, CachedMonth, isSettled, lastDayOf, Limiter, mergeMonths, monthFromJson, monthsOfRange,
    monthsOutcome, monthToJson, planMonths, rangeOrDefault,
} from '../core/monthly';

const day = (date: string, credits: number) => ({ date, credits });

function month(m: string, credits: number, change: Partial<History> = {}): History {
    return {
        start: m + '-01', end: lastDayOf(m), daily: credits > 0 ? [day(m + '-10', credits)] : [],
        flows: [{ id: 'chat', title: 'Duo Chat', daily: credits > 0 ? [day(m + '-10', credits)] : [] }],
        nonBillable: [], flowsError: null, ...change,
    };
}

test('a range is this month and the ones before it, 3 unless 6 or 12 was picked', () => {
    assert.deepEqual(monthsOfRange(3, '2026-11-03'), ['2026-11', '2026-10', '2026-09']);
    assert.deepEqual(monthsOfRange(6, '2026-03-31'), ['2026-03', '2026-02', '2026-01', '2025-12', '2025-11', '2025-10']);
    assert.equal(monthsOfRange(12, '2026-11-03').length, 12);
    assert.equal(rangeOrDefault(6), 6);
    assert.equal(rangeOrDefault(12), 12);
    assert.equal(rangeOrDefault(undefined), 3);
    assert.equal(rangeOrDefault(7), 3);
    assert.equal(rangeOrDefault('12'), 3);
    assert.equal(lastDayOf('2026-02'), '2026-02-28');
    assert.equal(lastDayOf('2028-02'), '2028-02-29');
});

test('a month is settled two days after its last day', () => {
    assert.equal(isSettled('2026-10', '2026-11-01'), false, 'GitLab lags');
    assert.equal(isSettled('2026-10', '2026-11-02'), true);
    assert.equal(isSettled('2026-10', '2026-12-15'), true);
    assert.equal(isSettled('2026-11', '2026-11-30'), false, 'the current month');
});

test('kept months are read from the disk; the rest is asked', () => {
    const allKept = planMonths(6, '2026-11-03', () => true);
    assert.deepEqual(allKept.fetch, ['2026-11'], 'every past month kept: only the current one is asked');
    assert.deepEqual(allKept.fromCache, ['2026-10', '2026-09', '2026-08', '2026-07', '2026-06']);

    // On Nov 1 October can still change: a reload asks for it with November, whatever the disk has.
    const early = planMonths(3, '2026-11-01', () => true);
    assert.deepEqual(early.fetch, ['2026-11', '2026-10']);
    assert.deepEqual(early.fromCache, ['2026-09']);

    const nothingKept = planMonths(3, '2026-11-03', () => false);
    assert.deepEqual(nothingKept.fetch, ['2026-11', '2026-10', '2026-09']);
    assert.deepEqual(planMonths(3, '2026-11-03', m => m === '2026-09').fetch, ['2026-11', '2026-10']);
});

test('a kept month has its file under its account, by group and month', () => {
    assert.deepEqual(cacheFile('gitlab.com/dana.k', 'acme', '2026-09'), ['history', 'gitlab.com_dana.k', 'acme-2026-09.json']);
    assert.deepEqual(cacheFolder('gitlab.acme.dev/dana.k'), ['history', 'gitlab.acme.dev_dana.k']);
    assert.notDeepEqual(cacheFile('gitlab.com/dana.k', 'other', '2026-09'), cacheFile('gitlab.com/dana.k', 'acme', '2026-09'),
        'another group starts afresh');
    for (const odd of ['..', '../../etc', 'a/b\\c', '']) {
        const [, folder, file] = cacheFile(odd, odd, '2026-09');
        assert.ok(!folder.startsWith('.') && !folder.includes('/') && !folder.includes('\\') && folder !== '', folder);
        assert.ok(!file.startsWith('.') && !file.includes('/') && !file.includes('\\'), file);
    }
});

test('a month goes to the disk and back as it was', () => {
    const september = month('2026-09', 7, {
        start: '2026-09-15', nonBillable: [day('2026-09-20', 2.5)],
        flows: [{ id: 'chat', title: 'Duo Chat', daily: [day('2026-09-10', 4)] }, { id: 'review', title: 'Code Review', daily: [day('2026-09-11', 3)] }],
    });
    const json = monthToJson('2026-09', september);
    assert.ok(json.includes('"start":"2026-09-15"'), 'dates as yyyy-MM-dd');
    assert.deepEqual(monthFromJson(json), { month: '2026-09', history: september });
    assert.deepEqual(monthFromJson(monthToJson('2025-12', null)), { month: '2025-12', history: null }, 'GitLab had nothing');

    for (const bad of ['', 'not json', '{}', '{"version":2,"month":"2026-09","history":null}',
        '{"version":1,"month":"Sept","history":null}',
        '{"version":1,"month":"2026-09","history":{"start":"2026-09-31","end":"2026-09-30","daily":[],"flows":[],"nonBillable":[],"flowsError":null}}',
        '{"version":1,"month":"2026-09","history":{"start":"2026-09-01","end":"2026-09-30","daily":[{"date":"2026-09-01","credits":"7"}],"flows":[],"nonBillable":[],"flowsError":null}}']) {
        assert.equal(monthFromJson(bad), undefined, bad);
    }
});

test('the months that answered make one history', () => {
    const merged = mergeMonths([month('2026-11', 5), null, month('2026-09', 7), month('2026-08', 0)])!;
    assert.equal(merged.start, '2026-08-01', 'from the oldest month that answered');
    assert.equal(merged.end, '2026-11-30');
    assert.equal(total(merged), 12);
    assert.deepEqual(months(merged).map(m => [m.month, m.credits]), [['2026-08', 0], ['2026-09', 7], ['2026-10', 0], ['2026-11', 5]],
        'months without data inside stay as zeros');
    assert.equal(merged.flows.length, 1, 'one feature, its days from every month');
    assert.equal(mergeMonths([null, null]), null);
});

test('what is kept, merged and said once the months are back', () => {
    const today = '2026-11-03';
    const kept: CachedMonth[] = [{ month: '2026-08', history: month('2026-08', 3) }, { month: '2026-07', history: null }];
    const outcome = monthsOutcome(today, kept, [
        { month: '2026-11', history: month('2026-11', 5) },
        { month: '2026-10', history: month('2026-10', 6) },
        { month: '2026-09', history: month('2026-09', 7, { flowsError: 'GitLab returned HTTP 502 from gitlab.com.' }) },
    ]);
    assert.deepEqual(outcome.keep.map(k => k.month), ['2026-10'],
        'the current month is never kept, nor one whose features failed');
    assert.equal(total(outcome.history!), 21);
    assert.equal(outcome.refused, null);
    assert.deepEqual(outcome.failed, []);

    const before = monthsOutcome(today, [], [
        { month: '2026-11', history: month('2026-11', 5) },
        { month: '2026-10', error: 'GitLab returned HTTP 500 from gitlab.com.', status: 500 },
    ]);
    assert.deepEqual(before.keep, [{ month: '2026-10', history: null }], 'a settled month GitLab fails on is kept as nothing');
    assert.equal(total(before.history!), 5);

    const lagging = monthsOutcome('2026-11-01', [], [
        { month: '2026-11', history: month('2026-11', 1) },
        { month: '2026-10', error: 'GitLab returned HTTP 500 from gitlab.com.', status: 500 },
        { month: '2026-09', error: 'Cannot reach gitlab.com: timed out', status: 0 },
    ]);
    assert.deepEqual(lagging.keep, [], 'October can still change; September did not answer');
    assert.deepEqual(lagging.failed, ['2026-10: GitLab returned HTTP 500 from gitlab.com.', '2026-09: Cannot reach gitlab.com: timed out']);

    const refused = monthsOutcome(today, [], [{ month: '2026-11', error: 'GitLab rejected the token (HTTP 401)', status: 401 }]);
    assert.equal(refused.refused, 'GitLab rejected the token (HTTP 401)');
    assert.equal(refused.history, null);
});

test('at most three months are asked at once', async () => {
    const limiter = new Limiter(3);
    let running = 0;
    let most = 0;
    const order: number[] = [];
    await Promise.all(Array.from({ length: 10 }, (_, i) => limiter.run(async () => {
        running++;
        most = Math.max(most, running);
        await new Promise(resolve => setTimeout(resolve, 5 + (i % 3) * 3));
        order.push(i);
        running--;
        return i;
    })));
    assert.equal(most, 3);
    assert.equal(order.length, 10);
    // A new task while all slots are taken still waits its turn.
    assert.equal(await limiter.run(async () => 'done'), 'done');
});

test('a month is asked as the Usage view asks the current one, and with its dates otherwise', async () => {
    const asked: Record<string, unknown>[] = [];
    const http: Transport = {
        post: async (_endpoint, _token, body) => {
            asked.push(JSON.parse(body).variables);
            return { status: 200, body: '{"data":{"currentUser":{"creditsUsage":{"dailyUsage":[],"usedFlowTypes":[]}}}}' };
        },
    };
    const client = new CreditsClient('https://gitlab.com', 't', http);
    const current = await client.historyOfMonth('acme', '2030-05', '2030-05-03');
    assert.deepEqual(asked[0], { namespacePath: 'acme' }, 'no dates');
    assert.deepEqual([current.start, current.end], ['2030-05-01', '2030-05-03'], 'GitLab gave no dates: this month to today');
    await client.historyOfMonth('acme', '2026-02', '2026-11-03');
    assert.deepEqual(asked[2], { namespacePath: 'acme', startDate: '2026-02-01', endDate: '2026-02-28' });
});
