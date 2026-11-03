import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
    accountId, attention, bareUsername, cliOwner, creditsLeft, expiryPopupKey, glabCanWrite, idOf, isExpired,
    matchesFilter, migratedAccount, missingUsageScopes, needsMigration, normalized, rejectedPopupKey, sortStates,
    usageReadable, withCli, withToken,
} from '../core/accounts';
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

test('an account is one user on one GitLab', () => {
    assert.equal(accountId('https://GitLab.com/', '@dana.k'), 'gitlab.com/dana.k');
    assert.equal(accountId('https://gitlab.acme.dev/gitlab', 'dana.k'), 'gitlab.acme.dev/dana.k');
    assert.notEqual(accountId('https://gitlab.com', 'dana.k'), accountId('https://gitlab.acme.dev', 'dana.k'));
    assert.equal(bareUsername(' @@alice '), 'alice');
    assert.equal(idOf(acme.account), 'gitlab.com/dana.k');
});

test('a stored account gets defaults for what it lacks', () => {
    const a = normalized({ gitlabUrl: 'https://gitlab.com', username: '@alice', duo: true });
    assert.equal(a.username, 'alice');
    assert.equal(a.groupPath, '');
    assert.equal(a.limit, 100);
    assert.equal(a.expiresAt, null);
    assert.equal(a.scopes, null);
    assert.equal(a.glab, true, 'the Duo CLI signs in through glab');
    assert.equal(a.lastAlertKey, null);
});

test('usage is readable with ai_features, and read_api or api', () => {
    assert.equal(usageReadable(['read_api', 'ai_features']), true);
    assert.equal(usageReadable(['api', 'write_repository', 'ai_features']), true);
    assert.equal(usageReadable(['api', 'write_repository']), false);
    assert.equal(usageReadable(['read_api']), false);
    assert.equal(usageReadable(null), null, 'unknown scopes: try anyway');
    assert.deepEqual(missingUsageScopes(['api', 'write_repository']), ['ai_features']);
    assert.deepEqual(missingUsageScopes([]), ['read_api', 'ai_features']);
    assert.deepEqual(missingUsageScopes(null), []);
});

test('glab writes with api and write_repository', () => {
    assert.equal(glabCanWrite(['api', 'write_repository', 'ai_features']), true);
    assert.equal(glabCanWrite(['read_api', 'ai_features']), false);
    assert.equal(glabCanWrite(null), null);
});

test('accounts sort by credits left, most first, unknown last', () => {
    assert.deepEqual(sortStates(ALL).map(s => s.id), [
        'gitlab.com/dana.k', 'gitlab.acme.dev/dana.k', 'gitlab.com/dana-contractor', 'gitlab.lab.io/dana-lab',
        'git.northwind.io/dkovalenko']);
    assert.ok(Math.abs(creditsLeft(acme)! - 151.4) < 1e-9);
    assert.equal(creditsLeft(northwind), null);
    assert.equal(creditsLeft(state(account({ limit: 100 }), 120)), 0, 'over the limit leaves none');

    // Ties: by username, then by GitLab.
    const onCom = state(account(), null);
    const onDev = state(account({ gitlabUrl: 'https://gitlab.acme.dev' }), null);
    const bob = state(account({ username: 'bob' }), null);
    assert.deepEqual(sortStates([onCom, bob, onDev]).map(s => s.id),
        ['gitlab.com/bob', 'gitlab.acme.dev/dana.k', 'gitlab.com/dana.k']);
});

test('the filters: has credits, expired', () => {
    assert.deepEqual(ALL.filter(s => matchesFilter('credits', s, TODAY)).map(s => s.id),
        ['gitlab.acme.dev/dana.k', 'gitlab.com/dana.k']);
    assert.deepEqual(ALL.filter(s => matchesFilter('expired', s, TODAY)).map(s => s.id), ['gitlab.com/dana-contractor']);
    assert.equal(ALL.filter(s => matchesFilter('all', s, TODAY)).length, 5);
    assert.equal(matchesFilter('credits', state(account({ limit: 100 }), 100), TODAY), false);

    assert.equal(isExpired(state(account({ expiresAt: TODAY }), 10), TODAY), true, 'GitLab stops taking it on the day');
    assert.equal(isExpired(state(account({ expiresAt: null }), null, 'rejected'), TODAY), true, 'GitLab turned it down');
    assert.equal(isExpired(state(account({ expiresAt: '2026-10-19' }), 10), TODAY), false);
});

test('what needs attention', () => {
    assert.equal(attention(acme, PERCENTS, TODAY), null);
    assert.equal(attention(platform, PERCENTS, TODAY), '95% of its limit used');
    assert.equal(attention(northwind, PERCENTS, TODAY), 'token expired on Oct 15');
    assert.equal(attention(lab, PERCENTS, TODAY), 'no token');
    assert.equal(attention(untracked, PERCENTS, TODAY), null, 'not tracked is a state, not an alarm');
    assert.equal(attention(state(account(), 300, null, true), PERCENTS, TODAY), 'blocked by GitLab');
    assert.equal(attention(state(account({ expiresAt: '2026-10-23' }), 10), PERCENTS, TODAY), 'token expires in 5 days');
    assert.equal(attention(state(account({ expiresAt: '2026-10-19' }), 10), PERCENTS, TODAY), 'token expires tomorrow');
    assert.equal(attention(state(account({ expiresAt: '2026-10-26' }), 10), PERCENTS, TODAY), null, 'eight days is not yet');
    assert.equal(attention(state(account({ expiresAt: null }), null, 'rejected'), PERCENTS, TODAY), 'GitLab turns the token down');
});

test('the expiry popup shows once a token, from three days before', () => {
    const expiring = (expiresAt: string | null, shown: string | null = null) =>
        expiryPopupKey(account({ expiresAt, expiryPopupKey: shown }), TODAY);
    assert.equal(expiring('2026-10-21'), 'expiry|2026-10-21');
    assert.equal(expiring('2026-10-19'), 'expiry|2026-10-19');
    assert.equal(expiring('2026-10-22'), null, 'four days is not yet');
    assert.equal(expiring(TODAY), null, 'expired: GitLab turns it down instead');
    assert.equal(expiring('2026-10-01'), null);
    assert.equal(expiring(null), null);
    assert.equal(expiring('2026-10-21', 'expiry|2026-10-21'), null, 'shown already');
    assert.equal(expiring('2026-10-20', 'expiry|2026-10-21'), 'expiry|2026-10-20', 'another token, another popup');
});

test('the turned-down popup shows once a saved token', () => {
    const a = account({ tokenSavedAt: 1000 });
    assert.equal(rejectedPopupKey(a, false), null);
    assert.equal(rejectedPopupKey(a, true), 'rejected|1000');
    assert.equal(rejectedPopupKey({ ...a, rejectedPopupKey: 'rejected|1000' }, true), null);
    assert.equal(rejectedPopupKey({ ...a, rejectedPopupKey: 'rejected|1000', tokenSavedAt: 2000 }, true), 'rejected|2000');
});

test('the old token becomes the first account', () => {
    assert.equal(needsMigration(0, 'glpat-old'), true);
    assert.equal(needsMigration(1, 'glpat-old'), false, 'only while there is no account');
    assert.equal(needsMigration(0, undefined), false);
    assert.equal(needsMigration(0, '  '), false);

    const old = { gitlabUrl: 'https://gitlab.example.com/', groupPath: 'https://gitlab.example.com/groups/acme/-/settings',
        limit: 250, lastAlertKey: 'threshold|2026-10-01|250|80' };
    const a = migratedAccount(old, '@alice', { name: 'Duo Watchdog', scopes: ['read_api', 'ai_features'], expiresAt: '2027-01-01' }, 1000);
    assert.equal(idOf(a), 'gitlab.example.com/alice');
    assert.equal(a.groupPath, 'acme', 'a pasted group URL becomes its path');
    assert.equal(a.limit, 250);
    assert.equal(a.lastAlertKey, 'threshold|2026-10-01|250|80', 'the alert shown already is not shown again');
    assert.equal(a.tokenSavedAt, 1000);
    assert.equal(a.expiresAt, '2027-01-01');
    assert.deepEqual(a.scopes, ['read_api', 'ai_features']);
    assert.equal(a.glab, false);

    const unknown = migratedAccount(old, 'alice', null, 1000);
    assert.equal(unknown.expiresAt, null);
    assert.equal(unknown.scopes, null);
});

test("a token for a listed account replaces its token: its group, limit and the rest stay", () => {
    const existing = account({ glab: true, duo: true, lastAlertKey: 'x', expiryPopupKey: 'expiry|2027-02-01' });
    const info = { name: 'Duo Watchdog', scopes: ['api', 'write_repository', 'ai_features'], expiresAt: '2027-11-02' };

    const replaced = withToken(existing, { gitlabUrl: 'https://gitlab.com/', username: 'dana.k', info, savedAt: 2000 },
        { groupPath: '', limit: 100 });

    assert.equal(replaced.groupPath, 'acme');
    assert.equal(replaced.limit, 300);
    assert.equal(replaced.glab, true);
    assert.equal(replaced.duo, true);
    assert.equal(replaced.lastAlertKey, 'x');
    assert.equal(replaced.tokenSavedAt, 2000);
    assert.equal(replaced.expiresAt, '2027-11-02');
    assert.deepEqual(replaced.scopes, info.scopes);

    const fresh = withToken(undefined, { gitlabUrl: ' https://gitlab.com ', username: '@dana.ops', info: null, savedAt: 3000 },
        { groupPath: 'acme', limit: 0 });
    assert.equal(fresh.gitlabUrl, 'https://gitlab.com');
    assert.equal(fresh.username, 'dana.ops');
    assert.equal(fresh.limit, 100, 'a limit of 0 is the default');
    assert.equal(fresh.glab, false, 'glab is an opt-in');
    assert.equal(fresh.duo, false, 'the Duo CLI is an opt-in');
});

test('glab and the Duo CLI: one account per GitLab, the Duo CLI through glab', () => {
    const a = account({ glab: true });
    const b = account({ username: 'dana-contractor' });
    const c = account({ gitlabUrl: 'https://gitlab.acme.dev', glab: true });
    const list = [a, b, c];
    const flags = (accounts: typeof list) => accounts.map(x => (x.glab ? 'g' : '-') + (x.duo ? 'd' : '-'));

    assert.deepEqual(flags(withCli(list, idOf(b), 'glab', true)), ['--', 'g-', 'g-'], 'one account per GitLab');
    const duo = withCli(list, idOf(b), 'duo', true);
    assert.deepEqual(flags(duo), ['--', 'gd', 'g-'], 'the Duo CLI turns glab on');
    assert.deepEqual(flags(withCli(duo, idOf(b), 'glab', false)), ['--', '--', 'g-'], 'glab off turns the Duo CLI off');
    assert.deepEqual(flags(withCli(duo, idOf(b), 'duo', false)), ['--', 'g-', 'g-'], 'the Duo CLI off keeps glab');
    assert.deepEqual(flags(withCli(list, 'nobody', 'glab', true)), flags(list));

    assert.equal(cliOwner(list, 'https://GITLAB.com/', 'glab', idOf(b)), a);
    assert.equal(cliOwner(list, 'https://gitlab.com', 'glab', idOf(a)), undefined);
    assert.equal(cliOwner(list, 'https://gitlab.com', 'duo'), undefined);
});
