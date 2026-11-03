import assert from 'node:assert/strict';
import { test } from 'node:test';
import { idOf } from '../core/accounts';
import {
    accountDetail, accountLabel, accountName, accountsDashboard, accountStatusText, addedText, addPlan, alertText,
    cliOffText, cliOnText, duoNote, expiryText, glabNote, othersNeedAttention, rejectedText, tooltipModel,
} from '../core/accountsView';
import { Snapshots } from '../core/report';
import { account, state, TODAY } from './fixtures';

const PERCENTS = [80, 95];

// The accounts of the mockup, on Oct 18.
const acme = state(account(), 148.6);
const platform = state(account({ gitlabUrl: 'https://gitlab.acme.dev', groupPath: 'platform', limit: 200, expiresAt: '2026-10-21' }), 190.4);
const northwind = state(account({ username: 'dana-contractor', groupPath: 'northwind', limit: 100, expiresAt: '2026-10-15' }), null, 'rejected');
const lab = state(account({ gitlabUrl: 'https://gitlab.lab.io', username: 'dana-lab', groupPath: 'research', limit: 50 }), null, 'noToken');
const untracked = state(account({
    gitlabUrl: 'https://git.northwind.io', username: 'dkovalenko', groupPath: '', limit: 100, expiresAt: null,
    scopes: ['api', 'write_repository'], glab: true,
}), null, 'notTracked');
const ALL = [untracked, lab, northwind, platform, acme];

test('an account reads as in the mockup', () => {
    assert.equal(accountLabel(acme.account), '@dana.k · gitlab.com · acme');
    assert.equal(accountLabel(untracked.account), '@dkovalenko · git.northwind.io');
    assert.equal(accountLabel(account({ groupPath: 'https://gitlab.com/groups/acme' })), '@dana.k · gitlab.com · acme');
    assert.equal(accountName(platform.account), '@dana.k on gitlab.acme.dev · platform');

    assert.equal(accountDetail(acme, TODAY), '151.4 left · ••••••• · expires Feb 1, 2027');
    assert.equal(accountDetail(platform, TODAY), '9.6 left · ••••••• · expires in 3 days');
    assert.equal(accountDetail(northwind, TODAY), '••••••• · expired Oct 15');
    assert.equal(accountDetail(lab, TODAY), 'no token');
    assert.equal(accountDetail(untracked, TODAY), 'not tracked · ••••••• · glab uses it');
    assert.equal(accountDetail(state(account({ expiresAt: null, glab: true, duo: true }), null, 'rejected'), TODAY),
        '••••••• · turned down by GitLab · glab and the Duo CLI use it');
    assert.equal(accountDetail(state(account({ expiresAt: '2026-12-01' }), null, 'noGroup'), TODAY),
        'no group · ••••••• · expires Dec 1');
});

test('the status bar shows the selected account, with a bell for the others', () => {
    assert.equal(accountStatusText(acme, Snapshots.notConfigured()), '$148.6 / 300');
    assert.equal(accountStatusText(lab, Snapshots.notConfigured()), 'Duo: set up');
    assert.equal(accountStatusText(northwind, Snapshots.notConfigured()), 'Duo: error');
    assert.equal(accountStatusText(untracked, Snapshots.notConfigured()), 'Duo: not tracked');
    assert.equal(accountStatusText(null, Snapshots.notConfigured()), 'Duo: set up');
    assert.equal(accountStatusText(null, Snapshots.failed(Snapshots.loading(), 'Cannot reach gitlab.com')), 'Duo: error');

    assert.equal(othersNeedAttention(ALL, acme.id, PERCENTS, TODAY), true);
    assert.equal(othersNeedAttention([acme, untracked], acme.id, PERCENTS, TODAY), false);
    assert.equal(othersNeedAttention([platform, acme], platform.id, PERCENTS, TODAY), false, 'the selected one has its own colours');
});

test('the tooltip lists every account, the selected one in full', () => {
    const tip = tooltipModel(ALL, acme.id, Snapshots.notConfigured(), PERCENTS, TODAY);

    assert.equal(tip.attention, '3 other accounts need attention');
    assert.deepEqual(tip.blocks.map(b => b.title), [
        '@dana.k · gitlab.com · acme (in the status bar)',
        '@dana.k · gitlab.acme.dev · platform',
        '@dana-contractor · gitlab.com · northwind',
        '@dana-lab · gitlab.lab.io · research',
        '@dkovalenko · git.northwind.io',
    ]);
    assert.deepEqual(tip.blocks[0].lines, ['Used 148.6 of 300 (50%), left 151.4', 'Spent today: 1.3', 'Forecast by Oct 31: 255.9']);
    assert.deepEqual(tip.blocks[1].lines, ['Used 190.4 of 200 (95%), left 9.6', 'Token expires in 3 days, on Oct 21']);
    assert.deepEqual(tip.blocks[2].lines, ['Token expired on Oct 15: credits unknown']);
    assert.deepEqual(tip.blocks[3].lines, ['No token']);
    assert.deepEqual(tip.blocks[4].lines, ['Not tracked: the token has no ai_features scope']);
    assert.deepEqual(tip.footer, ["Updated 14:05 · $ = credits at GitLab's $1 list price", 'Click for details.']);

    const one = tooltipModel([acme], acme.id, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.equal(one.attention, null);

    const none = tooltipModel([], null, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.deepEqual(none.blocks, []);
    assert.ok(none.footer[0].includes('Add or Replace a Token'), none.footer[0]);
});

test('the dashboard shows one account, with the picker and what to fix', () => {
    const shown = accountsDashboard(ALL, platform.id, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.deepEqual(shown.accounts.map(a => [a.label, a.selected]), [
        ['@dana.k · gitlab.com · acme', false],
        ['@dana.k · gitlab.acme.dev · platform', true],
        ['@dana-contractor · gitlab.com · northwind', false],
        ['@dana-lab · gitlab.lab.io · research', false],
        ['@dkovalenko · git.northwind.io', false],
    ]);
    assert.equal(shown.report!.used, '190.4');
    assert.equal(shown.report!.caption, 'of 200 credits used');
    assert.equal(shown.notice, null);

    const expired = accountsDashboard(ALL, northwind.id, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.equal(expired.report, null);
    assert.equal(expired.loading, false);
    assert.equal(expired.notConfigured, null);
    assert.deepEqual(expired.notice, {
        title: 'The saved token no longer works', tone: 'error', action: 'replaceToken', actionLabel: 'Replace Token…',
        text: 'GitLab turns down the token of @dana-contractor on gitlab.com: it expired on Oct 15, 2026. '
            + 'Replace it to see this account again.',
    });
    const missing = accountsDashboard(ALL, lab.id, Snapshots.notConfigured(), PERCENTS, TODAY).notice!;
    assert.equal(missing.title, 'No token for this account');
    assert.equal(missing.actionLabel, 'Add Token…');
    const notTracked = accountsDashboard(ALL, untracked.id, Snapshots.notConfigured(), PERCENTS, TODAY).notice!;
    assert.equal(notTracked.title, 'Usage is not tracked for this account');
    assert.ok(notTracked.text.includes('has no ai_features scope') && notTracked.text.endsWith('glab still uses it.'), notTracked.text);
    const noGroup = accountsDashboard([state(account({ groupPath: '' }), null, 'noGroup')], null, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.equal(noGroup.notice!.action, 'findGroup');

    assert.equal(accountsDashboard(ALL, 'gone/nobody', Snapshots.notConfigured(), PERCENTS, TODAY).report!.used, '148.6',
        'an unknown selection shows the account with the most credits left');
    const unset = accountsDashboard([], null, Snapshots.notConfigured(), PERCENTS, TODAY);
    assert.deepEqual(unset.accounts, []);
    assert.ok(unset.notConfigured!.includes('read_api and ai_features'), unset.notConfigured!);
});

test('alerts and popups name the account', () => {
    const alert = { key: 'k', kind: 'threshold' as const, title: 'Duo credits: 95% of your limit', message: 'Used 190.4 of 200.' };
    assert.equal(alertText(alert, platform.account),
        'Duo credits: 95% of your limit for @dana.k on gitlab.acme.dev · platform. Used 190.4 of 200.');
    assert.equal(expiryText(platform.account, TODAY),
        'Duo Watchdog: the token of @dana.k on gitlab.acme.dev · platform expires in 3 days, on Oct 21, 2026. '
        + "After that, this account's credits are no longer tracked.");
    assert.equal(rejectedText(northwind.account, TODAY),
        'Duo Watchdog: the token of @dana-contractor on gitlab.com · northwind no longer works. GitLab turns it down: '
        + "it expired on Oct 15, 2026. This account's credits are not tracked until you replace it.");
    assert.ok(rejectedText(acme.account, TODAY).includes('it is mistyped, expired or revoked'));
});

test('adding a token says what will happen first', () => {
    const fresh = addPlan(undefined, 'https://gitlab.com', 'dana.ops', '2027-03-03', ['read_api', 'ai_features']);
    assert.equal(fresh.kind, 'new');
    assert.equal(fresh.message, 'A new account: @dana.ops on gitlab.com');
    assert.ok(fresh.detail.startsWith('Expires on Mar 3, 2027 · Scopes: read_api, ai_features'), fresh.detail);
    assert.equal(fresh.button, 'Add Account');

    const replace = addPlan(acme.account, 'https://gitlab.com', 'dana.k', '2027-11-02', ['read_api', 'ai_features']);
    assert.equal(replace.kind, 'replace');
    assert.equal(replace.message, 'Already in the list: @dana.k on gitlab.com');
    assert.ok(replace.detail.startsWith('OK replaces its token. Its group, acme, and its limit, 300, stay.'), replace.detail);
    assert.ok(replace.detail.includes('The new token: Expires on Nov 2, 2027'), replace.detail);
    assert.equal(replace.button, 'Replace Token');

    const limited = addPlan(undefined, 'https://git.northwind.io', 'dkovalenko', '2027-03-14', ['api', 'write_repository']);
    assert.equal(limited.kind, 'untracked');
    assert.equal(limited.message, "A new account, but its usage can't be tracked: @dkovalenko on git.northwind.io");
    assert.ok(limited.detail.includes('No ai_features scope'), limited.detail);

    assert.equal(addPlan(undefined, 'https://gitlab.com', 'x', null, null).kind, 'new', 'unknown scopes: try anyway');
});

test('after adding, the notification says what happened', () => {
    assert.equal(addedText('replace', acme.account, false, null),
        'Duo Watchdog: replaced the token of @dana.k on gitlab.com. Its group and limit stay.');
    assert.equal(addedText('replace', { ...acme.account, glab: true }, true, true),
        'Duo Watchdog: replaced the token of @dana.k on gitlab.com. Its group and limit stay; glab uses the new token too.');
    assert.equal(addedText('new', account({ username: 'dana.ops', glab: true, duo: true }), true, null),
        'Duo Watchdog: added @dana.ops on gitlab.com · acme. glab and the Duo CLI use its token too.');
    assert.ok(addedText('new', account({ glab: true }), true, false).endsWith(
        'glab can only read with this token: it needs the api and write_repository scopes.'));
    assert.ok(addedText('untracked', untracked.account, false, null).includes('Its usage is not tracked'));
});

test('glab and the Duo CLI say who uses which token', () => {
    assert.equal(glabNote(acme.account, 'dana.ops', 'https://gitlab.com', ['read_api', 'ai_features']),
        "glab keeps one token per GitLab. It uses @dana.k's for gitlab.com now; Yes switches it to @dana.ops. "
        + 'This token lacks the api and write_repository scopes, so glab could only read with it.');
    assert.ok(glabNote(undefined, 'dana.ops', 'https://gitlab.com', null).includes('has not given glab a token for gitlab.com'));
    assert.ok(duoNote(false, acme.account, 'dana.ops', 'https://gitlab.com').includes("also signs glab in to gitlab.com with this token, instead of @dana.k's"));

    assert.equal(cliOnText(acme.account, 'glab', undefined, true), 'Duo Watchdog: glab uses the token of @dana.k for gitlab.com now.');
    assert.equal(cliOnText(acme.account, 'duo', account({ username: 'bob' }), null),
        "Duo Watchdog: the GitLab Duo CLI uses the token of @dana.k for gitlab.com now, through glab, instead of @bob's. "
        + 'This turned on glab for this account too.');
    assert.ok(cliOnText({ ...acme.account, glab: true }, 'glab', undefined, false).includes('glab can only read with this token'));
    assert.equal(cliOffText({ ...acme.account, glab: true }, 'glab'),
        'Duo Watchdog: the token of @dana.k is no longer handed to glab. glab stays signed in to gitlab.com.');
    assert.ok(cliOffText({ ...acme.account, glab: true, duo: true }, 'duo').includes('Nothing is signed out'));
    assert.equal(idOf(acme.account), acme.id);
});
