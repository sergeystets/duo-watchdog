import assert from 'node:assert/strict';
import { test } from 'node:test';
import { glabDecisions } from '../core/accounts';
import { diagnosticsReport, DiagnosticsInput } from '../core/diagnostics';
import { RingBuffer, scrub } from '../core/log';
import { account, AT } from './fixtures';

/** A made-up token, put together at run time so the source holds nothing shaped like one. */
const fake = (prefix: string, body: string): string => prefix + '-' + body;

const TOKEN = fake('glpat', 'AbC123xyz_-987654321');

test('the scrubber masks GitLab tokens by their shape', () => {
    assert.equal(scrub('Bearer ' + TOKEN + ' failed'), 'Bearer glpat-••• failed');
    assert.equal(scrub(fake('gloas', '0123456789abcdef') + ' and ' + fake('gldt', 'ABCDEFGH12') + ' and ' + fake('glrt', 't1_abcdefghij')),
        'gloas-••• and gldt-••• and glrt-•••');
    assert.equal(scrub('(' + fake('glptt', 'aaaaaaaaaaaa') + ')'), '(glptt-•••)');
    assert.equal(scrub('glpat-short is too short to be a token'), 'glpat-short is too short to be a token');
    assert.equal(scrub('see https://gitlab.com/glab-cli/glab'), 'see https://gitlab.com/glab-cli/glab', 'not a token');
    assert.equal(scrub('a token: ' + TOKEN + ', again ' + TOKEN), 'a token: glpat-•••, again glpat-•••', 'every one');
});

test('the scrubber masks the exact tokens it is given, whatever their shape', () => {
    assert.equal(scrub('token=s3cr3t-value-0042 end', ['s3cr3t-value-0042']), 'token=••• end');
    assert.equal(scrub(fake('glpat', 'abcdefghijkl'), [fake('glpat', 'abcdefghijkl')]), '•••');
    assert.equal(scrub('abcdefgh and abcdefgh12', ['abcdefgh', 'abcdefgh12']), '••• and •••', 'the longest first');
    assert.equal(scrub('a b c', ['', ' ', 'ab']), 'a b c', 'blank and very short ones are ignored');
});

test('the ring buffer keeps the last lines', () => {
    const buffer = new RingBuffer(3);
    for (const line of ['1', '2', '3', '4', '5']) {
        buffer.push(line);
    }
    assert.deepEqual(buffer.lines(), ['3', '4', '5']);
    assert.deepEqual(buffer.lines(2), ['4', '5']);
    assert.deepEqual(buffer.lines(10), ['3', '4', '5']);
    assert.equal(new RingBuffer().size, 200);
});

function input(change: Partial<DiagnosticsInput> = {}): DiagnosticsInput {
    const acme = account({ glab: true, glabHanded: 1_759_318_692_000 });
    const lab = account({ gitlabUrl: 'https://gitlab.lab.io', username: 'dana-lab', groupPath: '', scopes: null, expiresAt: null });
    return {
        now: AT,
        extensionVersion: '0.4.0',
        vscodeVersion: '1.105.1',
        platform: 'linux',
        arch: 'x64',
        remoteName: 'wsl',
        remoteHost: true,
        glab: { path: null, looked: ['/usr/bin/glab', '/opt/homebrew/bin/glab'] },
        selectedId: 'gitlab.com/dana.k',
        accounts: [
            { id: 'gitlab.com/dana.k', account: acme, hasToken: true, status: 'ok', problem: null, creditsUsed: 148.6, error: null },
            { id: 'gitlab.lab.io/dana-lab', account: lab, hasToken: false, status: 'notConfigured', problem: 'noToken', creditsUsed: null, error: null },
        ],
        log: ['2026-10-18 14:05:00.000 [info] refresh gitlab.com/dana.k: ok, 148.6 used'],
        ...change,
    };
}

test('the diagnostics say what someone troubleshooting needs', () => {
    const report = diagnosticsReport(input());
    const lines = report.split('\n');
    assert.equal(lines[0], 'Duo Watchdog diagnostics');
    assert.equal(lines[1], 'Generated: ' + AT.toISOString());
    assert.ok(report.includes('Extension: 0.4.0\nVS Code: 1.105.1\nPlatform: linux x64\nRemote: wsl (the extension host is remote)\n'), report);
    assert.ok(report.includes('glab: not found; looked in: /usr/bin/glab, /opt/homebrew/bin/glab'), report);
    assert.ok(report.includes('Selected account: gitlab.com/dana.k'));
    assert.ok(report.includes(`Accounts (2):
- gitlab.com/dana.k (selected)
  GitLab: https://gitlab.com · group: acme · limit: 300
  token saved: yes · saved at: 2025-10-01T11:38:12.000Z · expires: 2027-02-01 · scopes: read_api, ai_features
  glab: on · Duo CLI: off · glab has it: yes · glab refused it: no
  last refresh: ok · credits used: 148.6
- gitlab.lab.io/dana-lab
  GitLab: https://gitlab.lab.io · group: none · limit: 300
  token saved: no · saved at: 2025-10-01T11:38:12.000Z · expires: unknown · scopes: unknown
  glab: off · Duo CLI: off · glab has it: no · glab refused it: no
  last refresh: notConfigured (noToken)
`), report);
    assert.ok(report.endsWith('Last 1 log lines:\n2026-10-18 14:05:00.000 [info] refresh gitlab.com/dana.k: ok, 148.6 used\n'), report);
    assert.ok(diagnosticsReport(input({ remoteName: undefined, remoteHost: false })).includes('Remote: none (the extension host is local)'));
});

test('the diagnostics never hold a token, even when given one', () => {
    const odd = 'x9-not-shaped-like-a-gitlab-token';
    const acme = account({ gitlabUrl: 'https://gitlab.com/?private_token=' + TOKEN });
    const report = diagnosticsReport(input({
        accounts: [{ id: 'gitlab.com/dana.k', account: acme, hasToken: true, status: 'error', problem: null, creditsUsed: null,
            error: 'GitLab said: invalid token ' + TOKEN + ' / ' + odd }],
        log: ['[warn] glab: error: token ' + TOKEN + ' is not valid', '[info] Authorization: Bearer ' + odd,
            '[info] ' + fake('gldt', '0123456789abcdef') + ' deploy token', ...Array.from({ length: 150 }, (_, i) => 'line ' + i)],
        glab: { path: '/home/dana/' + TOKEN + '/glab', looked: [] },
        secrets: [TOKEN, odd],
    }));
    assert.ok(!report.includes(TOKEN), report);
    assert.ok(!report.includes(odd), report);
    assert.ok(!/gl[a-z]{2,8}-[A-Za-z0-9_-]{8,}/.test(report), 'nothing shaped like a token');
    assert.ok(report.includes('invalid token •••'), report);
    assert.ok(report.includes('Last 100 log lines:'), 'the last 100 lines only');
    assert.ok(report.includes('line 149') && !report.includes('line 49\n'), report.slice(-200));
});

test('a glab sync says, for each GitLab, whom glab follows and why it hands over or not', () => {
    const a = account({ username: 'a', glab: true, tokenSavedAt: 10 });
    const b = account({ username: 'b', glab: true, tokenSavedAt: 20, glabHanded: 20 });
    const d = account({ gitlabUrl: 'https://gitlab.acme.dev', username: 'd', duo: true, glab: true, tokenSavedAt: 40, glabRefused: 40 });
    assert.deepEqual(glabDecisions([a, b, d], 'gitlab.com/a', true), [
        { host: 'gitlab.com', allowed: ['gitlab.com/a', 'gitlab.com/b'], owner: 'gitlab.com/a', action: 'hand' },
        { host: 'gitlab.acme.dev', allowed: ['gitlab.acme.dev/d'], owner: 'gitlab.acme.dev/d', action: 'refused' },
    ]);
    assert.equal(glabDecisions([a, b, d], 'gitlab.com/b', true)[0].action, 'has');
    assert.equal(glabDecisions([a, b, d], 'gitlab.com/a', false)[0].action, 'notInstalled');
    assert.deepEqual(glabDecisions([account()], null, true), [], 'nobody allowed: nothing to say');
});
