import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
    assetOf, availableText, compareVersions, downloadProblem, due, EVERY_MS, isNewer, parseRelease, sha256, trusted,
    versionOf,
} from '../core/updates';

const RELEASE = JSON.stringify({
    tag_name: 'v0.7.0',
    html_url: 'https://github.com/sergeystets/duo-watchdog/releases/tag/v0.7.0',
    draft: false,
    prerelease: false,
    body: '  Updates from the app.\n',
    assets: [
        {
            name: 'duo-watchdog-0.7.0.vsix', size: 5, digest: 'sha256:' + sha256(Buffer.from('hello')).toUpperCase(),
            browser_download_url: 'https://github.com/sergeystets/duo-watchdog/releases/download/v0.7.0/duo-watchdog-0.7.0.vsix',
        },
        {
            name: 'duo-watchdog-0.7.0.zip', size: 3400,
            browser_download_url: 'https://github.com/sergeystets/duo-watchdog/releases/download/v0.7.0/duo-watchdog-0.7.0.zip',
        },
        { name: 'duo-watchdog-evil.vsix', size: 1, browser_download_url: 'http://example.com/duo-watchdog-evil.vsix' },
    ],
});

test('the latest release has its version, page and files', () => {
    const r = parseRelease(RELEASE)!;
    assert.equal(r.version, '0.7.0');
    assert.equal(r.page, 'https://github.com/sergeystets/duo-watchdog/releases/tag/v0.7.0');
    assert.equal(r.notes, 'Updates from the app.');
    assert.equal(r.assets.length, 2, 'a file anywhere but GitHub is left out');
    const vsix = assetOf(r, '.vsix')!;
    assert.equal(vsix.name, 'duo-watchdog-0.7.0.vsix');
    assert.equal(vsix.sha256, sha256(Buffer.from('hello')));
    assert.equal(assetOf(r, '.zip')!.sha256, null, 'no digest, nothing to check against');
    assert.equal(assetOf(r, '.jar'), null);
    assert.equal(availableText('0.7.0', '0.6.0'), 'Duo Watchdog 0.7.0 is available; you have 0.6.0.');
});

test('drafts and pre-releases are not offered, and an answer that is no release fails', () => {
    assert.equal(parseRelease('{"tag_name": "v0.8.0-rc.1", "prerelease": true}'), null);
    assert.equal(parseRelease('{"tag_name": "v0.8.0", "draft": true}'), null);
    assert.throws(() => parseRelease('{"message": "Not Found"}'));
    assert.throws(() => parseRelease('<html>rate limited</html>'));
    assert.throws(() => parseRelease('[]'));
    assert.throws(() => parseRelease('{"tag_name": "v1/../../x"}'), 'it names the downloaded file');
});

test('versions are compared as numbers', () => {
    assert.ok(isNewer('0.7.0', '0.6.0'));
    assert.ok(isNewer('0.10.0', '0.9.3'));
    assert.ok(isNewer('v1.0', '0.99.99'));
    assert.ok(isNewer('0.6.1', '0.6'));
    assert.ok(!isNewer('0.6.0', '0.6.0'));
    assert.ok(!isNewer('0.6.0', '0.6.1'));
    assert.ok(!isNewer('0.7.0-rc.1', '0.7.0'), 'a pre-release comes before its release');
    assert.ok(isNewer('0.7.0', '0.7.0-rc.1'));
    assert.equal(compareVersions('0.7.0+build.5', '0.7.0'), 0);
    assert.equal(versionOf('v0.7.0'), '0.7.0');
    assert.equal(versionOf('version-x'), 'version-x');
});

test('it asks once a day', () => {
    const now = Date.parse('2026-11-09T10:00:00Z');
    assert.ok(due(undefined, now));
    assert.ok(due(0, now));
    assert.ok(!due(now - 3_600_000, now));
    assert.ok(due(now - EVERY_MS, now));
    assert.ok(due(now + 3_600_000, now), 'a clock set back');
});

test('only GitHub is trusted', () => {
    assert.ok(trusted('https://github.com/sergeystets/duo-watchdog/releases/download/v1/a.vsix'));
    assert.ok(trusted('https://objects.githubusercontent.com/github-production-release-asset/1'));
    assert.ok(!trusted('http://github.com/a.vsix'));
    assert.ok(!trusted('https://github.com.example.com/a.vsix'));
    assert.ok(!trusted('https://evilgithub.com/a.vsix'));
    assert.ok(!trusted('not a url'));
});

test('a download must match its release', () => {
    const hello = Buffer.from('hello');
    const hash = sha256(hello);
    assert.equal(hash, '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
    assert.equal(downloadProblem(hello, { name: 'a', url: 'u', size: 5, sha256: hash }), null);
    assert.equal(downloadProblem(hello, { name: 'a', url: 'u', size: -1, sha256: null }), null);
    assert.match(downloadProblem(hello, { name: 'a', url: 'u', size: 6, sha256: null })!, /5 bytes/);
    assert.match(downloadProblem(hello, { name: 'a', url: 'u', size: 5, sha256: '00' + hash.slice(2) })!, /SHA-256/);
});
