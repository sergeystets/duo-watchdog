import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CreditsClient, endpoint, GitLabError, groupLabel, Transport } from '../core/client';
import { months, nonBillableTotal, total } from '../core/history';

const SELF_OK = `{"data":{"currentUser":{"username":"alice","creditsUsage":{
  "enabled":true,"startDate":"2026-10-01","endDate":"2026-10-31","creditsUsed":42.37,
  "blockedStatus":{"blocked":false,"capType":null},
  "dailyUsage":[{"date":"2026-10-17","creditsUsed":3.5},{"date":"2026-10-18","creditsUsed":1.25}],
  "usedFlowTypes":[{"id":"chat","title":"Duo Chat"},{"id":"code_review","title":"Code Review"}]}}}}`;

const SELF_DENIED = `{"errors":[{"message":"The resource that you are attempting to access does not exist or you don't have permission to perform this action","path":["currentUser","creditsUsage"]}],
 "data":{"currentUser":{"username":"alice","creditsUsage":null}}}`;

class FakeTransport implements Transport {
    readonly gets: string[] = [];
    getReply: { status: number; body: string } | undefined;
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

    async get(uri: URL) {
        this.gets.push(uri.toString());
        return this.getReply ?? { status: 500, body: '' };
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

test("names the token's user", async () => {
    const http = new FakeTransport().reply(200, '{"data":{"currentUser":{"username":"alice"}}}');
    assert.equal(await new CreditsClient('https://gitlab.com', 't', http).username(), 'alice');
    assert.ok(http.requests[0].query.includes('username'));

    const anonymous = new FakeTransport().reply(200, '{"data":{"currentUser":null}}');
    await rejection(new CreditsClient('https://gitlab.com', 't', anonymous).username());
});

test("reads the token's scopes and expiry from the REST API", async () => {
    const http = new FakeTransport();
    http.getReply = { status: 200, body: `{"id":7,"name":"Duo Watchdog","revoked":false,"created_at":"2026-10-01T11:38:12.345Z",
        "scopes":["read_api","ai_features"],"user_id":3,"last_used_at":null,"active":true,"expires_at":"2026-12-31"}` };

    const info = await new CreditsClient('https://gitlab.example.com/gitlab/', 't', http).tokenInfo();

    assert.deepEqual(http.gets, ['https://gitlab.example.com/gitlab/api/v4/personal_access_tokens/self']);
    assert.deepEqual(info, { name: 'Duo Watchdog', scopes: ['read_api', 'ai_features'], expiresAt: '2026-12-31' });
});

test('a turned-down token carries its HTTP status', async () => {
    const http = new FakeTransport().reply(401, '{"message":"401 Unauthorized"}');
    http.getReply = { status: 401, body: '{"message":"401 Unauthorized"}' };
    const client = new CreditsClient('https://gitlab.com', 'glpat-old', http);

    const e = await rejection(client.tokenInfo());
    assert.equal(e.status, 401);
    assert.ok(e.message.includes('expired'), e.message);
    assert.equal((await rejection(client.username())).status, 401);
    assert.equal((await rejection(new CreditsClient('https://gitlab.com', 't', new FakeTransport().reply(403, '{}')).fetch('acme'))).status, 403);
    assert.equal((await rejection(new CreditsClient('https://gitlab.com', 't', { post: async () => { throw new Error('down'); } }).fetch('acme'))).status, 0);
});

test('a transport that only posts cannot read the token', async () => {
    const postOnly: Transport = { post: async () => ({ status: 200, body: '{}' }) };
    const e = await rejection(new CreditsClient('https://gitlab.com', 't', postOnly).tokenInfo());
    assert.ok(e.message.startsWith('Cannot reach gitlab.com'), e.message);
});

test('reads a year of history by month and feature', async () => {
    const http = new FakeTransport()
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"startDate":"2026-03-15","endDate":"2026-10-18",
          "dailyUsage":[{"date":"2026-03-20","creditsUsed":4},{"date":"2026-09-02","creditsUsed":10},
                        {"date":"2026-10-01","creditsUsed":6}],
          "usedFlowTypes":[{"id":"chat","title":"Duo Chat"},{"id":"review","title":"Code Review"}]}}}}`)
        .reply(200, `{"data":{"currentUser":{
          "f0":{"dailyUsage":[{"date":"2026-03-20","creditsUsed":4},{"date":"2026-10-01","creditsUsed":1}]},
          "f1":{"dailyUsage":[{"date":"2026-09-02","creditsUsed":10},{"date":"2026-10-01","creditsUsed":5}]}}}}`)
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"nonBillableUsage":[
          {"dailyUsage":[{"date":"2026-10-01","creditsUsed":2.5}]}]}}}}`);

    const h = await new CreditsClient('https://gitlab.com', 't', http).history('acme', '2025-11-01', '2026-10-18');

    assert.equal(http.requests[0].variables.startDate, '2025-11-01');
    assert.equal(http.requests[0].variables.endDate, '2026-10-18');
    assert.ok(http.requests[0].query.includes('creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate)'));
    const byFlow = http.requests[1];
    assert.ok(byFlow.query.includes('f1: creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate, flowTypes: $f1)'),
        byFlow.query);
    assert.deepEqual(byFlow.variables.f1, ['review']);
    assert.ok(http.requests[2].query.includes('nonBillableUsage'));
    assert.equal(h.start, '2026-03-15', "GitLab moves the start to the subscription's");
    const all = months(h);
    assert.equal(all.length, 8, 'March to October');
    assert.equal(all[0].credits, 4);
    assert.equal(all[1].credits, 0, 'April: nothing used');
    assert.equal(all[7].credits, 6);
    assert.deepEqual(all[7].byFlow.map(f => f.id), ['review', 'chat'], 'most credits first');
    assert.equal(all[7].byFlow[0].credits, 5);
    assert.equal(all[7].nonBillable, 2.5);
    assert.equal(total(h), 20);
    assert.equal(h.flowsError, null);
});

test('history goes month by month when GitLab fails on the year', async () => {
    const http = new FakeTransport()
        .reply(500, '')
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"startDate":"2026-10-01","endDate":"2026-10-31",
          "dailyUsage":[{"date":"2026-10-02","creditsUsed":5}],"usedFlowTypes":[]}}}}`)
        .reply(200, '{"data":{"currentUser":{"creditsUsage":{}}}}')
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"startDate":"2026-09-01","endDate":"2026-09-30",
          "dailyUsage":[{"date":"2026-09-10","creditsUsed":7}],"usedFlowTypes":[]}}}}`)
        .reply(200, '{"data":{"currentUser":{"creditsUsage":{}}}}');
    // August and July then fail too (the fake answers 500 when it runs out), and the search stops there.

    const h = await new CreditsClient('https://gitlab.com', 't', http).history('acme', '2025-11-01', '2026-10-18');

    assert.equal(total(h), 12);
    assert.equal(h.start, '2026-09-01', 'the oldest month GitLab answered');
    assert.equal(months(h).length, 2);
    assert.ok(!('startDate' in http.requests[1].variables) && !('endDate' in http.requests[1].variables),
        'the current month as the Usage view asks it, without dates');
    assert.equal(http.requests[3].variables.startDate, '2026-09-01');
    assert.equal(http.requests[3].variables.endDate, '2026-09-30');
    assert.equal(http.requests[5].variables.startDate, '2026-08-01');
    assert.equal(http.requests.length, 7, 'the year, two months with their not-billed, then August and July');
});

test('month by month stops at a refusal, and keeps the first error when no month answers', async () => {
    const refused = new FakeTransport().reply(500, '').reply(403, '{}');
    assert.equal((await rejection(new CreditsClient('https://gitlab.com', 't', refused).history('acme', '2025-11-01', '2026-10-18'))).status, 403);
    assert.equal(refused.requests.length, 2, 'a refusal is the same for every month');

    const down = new FakeTransport().reply(503, '');
    const e = await rejection(new CreditsClient('https://gitlab.com', 't', down).history('acme', '2025-11-01', '2026-10-18'));
    assert.equal(e.message, 'GitLab returned HTTP 503 from gitlab.com.');
    assert.equal(down.requests.length, 3, 'the year, then two months');

    const unreachable = new FakeTransport().reply(401, '{}');
    assert.equal((await rejection(new CreditsClient('https://gitlab.com', 't', unreachable).history('acme', '2025-11-01', '2026-10-18'))).status, 401,
        'only a server failure on the year goes month by month');

    // The first month of the range starts on its first asked day.
    const late = new FakeTransport().reply(500, '').reply(500, '')
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"dailyUsage":[{"date":"2026-09-20","creditsUsed":1}],"usedFlowTypes":[]}}}}`);
    const h = await new CreditsClient('https://gitlab.com', 't', late).history('acme', '2026-09-15', '2026-10-18');
    assert.equal(late.requests[2].variables.startDate, '2026-09-15');
    assert.equal(h.start, '2026-09-15', 'GitLab gave no start: the asked one');
    assert.equal(total(h), 1);
});

test('history stands without the features or the not billed', async () => {
    const http = new FakeTransport()
        .reply(200, `{"data":{"currentUser":{"creditsUsage":{"startDate":"2026-10-01","endDate":"2026-10-18",
          "dailyUsage":[{"date":"2026-10-01","creditsUsed":6}],
          "usedFlowTypes":[{"id":"chat","title":"Duo Chat"}]}}}}`)
        .reply(502, '')
        .reply(200, `{"errors":[{"message":"Field 'nonBillableUsage' doesn't exist on type 'CreditsUsage'"}]}`);

    const h = await new CreditsClient('https://gitlab.com', 't', http).history('acme', '2025-11-01', '2026-10-18');

    assert.equal(total(h), 6);
    assert.deepEqual(h.flows, []);
    assert.ok(h.flowsError!.includes('HTTP 502'), h.flowsError!);
    assert.equal(nonBillableTotal(h), 0);
});

test('a group GitLab gives no history for says so', async () => {
    const http = new FakeTransport().reply(200, `{"errors":[{"message":"no access"}],"data":{"currentUser":{"creditsUsage":null}}}`);
    const e = await rejection(new CreditsClient('https://gitlab.com', 't', http).history('acme', '2025-11-01', '2026-10-18'));
    assert.equal(e.message, 'GitLab returned no history for "acme": no access.');
});

test('reads the sessions page by page until older ones', async () => {
    const http = new FakeTransport()
        .reply(200, `{"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":true,"endCursor":"c1"},"nodes":[
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/10208239","title":"Review the gateway",
           "workflowDefinition":"chat","humanStatus":"Finished","createdAt":"2026-10-28T14:35:25Z",
           "updatedAt":"2026-10-28T15:08:33Z","webUrl":"https://gitlab.com/s/10208239",
           "project":{"fullPath":"acme/mobile/gateway"},"namespace":{"fullPath":"acme/mobile"},
           "modelMetadataName":"Claude Sonnet"}]}}}`)
        .reply(200, `{"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":true,"endCursor":"c2"},"nodes":[
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/7","title":null,"goal":"Fix the build\\nand more",
           "workflowDefinition":"developer/v1","humanStatus":"Running","createdAt":"2026-10-27T09:00:00Z",
           "updatedAt":"2026-10-27T10:00:00Z","project":null,"namespace":null},
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/5","title":"Old","workflowDefinition":"chat",
           "humanStatus":"Finished","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:30:00Z"}]}}}`);

    const sessions = await new CreditsClient('https://gitlab.com', 'glpat-x', http).sessions(Date.parse('2026-10-20T00:00:00Z'));

    assert.equal(http.requests.length, 2, 'stops at the page that reaches before the range');
    assert.equal(http.requests[0].variables.updatedAfter, '2026-10-20T00:00:00Z');
    assert.equal(http.requests[1].variables.after, 'c1');
    assert.deepEqual(sessions.map(s => s.id), [10208239, 7]);
    const first = sessions[0];
    assert.equal(first.title, 'Review the gateway');
    assert.equal(first.project, 'acme/mobile/gateway');
    assert.equal(first.model, 'Claude Sonnet');
    assert.equal(first.created, Date.parse('2026-10-28T14:35:25Z'));
    assert.equal(sessions[1].title, 'Fix the build', "the goal's first line, when it has no title");
    assert.equal(sessions[1].project, null);
});

test('an older GitLab is asked for sessions without the model and the date filter', async () => {
    const http = new FakeTransport()
        .reply(200, `{"errors":[{"message":"Field 'modelMetadataName' doesn't exist on type 'DuoWorkflow'"}]}`)
        .reply(200, `{"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/9","title":"New","createdAt":"2026-10-28T09:00:00Z",
           "updatedAt":"2026-10-28T09:30:00Z"},
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/8","title":"Old","createdAt":"2026-10-01T09:00:00Z",
           "updatedAt":"2026-10-01T09:30:00Z"}]}}}`);

    const sessions = await new CreditsClient('https://gitlab.example.com', 'glpat-x', http).sessions(Date.parse('2026-10-20T00:00:00Z'));

    const retried = http.requests[1].query;
    assert.ok(!retried.includes('modelMetadataName'));
    assert.ok(!retried.includes('updatedAfter'));
    assert.deepEqual(sessions.map(s => s.id), [9]);
});

test('finds a session by its number', async () => {
    const http = new FakeTransport().reply(200, `{"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[
          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/42","title":"Found","createdAt":"2026-10-28T09:00:00Z",
           "updatedAt":"2026-10-28T09:30:00Z"}]}}}`)
        .reply(200, '{"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[]}}}');
    const client = new CreditsClient('https://gitlab.com', 'glpat-x', http);

    assert.equal((await client.session(42))!.title, 'Found');
    assert.equal(http.requests[0].variables.workflowId, 'gid://gitlab/Ai::DuoWorkflows::Workflow/42');
    assert.equal(await client.session(43), null);
});
