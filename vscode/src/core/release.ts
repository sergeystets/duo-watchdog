import * as path from 'path';

/** What the extension says once after it is installed or updated, and the What's New page. */

export type ReleaseNotice = 'upgrade' | 'fresh' | null;

/**
 * Once a version: an update when another version was seen before, or when none was but the extension was set up
 * already (0.3 did not remember versions); a fresh install when nothing was seen or set up; nothing when this version
 * was seen.
 */
export function releaseNotice(current: string, lastSeen: string | null | undefined, configured: boolean): ReleaseNotice {
    if (lastSeen === current) {
        return null;
    }
    if (lastSeen != null && lastSeen !== '') {
        return 'upgrade';
    }
    return configured ? 'upgrade' : 'fresh';
}

export function upgradeText(version: string): string {
    return 'Duo Watchdog ' + version + ': several accounts, statistics and more.';
}

export const FRESH_TEXT = 'Duo Watchdog is installed. Add a GitLab token to start.';

/** The page's policy: its own images and inline styles, nothing else, no scripts. */
export function whatsNewCsp(cspSource: string): string {
    return "default-src 'none'; img-src " + cspSource + "; style-src 'unsafe-inline'";
}

/** A relative path inside the page's folder, "img/a.png" from "./img/a.png"; null for anything else. */
export function localPath(src: string): string | null {
    const value = src.trim();
    if (value === '' || /^[a-z][a-z0-9+.-]*:/i.test(value) || value.startsWith('/') || value.startsWith('\\')
        || value.startsWith('#')) {
        return null;
    }
    const decoded = (() => {
        try {
            return decodeURIComponent(value.split(/[?#]/)[0]);
        } catch {
            return null;
        }
    })();
    if (decoded == null) {
        return null;
    }
    const normalized = path.posix.normalize(decoded.replace(/\\/g, '/'));
    return normalized === '.' || normalized.startsWith('../') || normalized === '..' || normalized.startsWith('/')
        ? null : normalized;
}

function attribute(value: string): string {
    return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
}

/**
 * The page as the webview shows it: each image with a relative src pointed at its webview URI (an image outside the
 * folder gets none), scripts dropped, and the policy put first in the head.
 */
export function whatsNewHtml(html: string, toUri: (relative: string) => string, cspSource: string): string {
    let page = html.replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '').replace(/<script\b[^>]*\/?>/gi, '');
    page = page.replace(/<img\b[^>]*>/gi, img => img.replace(
        /(\ssrc\s*=\s*)("([^"]*)"|'([^']*)'|([^\s"'>]+))/i,
        (_all, prefix: string, _quoted, double?: string, single?: string, bare?: string) => {
            const src = (double ?? single ?? bare ?? '').replace(/&amp;/g, '&');
            if (/^[a-z][a-z0-9+.-]*:/i.test(src.trim()) || src.trim().startsWith('//')) {
                return prefix + '"' + attribute(src) + '"';
            }
            const local = localPath(src);
            return prefix + '"' + (local == null ? '' : attribute(toUri(local))) + '"';
        }));
    const meta = '<meta http-equiv="Content-Security-Policy" content="' + attribute(whatsNewCsp(cspSource)) + '">';
    if (/<head\b[^>]*>/i.test(page)) {
        return page.replace(/<head\b[^>]*>/i, head => head + '\n' + meta);
    }
    if (/<html\b[^>]*>/i.test(page)) {
        return page.replace(/<html\b[^>]*>/i, root => root + '\n<head>' + meta + '</head>');
    }
    return meta + '\n' + page;
}

/** Shown when the page is missing from the build. */
export const WHATS_NEW_MISSING = '<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>What\'s New in Duo Watchdog</title></head>'
    + '<body style="font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 16px">'
    + '<p>The release notes are not in this build. The extension\'s CHANGELOG.md lists what changed.</p></body></html>';
