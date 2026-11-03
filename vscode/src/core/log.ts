/** What the log keeps, and how a line is kept free of tokens. */

/** GitLab's token prefixes: glpat-, gloas-, gldt-, glrt-, glptt-, glft-, glimt-, glagent-, … */
export const TOKEN_PATTERN = /\bgl[a-z]{2,8}-[A-Za-z0-9_-]{8,}/g;

/** Where a token was. */
export const MASKED = '•••';

/** What every line of the log starts with, to find them among other extensions' lines. */
export const TAG = '[duo-watchdog]';

/** A line for the log: the tag, then the message without tokens ({@link scrub}). */
export function logLine(message: string, secrets: Iterable<string> = []): string {
    return TAG + ' ' + scrub(message, secrets);
}

/**
 * The line without tokens: each exact token the caller knows replaced by "•••", and anything shaped like a GitLab
 * token ("glpat-…") by its prefix and "•••", e.g. "glpat-•••".
 */
export function scrub(line: string, secrets: Iterable<string> = []): string {
    let text = line;
    // Longest first, so a token that contains another is masked whole.
    const known = [...new Set(secrets)].filter(s => s.trim().length >= 4).sort((a, b) => b.length - a.length);
    for (const secret of known) {
        text = text.split(secret).join(MASKED);
    }
    return text.replace(TOKEN_PATTERN, token => token.slice(0, token.indexOf('-') + 1) + MASKED);
}

/** The last lines written, oldest first; past its size the oldest go. */
export class RingBuffer {

    private readonly entries: string[] = [];

    constructor(readonly size = 200) {
    }

    push(line: string): void {
        this.entries.push(line);
        if (this.entries.length > this.size) {
            this.entries.splice(0, this.entries.length - this.size);
        }
    }

    /** The last n lines, or all of them. */
    lines(n?: number): string[] {
        return n == null ? [...this.entries] : this.entries.slice(Math.max(0, this.entries.length - n));
    }
}

/** Where the core writes what it does; the extension's log, or nothing in tests. */
export interface Logger {
    info(message: string): void;
    warn(message: string): void;
}

export const NO_LOG: Logger = { info: () => undefined, warn: () => undefined };
