import assert from 'node:assert/strict';
import { test } from 'node:test';
import { groupPath, host, tokenPage } from '../core/address';

const path = (typed: string | null) => groupPath(typed, 'https://gitlab.com');

test('keeps the top-level group of whatever was typed', () => {
    assert.equal(path('  acme '), 'acme');
    assert.equal(path('@acme'), 'acme');
    assert.equal(path('/acme/platform/'), 'acme');
    assert.equal(path('https://gitlab.com/acme/platform/-/issues'), 'acme');
    assert.equal(path('https://gitlab.com/groups/acme/-/settings/general'), 'acme');
    assert.equal(path('gitlab.com/acme'), 'acme');
    assert.equal(path('my.group'), 'my.group', 'a dot belongs to the path unless it is the GitLab host');
    assert.equal(path('https://gitlab.com/'), '');
    assert.equal(path(null), '');
});

test('the token page asks for the name and both scopes', () => {
    assert.equal(tokenPage(' https://gitlab.example.com/ '),
        'https://gitlab.example.com/-/user_settings/personal_access_tokens?name=Duo+Watchdog&scopes=read_api,ai_features');
});

test('reads the host', () => {
    assert.equal(host('https://gitlab.com'), 'gitlab.com');
    assert.equal(host('not a url'), null);
});
