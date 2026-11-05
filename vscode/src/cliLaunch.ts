import * as vscode from 'vscode';
import {
    cliCommand, commandProblem, commandText, MAX_QUICK_COMMANDS, nameProblem, QuickCommand, suggestedName,
} from './core/cliLaunch';
import { LiveSessionWatcher } from './liveSession';
import { log } from './log';
import { SECTION, settings } from './settings';

/** How long the CLI gets to start its log; after that what an action types is given up. */
const START_MS = 60_000;
/** From the CLI's log appearing to its prompt taking what is typed. */
const SETTLE_MS = 2_500;
/** Between the typed command and its Enter, so the prompt takes them as typing rather than one paste. */
const ENTER_MS = 300;

/**
 * New session, Doctor and the quick commands of your own: the Duo CLI in a new terminal in the workspace's first
 * folder, started as the settings say (`duo` or `glab duo cli`, asking before tools or not). A command is typed once the
 * CLI has started a log of its own, which the session watcher sees within its two-second poll. When none comes within a
 * minute nothing is typed, so a CLI that did not start never gets the command typed into the shell.
 * @param typed what to type at the CLI's prompt; null to only start it
 * @param name the button's name, for the terminal's; null for New session
 */
export async function launchCli(live: LiveSessionWatcher, typed: string | null, name: string | null): Promise<void> {
    const s = settings();
    const command = cliCommand(s.cli, s.cliMode);
    const before = new Set(live.logs());
    const terminal = vscode.window.createTerminal({
        name: name != null ? 'Duo CLI · ' + name : 'Duo CLI',
        cwd: vscode.workspace.workspaceFolders?.[0]?.uri,
        iconPath: new vscode.ThemeIcon('terminal'),
    });
    terminal.show();
    terminal.sendText(command);
    log.info('Duo CLI: ran ' + command + ' in a new terminal' + (typed != null ? ', to type ' + typed : ''));
    if (typed == null) {
        return;
    }
    const started = await live.waitForNewLog(before, START_MS);
    if (started == null) {
        log.warn('Duo CLI: no new log within a minute, so ' + typed + ' was not typed');
        void vscode.window.showWarningMessage('Duo Watchdog: the Duo CLI did not start within a minute, so ' + typed
            + ' was not typed. Once it shows its prompt, type it there.');
        return;
    }
    await delay(SETTLE_MS);
    if (terminal.exitStatus !== undefined) {
        return;
    }
    terminal.sendText(typed, false);
    await delay(ENTER_MS);
    terminal.sendText('', true);
}

/** Duo Watchdog: Run a Duo CLI Quick Command: one of your own, picked from a list. */
export async function runQuickCommand(live: LiveSessionWatcher): Promise<void> {
    const commands = settings().quickCommands;
    if (commands.length === 0) {
        if (await vscode.window.showInformationMessage('Duo Watchdog: no quick commands yet.', 'Add One…') != null) {
            await addQuickCommand();
        }
        return;
    }
    const picked = await vscode.window.showQuickPick(
        commands.map(c => ({ label: c.name, description: c.command, quick: c })),
        { title: 'Run a Duo CLI Quick Command', placeHolder: 'Starts the Duo CLI in a new terminal, then types the command' });
    if (picked != null) {
        await launchCli(live, picked.quick.command, picked.quick.name);
    }
}

/** Add quick command: what it types first, such as /ab-testing, then its button's name, suggested from it. */
export async function addQuickCommand(): Promise<void> {
    const commands = settings().quickCommands;
    if (commands.length >= MAX_QUICK_COMMANDS) {
        void vscode.window.showWarningMessage('Duo Watchdog: at most ' + MAX_QUICK_COMMANDS
            + ' quick commands. Edit or remove one first.');
        return;
    }
    const made = await askQuickCommand(commands, -1);
    if (made != null) {
        await saveQuickCommands([...commands, made]);
    }
}

interface CommandItem extends vscode.QuickPickItem {
    /** The command's index; -1 for Add. */
    index: number;
}

const MOVE_UP: vscode.QuickInputButton = { iconPath: new vscode.ThemeIcon('arrow-up'), tooltip: 'Move up' };
const REMOVE: vscode.QuickInputButton = { iconPath: new vscode.ThemeIcon('trash'), tooltip: 'Remove' };

/** Edit quick commands: pick one to change it; its buttons move it up or remove it; the last item adds one. */
export function editQuickCommands(): void {
    const pick = vscode.window.createQuickPick<CommandItem>();
    pick.title = 'Duo CLI Quick Commands';
    pick.placeholder = 'Pick one to change it';
    const render = () => {
        const commands = settings().quickCommands;
        pick.items = [
            ...commands.map((c, index): CommandItem => ({
                label: c.name, description: c.command, index, buttons: index > 0 ? [MOVE_UP, REMOVE] : [REMOVE],
            })),
            ...(commands.length < MAX_QUICK_COMMANDS ? [{ label: '$(add) Add a quick command…', index: -1 }] : []),
        ];
    };
    render();
    pick.onDidTriggerItemButton(async e => {
        const commands = [...settings().quickCommands];
        const i = e.item.index;
        if (e.button === REMOVE) {
            commands.splice(i, 1);
        } else if (i > 0) {
            [commands[i - 1], commands[i]] = [commands[i], commands[i - 1]];
        }
        await saveQuickCommands(commands);
        render();
    });
    pick.onDidAccept(async () => {
        const item = pick.selectedItems[0];
        pick.hide();
        if (item == null) {
            return;
        }
        if (item.index < 0) {
            await addQuickCommand();
            return;
        }
        const commands = settings().quickCommands;
        const changed = await askQuickCommand(commands, item.index);
        if (changed != null) {
            await saveQuickCommands(commands.map((c, i) => i === item.index ? changed : c));
        }
    });
    pick.onDidHide(() => pick.dispose());
    pick.show();
}

/** Asks for a quick command's command, then its name; undefined when either is cancelled. */
async function askQuickCommand(commands: readonly QuickCommand[], editing: number): Promise<QuickCommand | undefined> {
    const old = editing >= 0 ? commands[editing] : undefined;
    const title = old != null ? 'Edit ' + old.name : 'Add a Duo CLI Quick Command';
    const typed = await vscode.window.showInputBox({
        title: title + ' (1/2)',
        prompt: "What it types at the Duo CLI's prompt once the CLI has started: a slash command, or a prompt",
        placeHolder: '/ab-testing',
        value: old?.command ?? '/',
        valueSelection: old != null ? undefined : [1, 1],
        ignoreFocusOut: true,
        validateInput: value => commandProblem(value),
    });
    if (typed === undefined) {
        return undefined;
    }
    const command = commandText(typed)!;
    const name = await vscode.window.showInputBox({
        title: title + ' (2/2)',
        prompt: "Its button's name on Usage",
        placeHolder: 'AB Testing',
        value: old?.name ?? suggestedName(command),
        ignoreFocusOut: true,
        validateInput: value => nameProblem(value, commands, editing),
    });
    return name === undefined ? undefined : { name: name.trim(), command };
}

function saveQuickCommands(commands: readonly QuickCommand[]): Thenable<void> {
    return vscode.workspace.getConfiguration(SECTION).update('quickCommands', commands, vscode.ConfigurationTarget.Global);
}

function delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}
