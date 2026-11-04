import assert from 'node:assert/strict';
import { test } from 'node:test';
import { localPath, releaseNotice, upgradeText, whatsNewCsp, whatsNewHtml } from '../core/release';

test('an update or a fresh install is said once a version', () => {
    assert.equal(releaseNotice('0.4.0', '0.4.0', true), null, 'seen already');
    assert.equal(releaseNotice('0.4.1', '0.4.0', false), 'upgrade');
    assert.equal(releaseNotice('0.4.0', undefined, true), 'upgrade', '0.3 set up, but it did not remember versions');
    assert.equal(releaseNotice('0.4.0', undefined, false), 'fresh');
    assert.equal(releaseNotice('0.4.0', '', false), 'fresh');
    assert.equal(upgradeText('0.5.0'), 'Duo Watchdog 0.5.0: the Duo CLI session live in the status bar, and token rotation.');
});

test('only paths inside the page folder are images of its own', () => {
    assert.equal(localPath('accounts.png'), 'accounts.png');
    assert.equal(localPath('./img/../stats.png'), 'stats.png');
    assert.equal(localPath('my%20shot.png'), 'my shot.png');
    assert.equal(localPath('../secret.png'), null);
    assert.equal(localPath('..\\secret.png'), null);
    assert.equal(localPath('/etc/passwd'), null);
    assert.equal(localPath('data:image/png;base64,AAAA'), null);
    assert.equal(localPath('https://example.com/a.png'), null);
    assert.equal(localPath('#top'), null);
    assert.equal(localPath(''), null);
});

test("What's New points its images at the webview, keeps scripts out and sets its policy", () => {
    const base = 'https://file+.vscode-resource.vscode-cdn.net/ext/media/whats-new/';
    const html = whatsNewHtml(`<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>New</title></head>
<body style="margin: 0"><img src="accounts.png" alt="Accounts"><img alt='Stats' src='img/stats.png' width="400">
<img src=../secret.png><img src="https://example.com/x.png"><img src="/etc/passwd">
<script>alert(1)</script><script src="x.js"></script><p>Text</p></body></html>`, rel => base + rel, 'https://*.vscode-cdn.net');

    assert.ok(html.includes('<img src="' + base + 'accounts.png" alt="Accounts">'), html);
    assert.ok(html.includes(`<img alt='Stats' src="${base}img/stats.png" width="400">`), html);
    assert.ok(html.includes('<img src="">'), 'outside the folder: no image');
    assert.ok(html.includes('<img src="https://example.com/x.png">'), 'not rewritten; the policy blocks it');
    assert.ok(!/<script/i.test(html), html);
    assert.ok(html.includes('<head>\n<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
        + 'img-src https://*.vscode-cdn.net; style-src \'unsafe-inline\'">'), html);
    assert.ok(html.includes('<body style="margin: 0">'), 'inline styles stay');
    assert.equal(whatsNewCsp('X'), "default-src 'none'; img-src X; style-src 'unsafe-inline'");

    assert.ok(whatsNewHtml('<p>bare</p>', r => r, 'X').startsWith('<meta http-equiv="Content-Security-Policy"'));
    assert.ok(whatsNewHtml('<html><body></body></html>', r => r, 'X').startsWith('<html>\n<head><meta'));
});
