import assert from 'node:assert/strict';
import { test } from 'node:test';
import { dashboardModel, level, step } from '../core/dashboard';
import { Snapshots } from '../core/report';
import { AT, report, TODAY } from './fixtures';

const PERCENTS = [80, 95];

test('the level turns amber at the lowest percent and red when blocked', () => {
    assert.equal(level(null, 100, PERCENTS), 'normal');
    assert.equal(level(report(79, false), 100, PERCENTS), 'normal');
    assert.equal(level(report(80, false), 100, PERCENTS), 'warning');
    assert.equal(level(report(99, false), 100, []), 'normal', 'no percents: amber only at the limit');
    assert.equal(level(report(100, true), 100, PERCENTS), 'blocked');
});

test('gridlines step by 1, 2 or 5 times a power of ten', () => {
    assert.equal(step(0), 0.5);
    assert.equal(step(20.5), 10);
    assert.equal(step(3.5), 2);
    assert.equal(step(120), 50);
});

test('the dashboard reads as in the mockup', () => {
    const m = dashboardModel(Snapshots.ok(report(21.5, false), AT), 300, PERCENTS, TODAY, ' acme ');

    assert.equal(m.byline, '@alice · acme · Updated 14:05');
    assert.equal(m.error, null);
    assert.equal(m.notConfigured, null);
    const r = m.report!;
    assert.equal(r.used, '21.5');
    assert.equal(r.caption, 'of 300 credits used');
    assert.equal(r.period, 'October 1 – 31 · day 18 of 31');
    assert.deepEqual(r.tiles.map(t => [t.caption, t.value, t.unit]), [
        ['Left', '278.5', null], ['Forecast · Oct 31', '37', null], ['Pace', '1.2', 'a day']]);
    assert.equal(r.meter!.limitText, 'limit 300');
    assert.equal(r.meter!.forecastText, 'forecast 37');
    assert.equal(r.meter!.tone, 'accent');
    assert.equal(r.daily.caption, 'credits per day, October');
    assert.equal(r.daily.days, 31);
    assert.equal(r.daily.todayIndex, 17);
    assert.equal(r.daily.values[16], 3.5);
    assert.equal(r.daily.amounts[17], '1.3 credits');
    assert.deepEqual(r.daily.xLabels.map(l => l.label), ['1', '8', '15', '22', '29']);
    assert.deepEqual(r.features.map(f => [f.title, f.value, f.fraction]), [
        ['Code <Review>', '30.0', 1], ['Duo Chat', '12.0', 0.4]]);
});

test('blocked, unpriced, stale and unconfigured states', () => {
    const blocked = dashboardModel(Snapshots.ok(report(300, true), AT), 300, PERCENTS, TODAY, 'acme').report!;
    assert.equal(blocked.blocked, true);
    assert.equal(blocked.blockedBy, "Blocked by GitLab's default per-user cap");
    assert.equal(blocked.meter!.tone, 'error');

    const unpriced = report(21.5, false);
    unpriced.flows = [{ id: 'a', title: 'A', credits: null }, { id: 'b', title: 'B', credits: 2 }];
    unpriced.flowsError = 'request timed out';
    const features = dashboardModel(Snapshots.ok(unpriced, AT), 300, PERCENTS, TODAY, 'acme').report!;
    assert.deepEqual(features.features.map(f => [f.title, f.value, f.fraction]), [['B', '2.0', 1], ['A', '—', null]]);
    assert.equal(features.featuresError, 'Credits per feature did not load: request timed out');

    const stale = dashboardModel(Snapshots.failed(Snapshots.ok(report(21.5, false), AT), 'Cannot reach gitlab.com'),
        300, PERCENTS, TODAY, 'acme');
    assert.equal(stale.error, 'Last refresh failed: Cannot reach gitlab.com');
    assert.ok(stale.report != null, 'the last numbers stay');

    const unset = dashboardModel(Snapshots.notConfigured(), 100, PERCENTS, TODAY, '');
    assert.ok(unset.notConfigured!.includes('read_api and ai_features'));
    assert.equal(unset.loading, false);
    assert.equal(unset.byline, '');
    assert.equal(dashboardModel(Snapshots.loading(), 100, PERCENTS, TODAY, '').loading, true);
});
