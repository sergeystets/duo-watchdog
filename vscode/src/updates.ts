import * as https from 'https';
import * as vscode from 'vscode';
import {
    assetOf, availableText, downloadProblem, due, isNewer, LATEST, parseRelease, Release, RELEASES, trusted,
} from './core/updates';
import { log } from './log';
import { settings } from './settings';

const LAST_CHECK_KEY = 'duoWatchdog.lastUpdateCheck';
const SKIPPED_KEY = 'duoWatchdog.skippedUpdate';
/** Shown to the command palette's Install Update and the dashboard's button. */
const CONTEXT_KEY = 'duoWatchdog.updateAvailable';
const FIRST_MS = 20_000;
/** How often it looks whether a day has passed since it last asked. */
const LOOK_MS = 60 * 60 * 1000;
const MAX_REDIRECTS = 5;

/** The dashboard's toolbar button for a newer release: "Update to 0.7.0", then "Reload to Update". */
export interface UpdateButton {
    label: string;
    title: string;
}

/**
 * Duo Watchdog's own updates, from its releases on GitHub. Once a VS Code window run and then once a day it asks for
 * the latest release; a newer one is said in a notice and on the dashboard's toolbar. Update downloads the release's
 * .vsix, checks it against the release (its size, and its SHA-256 when GitHub gives one), and installs it with VS Code's
 * own Install from VSIX; Reload Window loads it.
 */
export class Updater implements vscode.Disposable {

    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChange = this.changed.event;
    private readonly timers: ReturnType<typeof setTimeout>[] = [];
    /** The versions said in a notice this run, so the hourly look does not say them again. */
    private readonly told = new Set<string>();
    private askedThisRun = false;
    private busy = false;
    /** A newer release with a .vsix, as last asked; null when there is none. */
    private latest: Release | null = null;
    /** The version installed and waiting for a reload; null before an Update. */
    private installed: string | null = null;

    constructor(private readonly context: vscode.ExtensionContext) {
    }

    private get current(): string {
        return String(this.context.extension.packageJSON.version ?? '');
    }

    /** Asks soon after start, and then looks every hour whether a day has passed. */
    start(): void {
        this.timers.push(setTimeout(() => void this.checkIfDue(), FIRST_MS));
        this.timers.push(setInterval(() => void this.checkIfDue(), LOOK_MS));
    }

    dispose(): void {
        this.timers.forEach(t => clearTimeout(t));
        this.changed.dispose();
    }

    button(): UpdateButton | null {
        if (this.installed != null) {
            return { label: 'Reload to Update', title: 'Reload the window to use Duo Watchdog ' + this.installed };
        }
        return this.latest == null ? null : {
            label: 'Update to ' + this.latest.version,
            title: 'Download and install Duo Watchdog ' + this.latest.version + '; you have ' + this.current,
        };
    }

    /** Once a run, so a release found in the last one is offered again, then once a day; never when turned off. */
    private async checkIfDue(): Promise<void> {
        if (!settings().checkForUpdates || this.installed != null) {
            return;
        }
        if (!this.askedThisRun || due(this.context.globalState.get<number>(LAST_CHECK_KEY), Date.now())) {
            this.askedThisRun = true;
            await this.check(false);
        }
    }

    /**
     * Asks GitHub for the latest release. Manual (Check for Updates) says what it found even when nothing is new or
     * something went wrong, and offers a version that Skip This Version passed over.
     */
    async check(manual: boolean): Promise<void> {
        if (this.installed != null) {
            if (manual) {
                await this.askReload(this.installed);
            }
            return;
        }
        if (this.busy) {
            return;
        }
        this.busy = true;
        let found: Release | null = null;
        let hasVsix = false;
        const current = this.current;
        try {
            const latest = await fetchLatest();
            await this.context.globalState.update(LAST_CHECK_KEY, Date.now());
            if (latest == null || !isNewer(latest.version, current)) {
                this.setLatest(null);
                log.info('updates: ' + current + ' is the latest' + (latest == null ? ' (no release on GitHub)' : ''));
                if (manual) {
                    void vscode.window.showInformationMessage('Duo Watchdog is up to date: ' + current + ' is the latest release.');
                }
                return;
            }
            hasVsix = assetOf(latest, '.vsix') != null;
            this.setLatest(hasVsix ? latest : null);
            log.info('updates: ' + latest.version + ' is out, this is ' + current + (hasVsix ? '' : '; it has no .vsix'));
            const skipped = this.context.globalState.get<string>(SKIPPED_KEY);
            if (manual || (latest.version !== skipped && !this.told.has(latest.version))) {
                this.told.add(latest.version);
                found = latest;
            }
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            log.warn('updates: could not ask GitHub: ' + message);
            if (manual) {
                const open = 'Open Releases';
                void vscode.window.showWarningMessage('Duo Watchdog could not check for updates: ' + message, open)
                    .then(choice => choice === open ? vscode.env.openExternal(vscode.Uri.parse(RELEASES)) : undefined);
            }
        } finally {
            this.busy = false;
        }
        if (found != null) {
            await this.offer(found, current, hasVsix);
        }
    }

    private async offer(release: Release, current: string, hasVsix: boolean): Promise<void> {
        const update = 'Update';
        const notes = 'Release Notes';
        const skip = 'Skip This Version';
        const choice = await vscode.window.showInformationMessage(availableText(release.version, current)
            + (hasVsix ? '' : ' Its release has no VS Code extension to download.'),
            ...(hasVsix ? [update] : []), notes, skip);
        if (choice === update) {
            await this.install(release);
        } else if (choice === notes) {
            await vscode.env.openExternal(vscode.Uri.parse(release.page));
        } else if (choice === skip) {
            await this.context.globalState.update(SKIPPED_KEY, release.version);
            log.info('updates: skipping ' + release.version);
        }
    }

    /** Downloads the release's .vsix, checks it, and installs it; with no newer release known, asks GitHub first. */
    async install(release: Release | null = this.latest): Promise<void> {
        if (this.installed != null) {
            await this.askReload(this.installed);
            return;
        }
        if (release == null) {
            await this.check(true);
            return;
        }
        const vsix = assetOf(release, '.vsix');
        if (vsix == null) {
            await vscode.env.openExternal(vscode.Uri.parse(release.page));
            return;
        }
        if (this.busy) {
            return;
        }
        this.busy = true;
        const version = release.version;
        try {
            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: 'Duo Watchdog ' + version,
                cancellable: true,
            }, async (progress, cancel) => {
                progress.report({ message: 'downloading…' });
                let reported = 0;
                const bytes = await download(vsix.url, { Accept: 'application/octet-stream' }, cancel, received => {
                    if (vsix.size > 0) {
                        const percent = Math.min(100, Math.floor(received * 100 / vsix.size));
                        progress.report({ increment: percent - reported, message: 'downloading… ' + percent + '%' });
                        reported = percent;
                    }
                });
                const problem = downloadProblem(bytes, vsix);
                if (problem != null) {
                    throw new Error(problem);
                }
                progress.report({ message: 'installing…' });
                const folder = vscode.Uri.joinPath(this.context.globalStorageUri, 'updates');
                await vscode.workspace.fs.createDirectory(folder);
                const file = vscode.Uri.joinPath(folder, 'duo-watchdog-' + version + '.vsix');
                await vscode.workspace.fs.writeFile(file, bytes);
                try {
                    // VS Code's own Install from VSIX: it replaces this extension, loaded on the next reload.
                    await vscode.commands.executeCommand('workbench.extensions.installExtension', file);
                } finally {
                    await Promise.resolve(vscode.workspace.fs.delete(file)).catch(() => undefined);
                }
            });
            this.installed = version;
            this.changed.fire();
            void vscode.commands.executeCommand('setContext', CONTEXT_KEY, false);
            log.info('updates: ' + version + ' installed; loaded on the next reload');
        } catch (e) {
            if (e instanceof vscode.CancellationError) {
                log.info('updates: the download of ' + version + ' was cancelled');
                return;
            }
            const message = e instanceof Error ? e.message : String(e);
            log.warn('updates: could not install ' + version + ': ' + message);
            const open = 'Open the Release';
            const choice = await vscode.window.showErrorMessage('Duo Watchdog ' + version + ' was not installed: ' + message, open);
            if (choice === open) {
                await vscode.env.openExternal(vscode.Uri.parse(release.page));
            }
            return;
        } finally {
            this.busy = false;
        }
        await this.askReload(version);
    }

    private async askReload(version: string): Promise<void> {
        const reload = 'Reload Window';
        const choice = await vscode.window.showInformationMessage(
            'Duo Watchdog ' + version + ' is installed. Reload the window to use it.', reload);
        if (choice === reload) {
            await vscode.commands.executeCommand('workbench.action.reloadWindow');
        }
    }

    private setLatest(release: Release | null): void {
        if (release?.version !== this.latest?.version) {
            this.latest = release;
            void vscode.commands.executeCommand('setContext', CONTEXT_KEY, release != null);
            this.changed.fire();
        }
    }
}

/** The latest release; null when the repository has none yet. */
async function fetchLatest(): Promise<Release | null> {
    const response = await request(LATEST, { Accept: 'application/vnd.github+json' }, undefined, undefined, 0);
    if (response.status === 404) {
        return null;
    }
    if (response.status === 403 || response.status === 429) {
        throw new Error('GitHub limits how often it is asked; try again in an hour');
    }
    if (response.status !== 200) {
        throw new Error('GitHub answered HTTP ' + response.status);
    }
    return parseRelease(response.body.toString('utf8'));
}

async function download(url: string, headers: Record<string, string>, cancel: vscode.CancellationToken,
                        onData: (received: number) => void): Promise<Uint8Array> {
    const response = await request(url, headers, cancel, onData, 0);
    if (response.status !== 200) {
        throw new Error('GitHub answered HTTP ' + response.status + ' for the download');
    }
    return response.body;
}

/**
 * A GET through Node's https, which VS Code patches with its proxy settings and the system's certificates, following
 * redirects to GitHub's own addresses only.
 */
function request(url: string, headers: Record<string, string>, cancel: vscode.CancellationToken | undefined,
                 onData: ((received: number) => void) | undefined, redirects: number): Promise<{ status: number; body: Buffer }> {
    if (!trusted(url)) {
        return Promise.reject(new Error('not a GitHub address: ' + url));
    }
    return new Promise((resolve, reject) => {
        const req = https.request(url, {
            method: 'GET',
            timeout: 30_000,
            headers: { 'User-Agent': 'duo-watchdog-vscode', ...headers },
        }, response => {
            const status = response.statusCode ?? 0;
            const location = response.headers.location;
            if (status >= 300 && status < 400 && location != null) {
                response.resume();
                if (redirects >= MAX_REDIRECTS) {
                    reject(new Error('too many redirects'));
                    return;
                }
                request(new URL(location, url).toString(), headers, cancel, onData, redirects + 1).then(resolve, reject);
                return;
            }
            const chunks: Buffer[] = [];
            let received = 0;
            response.on('data', (chunk: Buffer) => {
                chunks.push(chunk);
                received += chunk.length;
                onData?.(received);
            });
            response.on('end', () => resolve({ status, body: Buffer.concat(chunks) }));
            response.on('error', reject);
        });
        const listener = cancel?.onCancellationRequested(() => req.destroy(new vscode.CancellationError()));
        req.on('timeout', () => req.destroy(new Error('GitHub did not answer in time')));
        req.on('error', reject);
        req.on('close', () => listener?.dispose());
        req.end();
    });
}
