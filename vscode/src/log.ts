import * as vscode from 'vscode';
import { Logger, RingBuffer, scrub } from './core/log';

/**
 * The "Duo Watchdog" log (View, Output), and the last 200 lines for Copy Diagnostics. Every line is scrubbed: the
 * tokens the extension has read are masked exactly, and anything shaped like a GitLab token by its prefix.
 */
class Log implements Logger {

    private channel: vscode.LogOutputChannel | undefined;
    private readonly buffer = new RingBuffer(200);
    private readonly secrets = new Set<string>();

    /** Writes to the output channel from now on; until then, to the buffer only. */
    attach(channel: vscode.LogOutputChannel): vscode.Disposable {
        this.channel = channel;
        return { dispose: () => { this.channel = undefined; channel.dispose(); } };
    }

    /** A token the extension has: masked in every line from now on. */
    remember(token: string | undefined): void {
        if (token != null && token.trim() !== '') {
            this.secrets.add(token.trim());
        }
    }

    knownSecrets(): string[] {
        return [...this.secrets];
    }

    info(message: string): void {
        this.write('info', message);
    }

    warn(message: string): void {
        this.write('warn', message);
    }

    /** The last n lines, oldest first, each with its time and level. */
    lines(n?: number): string[] {
        return this.buffer.lines(n);
    }

    show(): void {
        this.channel?.show(true);
    }

    private write(level: 'info' | 'warn', message: string): void {
        const line = scrub(message, this.secrets);
        this.buffer.push(stamp(new Date()) + ' [' + level + '] ' + line);
        if (level === 'warn') {
            this.channel?.warn(line);
        } else {
            this.channel?.info(line);
        }
    }
}

/** "2026-11-03 14:05:01.123", on this machine's clock. */
function stamp(at: Date): string {
    const pad = (n: number, w = 2) => String(n).padStart(w, '0');
    return at.getFullYear() + '-' + pad(at.getMonth() + 1) + '-' + pad(at.getDate()) + ' ' + pad(at.getHours()) + ':'
        + pad(at.getMinutes()) + ':' + pad(at.getSeconds()) + '.' + pad(at.getMilliseconds(), 3);
}

export const log = new Log();
