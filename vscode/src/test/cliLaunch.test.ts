import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import { test } from 'node:test';
import {
    CLI_MODES, cliCommand, cliModeOrDefault, cliOrDefault, CLIS, commandProblem, commandText, launchTitle,
    MAX_QUICK_COMMANDS, nameProblem, newLog, QuickCommand, quickCommandsOrNone, quickView, suggestedName,
} from '../core/cliLaunch';

const AB: QuickCommand = { name: 'AB Testing', command: '/ab-testing' };

test('the CLI starts on its own or through glab, asking before tools unless auto-approve is picked', () => {
    assert.equal(cliCommand('duo', 'ask'), 'duo');
    assert.equal(cliCommand('duo', 'autoApprove'), 'duo --dangerously-skip-permissions');
    assert.equal(cliCommand('glab duo cli', 'ask'), 'glab duo cli');
    assert.equal(cliCommand('glab duo cli', 'autoApprove'), 'glab duo cli --dangerously-skip-permissions');
});

test('a setting that is none of the choices starts duo, asking', () => {
    assert.equal(cliOrDefault('glab duo cli'), 'glab duo cli');
    assert.equal(cliOrDefault('duo; rm -rf ~'), 'duo');
    assert.equal(cliOrDefault(undefined), 'duo');
    assert.equal(cliModeOrDefault('autoApprove'), 'autoApprove');
    assert.equal(cliModeOrDefault('yolo'), 'ask');
    assert.equal(cliModeOrDefault(null), 'ask');
});

test("a quick command's command is one line: a slash command or a prompt", () => {
    assert.equal(commandText('/ab-testing'), '/ab-testing');
    assert.equal(commandText('  /ab-testing \r\n'), '/ab-testing');
    assert.equal(commandText('review the open merge request'), 'review the open merge request');
    assert.equal(commandText(''), null);
    assert.equal(commandText('/'), null);
    assert.equal(commandText('/ab\nrm -rf ~'), null);
    assert.equal(commandText('/a\rb'), null);
    assert.equal(commandText('/ab\u001b[A'), null);
    assert.equal(commandText('/ab\u001b'), null);
    assert.equal(commandText('/' + 'a'.repeat(200)), null);
    assert.equal(commandText(undefined), null);
    assert.equal(commandProblem('/'), "Type what it types at the Duo CLI's prompt, such as /ab-testing");
    assert.equal(commandProblem('a\nb'), 'One line, with no control characters');
});

test('a name is one line, not taken, though the command edited may keep its own', () => {
    assert.equal(nameProblem('AB Testing', []), null);
    assert.equal(nameProblem(' ', []), 'Give its button a name, such as AB Testing');
    assert.equal(nameProblem('a'.repeat(41), []), 'At most 40 characters');
    assert.equal(nameProblem('ab testing', [AB]), 'There is a button named ab testing already');
    assert.equal(nameProblem('Doctor', []), 'There is a button named Doctor already');
    assert.equal(nameProblem('ab testing', [AB], 0), null);
});

test('the name starts from the command', () => {
    assert.equal(suggestedName('/ab-testing'), 'Ab Testing');
    assert.equal(suggestedName('/review_mr now'), 'Review Mr');
    assert.equal(suggestedName('review my changes'), 'Review my changes');
    assert.equal(suggestedName('x'.repeat(60)).length, 40);
});

test('a setting keeps the quick commands that can be: each name once, at most twelve', () => {
    assert.deepEqual(quickCommandsOrNone(undefined), []);
    assert.deepEqual(quickCommandsOrNone('nope'), []);
    assert.deepEqual(quickCommandsOrNone([
        { name: ' AB Testing ', command: ' /ab-testing ' }, { name: 'ab testing', command: '/other' },
        { name: 'Two lines', command: 'a\nb' }, { name: 'No command' }, null, 'text',
    ]), [AB]);
    const many = Array.from({ length: 20 }, (_, i) => ({ name: 'C' + i, command: '/c' + i }));
    assert.equal(quickCommandsOrNone(many).length, MAX_QUICK_COMMANDS);
});

test("the run's log is the first one not there before", () => {
    const before = new Set(['duo-cli-log-2026-11-05T09-00-00-a.log', 'duo-cli-log-2026-11-05T08-00-00-a.log']);
    assert.equal(newLog(before, [...before]), null);
    assert.equal(newLog(before, []), null);
    assert.equal(newLog(before, ['duo-cli-log-2026-11-05T10-00-00-a.log', ...before]), 'duo-cli-log-2026-11-05T10-00-00-a.log');
});

test('Usage shows the choices made, New session, Doctor and the quick commands, each saying what it runs', () => {
    const q = quickView('glab duo cli', 'autoApprove', [AB]);
    assert.equal(q.cli, 'glab duo cli');
    assert.equal(q.mode, 'autoApprove');
    assert.deepEqual(q.modes.map(m => m.label), ['Ask before tools', 'Auto-approve tools']);
    assert.deepEqual(q.buttons.map(b => b.label), ['New session', 'Doctor', 'AB Testing']);
    assert.deepEqual(q.buttons[2], {
        kind: 'command', index: 0, label: 'AB Testing',
        title: 'Runs glab duo cli --dangerously-skip-permissions in a new terminal, then types /ab-testing once it has started',
    });
    assert.equal(q.buttons[0].title, 'Runs glab duo cli --dangerously-skip-permissions in a new terminal');
    assert.equal(q.canAdd, true);
    const full = Array.from({ length: MAX_QUICK_COMMANDS }, (_, i) => ({ name: 'C' + i, command: '/c' + i }));
    assert.equal(quickView('duo', 'ask', full).canAdd, false);
    assert.equal(launchTitle('duo', 'ask', '/doctor'), 'Runs duo in a new terminal, then types /doctor once it has started');
});

test("the settings offer the same choices, and only the user's settings count", async () => {
    const manifest = JSON.parse(await fs.readFile(path.join(__dirname, '..', '..', 'package.json'), 'utf8'));
    const props = manifest.contributes.configuration.properties;
    assert.deepEqual(props['duoWatchdog.cli'].enum, [...CLIS]);
    assert.deepEqual(props['duoWatchdog.cliMode'].enum, [...CLI_MODES]);
    assert.equal(props['duoWatchdog.cliMode'].default, 'ask');
    assert.deepEqual(props['duoWatchdog.quickCommands'].default, []);
    assert.equal(props['duoWatchdog.quickCommands'].maxItems, MAX_QUICK_COMMANDS);
    for (const key of ['duoWatchdog.cli', 'duoWatchdog.cliMode', 'duoWatchdog.quickCommands']) {
        assert.equal(props[key].scope, 'application', key);
    }
    const commands = manifest.contributes.commands.map((c: { command: string }) => c.command);
    for (const command of ['duoWatchdog.cliSession', 'duoWatchdog.cliDoctor', 'duoWatchdog.runQuickCommand',
        'duoWatchdog.addQuickCommand', 'duoWatchdog.editQuickCommands']) {
        assert.ok(commands.includes(command), command);
    }
});
