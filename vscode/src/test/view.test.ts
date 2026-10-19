import assert from 'node:assert/strict';
import { test } from 'node:test';
import { addDays, parseDate, plusMonths } from '../core/dates';
import { BillingPeriod } from '../core/period';
import { Snapshots } from '../core/report';
import { day, monthText, number, periodText, statusText, tooltipLines } from '../core/view';
import { AT, report, TODAY } from './fixtures';

test('the billing period projects the run rate', () => {
    const p = BillingPeriod.of('2026-10-01', '2026-10-31', TODAY);
    assert.equal(p.elapsedDays, 18);
    assert.equal(p.periodDays, 31);
    assert.equal(p.project(36), 62);

    // GitLab reports the last day it has data for as endDate; the period still runs to the month's end.
    const dataSoFar = BillingPeriod.of('2026-10-01', '2026-10-18', TODAY);
    assert.equal(dataSoFar.end, '2026-10-31');
    assert.equal(dataSoFar.periodDays, 31);
    assert.ok(Math.abs(dataSoFar.project(21.5) - 37) < 0.05);

    const unknown = BillingPeriod.of(null, null, TODAY);
    assert.equal(unknown.start, '2026-10-01');
    assert.equal(unknown.end, '2026-10-31');

    const stale = BillingPeriod.of('2026-09-01', '2026-09-30', TODAY);
    assert.equal(stale.elapsedDays, stale.periodDays, 'a period already over projects to what was used');
    assert.equal(stale.project(90), 90);
});

test('the reach date is within the period or absent', () => {
    const p = BillingPeriod.of('2026-10-01', '2026-10-31', TODAY);
    // 4 a day, 28 left: seven days from the 18th.
    assert.equal(p.reachDate(72, 100, TODAY), '2026-10-25');
    assert.equal(p.reachDate(42, 100, TODAY), null, 'the pace does not reach the limit by the 31st');
    assert.equal(p.reachDate(100, 100, TODAY), null, 'already reached');
    assert.equal(p.reachDate(0, 100, TODAY), null, 'no pace');
});

test('the status text shows dollars used over the limit', () => {
    assert.equal(statusText(Snapshots.ok(report(42.37, false), AT), 100), '$42.4 / 100');
    assert.equal(statusText(Snapshots.ok(report(100, true), AT), 100), 'Blocked · $100 / 100');
    assert.equal(statusText(Snapshots.failed(Snapshots.ok(report(42.37, false), AT), 'x'), 300), '$42.4 / 300 ⚠');
    assert.equal(statusText(Snapshots.notConfigured(), 100), 'Duo: set up');
    assert.equal(statusText(Snapshots.failed(Snapshots.loading(), 'x'), 100), 'Duo: error');
});

test('the tooltip explains the dollar and keeps the last error', () => {
    const s = Snapshots.failed(Snapshots.ok(report(42, false), AT), 'Cannot reach gitlab.com: <timeout>');

    const tip = tooltipLines(s, 100, TODAY).join('\n');

    assert.ok(tip.includes('Used 42 of 100 (42%), left 58'), tip);
    assert.ok(tip.includes('Forecast by Oct 31: 72.3'), tip);
    assert.ok(tip.includes('Updated 2026-10-18 14:05'), tip);
    assert.ok(tip.includes("$ = credits at GitLab's $1 list price"), tip);
    assert.ok(tip.includes('Last refresh failed: Cannot reach gitlab.com: <timeout>'), tip);
});

test('the period and dates read as in the mockup', () => {
    const october = BillingPeriod.of('2026-10-01', '2026-10-18', TODAY);
    assert.equal(periodText(october), 'October 1 – 31 · day 18 of 31');
    assert.equal(monthText(october), 'October');
    assert.equal(day('2026-10-16'), 'Oct 16');

    const across = BillingPeriod.of('2026-09-15', '2026-10-14', '2026-10-14');
    assert.equal(periodText(across), 'September 15 – October 14 · day 30 of 30');
    assert.equal(monthText(across), 'September – October');
});

test('numbers are whole or one decimal', () => {
    assert.equal(number(100), '100');
    assert.equal(number(42.37), '42.4');
    assert.equal(number(0.04), '0');
    assert.equal(number(1.25), '1.3');
});

test('dates step like Java LocalDate', () => {
    assert.equal(plusMonths('2026-01-31', 1), '2026-02-28');
    assert.equal(plusMonths('2028-01-31', 1), '2028-02-29');
    assert.equal(plusMonths('2026-12-15', 1), '2027-01-15');
    assert.equal(addDays('2026-10-31', 1), '2026-11-01');
    assert.equal(parseDate('2026-10-01T00:00:00Z'), '2026-10-01');
    assert.equal(parseDate('2026-02-30'), null);
    assert.equal(parseDate('soon'), null);
});
