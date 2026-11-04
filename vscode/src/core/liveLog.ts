import { promises as fs } from 'fs';
import { HEAD, LiveSession, LOCAL_CLOCK, LogClock, Meter } from './liveSession';

/**
 * One Duo CLI log followed as it grows: each read takes the lines it gained since, up to the end of its last whole
 * line, into a LiveSession. Of a line only its first HEAD bytes are kept, the rest skipped as it is read, so a prompt
 * or code the CLI logged is never held whole. A port of LiveLog in the plugin's core.
 */
export class LiveLog {

    private session: LiveSession;
    private offset = 0;

    /** @param clock the zone the log's times are in: the one of the machine the CLI runs on */
    constructor(private readonly clock: LogClock = LOCAL_CLOCK) {
        this.session = new LiveSession(clock);
    }

    meter(): Meter | null {
        return this.session.meter();
    }

    /** Reads what the log gained since the last read; a log made again under the same name from its start. */
    async read(file: string): Promise<void> {
        const size = (await fs.stat(file)).size;
        if (size < this.offset) {
            this.session = new LiveSession(this.clock);
            this.offset = 0;
        }
        if (size === this.offset) {
            return;
        }
        const handle = await fs.open(file, 'r');
        try {
            const chunk = Buffer.alloc(64 * 1024);
            const head = Buffer.alloc(HEAD);
            let kept = 0;
            let at = this.offset;
            let lineStart = this.offset;
            for (;;) {
                const { bytesRead } = await handle.read(chunk, 0, chunk.length, at);
                if (bytesRead === 0) {
                    break;
                }
                for (let i = 0; i < bytesRead; i++) {
                    const b = chunk[i];
                    at++;
                    if (b === 0x0a) {
                        this.session.line(head.toString('utf8', 0, kept));
                        kept = 0;
                        lineStart = at;
                    } else if (kept < HEAD) {
                        head[kept++] = b;
                    }
                }
            }
            // A line not yet ended is read again, whole, next time.
            this.offset = lineStart;
        } finally {
            await handle.close();
        }
    }
}
