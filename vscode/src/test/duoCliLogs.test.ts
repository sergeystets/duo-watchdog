import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
    addSteps, isLog, logFolder, logFolders, offsetZone, parseLogs, readLog, wslDistro, writeLogs,
} from '../core/duoCliLogs';
import { Steps } from '../core/sessions';

const NAME = 'duo-cli-log-2026-10-28T14-35-25-dana-projects-mobile-gateway-service.log';

/** As the CLI writes it: steps, a repeated one, other lines, and code the agent read, which says nothing. */
const LOG = `2026-10-28T15:01:16:072 [debug]: fetch: request for https://gitlab.com/api/v4/usage_data/track_event made with https agent.
2026-10-28T15:02:14:753 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f-03ee-4707-b87b-7900113239dd] Action received: {"requestID":"28fd7
    for sheet,name in (('xl/worksheets/sheet1.xml','Action received'),):
2026-10-28T15:02:14:900 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f-03ee-4707-b87b-7900113239dd] Action received: {"requestID":"28fd7
2026-10-28T15:08:33:391 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38-f540-4ec3-b59f-304472f3a0ad] Action received: {"requestID":"c52c0
2026-10-28T23:30:00:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: d1] Action received: {}
2026-10-28T15:09:00:000 [debug]: [DuoWorkflowNodeExecutor][10208240] [requestID: e1] Action received: {}
2026-10-28T15:09:01:000 [debug]: [DuoWorkflowNodeExecutor][10208240] [requestID: e1] Action completed
`;

/** Chicago in late October: five hours behind UTC. */
const CHICAGO = offsetZone(-5);

test("counts each session's steps by UTC day", () => {
    const steps = readLog(LOG, NAME, CHICAGO);

    assert.deepEqual(steps.get(10208239)!.byDay, { '2026-10-28': 2, '2026-10-29': 1 },
        'a repeated request once; 23:30 in Chicago is the next day in UTC');
    assert.equal(Object.values(steps.get(10208240)!.byDay).reduce((a, b) => a + b, 0), 1);
    assert.equal(steps.get(10208239)!.folder, 'dana-projects-mobile-gateway-service');
});

test("knows the CLI's logs by name", () => {
    assert.equal(isLog(NAME), true);
    assert.equal(isLog('other.log'), false);
    assert.equal(logFolder('duo-cli-log-x.log'), '');
});

test('adds up the steps of a session resumed in another run', () => {
    const day = '2026-10-28';
    const a = new Map<number, Steps>([[1, { id: 1, byDay: { [day]: 3 }, folder: 'x' }]]);
    const b = new Map<number, Steps>([[1, { id: 1, byDay: { [day]: 2 }, folder: 'y' }], [2, { id: 2, byDay: { [day]: 1 }, folder: 'y' }]]);

    const sum = addSteps(a, b);

    assert.deepEqual(sum.get(1)!.byDay, { [day]: 5 });
    assert.equal(sum.get(1)!.folder, 'x');
    assert.deepEqual(sum.get(2)!.byDay, { [day]: 1 });
    assert.deepEqual(a.get(1)!.byDay, { [day]: 3 }, 'the first is left as it was');
});

test('keeps what was read across runs', () => {
    const steps = readLog(LOG, NAME, offsetZone(0));
    const json = writeLogs(new Map([[NAME, { size: 13684, modified: 1761662125000, steps }]]));

    const back = parseLogs(json).get(NAME)!;

    assert.equal(back.size, 13684);
    assert.equal(back.modified, 1761662125000);
    assert.deepEqual(back.steps, steps);
    assert.ok(!json.includes('sheet1'), 'nothing but counts is kept');
    assert.ok(!json.includes('28fd775f'), 'not even the request IDs');
    assert.equal(parseLogs('not json').size, 0);
    assert.equal(parseLogs('{"x":{"size":"1","modified":2,"sessions":{}}}').size, 0);
});

test("the CLI's logs are looked for in the temporary folders, and in WSL's from Windows", () => {
    assert.deepEqual(logFolders('darwin', '/var/folders/x/T', []).map(f => f.paths),
        [['/var/folders/x/T/gitlab-duo-cli'], ['/tmp/gitlab-duo-cli']]);
    assert.deepEqual(logFolders('linux', '/tmp', []).map(f => f.paths), [['/tmp/gitlab-duo-cli']], 'once');
    assert.deepEqual(logFolders('win32', 'C:\\Users\\dana\\AppData\\Local\\Temp', ['Ubuntu', 'Ubuntu']).map(f => f.paths), [
        ['C:\\Users\\dana\\AppData\\Local\\Temp\\gitlab-duo-cli'],
        ['\\\\wsl.localhost\\Ubuntu\\tmp\\gitlab-duo-cli', '\\\\wsl$\\Ubuntu\\tmp\\gitlab-duo-cli'],
    ]);
    assert.equal(wslDistro('\\\\wsl.localhost\\Ubuntu-22.04\\home\\dana\\gateway'), 'Ubuntu-22.04');
    assert.equal(wslDistro('\\\\WSL$\\Debian\\home'), 'Debian');
    assert.equal(wslDistro('C:\\Users\\dana'), null);
});
