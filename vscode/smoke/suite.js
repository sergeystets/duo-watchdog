// Runs inside VS Code (see run.js): the extension activates, every command it declares is there, and its views and
// pages open. With no account set up it asks GitLab nothing.
'use strict';

const assert = require('assert');
const vscode = require('vscode');

const ID = 'local.duo-watchdog';

async function check(name, test) {
    await test();
    console.log(`  ✓ ${name}`);
}

exports.run = async function run() {
    const extension = vscode.extensions.getExtension(ID);
    assert.ok(extension, `${ID} is loaded`);

    await check('it activates', async () => {
        await extension.activate();
        assert.ok(extension.isActive, 'active after activate()');
    });

    await check('it is the version packaged', async () => {
        assert.strictEqual(extension.packageJSON.version, process.env.DUO_WATCHDOG_SMOKE_VERSION);
    });

    await check('every command it declares is registered', async () => {
        const registered = new Set(await vscode.commands.getCommands(true));
        const declared = extension.packageJSON.contributes.commands.map(c => c.command);
        const missing = declared.filter(c => !registered.has(c));
        assert.deepStrictEqual(missing, [], 'declared in package.json but not registered');
    });

    await check('the dashboard opens', async () => {
        await vscode.commands.executeCommand('duoWatchdog.openDashboard');
    });

    await check("What's New opens", async () => {
        await vscode.commands.executeCommand('duoWatchdog.whatsNew');
        const titles = vscode.window.tabGroups.all.flatMap(group => group.tabs.map(tab => tab.label));
        assert.ok(titles.includes("What's New in Duo Watchdog"), `tabs: ${titles.join(', ')}`);
    });

    await check('its settings are there with their defaults', async () => {
        const config = vscode.workspace.getConfiguration('duoWatchdog');
        assert.strictEqual(config.get('checkForUpdates'), true);
        assert.strictEqual(config.get('cliMode'), 'ask');
        assert.strictEqual(config.get('refreshMinutes'), 15);
    });
};
