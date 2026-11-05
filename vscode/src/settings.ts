import * as vscode from 'vscode';
import { DEFAULT_LIMIT, OldSettings } from './core/accounts';
import { DEFAULT_PERCENTS, percentsOrDefault } from './core/alert';
import { Cli, cliModeOrDefault, CliMode, cliOrDefault, QuickCommand, quickCommandsOrNone } from './core/cliLaunch';
import { RECOMMENDED_ROUND_TRIPS } from './core/liveSession';
import { ASSUMED_MODEL, assumedModel } from './core/modelCredits';

export const SECTION = 'duoWatchdog';
/**
 * Where the one token was kept before accounts, in VS Code's secret storage (the OS keychain). It is moved to an
 * account once GitLab says whose it is.
 */
export const OLD_TOKEN_KEY = 'duoWatchdog.token';
/** The key of the last notice shown before accounts; it moves to the account too. */
export const OLD_LAST_ALERT_KEY = 'duoWatchdog.lastAlertKey';

/** The settings every account shares; the group path and the limit belong to each account. */
export interface Settings {
    /** The GitLab address a new token is for, unless another is typed. */
    gitlabUrl: string;
    alertPercents: string;
    percents: number[];
    forecastAlert: boolean;
    refreshMinutes: number;
    /** Have GitLab rotate a token a week before it expires. */
    rotateTokens: boolean;
    /** The round trips per Duo CLI session recommended at most; 0 for no colour. */
    roundTripLimit: number;
    /** The model a Duo CLI step is priced as when the log names none or one with no known rate: the group's default. */
    groupDefaultModel: string;
    /** How Usage's quick actions start the Duo CLI: `duo`, or `glab duo cli`. */
    cli: Cli;
    /** Whether the Duo CLI they start asks before each tool, or runs them all with --dangerously-skip-permissions. */
    cliMode: CliMode;
    /** Quick commands of your own: a button each, that types its command at the Duo CLI's prompt. */
    quickCommands: QuickCommand[];
}

export function settings(): Settings {
    const c = vscode.workspace.getConfiguration(SECTION);
    const alertPercents = c.get<string>('alertPercents', DEFAULT_PERCENTS);
    return {
        gitlabUrl: c.get<string>('gitlabUrl', 'https://gitlab.com'),
        alertPercents,
        percents: percentsOrDefault(alertPercents),
        forecastAlert: c.get<boolean>('forecastAlert', true),
        refreshMinutes: Math.max(1, c.get<number>('refreshMinutes', 15)),
        rotateTokens: c.get<boolean>('rotateTokens', true),
        roundTripLimit: Math.max(0, c.get<number>('roundTripLimit', RECOMMENDED_ROUND_TRIPS)),
        groupDefaultModel: assumedModel(c.get<string>('groupDefaultModel', ASSUMED_MODEL)),
        cli: cliOrDefault(c.get<string>('cli')),
        cliMode: cliModeOrDefault(c.get<string>('cliMode')),
        quickCommands: quickCommandsOrNone(c.get<unknown>('quickCommands')),
    };
}

/** The settings of the one account before accounts, read once to set up the first account. */
export function oldSettings(context: vscode.ExtensionContext): OldSettings {
    const c = vscode.workspace.getConfiguration(SECTION);
    return {
        gitlabUrl: c.get<string>('gitlabUrl', 'https://gitlab.com'),
        groupPath: c.get<string>('groupPath', ''),
        limit: c.get<number>('limit', DEFAULT_LIMIT),
        lastAlertKey: context.globalState.get<string>(OLD_LAST_ALERT_KEY) ?? null,
    };
}
