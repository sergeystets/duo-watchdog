import assert from 'node:assert/strict';
import { test } from 'node:test';
import { duoHasOwnLogin, GLAB_DIRS, glabArgs, glabCandidates, glabOutput } from '../core/glab';

test('glab is looked for on the PATH, then in the Homebrew folders', () => {
    assert.deepEqual(glabCandidates('/usr/bin::/opt/homebrew/bin', 'darwin'), [
        '/usr/bin/glab', '/opt/homebrew/bin/glab', '/usr/local/bin/glab', '/home/linuxbrew/.linuxbrew/bin/glab']);
    assert.deepEqual(glabCandidates(undefined, 'linux'), GLAB_DIRS.map(d => d + '/glab'));
    assert.deepEqual(glabCandidates('C:\\Tools;"C:\\Program Files\\glab"', 'win32'),
        ['C:\\Tools\\glab.exe', 'C:\\Program Files\\glab\\glab.exe']);
});

test('the token goes to standard input, never on the command line', () => {
    assert.deepEqual(glabArgs('gitlab.example.com'), ['auth', 'login', '--hostname', 'gitlab.example.com', '--stdin']);
});

test("glab's output is shown trimmed, without colours or the token", () => {
    assert.equal(glabOutput('\u001b[31mx\u001b[0m error: glpat-secret is bad\n\n   try again  \n', 'glpat-secret'),
        'x error: ••••••• is bad try again');
    const long = glabOutput('a'.repeat(1000) + 'END', 't');
    assert.equal(long.length, 300);
    assert.ok(long.startsWith('…') && long.endsWith('END'));
});

test("the Duo CLI's own login is a non-empty object in its storage", () => {
    assert.equal(duoHasOwnLogin('{"gitlab.com":{"token":"x"}}'), true);
    assert.equal(duoHasOwnLogin('{}'), false);
    assert.equal(duoHasOwnLogin(''), false);
    assert.equal(duoHasOwnLogin('not json'), false);
    assert.equal(duoHasOwnLogin('[1]'), false);
    assert.equal(duoHasOwnLogin(null), false);
});
