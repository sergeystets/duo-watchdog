/**
 * The quick actions on Usage: the Duo CLI started in a new terminal, as `duo` or as `glab duo cli` (which hands its
 * arguments to `duo`), asking before each tool or not. New session only starts it; Doctor then types `/doctor` at its
 * prompt, and each quick command of your own what you gave it, such as `/ab-testing`. Nothing is typed until the CLI
 * has started a log of its own, so a CLI that does not start never gets the command typed into the shell. A port of
 * CliLaunch in the plugin's core.
 */

/** How the CLI is started: on its own, or through glab. */
export type Cli = 'duo' | 'glab duo cli';
export const CLIS: readonly Cli[] = ['duo', 'glab duo cli'];

/** Whether the CLI asks before it runs a tool. */
export type CliMode = 'ask' | 'autoApprove';
export const CLI_MODES: readonly CliMode[] = ['ask', 'autoApprove'];

/** A quick command of your own: its button's name, and what it types at the CLI's prompt once the CLI has started. */
export interface QuickCommand {
    name: string;
    command: string;
}

/** The CLI's flag that runs every tool without asking. */
export const SKIP_PERMISSIONS = '--dangerously-skip-permissions';
/** The CLI's diagnostics, as typed at its prompt. */
export const DOCTOR = '/doctor';
/** Quick commands of your own, at most. */
export const MAX_QUICK_COMMANDS = 12;
const MAX_NAME = 40;
const MAX_COMMAND = 200;

const MODE_LABELS: Record<CliMode, string> = { ask: 'Manual', autoApprove: 'Auto' };

export function cliOrDefault(value: unknown): Cli {
    return CLIS.includes(value as Cli) ? value as Cli : 'duo';
}

export function cliModeOrDefault(value: unknown): CliMode {
    return CLI_MODES.includes(value as CliMode) ? value as CliMode : 'ask';
}

/** "duo", or "glab duo cli --dangerously-skip-permissions". */
export function cliCommand(cli: Cli, mode: CliMode): string {
    return cli + (mode === 'autoApprove' ? ' ' + SKIP_PERMISSIONS : '');
}

/** The text without the spaces and line ends around it. */
function trimmed(value: string | null | undefined): string {
    return (value ?? '').replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '');
}

function hasControl(text: string): boolean {
    return /[\x00-\x1f\x7f]/.test(text);
}

/** Why the text cannot be a quick command's command; null when it can. */
export function commandProblem(value: string | null | undefined): string | null {
    const text = trimmed(value);
    return text === '' || text === '/' ? 'Type what it types at the Duo CLI\'s prompt, such as /ab-testing'
        : text.length > MAX_COMMAND ? 'At most ' + MAX_COMMAND + ' characters'
            : hasControl(text) ? 'One line, with no control characters'
                : null;
}

/** "/ab-testing" from " /ab-testing "; null when it cannot be a command. */
export function commandText(value: string | null | undefined): string | null {
    return commandProblem(value) == null ? trimmed(value) : null;
}

/**
 * Why the text cannot name a quick command; null when it can.
 * @param editing the index of the command being edited, which may keep its name; -1 for a new one
 */
export function nameProblem(value: string | null | undefined, commands: readonly QuickCommand[], editing = -1): string | null {
    const text = trimmed(value);
    if (text === '') {
        return 'Give its button a name, such as AB Testing';
    }
    if (text.length > MAX_NAME) {
        return 'At most ' + MAX_NAME + ' characters';
    }
    if (hasControl(text)) {
        return 'One line, with no control characters';
    }
    const taken = commands.findIndex(c => c.name.toLowerCase() === text.toLowerCase());
    return taken >= 0 && taken !== editing || ['new session', 'doctor'].includes(text.toLowerCase())
        ? 'There is a button named ' + text + ' already' : null;
}

/** "Ab Testing" from "/ab-testing", "Review my changes" from "review my changes": a name to start from. */
export function suggestedName(command: string): string {
    const text = trimmed(command);
    const words = text.startsWith('/')
        ? text.slice(1).split(/\s/)[0].split(/[-_.:]+/).filter(w => w !== '')
        : [text];
    const name = words.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
    return name.length > MAX_NAME ? name.slice(0, MAX_NAME - 1).trimEnd() + '…' : name;
}

/** The quick commands a setting holds: those with a name and a command that can be, each name once, at most 12. */
export function quickCommandsOrNone(value: unknown): QuickCommand[] {
    const commands: QuickCommand[] = [];
    for (const item of Array.isArray(value) ? value : []) {
        const name = typeof item?.name === 'string' ? item.name : null;
        const command = typeof item?.command === 'string' ? commandText(item.command) : null;
        if (name != null && command != null && commands.length < MAX_QUICK_COMMANDS && nameProblem(name, commands) == null) {
            commands.push({ name: trimmed(name), command });
        }
    }
    return commands;
}

/** The log of a run started since `before` was taken: the first log not in it; null while there is none. */
export function newLog(before: ReadonlySet<string>, now: readonly string[]): string | null {
    return now.find(name => !before.has(name)) ?? null;
}

/** "Runs duo in a new terminal, then types /doctor once it has started": what a button does, for its tooltip. */
export function launchTitle(cli: Cli, mode: CliMode, typed: string | null): string {
    return 'Runs ' + cliCommand(cli, mode) + ' in a new terminal' + (typed != null ? ', then types ' + typed + ' once it has started' : '');
}

/** A button: New session, Doctor, or a quick command of your own, by its index. */
export type QuickButton =
    | { kind: 'session' | 'doctor'; label: string; title: string }
    | { kind: 'command'; index: number; label: string; title: string };

/** The quick actions as Usage shows them: the two choices, the buttons, and whether another command may be added. */
export interface QuickView {
    cli: Cli;
    mode: CliMode;
    clis: { value: Cli; label: string }[];
    modes: { value: CliMode; label: string; title: string }[];
    buttons: QuickButton[];
    canAdd: boolean;
}

export function quickView(cli: Cli, mode: CliMode, commands: readonly QuickCommand[]): QuickView {
    return {
        cli, mode,
        clis: CLIS.map(value => ({ value, label: value })),
        modes: CLI_MODES.map(value => ({
            value, label: MODE_LABELS[value],
            title: value === 'ask' ? 'Manual: the CLI asks before it runs each tool'
                : 'Auto, with ' + SKIP_PERMISSIONS + ': the CLI runs every tool without asking',
        })),
        buttons: [
            { kind: 'session', label: 'New session', title: launchTitle(cli, mode, null) },
            { kind: 'doctor', label: 'Doctor', title: launchTitle(cli, mode, DOCTOR) },
            ...commands.map((c, index): QuickButton => ({ kind: 'command', index, label: c.name, title: launchTitle(cli, mode, c.command) })),
        ],
        canAdd: commands.length < MAX_QUICK_COMMANDS,
    };
}
