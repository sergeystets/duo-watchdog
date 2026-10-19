import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CreditsClient, endpoint, GitLabError, groupLabel, Transport } from '../core/client';

const SELF_OK = `{"data":{"currentUser":{"username":"alice","creditsUsage":{
  "enabled":true,"startDate":"2026-10-01","endDate":"2026-10-31","creditsUsed":42.37,
  "blockedStatus":{"blocked":false,"capType":null},
  "dailyUsage":[{"date":"2026-10-17","creditsUsed":3.5},{"date":"2026-10-18","creditsUsed":1.25}],
  "usedFlowTypes":[{"id":"chat","title":"Duo Chat"},{"id":"code_review","title":"Code Review"}]}}}}`;

const SELF_DENIED = `{"errors":[{"message":"The resource that you are attempting to access does not exist or you don't have permission to perform this action","path":["currentUser","creditsUsage"]}],
 "data":{"currentUser":{"username":"alice","creditsUsage":null}}}`;

class FakeTransport implements Transport {
    readonly responses: { status: number; body: string }[] = [];
    readonly requests: { query: string; variables: Record<string, unknown> }[] = [];
    readonly tokens: string[] = [];
    endpoint: URL | undefined;

    reply(status: number, body: string): this {
        this.responses.push({ status, body });
        return this;
    }

    async post(endpoint: URL, token: string, jsonBody: string) {
        this.endpoint = endpoint;
        this.tokens.push(token);
        this.requests.push(JSON.parse(jsonBody));
        return this.responses.shift() ?? { status: 500, body: '' };
    }
}

async function rejection(promise: Promise<unknown>): Promise<GitLabError> {
    try {
        await promise;
    } catch (e) {
        assert.ok(e instanceof GitLabError, String(e));
        return e;
    }
    assert.fail('expected a GitLabError');
}

test("reads the user's own usage and prices each feature", async () => {
    const http = new FakeTransport()
        .reply(200, SELF_OK)
        .reply(200, '{"data":{"currentUser":{"f0":{"creditsUsed":30.0},"f1":{"creditsUsed":12.37}}}}');

    const r = await new CreditsClient('https://gitlab.example.com/', 'glpat-x', http).fetch('acme');

    assert.equal(http.endpoint?.toString(), 'https://gitlab.example.com/api/graphql');
    assert.deepEqual(http.tokens, ['glpat-x', 'glpat-x']);
    assert.equal(http.requests[0].variables.namespacePath, 'acme');
    assert.equal(r.username, 'alice');
    assert.equal(r.startDate, '2026-10-01');
    assert.equal(r.endDate, '2026-10-31');
    assert.equal(r.creditsUsed, 42.37);
    assert.equal(r.blocked, false);
    assert.equal(r.daily.length, 2);
    assert.deepEqual(r.flows, [
        { id: 'chat', title: 'Duo Chat', credits: 30 },
        { id: 'code_review', title: 'Code Review', credits: 12.37 },
    ]);
    assert.equal(r.flowsError, null);

    const breakdown = http.requests[1];
    assert.ok(breakdown.query.includes('$f1: [String!]'), breakdown.query);
    assert.ok(breakdown.query.includes('f1: creditsUsage(namespacePath: $namespacePath, flowTypes: $f1)'), breakdown.query);
    assert.deepEqual(breakdown.variables.f1, ['code_review']);
});

test('a failed breakdown keeps the total and lists features unpriced', async () => {
    const http = new FakeTransport().reply(200, SELF_OK).reply(500, 'boom');

    const r = await new CreditsClient('https://gitlab.com', 't', http).fetch('acme');

    assert.equal(r.creditsUsed, 42.37);
    assert.equal(r.flows.length, 2);
    assert.equal(r.flows[0].credits, null);
    assert.equal(r.flowsError, 'GitLab returned HTTP 500 from gitlab.com.');
});

test('features GitLab will not price carry its reason', async () => {
    const http = new FakeTransport().reply(200, SELF_OK).reply(200, `{"errors":[{"message":"Argument 'flowTypes' has an invalid value"}],
         "data":{"currentUser":{"f0":null,"f1":null}}}`);

    const r = await new CreditsClient('https://gitlab.com', 't', http).fetch('acme');

    assert.equal(r.flows[1].credits, null);
    assert.equal(r.flowsError, "Argument 'flowTypes' has an invalid value");
});

test('reports the blocked status', async () => {
    const http = new FakeTransport().reply(200, `{"data":{"currentUser":{"username":"alice","creditsUsage":{
          "enabled":true,"startDate":"2026-10-01","endDate":"2026-10-31","creditsUsed":100,
          "blockedStatus":{"blocked":true,"capType":"FLAT_USER_CAP"},"dailyUsage":[],"usedFlowTypes":[]}}}}`);

    const r = await new CreditsClient('https://gitlab.com', 't', http).fetch('acme');

    assert.equal(r.blocked, true);
    assert.equal(r.blockedBy, 'FLAT_USER_CAP');
    assert.equal(http.requests.length, 1, 'no features, so no breakdown query');
});

test('a refused group says to check the path or find the group', async () => {
    const http = new FakeTransport().reply(200, SELF_DENIED);

    const e = await rejection(new CreditsClient('https://gitlab.com', 't', http).fetch('Acme Corp'));

    assert.ok(e.message.includes('@alice in "Acme Corp"'), e.message);
    assert.ok(e.message.includes("don't have permission"), e.message);
    assert.ok(e.message.includes('Find My Group'), e.message);
    assert.equal(http.requests.length, 1, 'no owner fallback');
});

test('finds the top-level groups that give credits', async () => {
    const http = new FakeTransport()
        .reply(200, `{"data":{"currentUser":{"groups":{"nodes":[
          {"fullPath":"acme/platform/tools","name":"Tools"},
          {"fullPath":"acme","name":"Acme Corp"},
          {"fullPath":"hobby","name":"hobby"},
          {"fullPath":"oss/sub","name":"Sub"}]}}}}`)
        .reply(200, `{"errors":[{"message":"no access","path":["currentUser","g1"]}],
         "data":{"currentUser":{"g0":{"enabled":true,"creditsUsed":21.5},"g1":null,
                                "g2":{"enabled":false,"creditsUsed":null}}}}`);

    const groups = await new CreditsClient('https://gitlab.com', 't', http).findGroups();

    assert.deepEqual(groups.checked, ['acme', 'hobby', 'oss']);
    assert.deepEqual(groups.withCredits, [{ path: 'acme', name: 'Acme Corp' }]);
    assert.equal(groupLabel(groups.withCredits[0]), 'acme  (Acme Corp)');
    const probe = http.requests[1];
    assert.ok(probe.query.includes('g2: creditsUsage(namespacePath: $g2)'));
    assert.equal(probe.variables.g2, 'oss');
});

test('finds no groups without asking about none', async () => {
    const http = new FakeTransport().reply(200, '{"data":{"currentUser":{"groups":{"nodes":[]}}}}');

    const groups = await new CreditsClient('https://gitlab.com', 't', http).findGroups();

    assert.deepEqual(groups.checked, []);
    assert.equal(http.requests.length, 1);
});

test('an empty currentUser means the token was not accepted', async () => {
    const http = new FakeTransport().reply(200, '{"data":{"currentUser":null}}');

    const e = await rejection(new CreditsClient('https://gitlab.com', 't', http).fetch('acme'));

    assert.ok(e.message.includes('read_api'), e.message);
});

test('explains HTTP failures', async () => {
    for (const [status, body, expected] of [
        [401, '{}', 'HTTP 401'], [403, '{}', 'read_api'], [502, '', 'HTTP 502'], [200, '<html>sign in</html>', 'not JSON'],
    ] as const) {
        const e = await rejection(new CreditsClient('https://gitlab.com', 't', new FakeTransport().reply(status, body)).fetch('acme'));
        assert.ok(e.message.includes(expected), e.message);
    }
});

test('an unreachable host is named', async () => {
    const down: Transport = { post: async () => { throw new Error('Connection refused'); } };

    const e = await rejection(new CreditsClient('https://gitlab.example.com', 't', down).fetch('acme'));

    assert.equal(e.message, 'Cannot reach gitlab.example.com: Connection refused');
});

test('refuses an address that is not a URL', () => {
    assert.throws(() => endpoint('gitlab.example.com'), GitLabError);
    assert.throws(() => endpoint(''), GitLabError);
    assert.throws(() => endpoint('https://bad host'), GitLabError);
    assert.equal(endpoint('https://gitlab.com/').toString(), 'https://gitlab.com/api/graphql');
});

test('the error never carries the token', async () => {
    const http = new FakeTransport().reply(401, '{}');

    const e = await rejection(new CreditsClient('https://gitlab.com', 'glpat-secret', http).fetch('acme'));

    assert.ok(!e.message.includes('glpat-secret'));
});
