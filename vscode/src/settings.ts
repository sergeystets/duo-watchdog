import * as vscode from 'vscode';
import { DEFAULT_PERCENTS, percentsOrDefault } from './core/alert';

export const SECTION = 'duoWatchdog';
/** The token's key in VS Code's secret storage (the OS keychain), never in settings.json. */
export const TOKEN_KEY = 'duoWatchdog.token';
/** The key of the last notice shown, so it is not repeated on every refresh. */
export const LAST_ALERT_KEY = 'duoWatchdog.lastAlertKey';

export interface Settings {
    gitlabUrl: string;
    /** The top-level group that owns the Duo subscription, as typed: normalised with groupPath() before use. */
    groupPath: string;
    /** The credits the user means to stay within: their GitLab cap, or a lower budget of their own. */
    limit: number;
    alertPercents: string;
    percents: number[];
    forecastAlert: boolean;
    refreshMinutes: number;
}

export function settings(): Settings {
    const c = vscode.workspace.getConfiguration(SECTION);
    const alertPercents = c.get<string>('alertPercents', DEFAULT_PERCENTS);
    return {
        gitlabUrl: c.get<string>('gitlabUrl', 'https://gitlab.com'),
        groupPath: c.get<string>('groupPath', ''),
        limit: c.get<number>('limit', 100),
        alertPercents,
        percents: percentsOrDefault(alertPercents),
        forecastAlert: c.get<boolean>('forecastAlert', true),
        refreshMinutes: Math.max(1, c.get<number>('refreshMinutes', 15)),
    };
}
