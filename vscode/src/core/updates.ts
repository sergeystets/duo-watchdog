import { createHash } from 'crypto';

/**
 * Duo Watchdog's own updates: its latest release on GitHub, whether it is newer than the one running, and whether a
 * download is the one the release lists. Each release carries the VS Code extension as `duo-watchdog-<version>.vsix`
 * and the IntelliJ plugin as `duo-watchdog-<version>.zip`.
 */

export const REPOSITORY = 'sergeystets/duo-watchdog';
/** The latest release that is neither a draft nor a pre-release, as GitHub's REST API gives it. */
export const LATEST = 'https://api.github.com/repos/' + REPOSITORY + '/releases/latest';
export const RELEASES = 'https://github.com/' + REPOSITORY + '/releases';
/** How often it asks GitHub on its own, at most. */
export const EVERY_MS = 24 * 60 * 60 * 1000;
const NAME = 'duo-watchdog';

/** A file of a release, with its SHA-256 when GitHub gives one. */
export interface Asset {
    name: string;
    url: string;
    /** In bytes; -1 when not said. */
    size: number;
    sha256: string | null;
}

/** A release: its version without the tag's "v", its page on GitHub, what it says about itself, and its files. */
export interface Release {
    version: string;
    page: string;
    notes: string;
    assets: Asset[];
}

/**
 * The release in GitHub's answer; null for a draft or a pre-release, which are not offered. Throws when the answer is
 * not a release.
 */
export function parseRelease(json: string): Release | null {
    let o: Record<string, unknown>;
    try {
        o = JSON.parse(json) as Record<string, unknown>;
    } catch {
        throw new Error("GitHub's answer is not a release");
    }
    if (o == null || typeof o !== 'object' || Array.isArray(o)) {
        throw new Error("GitHub's answer is not a release");
    }
    const tag = typeof o.tag_name === 'string' ? o.tag_name : '';
    if (tag.trim() === '') {
        throw new Error("GitHub's answer has no release tag");
    }
    if (o.draft === true || o.prerelease === true) {
        return null;
    }
    const version = versionOf(tag);
    // It names the downloaded file, so a version and nothing else.
    if (!/^\d[0-9A-Za-z.+-]{0,39}$/.test(version)) {
        throw new Error('the release tag ' + tag + ' is not a version');
    }
    const assets: Asset[] = [];
    for (const a of Array.isArray(o.assets) ? o.assets as Record<string, unknown>[] : []) {
        if (a == null || typeof a !== 'object' || typeof a.name !== 'string' || typeof a.browser_download_url !== 'string'
            || !trusted(a.browser_download_url)) {
            continue;
        }
        const digest = typeof a.digest === 'string' ? a.digest.toLowerCase() : '';
        assets.push({
            name: a.name,
            url: a.browser_download_url,
            size: typeof a.size === 'number' ? a.size : -1,
            sha256: digest.startsWith('sha256:') ? digest.slice('sha256:'.length) : null,
        });
    }
    const page = typeof o.html_url === 'string' && trusted(o.html_url) ? o.html_url : RELEASES;
    return { version, page, notes: typeof o.body === 'string' ? o.body.trim() : '', assets };
}

/** The VS Code extension (`.vsix`) or the IntelliJ plugin's zip (`.zip`) of the release; null when it has none. */
export function assetOf(release: Release, extension: string): Asset | null {
    return release.assets.find(a => a.name === NAME + '-' + release.version + extension)
        ?? release.assets.find(a => a.name.startsWith(NAME) && a.name.endsWith(extension))
        ?? null;
}

/** "0.7.0" from the tag "v0.7.0". */
export function versionOf(tag: string): string {
    const t = tag.trim();
    return /^[vV]\d/.test(t) ? t.slice(1) : t;
}

/**
 * Orders versions part by part as numbers, "0.10.0" after "0.9.1"; a missing part is 0, and a pre-release
 * ("0.7.0-rc.1") comes before its release.
 */
export function compareVersions(a: string, b: string): number {
    const [x, xPre] = split(a);
    const [y, yPre] = split(b);
    const xs = x.split('.');
    const ys = y.split('.');
    for (let i = 0; i < Math.max(xs.length, ys.length); i++) {
        const d = part(xs[i]) - part(ys[i]);
        if (d !== 0) {
            return Math.sign(d);
        }
    }
    if (xPre === '' || yPre === '') {
        return (xPre === '' ? 1 : 0) - (yPre === '' ? 1 : 0);
    }
    return xPre < yPre ? -1 : xPre > yPre ? 1 : 0;
}

export function isNewer(candidate: string, current: string): boolean {
    return compareVersions(candidate, current) > 0;
}

/** Whether it is time to ask GitHub again: never asked, or a day since, or a clock set back. */
export function due(lastMs: number | undefined, nowMs: number): boolean {
    return lastMs == null || lastMs <= 0 || nowMs < lastMs || nowMs - lastMs >= EVERY_MS;
}

/** Only GitHub's own addresses, over https: a release's files and pages are there and nowhere else. */
export function trusted(url: string): boolean {
    let u: URL;
    try {
        u = new URL(url);
    } catch {
        return false;
    }
    const host = u.hostname.toLowerCase();
    return u.protocol === 'https:'
        && (host === 'github.com' || host.endsWith('.github.com') || host.endsWith('.githubusercontent.com'));
}

/** Why the downloaded bytes are not the asset; null when they are: as long as the release says, and its SHA-256. */
export function downloadProblem(bytes: Uint8Array, asset: Asset): string | null {
    if (asset.size >= 0 && bytes.length !== asset.size) {
        return 'the download has ' + bytes.length + ' bytes; the release lists ' + asset.size;
    }
    if (asset.sha256 != null && sha256(bytes) !== asset.sha256) {
        return "the download's SHA-256 is not the one the release lists";
    }
    return null;
}

export function sha256(bytes: Uint8Array): string {
    return createHash('sha256').update(bytes).digest('hex');
}

/** What the notice says about a newer release. */
export function availableText(version: string, current: string): string {
    return 'Duo Watchdog ' + version + ' is available; you have ' + current + '.';
}

/** The version's numbers and its pre-release part ("" for none), build metadata dropped. */
function split(version: string): [string, string] {
    const v = versionOf(version ?? '').split('+')[0];
    const dash = v.indexOf('-');
    return dash >= 0 ? [v.slice(0, dash), v.slice(dash + 1)] : [v, ''];
}

function part(text: string | undefined): number {
    const digits = /^\d+/.exec(text ?? '');
    return digits == null ? 0 : Number(digits[0]);
}
