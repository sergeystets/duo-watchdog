import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Alert, alertFor, DEFAULT_PERCENTS, parsePercents, percentsOrDefault } from '../core/alert';
import { report, TODAY } from './fixtures';

const DEFAULT = parsePercents(DEFAULT_PERCENTS);

function alert(used: number, limit: number, forecast: boolean): Alert | null {
    return alertFor(report(used, false), limit, DEFAULT, forecast, TODAY);
}

test('quiet while under every threshold and on pace', () => {
    // 42 by the 18th projects to 72: under the limit.
    assert.equal(alert(42, 100, true), null);
});

test('the forecast warns before any threshold', () => {
    // 72 by the 18th projects to 124.
    const a = alert(72, 100, true)!;
    assert.equal(a.kind, 'forecast');
    assert.equal(a.key, 'forecast|2026-10-01|100');
    assert.ok(a.message.includes('At this pace: 124 by Oct 31, the limit is reached around Oct 25.'), a.message);

    assert.equal(alert(72, 100, false), null, 'the forecast notice can be turned off');
});

test('the highest crossed threshold wins', () => {
    const at80 = alert(80, 100, true)!;
    assert.equal(at80.kind, 'threshold');
    assert.equal(at80.key, 'threshold|2026-10-01|100|80');
    assert.equal(at80.title, 'Duo credits: 80% of your limit');
    assert.equal(at80.message, 'Used 80 of 100. At this pace: 137.8 by Oct 31, the limit is reached around Oct 23.');

    assert.equal(alert(96, 100, true)!.key, 'threshold|2026-10-01|100|95');
});

test('reaching the limit and being blocked are their own notices', () => {
    const reached = alert(100, 100, true)!;
    assert.equal(reached.kind, 'limit');
    assert.equal(reached.key, 'limit|2026-10-01|100');

    const blocked = alertFor(report(100, true), 100, DEFAULT, true, TODAY)!;
    assert.equal(blocked.kind, 'blocked');
    assert.equal(blocked.key, 'blocked|2026-10-01');
});

test('another limit is another key', () => {
    assert.notEqual(alert(80, 100, true)!.key, alert(240, 300, true)!.key);
});

test('parses thresholds', () => {
    assert.deepEqual(parsePercents('95, 50 80%, 80'), [50, 80, 95]);
    assert.deepEqual(parsePercents('  '), []);
    assert.throws(() => parsePercents('80, lots'), /Not a whole percent: lots/);
    assert.throws(() => parsePercents('0'), /from 1 to 100/);
    assert.throws(() => parsePercents('120'), /from 1 to 100/);
    assert.deepEqual(percentsOrDefault('80, lots'), [80, 95], 'bad text falls back to the default');
});
